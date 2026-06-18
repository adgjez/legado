package io.legado.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.releaseComposeImage
import io.legado.app.ui.widget.image.CoverImageView

@Immutable
data class ReadRecordOverviewUi(
    val todayValue: String,
    val todayLabel: String,
    val monthValue: String,
    val monthLabel: String,
    val totalValue: String,
    val totalLabel: String,
    val activeDaysValue: String,
    val activeDaysLabel: String
)

@Immutable
data class ReadRecordRecentBookUi(
    val name: String,
    val meta: String,
    val readTime: String
)

@Immutable
data class ReadRecordDayUi(
    val title: String,
    val subtitle: String,
    val readTime: String
)

@Immutable
data class ReadRecordRankUi(
    val name: String,
    val meta: String,
    val readTime: String,
    val dimmed: Boolean,
    val book: Book?,
    val snapshot: ReadRecentVisualSnapshot?
)

@Immutable
data class ReadRecordCoverUi(
    val book: Book?,
    val snapshot: ReadRecentVisualSnapshot
)

@Composable
fun ReadRecordOverviewCard(
    ui: ReadRecordOverviewUi,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReadRecordOverviewMetric(
                value = ui.todayValue,
                label = ui.todayLabel,
                modifier = Modifier.weight(1f)
            )
            ReadRecordOverviewMetric(
                value = ui.monthValue,
                label = ui.monthLabel,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReadRecordOverviewMetric(
                value = ui.totalValue,
                label = ui.totalLabel,
                modifier = Modifier.weight(1f)
            )
            ReadRecordOverviewMetric(
                value = ui.activeDaysValue,
                label = ui.activeDaysLabel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ReadRecordRecentBooksList(
    items: List<ReadRecordRecentBookUi>,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            ReadRecordRecentBookRow(
                item = item,
                onClick = { onClick(index) },
                onLongClick = { onLongClick(index) }
            )
            if (index < items.lastIndex) {
                ReadRecordDivider()
            }
        }
    }
}

@Composable
fun ReadRecordDailyList(
    items: List<ReadRecordDayUi>,
    onLongClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            ReadRecordDayRow(
                item = item,
                onLongClick = { onLongClick(index) }
            )
            if (index < items.lastIndex) {
                ReadRecordDivider()
            }
        }
    }
}

@Composable
fun ReadRecordRankList(
    items: List<ReadRecordRankUi>,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            ReadRecordRankRow(
                item = item,
                onClick = { onClick(index) },
                onLongClick = { onLongClick(index) }
            )
            if (index < items.lastIndex) {
                ReadRecordDivider()
            }
        }
    }
}

@Composable
fun ReadRecordRankLazyList(
    items: List<ReadRecordRankUi>,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        itemsIndexed(
            items = items,
            key = { index, item -> item.snapshot?.bookUrl ?: item.book?.bookUrl ?: "${item.name}-$index" }
        ) { index, item ->
            ReadRecordRankRow(
                item = item,
                onClick = { onClick(index) },
                onLongClick = { onLongClick(index) }
            )
            if (index < items.lastIndex) {
                ReadRecordDivider()
            }
        }
    }
}

@Composable
fun ReadRecordRankDialogContent(
    items: List<ReadRecordRankItem>,
    formatDuring: (Long) -> String,
    onClick: (ReadRecordRankItem) -> Unit,
    onLongClick: (ReadRecordRankItem, () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rankItems = remember(items) {
        mutableStateListOf<ReadRecordRankItem>().apply {
            addAll(items)
        }
    }
    ReadRecordRankLazyList(
        items = rankItems.mapIndexed { index, item ->
            val author = item.book?.author ?: item.snapshot?.author ?: item.displayAuthor
            ReadRecordRankUi(
                name = item.book?.name ?: item.snapshot?.name ?: item.displayName,
                meta = if (author.isBlank()) {
                    context.getString(R.string.read_record_rank_number, index + 1)
                } else {
                    "${index + 1}. $author"
                },
                readTime = formatDuring(item.readTime),
                dimmed = item.book == null,
                book = item.book,
                snapshot = item.snapshot
            )
        },
        onClick = { index ->
            rankItems.getOrNull(index)?.let(onClick)
        },
        onLongClick = { index ->
            rankItems.getOrNull(index)?.let { item ->
                onLongClick(item) {
                    rankItems.remove(item)
                }
            }
        },
        modifier = modifier
    )
}

@Composable
fun ReadRecordCoverRow(
    items: List<ReadRecordCoverUi>,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> item.snapshot.bookUrl }
        ) { index, item ->
            ReadRecordCoverItem(
                item = item,
                onClick = { onClick(index) },
                onLongClick = { onLongClick(index) }
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReadRecordRecentBookRow(
    item: ReadRecordRecentBookUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val titleFont = FontFamily(context.titleTypeface())
    val bodyFont = FontFamily(context.uiTypeface())
    val primaryText = Color(ContextCompat.getColor(context, R.color.primaryText))
    val secondaryText = Color(ContextCompat.getColor(context, R.color.secondaryText))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(38.dp)
                .background(Color(context.accentColor))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = item.name,
                color = primaryText,
                fontSize = 15.sp,
                fontFamily = titleFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.meta,
                color = secondaryText,
                fontSize = 12.sp,
                fontFamily = bodyFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            text = item.readTime,
            color = secondaryText,
            fontSize = 12.sp,
            fontFamily = bodyFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReadRecordDayRow(
    item: ReadRecordDayUi,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val titleFont = FontFamily(context.titleTypeface())
    val bodyFont = FontFamily(context.uiTypeface())
    val primaryText = Color(ContextCompat.getColor(context, R.color.primaryText))
    val secondaryText = Color(ContextCompat.getColor(context, R.color.secondaryText))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = primaryText,
                fontSize = 15.sp,
                fontFamily = titleFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.subtitle,
                color = secondaryText,
                fontSize = 12.sp,
                fontFamily = bodyFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            text = item.readTime,
            color = primaryText,
            fontSize = 14.sp,
            fontFamily = bodyFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReadRecordRankRow(
    item: ReadRecordRankUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val titleFont = FontFamily(context.titleTypeface())
    val bodyFont = FontFamily(context.uiTypeface())
    val primaryText = Color(ContextCompat.getColor(context, R.color.primaryText))
    val secondaryText = Color(ContextCompat.getColor(context, R.color.secondaryText))
    val alpha = if (item.dimmed) 0.72f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AndroidView(
            modifier = Modifier
                .width(54.dp)
                .height(74.dp)
                .clip(RoundedCornerShape(context.composeReadRecordPanelRadius())),
            factory = { CoverImageView(it) },
            update = { cover ->
                cover.alpha = alpha
                cover.loadReadRecordCover(item.book, item.snapshot)
            },
            onRelease = { it.releaseComposeImage() }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = item.name,
                color = primaryText.copy(alpha = alpha),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = titleFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.meta,
                color = secondaryText.copy(alpha = alpha),
                fontSize = 12.sp,
                fontFamily = bodyFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            text = item.readTime,
            color = secondaryText.copy(alpha = alpha),
            fontSize = 12.sp,
            fontFamily = bodyFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReadRecordCoverItem(
    item: ReadRecordCoverUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier
            .width(78.dp)
            .height(108.dp)
            .clip(RoundedCornerShape(context.composeReadRecordPanelRadius()))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        factory = { CoverImageView(it) },
        update = { cover ->
            cover.loadReadRecordCover(item.book, item.snapshot)
        },
        onRelease = { it.releaseComposeImage() }
    )
}

@Composable
private fun android.content.Context.composeReadRecordPanelRadius() = androidx.compose.ui.unit.Dp(
    UiCorner.panelRadius(this) / resources.displayMetrics.density
)

@Composable
private fun ReadRecordDivider() {
    val color = Color(ContextCompat.getColor(LocalContext.current, R.color.divider))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(color)
    )
}

@Composable
private fun ReadRecordOverviewMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val surfaceColor = UiCorner.surfaceColor(ContextCompat.getColor(context, R.color.background_card))
    val borderColor = UiCorner.panelBorderColor(context)
    val radiusPx = UiCorner.panelRadius(context)
    val panelImage = androidx.compose.runtime.remember(context, radiusPx) {
        UiCorner.panelImageDrawable(context, radiusPx)
    }
    val primaryText = androidx.compose.ui.graphics.Color(
        ContextCompat.getColor(context, R.color.primaryText)
    )
    val secondaryText = androidx.compose.ui.graphics.Color(
        ContextCompat.getColor(context, R.color.secondaryText)
    )
    val titleFont = FontFamily(context.titleTypeface())
    val bodyFont = FontFamily(context.uiTypeface())
    Column(
        modifier = modifier
            .heightIn(min = 88.dp)
            .appSettingPanelBackground(
                normalColor = surfaceColor,
                panelImage = panelImage,
                borderColor = borderColor,
                radiusPx = radiusPx
            )
            .padding(16.dp)
    ) {
        Text(
            text = value,
            color = primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = titleFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            color = secondaryText,
            fontSize = 12.sp,
            fontFamily = bodyFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
