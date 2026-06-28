package io.legado.app.ui.book.read

import android.app.Activity
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.help.CoverDisplayResolver
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivityForBook
import splitties.init.appCtx
import java.util.Collections
import java.util.WeakHashMap

object ReadAloudAppCapsuleHost {

    private enum class HostKind {
        Main,
        ReadBook
    }

    private data class PendingPanelOpenRequest(
        val bookUrl: String,
        val requestedAt: Long
    )

    private val observedOwners = Collections.newSetFromMap(WeakHashMap<LifecycleOwner, Boolean>())
    private val overlays = WeakHashMap<Activity, CapsuleOverlay>()
    private var lastPlaybackState by mutableStateOf(ReadAloudPlaybackState())
    private var themeRevision by mutableStateOf(0)
    private var capsulePosition by mutableStateOf(CapsulePositionState())
    private var readBookPanelActive by mutableStateOf(false)
    private var readMenuAvoidBounds by mutableStateOf<RectF?>(null)
    private var pendingPanelOpenRequest: PendingPanelOpenRequest? = null

    fun attachMain(activity: MainActivity, parent: ViewGroup) {
        attach(
            activity = activity,
            parent = parent,
            kind = HostKind.Main,
            openReadBookPanel = null
        )
    }

    fun attachReadBook(
        activity: ReadBookActivity,
        parent: ViewGroup,
        openReadBookPanel: () -> Boolean
    ) {
        attach(
            activity = activity,
            parent = parent,
            kind = HostKind.ReadBook,
            openReadBookPanel = openReadBookPanel
        )
    }

    fun detach(activity: Activity) {
        overlays.remove(activity)?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
    }

    fun updateReadBookPanelActive(active: Boolean) {
        if (readBookPanelActive == active) return
        readBookPanelActive = active
        refreshAll()
    }

    fun updateReadMenuAvoidBounds(bounds: RectF?) {
        val next = bounds?.let(::RectF)
        if (readMenuAvoidBounds == next) return
        readMenuAvoidBounds = next
        refreshAll()
    }

    fun requestReadAloudPanelOpen(bookUrl: String) {
        if (bookUrl.isBlank()) return
        pendingPanelOpenRequest = PendingPanelOpenRequest(
            bookUrl = bookUrl,
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

    private fun attach(
        activity: Activity,
        parent: ViewGroup,
        kind: HostKind,
        openReadBookPanel: (() -> Boolean)?
    ) {
        installObservers(activity)
        val current = overlays[activity]
        val overlay = if (current != null) {
            current.updateHost(kind, openReadBookPanel)
            current
        } else {
            CapsuleOverlay(activity).also {
                it.updateHost(kind, openReadBookPanel)
                overlays[activity] = it
            }
        }
        if (overlay.parent !== parent) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            parent.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        overlay.sync()
    }

    private fun installObservers(activity: Activity) {
        val owner = activity as? LifecycleOwner ?: return
        if (!observedOwners.add(owner)) return
        LiveEventBus.get(EventBus.ALOUD_STATE, Int::class.java).observe(owner, Observer { status ->
            if (status == Status.STOP) {
                lastPlaybackState = ReadAloudPlaybackState()
            }
            refreshAll()
        })
        LiveEventBus.get(EventBus.READ_ALOUD_PLAYBACK_STATE, ReadAloudPlaybackState::class.java)
            .observe(owner, Observer { state ->
                lastPlaybackState = state
                refreshAll()
            })
        LiveEventBus.get(EventBus.RECREATE, String::class.java).observe(owner, Observer {
            refreshThemeAll()
        })
        LiveEventBus.get(EventBus.UP_CONFIG, ArrayList::class.java).observe(owner, Observer {
            refreshThemeAll()
        })
    }

    private fun refreshAll() {
        overlays.values.toList().forEach { it.sync() }
    }

    private fun refreshThemeAll() {
        themeRevision += 1
        refreshAll()
    }

    private fun shouldShow(kind: HostKind): Boolean {
        if (!BaseReadAloudService.isRun) return false
        if (ReadBook.book == null) return false
        if (kind == HostKind.ReadBook && readBookPanelActive) return false
        if (kind == HostKind.ReadBook &&
            pendingPanelOpenRequest?.bookUrl == ReadBook.book?.bookUrl
        ) {
            return false
        }
        return true
    }

    private fun buildPlayerState(
        activity: Activity,
        kind: HostKind
    ): ReadAloudPlayerPanel.PlayerUiState {
        val book = ReadBook.book
        val coverDisplay = book?.let { CoverDisplayResolver.resolve(it) }
        val playing = lastPlaybackState.playing ?: BaseReadAloudService.isPlay()
        return ReadAloudPlayerPanel.PlayerUiState(
            bookName = book?.name.orEmpty(),
            author = book?.author.orEmpty(),
            coverUrl = coverDisplay?.path,
            sourceOrigin = coverDisplay?.sourceOrigin,
            coverForcePath = coverDisplay?.forcePath ?: false,
            coverAllowNameOverlay = coverDisplay?.allowNameOverlay,
            chapterTitle = ReadBook.curTextChapter?.chapter?.title.orEmpty(),
            playing = playing,
            playbackPhase = lastPlaybackState.phase,
            playbackBusy = lastPlaybackState.busy && !BaseReadAloudService.pause,
            serviceRunning = BaseReadAloudService.isRun && shouldShow(kind),
            foregroundActive = true,
            expanded = false,
            readMenuVisible = false,
            readMenuAvoidBounds = if (kind == HostKind.ReadBook) {
                readMenuAvoidBounds?.let(::RectF)
            } else {
                null
            }
        )
    }

    private fun openReadAloudPanel(
        activity: Activity,
        openReadBookPanel: (() -> Boolean)?
    ) {
        val book = ReadBook.book ?: return
        if (activity is ReadBookActivity && openReadBookPanel?.invoke() == true) {
            readBookPanelActive = true
            refreshAll()
            return
        }
        requestReadAloudPanelOpen(book.bookUrl)
        activity.startActivityForBook(book) {
            putExtra("openReadAloudPanel", true)
        }
    }

    private fun updateCapsulePosition(x: Float, y: Float) {
        val current = capsulePosition
        if (current.x != x || current.y != y) {
            capsulePosition = CapsulePositionState(x, y)
        }
    }

    private class CapsuleOverlay(
        private val activity: Activity
    ) : FrameLayout(activity) {

        private val composeView = ComposeView(activity)
        private val capsuleBounds = RectF()
        private var kind by mutableStateOf(HostKind.Main)
        private var openReadBookPanel: (() -> Boolean)? = null
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
            composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            composeView.setContent {
                val paletteRevision = themeRevision
                val palette = remember(paletteRevision) { ReaderSheetStyle.resolve(activity) }
                val colors = rememberPlayerColors(palette)
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
                            onExpand = {
                                openReadAloudPanel(activity, openReadBookPanel)
                            },
                            onClose = {
                                ReadAloud.stop(appCtx)
                                visibility = GONE
                            },
                            capsulePosition = capsulePosition,
                            onPositionChange = ::updateCapsulePosition,
                            onBounds = { bounds ->
                                capsuleBounds.set(bounds)
                            }
                        )
                    }
                }
            }
        }

        fun updateHost(nextKind: HostKind, nextOpenReadBookPanel: (() -> Boolean)?) {
            kind = nextKind
            openReadBookPanel = nextOpenReadBookPanel
        }

        fun sync() {
            val show = shouldShow(kind)
            visibility = if (show) VISIBLE else GONE
            if (!show) {
                capsuleBounds.setEmpty()
            }
            uiState = buildPlayerState(activity, kind)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (visibility != VISIBLE || capsuleBounds.isEmpty) {
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
