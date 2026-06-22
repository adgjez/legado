package io.legado.app.ui.widget.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ComposeLazyListFastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minThumbHeight: Int = 48
) {
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleItems = state.layoutInfo.visibleItemsInfo.size
    val firstVisibleIndex = state.firstVisibleItemIndex
    ComposeFastScroller(
        totalItems = totalItems,
        visibleItems = visibleItems,
        firstVisibleIndex = firstVisibleIndex,
        isScrollInProgress = state.isScrollInProgress,
        enabled = enabled,
        minThumbHeight = minThumbHeight,
        modifier = modifier,
        onScrollToIndex = { index -> state.scrollToItem(index) }
    )
}

@Composable
fun ComposeLazyGridFastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minThumbHeight: Int = 48
) {
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleItems = state.layoutInfo.visibleItemsInfo.size
    val firstVisibleIndex = state.firstVisibleItemIndex
    ComposeFastScroller(
        totalItems = totalItems,
        visibleItems = visibleItems,
        firstVisibleIndex = firstVisibleIndex,
        isScrollInProgress = state.isScrollInProgress,
        enabled = enabled,
        minThumbHeight = minThumbHeight,
        modifier = modifier,
        onScrollToIndex = { index -> state.scrollToItem(index) }
    )
}

@Composable
private fun ComposeFastScroller(
    totalItems: Int,
    visibleItems: Int,
    firstVisibleIndex: Int,
    isScrollInProgress: Boolean,
    enabled: Boolean,
    minThumbHeight: Int,
    modifier: Modifier,
    onScrollToIndex: suspend (Int) -> Unit
) {
    if (!enabled || totalItems <= visibleItems || totalItems <= 0) return

    val scope = rememberCoroutineScope()
    var trackHeight by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var recentlyVisible by remember { mutableStateOf(false) }
    var dragThumbTop by remember { mutableFloatStateOf(0f) }
    val palette = rememberAppManagementPalette()
    LaunchedEffect(isScrollInProgress, dragging) {
        if (isScrollInProgress || dragging) {
            recentlyVisible = true
        } else {
            delay(800)
            recentlyVisible = false
        }
    }
    val visible by animateFloatAsState(
        targetValue = if (dragging || isScrollInProgress || recentlyVisible) 1f else 0f,
        label = "composeFastScrollerAlpha"
    )
    val thumbWidth by animateFloatAsState(
        targetValue = if (dragging) 8f else 6f,
        label = "composeFastScrollerThumbWidth"
    )
    val thumbAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.80f else 0.52f,
        label = "composeFastScrollerThumbAlpha"
    )
    val trackAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.10f else 0f,
        label = "composeFastScrollerTrackAlpha"
    )

    Canvas(
        modifier = modifier
            .width(40.dp)
            .fillMaxHeight()
            .padding(top = 8.dp, end = 6.dp, bottom = 8.dp)
            .onSizeChanged { trackHeight = it.height }
            .pointerInput(totalItems, visibleItems, trackHeight) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        val metrics = fastScrollerMetrics(
                            totalItems = totalItems,
                            visibleItems = visibleItems,
                            firstVisibleIndex = firstVisibleIndex,
                            trackHeight = trackHeight,
                            minThumbHeight = minThumbHeight
                        )
                        dragThumbTop = (offset.y - metrics.thumbHeight / 2f)
                            .coerceIn(0f, metrics.maxThumbTop)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val metrics = fastScrollerMetrics(
                            totalItems = totalItems,
                            visibleItems = visibleItems,
                            firstVisibleIndex = firstVisibleIndex,
                            trackHeight = trackHeight,
                            minThumbHeight = minThumbHeight
                        )
                        dragThumbTop = (dragThumbTop + dragAmount.y)
                            .coerceIn(0f, metrics.maxThumbTop)
                        val index = metrics.indexForThumbTop(dragThumbTop)
                        scope.launch { onScrollToIndex(index) }
                    }
                )
            }
    ) {
        val metrics = fastScrollerMetrics(
            totalItems = totalItems,
            visibleItems = visibleItems,
            firstVisibleIndex = firstVisibleIndex,
            trackHeight = size.height.roundToInt(),
            minThumbHeight = minThumbHeight
        )
        val thumbTop = if (dragging) dragThumbTop else metrics.thumbTop
        drawFastScroller(
            alpha = visible,
            thumbTop = thumbTop,
            thumbHeight = metrics.thumbHeight,
            thumbWidthDp = thumbWidth,
            thumbAlpha = thumbAlpha,
            trackAlpha = trackAlpha,
            color = palette.settings.secondaryText
        )
    }
}

private data class FastScrollerMetrics(
    val thumbTop: Float,
    val thumbHeight: Float,
    val maxThumbTop: Float,
    val maxFirstIndex: Int
) {
    fun indexForThumbTop(top: Float): Int {
        if (maxThumbTop <= 0f || maxFirstIndex <= 0) return 0
        return ((top / maxThumbTop) * maxFirstIndex).roundToInt().coerceIn(0, maxFirstIndex)
    }
}

private fun fastScrollerMetrics(
    totalItems: Int,
    visibleItems: Int,
    firstVisibleIndex: Int,
    trackHeight: Int,
    minThumbHeight: Int
): FastScrollerMetrics {
    val safeTrackHeight = trackHeight.coerceAtLeast(1).toFloat()
    val safeVisibleItems = visibleItems.coerceAtLeast(1)
    val maxFirstIndex = (totalItems - safeVisibleItems).coerceAtLeast(0)
    val thumbHeight = (safeTrackHeight * safeVisibleItems / totalItems.coerceAtLeast(1))
        .coerceIn(minThumbHeight.toFloat(), safeTrackHeight)
    val maxThumbTop = (safeTrackHeight - thumbHeight).coerceAtLeast(0f)
    val progress = if (maxFirstIndex == 0) 0f else firstVisibleIndex.toFloat() / maxFirstIndex
    val thumbTop = (maxThumbTop * progress).coerceIn(0f, maxThumbTop)
    return FastScrollerMetrics(
        thumbTop = thumbTop,
        thumbHeight = thumbHeight,
        maxThumbTop = maxThumbTop,
        maxFirstIndex = maxFirstIndex
    )
}

private fun DrawScope.drawFastScroller(
    alpha: Float,
    thumbTop: Float,
    thumbHeight: Float,
    thumbWidthDp: Float,
    thumbAlpha: Float,
    trackAlpha: Float,
    color: Color
) {
    if (alpha <= 0.01f) return
    val trackWidth = 2.dp.toPx()
    val thumbWidth = thumbWidthDp.dp.toPx()
    val centerX = size.width - thumbWidth / 2f
    if (trackAlpha > 0f) {
        drawRoundRect(
            color = color.copy(alpha = trackAlpha * alpha),
            topLeft = Offset(centerX - trackWidth / 2f, 0f),
            size = Size(trackWidth, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackWidth, trackWidth)
        )
    }
    drawRoundRect(
        color = color.copy(alpha = thumbAlpha * alpha),
        topLeft = Offset(centerX - thumbWidth / 2f, thumbTop),
        size = Size(thumbWidth, thumbHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbWidth, thumbWidth)
    )
}
