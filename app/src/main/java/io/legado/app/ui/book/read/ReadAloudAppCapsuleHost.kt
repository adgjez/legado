package io.legado.app.ui.book.read

import android.app.Activity
import android.app.Application
import android.graphics.RectF
import android.os.Bundle
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

    private val observedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var currentActivity: Activity? = null
    private var overlay: ComposeCapsuleOverlay? = null
    private var lastPlaybackState by mutableStateOf(ReadAloudPlaybackState())
    private var capsulePosition by mutableStateOf(CapsulePositionState())
    private val capsuleBounds = RectF()

    fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        installObservers(activity)
        activity.window?.decorView?.post {
            if (currentActivity === activity) {
                sync(activity)
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity === activity) {
            detach()
            currentActivity = null
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) {
            detach()
            currentActivity = null
        }
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
            detach()
            ComposeCapsuleOverlay(activity).also { overlay = it }
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
            readMenuAvoidBounds = null
        )
    }

    private fun shouldShow(activity: Activity): Boolean {
        if (activity is ReadBookActivity) return false
        if (!BaseReadAloudService.isRun) return false
        return ReadBook.book != null
    }

    private fun detach() {
        overlay?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        overlay = null
        capsuleBounds.setEmpty()
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
            activity.startActivityForBook(book) {
                putExtra("openReadAloudPanel", true)
            }
        }
    }

    private class ComposeCapsuleOverlay(
        val activity: Activity
    ) : FrameLayout(activity) {

        private val composeView = ComposeView(activity)
        private var uiState by mutableStateOf(ReadAloudPlayerPanel.PlayerUiState())

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
            if (capsuleBounds.isEmpty) return false
            val extra = 16.dpToPx().toFloat()
            val inside = ev.x >= capsuleBounds.left - extra &&
                    ev.x <= capsuleBounds.right + extra &&
                    ev.y >= capsuleBounds.top - extra &&
                    ev.y <= capsuleBounds.bottom + extra
            return inside && super.dispatchTouchEvent(ev)
        }
    }
}
