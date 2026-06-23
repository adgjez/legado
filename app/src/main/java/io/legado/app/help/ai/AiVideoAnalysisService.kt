package io.legado.app.help.ai

import android.util.Log
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiVideoAnalysis
import io.legado.app.help.ai.asr.AsrConfig
import io.legado.app.help.ai.asr.AsrEngine
import io.legado.app.help.ai.asr.AsrEngineFactory
import io.legado.app.help.ai.asr.AsrSegment
import io.legado.app.help.ai.asr.toSrtText
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * AI 视频分析服务（P3）。
 *
 * 提供 5 个高层 API：summarize / extractSubtitles / extractKeyFrames /
 * detectChapters / extractCover。每次调用走缓存：(bookId, kind, language) 命中且状态
 * success 直接返回。
 *
 * 进度可取消（依赖 [Job.cancel]，调用方负责）。
 */
object AiVideoAnalysisService {

    private const val TAG = "AiVideoAnalysisService"
    private val SEQ = AtomicLong(0)

    /**
     * 总结视频内容（基于已有字幕或元信息）。
     *
     * @param bookId 关联的 bookId。
     * @param videoPath 视频本地路径（用于抽取音频做 ASR 后总结；为空时只基于元信息）。
     * @param asrConfig ASR 配置（用于先抽字幕再总结）。
     * @param llmConfig LLM 配置。
     * @param language 字幕语种，可空。
     * @return 缓存行（payloadJson 中是 "summary" 字段）。
     */
    suspend fun summarize(
        bookId: String,
        videoPath: String? = null,
        asrConfig: AsrConfig? = null,
        llmConfig: AiProviderConfig? = null,
        language: String = ""
    ): AiVideoAnalysis = withContext(Dispatchers.IO) {
        val cached = cacheHit(bookId, AiVideoAnalysis.KIND_SUMMARY, language)
        if (cached != null) return@withContext cached

        val rowId = upsertPending(bookId, AiVideoAnalysis.KIND_SUMMARY, language)

        try {
            val subtitleText = if (!videoPath.isNullOrBlank() && asrConfig != null) {
                val audio = VideoAudioExtractor.extract(videoPath, bookId)
                if (audio != null) {
                    val engine = AsrEngineFactory.create(asrConfig)
                    val segs = engine.transcribe(audio, language)
                    segs.joinToString("\n") { it.text }
                } else null
            } else null

            val summaryText = if (llmConfig != null && !subtitleText.isNullOrBlank()) {
                summarizeByLlm(llmConfig, subtitleText)
            } else {
                // 元信息兜底：仅放一个标记
                "（未配置 LLM 或没有字幕可总结）"
            }

            val payload = JSONObject().apply { put("summary", summaryText) }.toString()
            markSuccess(rowId, currentModelId(), llmConfig?.id ?: "", payload)
        } catch (e: CancellationException) {
            markCancelled(rowId)
            throw e
        } catch (e: Throwable) {
            markFailed(rowId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 提取字幕。流程：抽音频 → ASR → SRT。
     */
    suspend fun extractSubtitles(
        bookId: String,
        videoPath: String,
        asrConfig: AsrConfig,
        language: String = ""
    ): AiVideoAnalysis = withContext(Dispatchers.IO) {
        val cached = cacheHit(bookId, AiVideoAnalysis.KIND_SUBTITLE, language)
        if (cached != null) return@withContext cached

        val rowId = upsertPending(bookId, AiVideoAnalysis.KIND_SUBTITLE, language)
        try {
            val audio = VideoAudioExtractor.extract(videoPath, bookId)
                ?: error("no audio track or extract failed")
            val engine = AsrEngineFactory.create(asrConfig)
            val segs: List<AsrSegment> = engine.transcribe(audio, language)
            if (segs.isEmpty()) error("ASR returned no segments")
            val srt = segs.toSrtText()
            val payload = JSONObject().apply {
                put("srt", srt)
                put("segments", JSONArray().also { arr ->
                    segs.forEach {
                        arr.put(JSONObject().apply {
                            put("start", it.startMs)
                            put("end", it.endMs)
                            put("text", it.text)
                        })
                    }
                })
            }.toString()
            markSuccess(rowId, asrConfig.model, "", payload)
        } catch (e: CancellationException) {
            markCancelled(rowId)
            throw e
        } catch (e: Throwable) {
            markFailed(rowId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 抽取 N 个关键帧。
     */
    suspend fun extractKeyFrames(
        bookId: String,
        videoPath: String,
        n: Int = 8
    ): AiVideoAnalysis = withContext(Dispatchers.IO) {
        val cached = cacheHit(bookId, AiVideoAnalysis.KIND_KEYFRAMES, "")
        if (cached != null) return@withContext cached

        val rowId = upsertPending(bookId, AiVideoAnalysis.KIND_KEYFRAMES, "")
        try {
            val frames = VideoKeyFrameExtractor.extract(videoPath, bookId, n)
            if (frames.isEmpty()) error("no keyframe extracted")
            val payload = JSONObject().apply {
                put("count", frames.size)
                put("paths", JSONArray(frames))
            }.toString()
            markSuccess(rowId, "media-metadata-retriever", "", payload)
        } catch (e: CancellationException) {
            markCancelled(rowId)
            throw e
        } catch (e: Throwable) {
            markFailed(rowId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 章节切分（基于字幕静音检测 → LLM 总结）。
     *
     * v1 简化：直接用 LLM 总结字幕得到章节；静音检测留待 v2。
     */
    suspend fun detectChapters(
        bookId: String,
        llmConfig: AiProviderConfig,
        asrConfig: AsrConfig? = null,
        videoPath: String? = null,
        language: String = ""
    ): AiVideoAnalysis = withContext(Dispatchers.IO) {
        val cached = cacheHit(bookId, AiVideoAnalysis.KIND_CHAPTERS, language)
        if (cached != null) return@withContext cached

        val rowId = upsertPending(bookId, AiVideoAnalysis.KIND_CHAPTERS, language)
        try {
            // 1) 取字幕
            val subtitleRow = if (!videoPath.isNullOrBlank() && asrConfig != null) {
                extractSubtitles(bookId, videoPath, asrConfig, language)
            } else {
                appDb.aiVideoAnalysisDao.byBookAndKind(
                    bookId, AiVideoAnalysis.KIND_SUBTITLE, language
                )
            }
            val subtitleText = subtitleRow?.payloadJson?.let {
                runCatching { JSONObject(it).optString("srt", "") }.getOrNull()
            }.orEmpty()
            if (subtitleText.isBlank()) {
                error("no subtitle available; need videoPath + asrConfig to extract first")
            }
            // 2) LLM 切章节
            val chapters = chaptersByLlm(llmConfig, subtitleText)
            val arr = JSONArray()
            chapters.forEach { (startMs, title) ->
                arr.put(JSONObject().apply {
                    put("startMs", startMs)
                    put("title", title)
                })
            }
            val payload = JSONObject().apply { put("chapters", arr) }.toString()
            markSuccess(rowId, currentModelId(), llmConfig.id, payload)
        } catch (e: CancellationException) {
            markCancelled(rowId)
            throw e
        } catch (e: Throwable) {
            markFailed(rowId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 抽取封面（取首帧）。
     */
    suspend fun extractCover(bookId: String, videoPath: String): AiVideoAnalysis =
        withContext(Dispatchers.IO) {
            val cached = cacheHit(bookId, AiVideoAnalysis.KIND_COVER, "")
            if (cached != null) return@withContext cached

            val rowId = upsertPending(bookId, AiVideoAnalysis.KIND_COVER, "")
            try {
                val frames = VideoKeyFrameExtractor.extract(videoPath, bookId, 1)
                val cover = frames.firstOrNull() ?: error("no cover frame extracted")
                val payload = JSONObject().apply { put("coverPath", cover) }.toString()
                markSuccess(rowId, "media-metadata-retriever", "", payload)
            } catch (e: CancellationException) {
                markCancelled(rowId)
                throw e
            } catch (e: Throwable) {
                markFailed(rowId, e.message ?: e.javaClass.simpleName)
                throw e
            }
        }

    // ---------- 内部 ----------

    private fun cacheHit(bookId: String, kind: String, language: String): AiVideoAnalysis? {
        val row = appDb.aiVideoAnalysisDao.byBookAndKind(bookId, kind, language)
        return if (row != null && row.status == AiVideoAnalysis.STATUS_SUCCESS) row else null
    }

    private fun upsertPending(bookId: String, kind: String, language: String): Long {
        // 删除旧的 failed 行，便于重试
        val old = appDb.aiVideoAnalysisDao.byBookAndKind(bookId, kind, language)
        if (old != null) appDb.aiVideoAnalysisDao.delete(old.id)
        val row = AiVideoAnalysis(
            bookId = bookId,
            kind = kind,
            language = language,
            status = AiVideoAnalysis.STATUS_RUNNING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return appDb.aiVideoAnalysisDao.insert(row)
    }

    private fun markSuccess(rowId: Long, model: String, providerId: String, payloadJson: String): AiVideoAnalysis {
        val cur = appDb.aiVideoAnalysisDao.get(rowId) ?: error("row not found: $rowId")
        val updated = cur.copy(
            payloadJson = payloadJson,
            model = model,
            providerId = providerId,
            status = AiVideoAnalysis.STATUS_SUCCESS,
            failReason = "",
            updatedAt = System.currentTimeMillis()
        )
        appDb.aiVideoAnalysisDao.insert(updated)
        return updated
    }

    private fun markFailed(rowId: Long, reason: String) {
        val cur = appDb.aiVideoAnalysisDao.get(rowId) ?: return
        val updated = cur.copy(
            status = AiVideoAnalysis.STATUS_FAILED,
            failReason = reason,
            updatedAt = System.currentTimeMillis()
        )
        appDb.aiVideoAnalysisDao.insert(updated)
    }

    private fun markCancelled(rowId: Long) {
        val cur = appDb.aiVideoAnalysisDao.get(rowId) ?: return
        val updated = cur.copy(
            status = AiVideoAnalysis.STATUS_CANCELLED,
            updatedAt = System.currentTimeMillis()
        )
        appDb.aiVideoAnalysisDao.insert(updated)
    }

    private suspend fun summarizeByLlm(llm: AiProviderConfig, text: String): String {
        // 检查 LLM 是否已配置
        val provider = AppConfig.aiCurrentProvider
            ?: error("未配置 LLM Provider，请在 AI 设置中配置")
        val prompt = "你是视频内容总结助手。请基于以下字幕内容生成 200 字以内的中文摘要，分段列出要点：\n\n$text"
        val reply = AiChatService.chat(
            listOf(
                AiChatMessage(role = AiChatMessage.Role.USER, content = prompt)
            )
        )
        return reply
    }

    private suspend fun chaptersByLlm(llm: AiProviderConfig, srtText: String): List<Pair<Long, String>> {
        // 检查 LLM 是否已配置
        val provider = AppConfig.aiCurrentProvider
            ?: error("未配置 LLM Provider，请在 AI 设置中配置")
        val prompt = "你是视频章节识别助手。请基于以下带时间戳的字幕，输出 5~10 个章节，每章一行，格式 `startMs|标题`：\n\n$srtText"
        val reply = AiChatService.chat(
            listOf(
                AiChatMessage(role = AiChatMessage.Role.USER, content = prompt)
            )
        )
        return reply.lines().mapNotNull { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2) {
                val ms = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
                val title = parts[1].trim()
                if (title.isNotEmpty()) ms to title else null
            } else null
        }
    }

    /**
     * 清理 30 天未更新的 success 行。
     */
    suspend fun cleanupOld(beforeMillis: Long = System.currentTimeMillis() - 30L * 24 * 3600 * 1000): Int =
        withContext(Dispatchers.IO) {
            appDb.aiVideoAnalysisDao.cleanupOldSuccess(AiVideoAnalysis.STATUS_SUCCESS, beforeMillis)
        }

    @Suppress("unused")
    private fun rootDir(bookId: String): File =
        File(appCtx.externalFiles, "ai_video_analysis/$bookId").apply { mkdirs() }

    private fun currentModelId(): String =
        AppConfig.aiCurrentModelConfig?.modelId.orEmpty()
}
