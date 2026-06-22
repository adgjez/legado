package io.legado.app.help.ai.video

import android.app.Activity
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiVideoAnalysis
import io.legado.app.help.ai.AiVideoAnalysisService
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * P4 AI 字幕渲染器。
 *
 * 职责：
 * 1. [attach] 时从 P3 缓存加载 SRT 字幕到内存
 * 2. [onPositionChanged] 按播放位置二分查找当前字幕
 * 3. 无缓存时提示用户是否立即生成（调 P3 extractSubtitles）
 */
class VideoAiSubtitleRenderer(
    private val activity: Activity,
    private val bookId: String,
    private val subtitleLanguage: String,
    private val videoPath: String?,
    private val onSubtitleReady: (String?) -> Unit,
    private val scope: CoroutineScope
) {
    /** SRT cue 列表，按 startMs 升序 */
    private var cues: List<SrtCue> = emptyList()
    /** 上次匹配的索引，用于增量查找 */
    private var lastIndex = 0
    private var loaded = false

    /**
     * 装配：从 P3 缓存加载字幕。
     */
    fun attach() {
        scope.launch(Dispatchers.IO) {
            loadSubtitlesFromCache()
        }
    }

    /**
     * 卸载：清空字幕。
     */
    fun detach() {
        cues = emptyList()
        lastIndex = 0
        loaded = false
        onSubtitleReady(null)
    }

    /**
     * 播放位置变化时匹配字幕。
     * 用增量查找：从上次位置前后扫描，O(1) 平均复杂度。
     */
    fun onPositionChanged(positionMs: Long) {
        if (!loaded || cues.isEmpty()) return

        // 回退场景：用户 seek 后退到当前 cue 起始之前
        if (lastIndex > 0 && lastIndex < cues.size && positionMs < cues[lastIndex].startMs) {
            lastIndex = findLowerBound(positionMs)
        } else if (lastIndex >= cues.size) {
            // lastIndex 越界，重新定位
            lastIndex = findLowerBound(positionMs)
        }

        // 前进扫描
        while (lastIndex < cues.size) {
            val cue = cues[lastIndex]
            if (positionMs in cue.startMs..cue.endMs) {
                onSubtitleReady(cue.text)
                return
            }
            if (positionMs < cue.startMs) {
                // 还没到下一条字幕
                onSubtitleReady(null)
                return
            }
            // 跳过已过期的 cue
            lastIndex++
        }
        // 所有字幕都已播放完
        onSubtitleReady(null)
    }

    /**
     * 二分查找：返回最后一个 startMs <= positionMs 的索引（lower bound）。
     * 如果所有 cue 的 startMs 都 > positionMs，返回 0。
     */
    private fun findLowerBound(positionMs: Long): Int {
        var lo = 0
        var hi = cues.size - 1
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    private suspend fun loadSubtitlesFromCache() {
        val analysis = appDb.aiVideoAnalysisDao.byBookAndKind(
            bookId, AiVideoAnalysis.KIND_SUBTITLE, subtitleLanguage
        )
        if (analysis?.status == AiVideoAnalysis.STATUS_SUCCESS && analysis.payloadJson.isNotBlank()) {
            val srt = parseSrtFromPayload(analysis.payloadJson)
            cues = parseSrt(srt)
            loaded = true
        } else {
            // 无缓存，在主线程提示用户
            scope.launch(Dispatchers.Main) {
                promptGenerateSubtitles()
            }
        }
    }

    private fun parseSrtFromPayload(payloadJson: String): String {
        return runCatching {
            JSONObject(payloadJson).optString("srt", "")
        }.getOrDefault("")
    }

    /**
     * 提示用户是否立即生成字幕。
     */
    private fun promptGenerateSubtitles() {
        if (videoPath.isNullOrBlank()) {
            activity.toastOnUi(R.string.video_ai_subtitle_not_found)
            return
        }
        activity.alert(
            activity.getString(R.string.video_ai_settings),
            activity.getString(R.string.video_ai_subtitle_no_cache)
        ) {
            okButton {
                generateSubtitles()
            }
            cancelButton()
        }
    }

    /**
     * 调 P3 extractSubtitles 生成字幕。
     */
    private fun generateSubtitles() {
        if (videoPath.isNullOrBlank()) return
        val asrConfig = AppConfig.asrConfig() ?: run {
            activity.toastOnUi(R.string.video_ai_subtitle_not_found)
            return
        }
        scope.launch {
            activity.toastOnUi(R.string.video_ai_subtitle_generating)
            try {
                AiVideoAnalysisService.extractSubtitles(
                    bookId = bookId,
                    videoPath = videoPath,
                    asrConfig = asrConfig,
                    language = subtitleLanguage
                )
                // 生成完成后重新加载
                withContext(Dispatchers.IO) {
                    loadSubtitlesFromCache()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.toastOnUi(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ==================== SRT 解析 ====================

    data class SrtCue(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    companion object {
        /**
         * 解析 SRT 格式字幕文本为 cue 列表。
         *
         * SRT 格式：
         * 1
         * 00:00:01,000 --> 00:00:04,000
         * 字幕文本
         *
         * 2
         * 00:00:05,000 --> 00:00:08,000
         * 下一句
         */
        fun parseSrt(srt: String): List<SrtCue> {
            if (srt.isBlank()) return emptyList()
            val cues = mutableListOf<SrtCue>()
            val blocks = srt.trim().split(Regex("\\n\\s*\\n"))
            for (block in blocks) {
                val lines = block.trim().lines()
                if (lines.size < 2) continue
                // 第一行是序号（可能跳过）
                val timeLineIndex = if (lines[0].contains("-->")) 0 else 1
                if (timeLineIndex >= lines.size) continue
                val timeLine = lines[timeLineIndex]
                val timeMatch = Regex(
                    "(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})"
                ).find(timeLine) ?: continue
                val startMs = timeToMs(
                    timeMatch.groupValues[1], timeMatch.groupValues[2],
                    timeMatch.groupValues[3], timeMatch.groupValues[4]
                )
                val endMs = timeToMs(
                    timeMatch.groupValues[5], timeMatch.groupValues[6],
                    timeMatch.groupValues[7], timeMatch.groupValues[8]
                )
                val text = lines.drop(timeLineIndex + 1).joinToString("\n").trim()
                if (text.isNotBlank()) {
                    cues.add(SrtCue(cues.size, startMs, endMs, text))
                }
            }
            return cues
        }

        private fun timeToMs(h: String, m: String, s: String, ms: String): Long {
            return h.toLong() * 3600000 +
                m.toLong() * 60000 +
                s.toLong() * 1000 +
                ms.toLong()
        }
    }
}
