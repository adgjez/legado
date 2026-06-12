package io.legado.app.ui.widget

import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.annotation.MenuRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx

object ModernActionPopup {

    private const val MIN_WIDTH_DP = 124
    private const val MAX_WIDTH_DP = 244
    private const val ROW_HEIGHT_DP = 44

    data class Action(
        val title: String,
        val description: String? = null,
        val iconName: String? = null,
        val checked: Boolean = false,
        val enabled: Boolean = true,
        val invoke: () -> Unit
    )

    class Handle internal constructor(
        private val visibleState: MutableState<Boolean>,
        private val overlay: ComposeView,
        private val host: ViewGroup,
        private val backCallback: OnBackPressedCallback?,
        private val anchor: View,
        private val anchorDetachListener: View.OnAttachStateChangeListener,
        private val hostDetachListener: View.OnAttachStateChangeListener
    ) {
        private var dismissed = false
        val isShowing: Boolean
            get() = !dismissed && overlay.parent != null

        fun dismiss() {
            if (dismissed) return
            dismissed = true
            visibleState.value = false
            backCallback?.remove()
            anchor.removeOnAttachStateChangeListener(anchorDetachListener)
            host.removeOnAttachStateChangeListener(hostDetachListener)
            val delay = if (AppConfig.isEInkMode) 0L else 170L
            overlay.postDelayed({
                if (overlay.parent === host) {
                    host.removeView(overlay)
                }
            }, delay)
        }
    }

    private data class AnchorSnapshot(
        val anchorLeft: Int,
        val anchorTop: Int,
        val anchorRight: Int,
        val anchorBottom: Int,
        val hostWidth: Int,
        val hostHeight: Int,
        val maxWidthPx: Int,
        val maxHeightPx: Int,
        val fallbackWidthPx: Int,
        val fallbackHeightPx: Int
    )

    fun show(
        anchor: View,
        actions: List<Action>,
        previousPopup: Handle? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8
    ): Handle? {
        if (actions.isEmpty()) return previousPopup
        val host = anchor.rootView as? ViewGroup
            ?: anchor.activity?.window?.decorView as? ViewGroup
            ?: return previousPopup
        val snapshot = calculateAnchorSnapshot(anchor, host, actions, maxHeightRatio, bottomGapDp)
        previousPopup?.dismiss()
        val visibleState = mutableStateOf(false)
        var handle: Handle? = null
        val overlay = ComposeView(host.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    handle?.dismiss()
                    true
                } else {
                    false
                }
            }
        }
        val anchorDetachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                handle?.dismiss()
            }
        }
        val hostDetachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                handle?.dismiss()
            }
        }
        anchor.addOnAttachStateChangeListener(anchorDetachListener)
        host.addOnAttachStateChangeListener(hostDetachListener)
        val backCallback = anchor.activity?.let { activity ->
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handle?.dismiss()
                }
            }.also { activity.onBackPressedDispatcher.addCallback(it) }
        }
        handle = Handle(
            visibleState = visibleState,
            overlay = overlay,
            host = host,
            backCallback = backCallback,
            anchor = anchor,
            anchorDetachListener = anchorDetachListener,
            hostDetachListener = hostDetachListener
        )
        overlay.setContent {
            ModernActionPopupOverlay(
                snapshot = snapshot,
                actions = actions,
                visible = visibleState.value,
                onDismiss = handle::dismiss
            )
        }
        host.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.requestFocus()
        overlay.post {
            visibleState.value = true
        }
        return handle
    }

    fun showFromMenu(
        anchor: View,
        @MenuRes menuRes: Int,
        previousPopup: Handle? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8,
        prepare: (Menu.() -> Unit)? = null,
        onClick: (MenuItem) -> Boolean
    ): Handle? {
        val popupMenu = PopupMenu(anchor.context, anchor)
        popupMenu.inflate(menuRes)
        prepare?.invoke(popupMenu.menu)
        val actions = mutableListOf<Action>()
        for (index in 0 until popupMenu.menu.size()) {
            val item = popupMenu.menu.getItem(index)
            if (item.isVisible) {
                actions.add(
                    Action(
                        title = item.title.toString(),
                        checked = item.isChecked,
                        enabled = item.isEnabled
                    ) {
                        onClick(item)
                    }
                )
            }
        }
        return show(anchor, actions, previousPopup, maxHeightRatio, bottomGapDp)
    }

    @Composable
    private fun ModernActionPopupOverlay(
        snapshot: AnchorSnapshot,
        actions: List<Action>,
        visible: Boolean,
        onDismiss: () -> Unit
    ) {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val density = LocalDensity.current
        val maxHeightDp = with(density) { snapshot.maxHeightPx.toDp() }
        var panelSize by remember { mutableStateOf(IntSize.Zero) }
        var panelBounds by remember { mutableStateOf<ComposeRect?>(null) }
        val panelWidth = panelSize.width.takeIf { it > 0 } ?: snapshot.fallbackWidthPx
        val panelHeight = panelSize.height.takeIf { it > 0 } ?: snapshot.fallbackHeightPx
        val panelOffset = calculatePanelOffset(snapshot, panelWidth, panelHeight)
        val panelTransformOrigin = calculateTransformOrigin(snapshot, panelOffset, panelWidth, panelHeight)
        val progress by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (AppConfig.isEInkMode) 0 else 150,
                easing = FastOutSlowInEasing
            ),
            label = "modernActionPopup"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(panelBounds) {
                    detectTapGestures { tap ->
                        val bounds = panelBounds
                        if (bounds == null || !bounds.contains(tap)) {
                            onDismiss()
                        }
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
            ) {
                LegadoMiuixCard(
                    modifier = Modifier
                        .offset { panelOffset }
                        .width(with(density) { snapshot.fallbackWidthPx.toDp() })
                        .heightIn(max = maxHeightDp)
                        .onSizeChanged { panelSize = it }
                        .onGloballyPositioned { panelBounds = it.boundsInRoot() }
                        .graphicsLayer {
                            alpha = progress
                            transformOrigin = panelTransformOrigin
                            scaleX = 0.96f + 0.04f * progress
                            scaleY = 0.96f + 0.04f * progress
                            translationY = (if (panelOffset.y >= snapshot.anchorBottom) -6f else 6f) *
                                (1f - progress)
                        },
                    color = style.surface,
                    contentColor = style.primaryText,
                    cornerRadius = style.panelRadius,
                    insidePadding = PaddingValues(6.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = maxHeightDp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(
                            items = actions,
                            key = { index, action -> "${action.title}#$index" }
                        ) { _, action ->
                            LegadoMiuixChoiceRow(
                                text = action.title,
                                selected = action.checked,
                                palette = palette,
                                onClick = {
                                    onDismiss()
                                    anchorPostAction(action.invoke)
                                },
                                minHeight = 42.dp,
                                compact = true,
                                showSelectedMark = action.checked,
                                enabled = action.enabled,
                                description = action.description,
                                leadingIconName = action.iconName
                            )
                        }
                    }
                }
            }
        }
    }

    private fun anchorPostAction(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    private fun calculateAnchorSnapshot(
        anchor: View,
        host: ViewGroup,
        actions: List<Action>,
        maxHeightRatio: Float,
        bottomGapDp: Int
    ): AnchorSnapshot {
        val gap = 8.dpToPx()
        val bottomGap = bottomGapDp.dpToPx()
        val hostLocation = IntArray(2)
        val anchorLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        anchor.getLocationOnScreen(anchorLocation)
        val hostWidth = host.width.takeIf { it > 0 } ?: anchor.rootView.width
        val hostHeight = host.height.takeIf { it > 0 } ?: anchor.rootView.height
        val anchorLeft = anchorLocation[0] - hostLocation[0]
        val anchorTop = anchorLocation[1] - hostLocation[1]
        val anchorRight = anchorLeft + anchor.width
        val anchorBottom = anchorTop + anchor.height
        val belowSpace = hostHeight - anchorBottom - gap - bottomGap
        val aboveSpace = anchorTop - gap
        val usableHeight = (hostHeight - gap * 2 - bottomGap).coerceAtLeast(1)
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
        val maxWidth = (hostWidth - gap * 2).coerceAtLeast(1)
        val fallbackWidth = estimatePanelWidthPx(actions, maxWidth)
        val rowHeight = ROW_HEIGHT_DP.dpToPx()
        val fallbackHeight = minOf(
            maxHeight,
            (actions.size * rowHeight + 12.dpToPx()).coerceAtLeast(minimumHeight)
        )
        return AnchorSnapshot(
            anchorLeft = anchorLeft,
            anchorTop = anchorTop,
            anchorRight = anchorRight,
            anchorBottom = anchorBottom,
            hostWidth = hostWidth,
            hostHeight = hostHeight,
            maxWidthPx = maxWidth,
            maxHeightPx = maxHeight,
            fallbackWidthPx = fallbackWidth,
            fallbackHeightPx = fallbackHeight
        )
    }

    private fun calculatePanelOffset(
        snapshot: AnchorSnapshot,
        panelWidth: Int,
        panelHeight: Int
    ): IntOffset {
        val gap = 4.dpToPx()
        val desiredX = snapshot.anchorRight - panelWidth
        val x = desiredX.coerceIn(
            gap,
            (snapshot.hostWidth - panelWidth - gap).coerceAtLeast(gap)
        )
        val belowY = snapshot.anchorBottom + gap
        val aboveY = snapshot.anchorTop - panelHeight - gap
        val hasRoomBelow = belowY + panelHeight <= snapshot.hostHeight - gap
        val y = if (hasRoomBelow || aboveY < gap) {
            belowY
        } else {
            aboveY
        }.coerceIn(
            gap,
            (snapshot.hostHeight - panelHeight - gap).coerceAtLeast(gap)
        )
        return IntOffset(x, y)
    }

    private fun calculateTransformOrigin(
        snapshot: AnchorSnapshot,
        panelOffset: IntOffset,
        panelWidth: Int,
        panelHeight: Int
    ): TransformOrigin {
        val anchorCenterX = (snapshot.anchorLeft + snapshot.anchorRight) / 2f
        val anchorCenterY = (snapshot.anchorTop + snapshot.anchorBottom) / 2f
        val pivotX = ((anchorCenterX - panelOffset.x) / panelWidth.coerceAtLeast(1))
            .coerceIn(0f, 1f)
        val pivotY = ((anchorCenterY - panelOffset.y) / panelHeight.coerceAtLeast(1))
            .coerceIn(0f, 1f)
        return TransformOrigin(pivotX, pivotY)
    }

    private fun estimatePanelWidthPx(actions: List<Action>, maxWidthPx: Int): Int {
        val longest = actions.maxOfOrNull { it.title.length } ?: 0
        val estimatedDp = (56 + longest.coerceAtMost(14) * 13)
            .coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)
        return minOf(estimatedDp.dpToPx(), maxWidthPx).coerceAtLeast(1)
    }
}
