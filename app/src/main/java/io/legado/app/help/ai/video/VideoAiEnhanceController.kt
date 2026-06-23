package io.legado.app.help.ai.video

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import io.legado.app.constant.PreferKey
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * P4 AI 视频增强控制器。
 *
 * 在 [VideoPlayerActivity.onCreate] 时创建并 [attach]，
 * [onDestroy] 时调 [detach]。
 *
 * 职责：
 * 1. 读取偏好，按开关装配字幕渲染器和章节标记
 * 2. 提供轮询协程，定期刷新当前字幕
 * 3. 设置变更后调 [applySettings] 热更新
 */
class VideoAiEnhanceController(
    private val activity: Activity,
    private val player: StandardGSYVideoPlayer,
    private val bookId: String,
    private val videoPath: String?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var settings: VideoAiSettings = loadSettings()
    private var pollJob: Job? = null

    private var subtitleView: TextView? = null
    private var subtitleRenderer: VideoAiSubtitleRenderer? = null
    private var chapterOverlay: VideoAiChapterOverlay? = null

    /**
     * 装配增强组件。在 Activity onCreate 末尾调用。
     */
    fun attach() {
        applySettings(settings)
        startPolling()
    }

    /**
     * 卸载所有增强组件。在 Activity onDestroy 调用。
     */
    fun detach() {
        scope.cancel()
        pollJob?.cancel()
        subtitleRenderer?.detach()
        chapterOverlay?.detach()
        subtitleView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        subtitleView = null
    }

    /**
     * 热更新设置。用户在设置 Dialog 改开关后调用。
     */
    fun applySettings(newSettings: VideoAiSettings) {
        settings = newSettings

        // 字幕
        if (newSettings.subtitleEnabled) {
            ensureSubtitleView()
            ensureSubtitleRenderer()
        } else {
            subtitleRenderer?.detach()
            subtitleRenderer = null
            subtitleView?.visibility = View.GONE
        }

        // 章节标记
        if (newSettings.chapterMarkerEnabled) {
            ensureChapterOverlay()
        } else {
            chapterOverlay?.detach()
            chapterOverlay = null
        }
    }

    private fun loadSettings(): VideoAiSettings {
        return VideoAiSettings(
            subtitleEnabled = appCtx.getPrefBoolean(PreferKey.videoAiSubtitleEnabled, false),
            subtitleLanguage = appCtx.getPrefString(PreferKey.videoAiSubtitleLanguage)
                ?: "zh-CN",
            chapterMarkerEnabled = appCtx.getPrefBoolean(PreferKey.videoAiChapterMarkerEnabled, false)
        )
    }

    // ==================== 字幕 ====================

    private fun ensureSubtitleView() {
        if (subtitleView != null) return
        val parent = player.parent as? ViewGroup ?: return
        val tv = TextView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 48.dpToPx()
                leftMargin = 16.dpToPx()
                rightMargin = 16.dpToPx()
            }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        parent.addView(tv)
        subtitleView = tv
    }

    private fun ensureSubtitleRenderer() {
        if (subtitleRenderer != null) return
        subtitleRenderer = VideoAiSubtitleRenderer(
            activity = activity,
            bookId = bookId,
            subtitleLanguage = settings.subtitleLanguage,
            videoPath = videoPath,
            onSubtitleReady = { text ->
                subtitleView?.let { view ->
                    if (text.isNullOrBlank()) {
                        view.visibility = View.GONE
                    } else {
                        view.text = text
                        view.visibility = View.VISIBLE
                    }
                }
            },
            scope = scope
        ).also { it.attach() }
    }

    // ==================== 章节标记 ====================

    private fun ensureChapterOverlay() {
        if (chapterOverlay != null) return
        chapterOverlay = VideoAiChapterOverlay(
            activity = activity,
            player = player,
            bookId = bookId,
            scope = scope
        ).also { it.attach() }
    }

    // ==================== 轮询 ====================

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                delay(200) // 5fps 刷新
                try {
                    val pos = player.getCurrentPositionWhenPlaying().toLong()
                    subtitleRenderer?.onPositionChanged(pos)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 播放器未就绪或已释放，忽略
                }
            }
        }
    }
}
