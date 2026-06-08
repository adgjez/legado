package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.MenuRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.dpToPx

object ModernActionPopup {

    data class Action(
        val title: String,
        val checked: Boolean = false,
        val invoke: () -> Unit
    )

    private data class PopupConstraints(
        val maxWidthPx: Int,
        val maxHeightPx: Int
    )

    fun show(
        anchor: View,
        actions: List<Action>,
        previousPopup: PopupWindow? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8
    ): PopupWindow? {
        if (actions.isEmpty()) return previousPopup
        val context = anchor.context
        var popup: PopupWindow? = null
        val constraints = calculatePopupConstraints(anchor, maxHeightRatio, bottomGapDp)
        val content = createContent(context, actions, constraints) {
            popup?.dismiss()
        }
        previousPopup?.dismiss()
        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            animationStyle = R.style.AnimPopupMenu
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 0f
            }
            showAnchored(anchor, constraints)
            content.post {
                updateAnchored(anchor, content, constraints)
            }
        }
        return popup
    }

    fun showFromMenu(
        anchor: View,
        @MenuRes menuRes: Int,
        previousPopup: PopupWindow? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8,
        prepare: (Menu.() -> Unit)? = null,
        onClick: (MenuItem) -> Boolean
    ): PopupWindow? {
        val popupMenu = PopupMenu(anchor.context, anchor)
        popupMenu.inflate(menuRes)
        prepare?.invoke(popupMenu.menu)
        val actions = mutableListOf<Action>()
        for (index in 0 until popupMenu.menu.size()) {
            val item = popupMenu.menu.getItem(index)
            if (item.isVisible) {
                actions.add(Action(item.title.toString(), item.isChecked) { onClick(item) })
            }
        }
        return show(anchor, actions, previousPopup, maxHeightRatio, bottomGapDp)
    }

    private fun createContent(
        context: Context,
        actions: List<Action>,
        constraints: PopupConstraints,
        dismiss: () -> Unit
    ): View {
        val maxWidth = minOf(280.dpToPx(), constraints.maxWidthPx).coerceAtLeast(1)
        val minWidth = minOf(132.dpToPx(), maxWidth).coerceAtLeast(1)
        val actionRadius = UiCorner.actionRadius(context)
        val selectedBackground = ColorUtils.blendARGB(
            ContextCompat.getColor(context, R.color.background_menu),
            context.accentColor,
            0.08f
        )
        val pressedBackground = ColorUtils.setAlphaComponent(
            context.accentColor,
            if (ColorUtils.calculateLuminance(context.accentColor) > 0.5) 42 else 52
        )
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
            background = UiCorner.opaqueRounded(
                ContextCompat.getColor(context, R.color.dialog_surface),
                UiCorner.panelRadius(context)
            )
        }
        actions.forEach { action ->
            val row = TextView(context).apply {
                text = action.title
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                minHeight = 42.dpToPx()
                setTextColor(if (action.checked) context.accentColor else context.primaryTextColor)
                setPadding(14.dpToPx(), 9.dpToPx(), 14.dpToPx(), 9.dpToPx())
                isSelected = action.checked
                background = UiCorner.actionSelector(
                    if (action.checked) selectedBackground else Color.TRANSPARENT,
                    pressedBackground,
                    actionRadius
                )
                setOnClickListener {
                    dismiss()
                    action.invoke()
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> view.alpha = 0.78f
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> view.alpha = 1f
                    }
                    false
                }
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 1.dpToPx()
                    bottomMargin = 1.dpToPx()
                }
            )
        }
        return ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            minimumWidth = minWidth
            layoutParams = ViewGroup.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 0f
            }
        }
    }

    private fun calculatePopupConstraints(
        anchor: View,
        maxHeightRatio: Float,
        bottomGapDp: Int
    ): PopupConstraints {
        val gap = 8.dpToPx()
        val bottomGap = bottomGapDp.dpToPx()
        val visibleFrame = Rect()
        anchor.rootView.getWindowVisibleDisplayFrame(visibleFrame)
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val belowSpace = visibleFrame.bottom - (location[1] + anchor.height) - gap - bottomGap
        val aboveSpace = location[1] - visibleFrame.top - gap
        val usableHeight = (visibleFrame.height() - gap * 2 - bottomGap).coerceAtLeast(1)
        val minimumHeight = minOf(72.dpToPx(), usableHeight).coerceAtLeast(1)
        val anchorSpace = maxOf(belowSpace, aboveSpace).coerceIn(
            minOf(48.dpToPx(), usableHeight).coerceAtLeast(1),
            usableHeight
        )
        val ratio = maxHeightRatio.coerceIn(0.35f, 0.9f)
        val ratioHeight = (usableHeight * ratio).toInt().coerceAtLeast(minimumHeight)
        val maxHeight = minOf(anchorSpace, ratioHeight)
            .coerceAtLeast(minimumHeight)
            .coerceAtMost(usableHeight)
        val maxWidth = (visibleFrame.width() - gap * 2).coerceAtLeast(1)
        return PopupConstraints(maxWidthPx = maxWidth, maxHeightPx = maxHeight)
    }

    private fun measureAttachedPopupSize(content: View, constraints: PopupConstraints): Pair<Int, Int> {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(constraints.maxWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(constraints.maxHeightPx, View.MeasureSpec.AT_MOST)
        )
        return content.measuredWidth.coerceAtMost(constraints.maxWidthPx) to
            content.measuredHeight.coerceAtMost(constraints.maxHeightPx)
    }

    private fun PopupWindow.showAnchored(anchor: View, constraints: PopupConstraints) {
        val fallbackWidth = minOf(280.dpToPx(), constraints.maxWidthPx).coerceAtLeast(1)
        val fallbackHeight = constraints.maxHeightPx.coerceAtLeast(1)
        val position = calculateAnchoredPosition(anchor, fallbackWidth, fallbackHeight)
        showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, position.first, position.second)
    }

    private fun PopupWindow.updateAnchored(
        anchor: View,
        content: View,
        constraints: PopupConstraints
    ) {
        if (!isShowing) return
        val (popupWidth, popupHeight) = measureAttachedPopupSize(content, constraints)
        val position = calculateAnchoredPosition(anchor, popupWidth, popupHeight)
        update(position.first, position.second, popupWidth, popupHeight)
    }

    private fun calculateAnchoredPosition(
        anchor: View,
        popupWidth: Int,
        popupHeight: Int
    ): Pair<Int, Int> {
        val gap = 4.dpToPx()
        val location = IntArray(2)
        val visibleFrame = Rect()
        anchor.getLocationOnScreen(location)
        anchor.rootView.getWindowVisibleDisplayFrame(visibleFrame)

        val desiredX = location[0] + anchor.width - popupWidth
        val x = desiredX.coerceIn(
            visibleFrame.left + gap,
            (visibleFrame.right - popupWidth - gap).coerceAtLeast(visibleFrame.left + gap)
        )
        val belowY = location[1] + anchor.height + gap
        val aboveY = location[1] - popupHeight - gap
        val hasRoomBelow = belowY + popupHeight <= visibleFrame.bottom - gap
        val y = if (hasRoomBelow || aboveY < visibleFrame.top + gap) {
            belowY
        } else {
            aboveY
        }.coerceIn(
            visibleFrame.top + gap,
            (visibleFrame.bottom - popupHeight - gap).coerceAtLeast(visibleFrame.top + gap)
        )
        return x to y
    }
}
