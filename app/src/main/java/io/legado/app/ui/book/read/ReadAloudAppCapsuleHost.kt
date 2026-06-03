package io.legado.app.ui.book.read

import android.app.Activity
import android.app.Application
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivityForBook
import splitties.init.appCtx
import java.util.Collections
import java.util.WeakHashMap

object ReadAloudAppCapsuleHost : Application.ActivityLifecycleCallbacks {

    private data class PendingPanelOpenRequest(
        val bookUrl: String,
        val token: Long,
        val requestedAt: Long
    )

    private const val ACTIVITY_SWITCH_DETACH_DELAY_MILLIS = 260L

    private val observedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentActivity: Activity? = null
    private var overlay: ComposeCapsuleOverlay? = null
    private var pendingDetach: Runnable? = null
    private var lastPlaybackState by mutableStateOf(ReadAloudPlaybackState())
    private var capsulePosition by mutableStateOf(CapsulePositionState())
    private var readBookPanelActive by mutableStateOf(false)
    private var readMenuAvoidBounds by mutableStateOf<RectF?>(null)
    private val capsuleBounds = RectF()
    private var pendingPanelOpenRequest: PendingPanelOpenRequest? = null
    private var pendingPanelOpenToken = 0L

    fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        cancelPendingDetach()
        currentActivity = activity
        if (activity !is ReadBookActivity) {
            readBookPanelActive = false
            readMenuAvoidBounds = null
        }
        installObservers(activity)
        activity.window?.decorView?.post {
            if (currentActivity === activity) {
                sync(activity)
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity === activity) {
            currentActivity = null
            scheduleDetach(activity)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) {
            currentActivity = null
        }
        detach(activity)
        observedActivities.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun installObservers(activity: Activity) {
        if (!observedActivities.add(activity)) return
        val owner = activity as? LifecycleOwner ?: return
        LiveEventBus.get(EventBus.ALOUD_STATE, Int::class.java).observe(owner, Observer { status ->
            if (status == Status.STOP) {
                lastPlaybackState = ReadAloudPlaybackState()
                detach()
            } else {
                currentActivity?.let { sync(it) }
            }
        })
        LiveEventBus.get(EventBus.READ_ALOUD_PLAYBACK_STATE, ReadAloudPlaybackState::class.java)
            .observe(owner, Observer { state ->
                lastPlaybackState = state
                currentActivity?.let { sync(it) }
            })
    }

    private fun sync(activity: Activity) {
        if (!shouldShow(activity)) {
            detach()
            return
        }
        val parent = activity.window?.decorView as? ViewGroup ?: return
        val current = overlay
        val view = if (current?.activity === activity) {
            current
        } else {
            capsuleBounds.setEmpty()
            ComposeCapsuleOverlay(activity).also { newView ->
                overlay = newView
                parent.addView(
                    newView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                current?.let(::removeOverlay)
            }
        }
        if (view.parent !== parent) {
            (view.parent as? ViewGroup)?.removeView(view)
            parent.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        view.bind(buildPlayerState(activity))
    }

    private fun buildPlayerState(activity: Activity): ReadAloudPlayerPanel.PlayerUiState {
        val book = ReadBook.book
        val playing = lastPlaybackState.playing ?: BaseReadAloudService.isPlay()
        return ReadAloudPlayerPanel.PlayerUiState(
            bookName = book?.name.orEmpty(),
            author = book?.author.orEmpty(),
            coverUrl = book?.getDisplayCover(),
            sourceOrigin = ReadBook.bookSource?.bookSourceUrl,
            chapterTitle = ReadBook.curTextChapter?.chapter?.title.orEmpty(),
            playing = playing,
            playbackPhase = lastPlaybackState.phase,
            playbackBusy = lastPlaybackState.busy && !BaseReadAloudService.pause,
            serviceRunning = BaseReadAloudService.isRun,
            foregroundActive = true,
            expanded = false,
            readMenuVisible = false,
            readMenuAvoidBounds = if (activity is ReadBookActivity) {
                readMenuAvoidBounds?.let(::RectF)
            } else {
                null
            }
        )
    }

    private fun shouldShow(activity: Activity): Boolean {
        if (!BaseReadAloudService.isRun) return false
        if (activity is ReadBookActivity && readBookPanelActive) return false
        if (activity is ReadBookActivity &&
            pendingPanelOpenRequest?.bookUrl == ReadBook.book?.bookUrl
        ) {
            return false
        }
        return ReadBook.book != null
    }

    private fun detach() {
        overlay?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        overlay = null
        capsuleBounds.setEmpty()
    }

    private fun detach(activity: Activity) {
        if (overlay?.activity === activity) {
            detach()
        }
    }

    private fun removeOverlay(view: ComposeCapsuleOverlay) {
        (view.parent as? ViewGroup)?.removeView(view)
        if (overlay === view) {
            overlay = null
            capsuleBounds.setEmpty()
        }
    }

    private fun scheduleDetach(activity: Activity) {
        cancelPendingDetach()
        pendingDetach = Runnable {
            pendingDetach = null
            if (currentActivity == null || currentActivity === activity) {
                detach(activity)
            }
        }.also {
            mainHandler.postDelayed(it, ACTIVITY_SWITCH_DETACH_DELAY_MILLIS)
        }
    }

    private fun cancelPendingDetach() {
        pendingDetach?.let(mainHandler::removeCallbacks)
        pendingDetach = null
    }

    private fun updateCapsulePosition(x: Float, y: Float) {
        val current = capsulePosition
        if (current.x != x || current.y != y) {
            capsulePosition = CapsulePositionState(x, y)
        }
    }

    private fun updateCapsuleBounds(bounds: RectF) {
        capsuleBounds.set(bounds)
    }

    private fun openReadAloudPanel(activity: Activity) {
        ReadBook.book?.let { book ->
            if (activity is ReadBookActivity && activity.openReadAloudPanelFromGlobalCapsule()) {
                readBookPanelActive = true
                detach()
                return
            }
            requestReadAloudPanelOpen(book.bookUrl)
            activity.startActivityForBook(book) {
                putExtra("openReadAloudPanel", true)
            }
        }
    }

    fun updateReadBookPanelActive(active: Boolean) {
        if (readBookPanelActive == active) return
        readBookPanelActive = active
        currentActivity?.let { activity ->
            activity.window?.decorView?.post {
                if (currentActivity === activity) {
                    sync(activity)
                }
            }
        }
    }

    fun updateReadMenuAvoidBounds(bounds: RectF?) {
        val next = bounds?.let(::RectF)
        if (readMenuAvoidBounds == next) return
        readMenuAvoidBounds = next
        currentActivity?.let { activity ->
            if (activity is ReadBookActivity) {
                activity.window?.decorView?.post {
                    if (currentActivity === activity) {
                        sync(activity)
                    }
                }
            }
        }
    }

    fun requestReadAloudPanelOpen(bookUrl: String) {
        if (bookUrl.isBlank()) return
        pendingPanelOpenToken += 1
        pendingPanelOpenRequest = PendingPanelOpenRequest(
            bookUrl = bookUrl,
            token = pendingPanelOpenToken,
            requestedAt = System.currentTimeMillis()
        )
    }

    fun consumeReadAloudPanelOpen(bookUrl: String?): Boolean {
        val request = pendingPanelOpenRequest ?: return false
        if (bookUrl.isNullOrBlank() || request.bookUrl != bookUrl) return false
        if (System.currentTimeMillis() - request.requestedAt > 30_000L) {
            pendingPanelOpenRequest = null
            return false
        }
        pendingPanelOpenRequest = null
        return true
    }

    private class ComposeCapsuleOverlay(
        val activity: Activity
    ) : FrameLayout(activity) {

        private val composeView = ComposeView(activity)
        private var uiState by mutableStateOf(ReadAloudPlayerPanel.PlayerUiState())
        private var touchCaptured = false

        init {
            isClickable = false
            clipChildren = false
            clipToPadding = false
            addView(
                composeView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            composeView.setContent {
                val colors = rememberPlayerColors(ReaderSheetStyle.resolve(activity))
                Box(modifier = Modifier.fillMaxSize()) {
                    if (uiState.serviceRunning) {
                        ReadAloudCapsule(
                            state = uiState,
                            colors = colors,
                            onPlayPause = {
                                if (BaseReadAloudService.pause) {
                                    ReadAloud.resume(appCtx)
                                } else {
                                    ReadAloud.pause(appCtx)
                                }
                            },
                            onExpand = { openReadAloudPanel(activity) },
                            onClose = {
                                ReadAloud.stop(appCtx)
                                visibility = GONE
                            },
                            capsulePosition = capsulePosition,
                            onPositionChange = ::updateCapsulePosition,
                            onBounds = ::updateCapsuleBounds
                        )
                    }
                }
            }
        }

        fun bind(state: ReadAloudPlayerPanel.PlayerUiState) {
            uiState = state
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (capsuleBounds.isEmpty) {
                touchCaptured = false
                return false
            }
            val extra = 16.dpToPx().toFloat()
            val inside = ev.x >= capsuleBounds.left - extra &&
                    ev.x <= capsuleBounds.right + extra &&
                    ev.y >= capsuleBounds.top - extra &&
                    ev.y <= capsuleBounds.bottom + extra
            return when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchCaptured = inside
                    touchCaptured && super.dispatchTouchEvent(ev)
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    val captured = touchCaptured
                    touchCaptured = false
                    captured && super.dispatchTouchEvent(ev)
                }

                else -> touchCaptured && super.dispatchTouchEvent(ev)
            }
        }
    }
}
