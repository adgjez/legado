package io.legado.app.ui.main.compose.readrecord

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.ColorUtils

@Composable
fun ReadRecordScreen(
    state: ReadRecordUiState,
    modifier: Modifier = Modifier,
    actions: ReadRecordActions = ReadRecordActions(),
    contentPadding: PaddingValues = PaddingValues(bottom = 92.dp)
) {
    val style = readRecordComposeStyle()
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(style.colors.pageBackground)
            .statusBarsPadding(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            ReadRecordHeader(state, style, actions)
        }
        if (!state.hasContent) {
            item(key = "empty") {
                EmptyReadRecordCard(state.emptyHint, style)
            }
        } else {
            state.componentOrder.forEach { component ->
                when (component) {
                    ReadRecordComponentUi.Goal -> {
                        state.goal?.let {
                            item(key = "goal") {
                                GoalCard(goal = it, style = style, onClick = actions.onGoalClick)
                            }
                        }
                    }

                    ReadRecordComponentUi.Overview -> {
                        if (state.overview.isNotEmpty()) {
                            item(key = "overview") {
                                OverviewSection(metrics = state.overview, style = style)
                            }
                        }
                    }

                    ReadRecordComponentUi.Heatmap -> {
                        if (state.heatmap.isNotEmpty()) {
                            item(key = "heatmap") {
                                HeatmapSection(cells = state.heatmap, style = style)
                            }
                        }
                    }

                    ReadRecordComponentUi.RecentBooks -> {
                        if (state.recentBooks.isNotEmpty()) {
                            item(key = "recent") {
                                RecentBooksSection(
                                    books = state.recentBooks,
                                    style = style,
                                    onClick = actions.onOpenBook,
                                    onLongClick = actions.onBookLongClick
                                )
                            }
                        }
                    }

                    ReadRecordComponentUi.Ranking -> {
                        if (state.rankItems.isNotEmpty()) {
                            item(key = "rank") {
                                RankingSection(
                                    items = state.rankItems,
                                    style = style,
                                    onShowAll = actions.onShowAllRank,
                                    onClick = actions.onRankItemClick
                                )
                            }
                        }
                    }

                    ReadRecordComponentUi.DailyRecords -> {
                        if (state.dailyRecords.isNotEmpty()) {
                            item(key = "daily") {
                                DailyRecordsSection(records = state.dailyRecords, style = style)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadRecordHeader(
    state: ReadRecordUiState,
    style: ReadRecordComposeStyle,
    actions: ReadRecordActions
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = actions.onDateClick)
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = state.title,
                color = style.colors.primaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (state.subtitle.isNotBlank()) {
                Text(
                    text = state.subtitle,
                    color = style.colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        Text(
            text = "组件",
            color = style.colors.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = actions.onMoreClick)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun OverviewSection(metrics: List<ReadRecordMetricUi>, style: ReadRecordComposeStyle) {
    SectionBlock(title = "概览", style = style) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            metrics.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { metric ->
                        MetricTile(
                            metric = metric,
                            style = style,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    metric: ReadRecordMetricUi,
    style: ReadRecordComposeStyle,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.heightIn(min = 84.dp),
        shape = RoundedCornerShape(8.dp),
        color = style.colors.cardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = metric.label,
                color = style.colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = metric.value,
                color = style.colors.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (metric.helper.isNotBlank()) {
                Text(
                    text = metric.helper,
                    color = style.colors.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun GoalCard(goal: ReadRecordGoalUi, style: ReadRecordComposeStyle, onClick: () -> Unit) {
    SectionBlock(title = goal.title, style = style, trailing = goal.actionText, onTrailingClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GoalRing(progress = goal.normalizedProgress, style = style, modifier = Modifier.size(70.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = goal.userName.ifBlank { "今日阅读" },
                    color = style.colors.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${goal.currentText} / ${goal.targetText}",
                    color = style.colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
                LinearProgressIndicator(
                    progress = { goal.normalizedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = style.colors.accent,
                    trackColor = style.colors.accent.copy(alpha = 0.12f)
                )
                if (goal.helper.isNotBlank()) {
                    Text(
                        text = goal.helper,
                        color = style.colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalRing(progress: Float, style: ReadRecordComposeStyle, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            drawCircle(
                color = style.colors.accent.copy(alpha = 0.12f),
                style = Stroke(width = stroke)
            )
            drawArc(
                color = style.colors.accent,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = stroke)
            )
        }
        Text(
            text = "${(progress * 100).toInt()}%",
            color = style.colors.primaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HeatmapSection(cells: List<ReadRecordHeatmapCellUi>, style: ReadRecordComposeStyle) {
    SectionBlock(title = "阅读热力", style = style) {
        ReadRecordHeatmap(
            cells = cells,
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
        )
    }
}

@Composable
private fun ReadRecordHeatmap(
    cells: List<ReadRecordHeatmapCellUi>,
    style: ReadRecordComposeStyle,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (cells.isEmpty()) return@Canvas
        val rows = 7
        val columns = ((cells.size + rows - 1) / rows).coerceAtLeast(1)
        val gap = 4.dp.toPx()
        val cellSize = minOf(
            (size.width - gap * (columns - 1)) / columns,
            (size.height - gap * (rows - 1)) / rows
        ).coerceAtLeast(1f)
        val contentWidth = cellSize * columns + gap * (columns - 1)
        val contentHeight = cellSize * rows + gap * (rows - 1)
        val startX = (size.width - contentWidth) / 2f
        val startY = (size.height - contentHeight) / 2f
        cells.forEachIndexed { index, cell ->
            val column = index / rows
            val row = index % rows
            val alpha = 0.10f + cell.normalizedLevel * 0.72f
            drawRoundRect(
                color = style.colors.accent.copy(alpha = alpha),
                topLeft = Offset(startX + column * (cellSize + gap), startY + row * (cellSize + gap)),
                size = androidx.compose.ui.geometry.Size(cellSize, cellSize),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            )
        }
    }
}

@Composable
private fun RecentBooksSection(
    books: List<ReadRecordBookUi>,
    style: ReadRecordComposeStyle,
    onClick: (ReadRecordBookUi) -> Unit,
    onLongClick: (ReadRecordBookUi) -> Unit
) {
    SectionBlock(title = "最近在读", style = style) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(books, key = { it.id }) { book ->
                RecentBookCard(
                    book = book,
                    style = style,
                    onClick = { onClick(book) },
                    onLongClick = { onLongClick(book) }
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun RecentBookCard(
    book: ReadRecordBookUi,
    style: ReadRecordComposeStyle,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(132.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
        color = style.colors.cardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(style.colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = book.title.take(1).ifBlank { "书" },
                    color = style.colors.accent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = book.title,
                color = style.colors.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
            if (book.author.isNotBlank()) {
                Text(
                    text = book.author,
                    color = style.colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Text(
                text = listOf(book.progressText, book.lastReadText).filter { it.isNotBlank() }.joinToString(" · "),
                color = style.colors.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun RankingSection(
    items: List<ReadRecordRankUi>,
    style: ReadRecordComposeStyle,
    onShowAll: () -> Unit,
    onClick: (ReadRecordRankUi) -> Unit
) {
    SectionBlock(title = "阅读排行", style = style, trailing = "全部", onTrailingClick = onShowAll) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.take(5).forEachIndexed { index, item ->
                RankRow(index = index, item = item, style = style, onClick = { onClick(item) })
            }
        }
    }
}

@Composable
private fun RankRow(index: Int, item: ReadRecordRankUi, style: ReadRecordComposeStyle, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (index < 3) style.colors.accent.copy(alpha = 0.16f) else style.colors.cardSurface),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${index + 1}",
                color = if (index < 3) style.colors.accent else style.colors.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = style.colors.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.author,
                color = style.colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { item.normalizedPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = style.colors.accent,
                trackColor = style.colors.accent.copy(alpha = 0.10f)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = item.readTimeText,
            color = style.colors.secondaryText,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun DailyRecordsSection(records: List<ReadRecordDailyUi>, style: ReadRecordComposeStyle) {
    SectionBlock(title = "每日记录", style = style) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            records.take(7).forEach { record ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.dateText,
                            color = style.colors.primaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (record.bookCountText.isNotBlank()) {
                            Text(
                                text = record.bookCountText,
                                color = style.colors.secondaryText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = record.readTimeText,
                        color = style.colors.secondaryText,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionBlock(
    title: String,
    style: ReadRecordComposeStyle,
    trailing: String? = null,
    onTrailingClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = style.colors.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            trailing?.let {
                Text(
                    text = it,
                    color = style.colors.accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onTrailingClick)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(style.metrics.cardRadius),
            color = style.colors.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            Box(modifier = Modifier.padding(14.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun EmptyReadRecordCard(text: String, style: ReadRecordComposeStyle) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(style.metrics.cardRadius),
        color = style.colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
    ) {
        Text(
            text = text,
            color = style.colors.secondaryText,
            fontSize = 14.sp,
            modifier = Modifier.padding(22.dp)
        )
    }
}

@Immutable
private data class ReadRecordColors(
    val accent: Color,
    val pageBackground: Color,
    val surface: Color,
    val cardSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val stroke: Color
)

@Immutable
private data class ReadRecordMetrics(
    val cardRadius: Dp
)

@Immutable
private data class ReadRecordComposeStyle(
    val colors: ReadRecordColors,
    val metrics: ReadRecordMetrics
)

@Stable
@Composable
private fun readRecordComposeStyle(): ReadRecordComposeStyle {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    val background = ContextCompat.getColor(context, if (night) R.color.md_grey_900 else R.color.white)
    val surface = if (night) 0xff202329.toInt() else 0xffffffff.toInt()
    val cardSurface = if (night) 0xff282c32.toInt() else 0xfff6f8fa.toInt()
    val primaryText = if (night) 0xfff2f3f5.toInt() else 0xff202124.toInt()
    val secondaryText = if (night) 0xffaeb4bc.toInt() else 0xff66707a.toInt()
    return ReadRecordComposeStyle(
        colors = ReadRecordColors(
            accent = Color(accent),
            pageBackground = Color(background),
            surface = Color(surface),
            cardSurface = Color(cardSurface),
            primaryText = Color(primaryText),
            secondaryText = Color(secondaryText),
            stroke = Color(ColorUtils.adjustAlpha(if (night) 0xffffffff.toInt() else 0xff000000.toInt(), 0.10f))
        ),
        metrics = ReadRecordMetrics(cardRadius = 8.dp)
    )
}
