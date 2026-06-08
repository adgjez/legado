package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.PopupWindow
import androidx.annotation.MenuRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.dpToPx

object ModernActionPopup {

    data class Action(
        val title: String,
        val checked: Boolean = false,
        val invoke: () -> Unit
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
        val maxHeightPx = calculatePopupMaxHeight(anchor, maxHeightRatio, bottomGapDp)
        val content = createContent(context, actions, maxHeightPx) {
            popup?.dismiss()
        }
        previousPopup?.dismiss()
        val popupSize = measurePopupSize(content, maxHeightPx)
        popup = PopupWindow(
            content,
            popupSize.first,
            popupSize.second,
            true
        ).apply {
            animationStyle = R.style.AnimPopupMenu
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 0f
            }
            showAnchored(anchor, content)
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
        maxHeightPx: Int,
        dismiss: () -> Unit
    ): ComposeView {
        val density = context.resources.displayMetrics.density
        val maxHeightDp = (maxHeightPx / density).dp
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    LegadoMiuixCard(
                        modifier = Modifier
                            .widthIn(min = 132.dp, max = 280.dp)
                            .heightIn(max = maxHeightDp),
                        color = style.surface,
                        contentColor = style.primaryText,
                        cornerRadius = style.panelRadius,
                        insidePadding = PaddingValues(6.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = maxHeightDp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(actions) { action ->
                                LegadoMiuixChoiceRow(
                                    text = action.title,
                                    selected = action.checked,
                                    palette = palette,
                                    onClick = {
                                        dismiss()
                                        action.invoke()
                                    },
                                    minHeight = 42.dp,
                                    compact = true,
                                    showSelectedMark = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun calculatePopupMaxHeight(
        anchor: View,
        maxHeightRatio: Float,
        bottomGapDp: Int
    ): Int {
        val gap = 8.dpToPx()
        val bottomGap = bottomGapDp.dpToPx()
        val visibleFrame = Rect()
        anchor.rootView.getWindowVisibleDisplayFrame(visibleFrame)
        val ratio = maxHeightRatio.coerceIn(0.35f, 0.9f)
        return ((visibleFrame.height() - gap - bottomGap) * ratio).toInt()
            .coerceAtLeast(160.dpToPx())
    }

    private fun measurePopupSize(
        content: View,
        maxHeight: Int
    ): Pair<Int, Int> {
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return content.measuredWidth to content.measuredHeight.coerceAtMost(maxHeight)
    }

    private fun PopupWindow.showAnchored(anchor: View, content: View) {
        val gap = 4.dpToPx()
        val location = IntArray(2)
        val visibleFrame = Rect()
        anchor.getLocationOnScreen(location)
        anchor.rootView.getWindowVisibleDisplayFrame(visibleFrame)

        val popupWidth = width
        val popupHeight = height
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
        showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, x, y)
    }
}
