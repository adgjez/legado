package io.legado.app.ui.widget.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
    minThumbHeight: Int = 48
) {
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleItems = state.layoutInfo.visibleItemsInfo.size
    val maxFirstIndex = (totalItems - visibleItems).coerceAtLeast(0)
    // 连续滚动进度：整数索引 + 首个可见项已滚出的像素比例，消除按整项跳动的顿挫
    val firstItemSize = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
    val scrollProgress = if (maxFirstIndex <= 0) {
        0f
    } else {
        val offsetFraction = if (firstItemSize > 0) {
            state.firstVisibleItemScrollOffset.toFloat() / firstItemSize
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
    val maxFirstIndex = (totalItems - visibleItems).coerceAtLeast(0)
    val firstItemHeight = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: 0
    val scrollProgress = if (maxFirstIndex <= 0) {
        0f
    } else {
        val offsetFraction = if (firstItemHeight > 0) {
            state.firstVisibleItemScrollOffset.toFloat() / firstItemHeight
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
    minThumbHeight: Int,
    modifier: Modifier,
    onScrollToIndex: suspend (Int) -> Unit
) {
    if (!enabled || totalItems <= visibleItems || totalItems <= 0) return

    var trackHeight by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var thumbVisible by remember { mutableStateOf(false) }
    var hotzoneAlive by remember { mutableStateOf(false) }
    var dragThumbTop by remember { mutableFloatStateOf(0f) }
    val scrollTargets = remember {
        MutableSharedFlow<Int>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    val palette = rememberAppManagementPalette()
    // 视觉滑块：停止滚动后较快淡出，避免长期遮挡内容
    LaunchedEffect(isScrollInProgress, dragging) {
        if (isScrollInProgress || dragging) {
            thumbVisible = true
        } else {
            delay(900)
            thumbVisible = false
        }
    }
    // 拖拽热区：停止后仍保留数秒，滑块淡出也能直接拖回来（易抓取）
    LaunchedEffect(isScrollInProgress, dragging) {
        if (isScrollInProgress || dragging) {
            hotzoneAlive = true
        } else {
            delay(3000)
            hotzoneAlive = false
        }
    }
    val visible by animateFloatAsState(
        targetValue = if (dragging || isScrollInProgress || thumbVisible) 1f else 0f,
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
    val hotzoneVisible = dragging || isScrollInProgress || hotzoneAlive

    LaunchedEffect(scrollTargets, maxFirstIndex) {
        scrollTargets
            .distinctUntilChanged()
            .collectLatest { index ->
                onScrollToIndex(index.coerceIn(0, maxFirstIndex))
            }
    }

    Box(
        modifier = modifier
            .width(48.dp)
            .fillMaxHeight()
            .padding(top = 8.dp, end = 10.dp, bottom = 8.dp)
            .onSizeChanged { trackHeight = it.height }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val metrics = fastScrollerMetrics(
                totalItems = totalItems,
                visibleItems = visibleItems,
                scrollProgress = scrollProgress,
                maxFirstIndex = maxFirstIndex,
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
        if (hotzoneVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(if (dragging) 40.dp else 32.dp)
                    .fillMaxHeight()
                    .pointerInput(totalItems, visibleItems, trackHeight, maxFirstIndex) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                val metrics = fastScrollerMetrics(
                                    totalItems = totalItems,
                                    visibleItems = visibleItems,
                                    scrollProgress = scrollProgress,
                                    maxFirstIndex = maxFirstIndex,
                                    trackHeight = trackHeight,
                                    minThumbHeight = minThumbHeight
                                )
                                dragThumbTop = (offset.y - metrics.thumbHeight / 2f)
                                    .coerceIn(0f, metrics.maxThumbTop)
                                val index = metrics.indexForThumbTop(dragThumbTop)
                                scrollTargets.tryEmit(index)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val metrics = fastScrollerMetrics(
                                    totalItems = totalItems,
                                    visibleItems = visibleItems,
                                    scrollProgress = scrollProgress,
                                    maxFirstIndex = maxFirstIndex,
                                    trackHeight = trackHeight,
                                    minThumbHeight = minThumbHeight
                                )
                                dragThumbTop = (dragThumbTop + dragAmount.y)
                                    .coerceIn(0f, metrics.maxThumbTop)
                                val index = metrics.indexForThumbTop(dragThumbTop)
                                scrollTargets.tryEmit(index)
                            }
                        )
                    }
            )
        }
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
    minThumbHeight: Int
): FastScrollerMetrics {
    val safeTrackHeight = trackHeight.coerceAtLeast(1).toFloat()
    val safeVisibleItems = visibleItems.coerceAtLeast(1)
    val thumbHeight = (safeTrackHeight * safeVisibleItems / totalItems.coerceAtLeast(1))
        .coerceIn(minThumbHeight.toFloat(), safeTrackHeight)
    val maxThumbTop = (safeTrackHeight - thumbHeight).coerceAtLeast(0f)
    val thumbTop = (maxThumbTop * scrollProgress.coerceIn(0f, 1f)).coerceIn(0f, maxThumbTop)
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
