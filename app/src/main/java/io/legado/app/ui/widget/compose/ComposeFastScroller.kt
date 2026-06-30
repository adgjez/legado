package io.legado.app.ui.widget.compose

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import my.nanihadesuka.compose.InternalLazyColumnScrollbar
import my.nanihadesuka.compose.InternalLazyVerticalGridScrollbar
import my.nanihadesuka.compose.ScrollbarLayoutSide
import my.nanihadesuka.compose.ScrollbarSelectionActionable
import my.nanihadesuka.compose.ScrollbarSelectionMode
import my.nanihadesuka.compose.ScrollbarSettings

@Composable
fun ComposeLazyListFastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minThumbHeight: Dp = 44.dp,
    touchTargetWidth: Dp = 48.dp,
    dragHotZoneWidth: Dp = 40.dp
) {
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleItems = state.layoutInfo.visibleItemsInfo.size
    if (!enabled || totalItems <= visibleItems || visibleItems <= 0 || totalItems <= 0) return
    val settings = rememberLegadoScrollbarSettings(minThumbHeight)
    InternalLazyColumnScrollbar(
        state = state,
        modifier = modifier
            .fillMaxHeight()
            .width(maxOf(48.dp, touchTargetWidth, dragHotZoneWidth)),
        settings = settings
    )
}

@Composable
fun ComposeLazyGridFastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minThumbHeight: Dp = 44.dp,
    touchTargetWidth: Dp = 48.dp,
    dragHotZoneWidth: Dp = 40.dp
) {
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleItems = state.layoutInfo.visibleItemsInfo.size
    if (!enabled || totalItems <= visibleItems || visibleItems <= 0 || totalItems <= 0) return
    val settings = rememberLegadoScrollbarSettings(minThumbHeight)
    InternalLazyVerticalGridScrollbar(
        state = state,
        modifier = modifier
            .fillMaxHeight()
            .width(maxOf(48.dp, touchTargetWidth, dragHotZoneWidth)),
        settings = settings
    )
}

@Composable
private fun rememberLegadoScrollbarSettings(@Suppress("UNUSED_PARAMETER") minThumbHeight: Dp): ScrollbarSettings {
    val palette = rememberAppManagementPalette()
    val minThumbFraction = 0.08f
    return ScrollbarSettings(
        enabled = true,
        side = ScrollbarLayoutSide.End,
        alwaysShowScrollbar = false,
        scrollbarPadding = 4.dp,
        thumbThickness = 6.dp,
        thumbShape = CircleShape,
        thumbMinLength = minThumbFraction,
        thumbMaxLength = maxOf(minThumbFraction, 0.22f),
        thumbUnselectedColor = palette.settings.secondaryText.copy(alpha = 0.54f),
        thumbSelectedColor = palette.settings.secondaryText.copy(alpha = 0.82f),
        selectionMode = ScrollbarSelectionMode.Thumb,
        selectionActionable = ScrollbarSelectionActionable.WhenVisible,
        hideDelayMillis = 900,
        hideDisplacement = 10.dp,
        durationAnimationMillis = 160
    )
}
