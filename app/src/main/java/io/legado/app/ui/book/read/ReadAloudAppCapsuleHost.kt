package io.legado.app.ui.book.read

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.lib.theme.UiCorner
import io.legado.app.model.BookCover
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivityForBook
import splitties.init.appCtx
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

object ReadAloudAppCapsuleHost : Application.ActivityLifecycleCallbacks {

    private val observedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var currentActivity: Activity? = null
    private var overlay: CapsuleOverlayView? = null
    private var lastPlaybackState: ReadAloudPlaybackState = ReadAloudPlaybackState()

    fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        installObservers(activity)
        sync(activity)
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
                sync(currentActivity ?: activity)
            }
        })
        LiveEventBus.get(EventBus.READ_ALOUD_PLAYBACK_STATE, ReadAloudPlaybackState::class.java)
            .observe(owner, Observer { state ->
                lastPlaybackState = state
                sync(currentActivity ?: activity)
            })
    }

    private fun sync(activity: Activity) {
        if (!shouldShow(activity)) {
            detach()
            return
        }
        val parent = activity.window?.decorView as? ViewGroup ?: return
        val view = overlay ?: CapsuleOverlayView(activity).also { overlay = it }
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
        view.bind(lastPlaybackState)
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
    }

    private class CapsuleOverlayView(activity: Activity) : FrameLayout(activity) {

        private val capsule = LinearLayout(activity)
        private val cover = ImageView(activity)
        private val title = TextView(activity)
        private val play = ImageView(activity)
        private val close = ImageView(activity)
        private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        private val sideGap = 14.dpToPx()
        private val bottomGap = 18.dpToPx()
        private val capsuleWidth = 184.dpToPx()
        private val capsuleHeight = 56.dpToPx()
        private var baseX: Float? = null
        private var baseY: Float? = null
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var lastCoverKey = ""

        init {
            isClickable = false
            clipChildren = false
            clipToPadding = false

            capsule.orientation = LinearLayout.HORIZONTAL
            capsule.gravity = Gravity.CENTER_VERTICAL
            capsule.elevation = 10.dpToPx().toFloat()
            capsule.setPadding(7.dpToPx(), 6.dpToPx(), 7.dpToPx(), 6.dpToPx())
            capsule.background = UiCorner.panelRounded(
                context,
                ColorUtils.setAlphaComponent(0xFF202124.toInt(), 232),
                UiCorner.actionRadius(context).coerceAtLeast(24.dpToPx().toFloat())
            )

            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.background = UiCorner.opaqueRounded(0xFF2F3337.toInt(), 18.dpToPx().toFloat())
            capsule.addView(cover, LinearLayout.LayoutParams(42.dpToPx(), 42.dpToPx()))

            title.setTextColor(0xF2FFFFFF.toInt())
            title.textSize = 13f
            title.maxLines = 1
            title.includeFontPadding = false
            title.setPadding(9.dpToPx(), 0, 6.dpToPx(), 0)
            capsule.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            play.scaleType = ImageView.ScaleType.CENTER
            capsule.addView(play, LinearLayout.LayoutParams(34.dpToPx(), 42.dpToPx()))

            close.setImageResource(R.drawable.ic_close_x)
            close.alpha = 0.86f
            close.scaleType = ImageView.ScaleType.CENTER
            capsule.addView(close, LinearLayout.LayoutParams(34.dpToPx(), 42.dpToPx()))

            addView(
                capsule,
                LayoutParams(capsuleWidth, capsuleHeight).apply {
                    gravity = Gravity.START or Gravity.TOP
                }
            )
            capsule.setOnTouchListener(::onCapsuleTouch)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val left = capsule.x
            val top = capsule.y
            val inside = ev.x >= left &&
                    ev.x <= left + capsule.width &&
                    ev.y >= top &&
                    ev.y <= top + capsule.height
            return inside && super.dispatchTouchEvent(ev)
        }

        fun bind(state: ReadAloudPlaybackState) {
            val book = ReadBook.book ?: return
            title.text = book.name
            play.setImageResource(
                if (state.playing == true || BaseReadAloudService.isPlay()) {
                    R.drawable.ic_pause_24dp
                } else {
                    R.drawable.ic_play_24dp
                }
            )
            val coverKey = book.getDisplayCover().orEmpty()
            if (coverKey != lastCoverKey) {
                lastCoverKey = coverKey
                BookCover.load(context, coverKey).centerCrop().into(cover)
            }
            post { ensurePosition() }
        }

        private fun onCapsuleTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = view.x
                    downY = view.y
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        place(downX + dx, downY + dy)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        snap()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        handleTap(event.x)
                    }
                    return true
                }
            }
            return false
        }

        private fun handleTap(localX: Float) {
            val closeStart = capsule.width - 38.dpToPx()
            val playStart = closeStart - 38.dpToPx()
            when {
                localX >= closeStart -> {
                    ReadAloud.stop(appCtx)
                    visibility = GONE
                }

                localX >= playStart -> {
                    if (BaseReadAloudService.pause) {
                        ReadAloud.resume(appCtx)
                    } else {
                        ReadAloud.pause(appCtx)
                    }
                }

                else -> {
                    ReadBook.book?.let { book ->
                        context.startActivityForBook(book) {
                            putExtra("openReadAloudPanel", true)
                        }
                    }
                }
            }
        }

        private fun ensurePosition() {
            val maxX = (width - capsule.width - sideGap).coerceAtLeast(sideGap)
            val maxY = (height - capsule.height - bottomGap - safeBottom()).coerceAtLeast(sideGap)
            val x = (baseX ?: maxX.toFloat()).coerceIn(sideGap.toFloat(), maxX.toFloat())
            val y = (baseY ?: maxY.toFloat()).coerceIn(sideGap.toFloat(), maxY.toFloat())
            place(x, y)
        }

        private fun place(x: Float, y: Float) {
            val maxX = (width - capsule.width - sideGap).coerceAtLeast(sideGap)
            val maxY = (height - capsule.height - bottomGap - safeBottom()).coerceAtLeast(sideGap)
            baseX = x.coerceIn(sideGap.toFloat(), maxX.toFloat())
            baseY = y.coerceIn(sideGap.toFloat(), maxY.toFloat())
            capsule.x = baseX ?: sideGap.toFloat()
            capsule.y = baseY ?: sideGap.toFloat()
        }

        private fun snap() {
            val maxX = (width - capsule.width - sideGap).coerceAtLeast(sideGap)
            val targetX = if (capsule.x + capsule.width / 2f < width / 2f) {
                sideGap.toFloat()
            } else {
                maxX.toFloat()
            }
            place(targetX, capsule.y)
        }

        private fun safeBottom(): Int {
            return if (AppConfig.isEInkMode) 0 else 8.dpToPx()
        }
    }
}
