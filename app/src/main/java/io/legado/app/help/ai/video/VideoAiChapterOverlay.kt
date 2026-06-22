package io.legado.app.help.ai.video

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiVideoAnalysis
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * P4 章节标记覆盖层。
 *
 * 在播放器底部进度条上方叠加章节标记小三角，点击可跳转到对应时间。
 *
 * 数据来源：P3 的 [AiVideoAnalysis.KIND_CHAPTERS] 缓存。
 */
class VideoAiChapterOverlay(
    private val activity: Activity,
    private val player: StandardGSYVideoPlayer,
    private val bookId: String,
    private val scope: CoroutineScope
) {
    /** 章节列表，按 startMs 升序 */
    private var chapters: List<ChapterMark> = emptyList()
    /** 标记容器，叠加在播放器上 */
    private var markerContainer: FrameLayout? = null

    /**
     * 装配：从 P3 缓存加载章节数据，创建标记 View。
     */
    fun attach() {
        scope.launch(Dispatchers.IO) {
            loadChaptersFromCache()
        }
    }

    /**
     * 卸载：移除标记 View。
     */
    fun detach() {
        chapters = emptyList()
        markerContainer?.let { container ->
            (container.parent as? ViewGroup)?.removeView(container)
        }
        markerContainer = null
    }

    /**
     * 播放位置变化时更新标记高亮（可选，v1 不做高亮）。
     */
    fun onPositionChanged(positionMs: Long) {
        // v1 不需要逐帧更新标记位置，标记是静态的
    }

    private suspend fun loadChaptersFromCache() {
        val analysis = appDb.aiVideoAnalysisDao.byBookAndKind(
            bookId, AiVideoAnalysis.KIND_CHAPTERS, ""
        )
        if (analysis?.status == AiVideoAnalysis.STATUS_SUCCESS && analysis.payloadJson.isNotBlank()) {
            chapters = parseChapters(analysis.payloadJson)
            if (chapters.isNotEmpty()) {
                scope.launch(Dispatchers.Main) {
                    createMarkers()
                }
            }
        }
    }

    private fun parseChapters(payloadJson: String): List<ChapterMark> {
        return runCatching {
            val arr = JSONObject(payloadJson).optJSONArray("chapters") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val obj = arr.optJSONObject(i) ?: return@map null
                ChapterMark(
                    startMs = obj.optLong("startMs"),
                    title = obj.optString("title")
                )
            }.filterNotNull()
        }.getOrDefault(emptyList())
    }

    /**
     * 在播放器上创建章节标记。
     * 标记按 startMs / duration 比例水平排列。
     */
    private fun createMarkers() {
        val parent = player.parent as? ViewGroup ?: return
        val duration = player.duration
        if (duration <= 0) return

        // 移除旧容器
        markerContainer?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = 24.dpToPx() // 在进度条上方
            }
        }

        val parentWidth = parent.width

        for (chapter in chapters) {
            if (chapter.startMs <= 0L || chapter.startMs >= duration) continue
            val ratio = chapter.startMs.toFloat() / duration.toFloat()
            val marker = ImageView(activity).apply {
                setImageResource(R.drawable.ic_video_chapter_marker)
                layoutParams = FrameLayout.LayoutParams(
                    2.dpToPx(),
                    10.dpToPx()
                ).apply {
                    if (parentWidth > 0) {
                        leftMargin = (ratio * parentWidth).toInt()
                    }
                    gravity = Gravity.START or Gravity.BOTTOM
                }
                contentDescription = chapter.title
                setOnClickListener {
                    player.seekTo(chapter.startMs)
                    activity.toastOnUi(chapter.title)
                }
            }
            container.addView(marker)
        }

        parent.addView(container)
        markerContainer = container
    }

    data class ChapterMark(
        val startMs: Long,
        val title: String
    )
}
