package io.legado.app.ui.widget.compose

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

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
    val maxFirstIndex = (totalItems - visibleItems).coerceAtLeast(0)
    val averageItemSize = averageVisibleListItemSize(state)
    val scrollProgress = if (maxFirstIndex <= 0) {
        0f
    } else {
        val offsetFraction = if (averageItemSize > 0f) {
            state.firstVisibleItemScrollOffset / averageItemSize
        } else {
            0f
        }
        ((state.firstVisibleItemIndex + offsetFraction) / maxFirstIndex).coerceIn(0f, 1f)
    }
    ComposeFastScroller(
        totalItems = totalItems,
        visibleItems = visibleItems,
        scrollProgress = scrollProgress,
        maxFirstIndex = maxFirstIndex,
        isScrollInProgress = state.isScrollInProgress,
        enabled = enabled,
        minThumbHeight = minThumbHeight,
        touchTargetWidth = touchTargetWidth,
        dragHotZoneWidth = dragHotZoneWidth,
        modifier = modifier,
        onScrollToIndex = { index -> state.scrollToItem(index) }
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
    val maxFirstIndex = (totalItems - visibleItems).coerceAtLeast(0)
    val gridLineMetrics = averageVisibleGridLineMetrics(state)
    val scrollProgress = if (maxFirstIndex <= 0) {
        0f
    } else {
        val offsetFraction = if (gridLineMetrics.averageLineHeight > 0f) {
            state.firstVisibleItemScrollOffset /
                gridLineMetrics.averageLineHeight *
                gridLineMetrics.firstLineItemCount
        } else {
            0f
        }
        ((state.firstVisibleItemIndex + offsetFraction) / maxFirstIndex).coerceIn(0f, 1f)
    }
    ComposeFastScroller(
        totalItems = totalItems,
        visibleItems = visibleItems,
        scrollProgress = scrollProgress,
        maxFirstIndex = maxFirstIndex,
        isScrollInProgress = state.isScrollInProgress,
        enabled = enabled,
        minThumbHeight = minThumbHeight,
        touchTargetWidth = touchTargetWidth,
        dragHotZoneWidth = dragHotZoneWidth,
        modifier = modifier,
        onScrollToIndex = { index -> state.scrollToItem(index) }
    )
}

@Composable
private fun ComposeFastScroller(
    totalItems: Int,
    visibleItems: Int,
    scrollProgress: Float,
    maxFirstIndex: Int,
    isScrollInProgress: Boolean,
    enabled: Boolean,
    minThumbHeight: Dp,
    touchTargetWidth: Dp,
    dragHotZoneWidth: Dp,
    modifier: Modifier,
    onScrollToIndex: suspend (Int) -> Unit
) {
    if (!enabled || totalItems <= visibleItems || visibleItems <= 0 || totalItems <= 0) return

    var trackHeight by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var thumbVisible by remember { mutableStateOf(false) }
    var dragThumbTop by remember { mutableFloatStateOf(0f) }
    var dragGrabOffset by remember { mutableFloatStateOf(0f) }
    val scrollTargets = remember {
        MutableSharedFlow<Int>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    val palette = rememberAppManagementPalette()
    val density = LocalDensity.current
    val minThumbHeightPx = with(density) { minThumbHeight.toPx() }
    val maxThumbHeightPx = with(density) { 72.dp.toPx() }
    val dragHotZoneWidthPx = with(density) { dragHotZoneWidth.toPx() }
    val thumbTouchSlopPx = with(density) { 12.dp.toPx() }
    val latestScrollProgress by rememberUpdatedState(scrollProgress)
    val active = dragging || isScrollInProgress || thumbVisible

    LaunchedEffect(isScrollInProgress, dragging) {
        if (isScrollInProgress || dragging) {
            thumbVisible = true
        } else {
            delay(900)
            thumbVisible = false
        }
    }

    val visible by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (active) 120 else 200,
            easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        ),
        label = "composeFastScrollerAlpha"
    )
    val thumbWidth by animateFloatAsState(
        targetValue = if (dragging) 8f else 6.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "composeFastScrollerThumbWidth"
    )
    val thumbAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.82f else 0.54f,
        animationSpec = tween(
            durationMillis = 140,
            easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        ),
        label = "composeFastScrollerThumbAlpha"
    )
    val trackAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.10f else 0f,
        animationSpec = tween(
            durationMillis = 160,
            easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        ),
        label = "composeFastScrollerTrackAlpha"
    )
    val thumbOffsetX by animateFloatAsState(
        targetValue = when {
            dragging -> 0f
            active -> 1f
            else -> 14f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "composeFastScrollerThumbOffset"
    )
    val metrics = fastScrollerMetrics(
        totalItems = totalItems,
        visibleItems = visibleItems,
        scrollProgress = scrollProgress,
        maxFirstIndex = maxFirstIndex,
        trackHeight = trackHeight,
        minThumbHeight = minThumbHeightPx,
        maxThumbHeight = maxThumbHeightPx
    )
    val settledThumbTop by animateFloatAsState(
        targetValue = metrics.thumbTop,
        animationSpec = tween(
            durationMillis = 80,
            easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        ),
        label = "composeFastScrollerThumbTop"
    )

    LaunchedEffect(scrollTargets, maxFirstIndex) {
        var lastIndex = -1
        scrollTargets
            .distinctUntilChanged()
            .collectLatest { index ->
                withFrameNanos { }
                val targetIndex = index.coerceIn(0, maxFirstIndex)
                if (targetIndex != lastIndex) {
                    lastIndex = targetIndex
                    onScrollToIndex(targetIndex)
                }
            }
    }

    Box(
        modifier = modifier
            .width(maxOf(56.dp, touchTargetWidth))
            .fillMaxHeight()
            .padding(top = 8.dp, end = 10.dp, bottom = 8.dp)
            .onSizeChanged { trackHeight = it.height }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val thumbTop = if (dragging) {
                dragThumbTop
            } else if (isScrollInProgress) {
                metrics.thumbTop
            } else {
                settledThumbTop.coerceIn(0f, metrics.maxThumbTop)
            }
            drawFastScroller(
                alpha = visible,
                thumbTop = thumbTop,
                thumbHeight = metrics.thumbHeight,
                thumbWidthDp = thumbWidth,
                thumbOffsetXDp = thumbOffsetX,
                thumbAlpha = thumbAlpha,
                trackAlpha = trackAlpha,
                color = palette.settings.secondaryText
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(touchTargetWidth)
                .fillMaxHeight()
                .pointerInput(
                    totalItems,
                    visibleItems,
                    trackHeight,
                    maxFirstIndex,
                    minThumbHeightPx,
                    maxThumbHeightPx,
                    dragHotZoneWidthPx,
                    touchTargetWidth
                ) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        if (down.position.x < size.width.toFloat() - dragHotZoneWidthPx) {
                            return@awaitEachGesture
                        }
                        down.consume()
                        val downMetrics = fastScrollerMetrics(
                            totalItems = totalItems,
                            visibleItems = visibleItems,
                            scrollProgress = latestScrollProgress,
                            maxFirstIndex = maxFirstIndex,
                            trackHeight = trackHeight,
                            minThumbHeight = minThumbHeightPx,
                            maxThumbHeight = maxThumbHeightPx
                        )
                        val downThumbTop = downMetrics.thumbTop
                        val downThumbBottom = downThumbTop + downMetrics.thumbHeight
                        val downOnThumb = down.position.y in
                            (downThumbTop - thumbTouchSlopPx)..(downThumbBottom + thumbTouchSlopPx)
                        if (!downOnThumb) {
                            thumbVisible = true
                            val pointerId = down.id
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                                change.consume()
                                if (!change.pressed) break
                            }
                            return@awaitEachGesture
                        }
                        fun startDrag(positionY: Float) {
                            dragging = true
                            dragGrabOffset = (positionY - downThumbTop)
                                .coerceIn(0f, downMetrics.thumbHeight)
                            dragThumbTop = downThumbTop.coerceIn(0f, downMetrics.maxThumbTop)
                        }
                        fun updateDrag(positionY: Float) {
                            val currentMetrics = fastScrollerMetrics(
                                totalItems = totalItems,
                                visibleItems = visibleItems,
                                scrollProgress = latestScrollProgress,
                                maxFirstIndex = maxFirstIndex,
                                trackHeight = trackHeight,
                                minThumbHeight = minThumbHeightPx,
                                maxThumbHeight = maxThumbHeightPx
                            )
                            dragThumbTop = (positionY - dragGrabOffset)
                                .coerceIn(0f, currentMetrics.maxThumbTop)
                            scrollTargets.tryEmit(currentMetrics.indexForThumbTop(dragThumbTop))
                        }
                        startDrag(down.position.y)
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull()
                                ?: break
                            if (!change.pressed) break
                            change.consume()
                            updateDrag(change.position.y)
                        }
                        dragging = false
                    }
                }
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
    scrollProgress: Float,
    maxFirstIndex: Int,
    trackHeight: Int,
    minThumbHeight: Float,
    maxThumbHeight: Float
): FastScrollerMetrics {
    val safeTrackHeight = trackHeight.coerceAtLeast(1).toFloat()
    val proportionalThumbHeight = safeTrackHeight * visibleItems.coerceAtLeast(1) /
        totalItems.coerceAtLeast(1)
    val safeMaxThumbHeight = minOf(maxThumbHeight, safeTrackHeight * 0.22f)
    val stableThumbHeight = proportionalThumbHeight
        .coerceIn(minThumbHeight, safeMaxThumbHeight.coerceAtLeast(minThumbHeight))
        .coerceIn(1f, safeTrackHeight)
    val maxThumbTop = (safeTrackHeight - stableThumbHeight).coerceAtLeast(0f)
    val thumbTop = (maxThumbTop * scrollProgress.coerceIn(0f, 1f)).coerceIn(0f, maxThumbTop)
    return FastScrollerMetrics(
        thumbTop = thumbTop,
        thumbHeight = stableThumbHeight,
        maxThumbTop = maxThumbTop,
        maxFirstIndex = maxFirstIndex
    )
}

private fun averageVisibleListItemSize(state: LazyListState): Float {
    val visibleItems = state.layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return 0f
    return visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
}

private data class GridLineMetrics(
    val averageLineHeight: Float,
    val firstLineItemCount: Int
)

private fun averageVisibleGridLineMetrics(state: LazyGridState): GridLineMetrics {
    val visibleItems = state.layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return GridLineMetrics(0f, 1)
    val lines = visibleItems.groupBy { it.offset.y }
    val firstLineOffset = lines.keys.minOrNull() ?: return GridLineMetrics(0f, 1)
    val averageLineHeight = lines.values
        .sumOf { line -> line.maxOf { it.size.height } }
        .toFloat() / lines.size
    return GridLineMetrics(
        averageLineHeight = averageLineHeight,
        firstLineItemCount = lines[firstLineOffset]?.size?.coerceAtLeast(1) ?: 1
    )
}

private fun DrawScope.drawFastScroller(
    alpha: Float,
    thumbTop: Float,
    thumbHeight: Float,
    thumbWidthDp: Float,
    thumbOffsetXDp: Float,
    thumbAlpha: Float,
    trackAlpha: Float,
    color: Color
) {
    if (alpha <= 0.01f) return
    val trackWidth = 2.dp.toPx()
    val thumbWidth = thumbWidthDp.dp.toPx()
    val thumbOffsetX = thumbOffsetXDp.dp.toPx()
    val centerX = size.width - thumbWidth / 2f + thumbOffsetX
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
