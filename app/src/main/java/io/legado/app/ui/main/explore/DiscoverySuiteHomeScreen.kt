package io.legado.app.ui.main.explore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import android.content.Context
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.releaseComposeImage
import io.legado.app.ui.widget.image.CoverImageView

@Composable
fun DiscoverySuiteHomeScreen(
    selectedSuite: DiscoverySuite?,
    widgetBooks: Map<String, List<SearchBook>>,
    loadingWidgetIds: Set<String>,
    scrollToTopSignal: Int,
    onSearchClick: () -> Unit,
    onSuiteClick: () -> Unit,
    onCreateSuiteClick: () -> Unit,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit,
    onCanScrollBackwardChanged: (Boolean) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val renderConfig = rememberBookshelfListRenderConfig()
    val palette = renderConfig.palette
    val canScrollBackward by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    LaunchedEffect(canScrollBackward) {
        onCanScrollBackwardChanged(canScrollBackward)
    }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            if (AppConfig.isEInkMode) {
                listState.scrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        DiscoverySuiteSearchBar(
            selectedSuiteLabel = selectedSuite?.displayName
                ?: context.getString(R.string.discovery_suite_no_suite),
            renderConfig = renderConfig,
            onSearchClick = onSearchClick,
            onSuiteClick = onSuiteClick
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            content = {
                if (selectedSuite == null) {
                    item(key = "suite_empty") {
                        DiscoverySuiteEmptyState(
                            title = context.getString(R.string.discovery_suite_empty_title),
                            summary = context.getString(R.string.discovery_suite_empty_summary),
                            action = context.getString(R.string.discovery_suite_create),
                            renderConfig = renderConfig,
                            onActionClick = onCreateSuiteClick
                        )
                    }
                } else if (selectedSuite.widgets.isEmpty()) {
                    item(key = "suite_no_widgets") {
                        DiscoverySuiteEmptyState(
                            title = context.getString(R.string.discovery_suite_no_widgets_title),
                            summary = context.getString(R.string.discovery_suite_no_widgets_summary),
                            action = context.getString(R.string.discovery_suite_manage),
                            renderConfig = renderConfig,
                            onActionClick = onSuiteClick
                        )
                    }
                } else {
                    selectedSuite.widgets.forEach { widget ->
                        item(key = widget.id) {
                            DiscoverySuiteWidgetSection(
                                widget = widget,
                                books = widgetBooks[widget.id].orEmpty(),
                                isLoading = widget.id in loadingWidgetIds,
                                renderConfig = renderConfig,
                                onBookClick = onBookClick,
                                onBookPreview = onBookPreview,
                                fragment = fragment,
                                lifecycle = lifecycle
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun DiscoverySuiteSearchBar(
    selectedSuiteLabel: String,
    renderConfig: BookshelfListRenderConfig,
    onSearchClick: () -> Unit,
    onSuiteClick: () -> Unit
) {
    val palette = renderConfig.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = palette.panelRadiusPx
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onSearchClick)
                .padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchGlyph(color = palette.secondaryText)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = LocalContext.current.getString(R.string.search),
                color = palette.secondaryText,
                fontSize = 20.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(28.dp)
                .background(
                    palette.borderColor?.let { Color(it) }?.copy(alpha = 0.55f)
                        ?: palette.secondaryText.copy(alpha = 0.20f)
                )
        )
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = 96.dp, max = 142.dp)
                .clickable(onClick = onSuiteClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = selectedSuiteLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.primaryText
            )
        }
    }
}

@Composable
private fun SearchGlyph(color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(24.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.6.dp.toPx())
        drawCircle(
            color = color,
            radius = 7.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(10.dp.toPx(), 10.dp.toPx()),
            style = stroke
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(15.5.dp.toPx(), 15.5.dp.toPx()),
            end = androidx.compose.ui.geometry.Offset(21.dp.toPx(), 21.dp.toPx()),
            strokeWidth = 2.6.dp.toPx()
        )
    }
}

@Composable
private fun DiscoverySuiteEmptyState(
    title: String,
    summary: String,
    action: String,
    renderConfig: BookshelfListRenderConfig,
    onActionClick: () -> Unit
) {
    val palette = renderConfig.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.titleFontFamily,
            color = palette.primaryText
        )
        Text(
            text = summary,
            fontSize = 14.sp,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText
        )
        Box(
            modifier = Modifier
                .height(42.dp)
                .clip(RoundedCornerShape(palette.actionRadius))
                .clickable(onClick = onActionClick)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = action,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    fontFamily = palette.bodyFontFamily,
                    color = palette.accent
                )
            }
        }
    }
}

@Composable
private fun DiscoverySuiteWidgetSection(
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    isLoading: Boolean,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val context = LocalContext.current
    val palette = renderConfig.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = widget.displayTitle(context),
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.titleFontFamily,
            color = palette.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        when {
            isLoading -> Text(
                text = LocalContext.current.getString(R.string.discovery_suite_widget_loading),
                fontSize = 14.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
            books.isEmpty() -> Text(
                text = LocalContext.current.getString(R.string.discovery_suite_widget_pending),
                fontSize = 14.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
            else -> when (widget.type) {
                DiscoverySuiteWidgetType.RankedList.value -> DiscoverySuiteRankedWidget(
                    widget = widget,
                    books = books,
                    renderConfig = renderConfig,
                    onBookClick = onBookClick,
                    onBookPreview = onBookPreview,
                    fragment = fragment,
                    lifecycle = lifecycle
                )
                else -> DiscoverySuiteHorizontalBooksWidget(
                    widget = widget,
                    books = books,
                    renderConfig = renderConfig,
                    onBookClick = onBookClick,
                    onBookPreview = onBookPreview,
                    fragment = fragment,
                    lifecycle = lifecycle
                )
            }
        }
    }
}

@Composable
private fun DiscoverySuiteHorizontalBooksWidget(
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val pageSize = 6
    val pageState = remember(widget.id, books.size) { mutableIntStateOf(0) }
    val displayBooks = remember(widget.id, books, pageState.intValue) {
        books
            .take(widget.displayLimit)
            .loopSlice(pageState.intValue * pageSize, pageSize)
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        DiscoverySuiteCoverGrid(
            books = displayBooks,
            maxCount = pageSize,
            rankStart = null,
            renderConfig = renderConfig,
            onBookClick = onBookClick,
            onBookPreview = onBookPreview,
            fragment = fragment,
            lifecycle = lifecycle
        )
        if (books.take(widget.displayLimit).size > pageSize) {
            DiscoverySuiteRefreshButton(renderConfig = renderConfig) {
                pageState.intValue += 1
            }
        }
    }
}

@Composable
private fun DiscoverySuiteRankedWidget(
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    DiscoverySuiteCoverGrid(
        books = books.take(widget.displayLimit.coerceAtMost(9)),
        maxCount = 9,
        rankStart = 1,
        renderConfig = renderConfig,
        onBookClick = onBookClick,
        onBookPreview = onBookPreview,
        fragment = fragment,
        lifecycle = lifecycle
    )
}

@Composable
private fun DiscoverySuiteCoverGrid(
    books: List<SearchBook>,
    maxCount: Int,
    rankStart: Int?,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val displayBooks = books.take(maxCount)
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        displayBooks.chunked(3).forEachIndexed { rowIndex, rowBooks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                rowBooks.forEachIndexed { columnIndex, book ->
                    val rank = rankStart?.plus(rowIndex * 3 + columnIndex)
                    DiscoverySuiteCoverBookItem(
                        book = book,
                        rank = rank,
                        modifier = Modifier.weight(1f),
                        renderConfig = renderConfig,
                        onBookClick = onBookClick,
                        onBookPreview = onBookPreview,
                        fragment = fragment,
                        lifecycle = lifecycle
                    )
                }
                repeat(3 - rowBooks.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DiscoverySuiteRefreshButton(
    renderConfig: BookshelfListRenderConfig,
    onClick: () -> Unit
) {
    val palette = renderConfig.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(palette.actionRadius))
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = palette.panelRadiusPx
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = LocalContext.current.getString(R.string.discovery_suite_refresh_batch),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverySuiteCoverBookItem(
    book: SearchBook,
    rank: Int?,
    modifier: Modifier = Modifier,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val palette = renderConfig.palette
    Box(
        modifier = modifier
            .aspectRatio(0.68f)
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(3.dp),
                clip = false
            )
            .clip(RoundedCornerShape(3.dp))
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookPreview(book) }
            )
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> CoverImageView(context) },
            update = { view -> view.load(book, AppConfig.loadCoverOnlyWifi, fragment, lifecycle) },
            onRelease = { it.releaseComposeImage() }
        )
        rank?.let {
            Box(
                modifier = Modifier
                    .padding(7.dp)
                    .size(22.dp)
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(palette.rowColor).copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = it.toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = palette.bodyFontFamily,
                    color = palette.accent
                )
            }
        }
    }
}

private fun <T> List<T>.loopSlice(startIndex: Int, count: Int): List<T> {
    if (isEmpty() || count <= 0) return emptyList()
    val resultSize = minOf(count, this.size)
    val start = startIndex.floorMod(this.size)
    return List(resultSize) { index -> this[(start + index).floorMod(this.size)] }
}

private fun Int.floorMod(divisor: Int): Int {
    return ((this % divisor) + divisor) % divisor
}

private fun DiscoverySuiteWidget.displayTitle(context: Context): String {
    val title = title.trim()
    val addWidgetTitle = context.getString(R.string.discovery_suite_add_widget)
    if (title.isBlank() || title == addWidgetTitle) {
        return if (type == DiscoverySuiteWidgetType.RankedList.value) {
            context.getString(R.string.discovery_suite_default_rank_title)
        } else {
            context.getString(R.string.discovery_suite_default_recommend_title)
        }
    }
    return title
}

/*
@Composable
private fun DiscoverySuiteLegacyHorizontalBooksWidget(
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(
            items = books.take(widget.displayLimit),
            key = { index, book -> "${widget.id}|$index|${book.bookUrl}|${book.origin}" }
        ) { _, book ->
            DiscoverySuiteLegacyCoverBookItem(
                book = book,
                renderConfig = renderConfig,
                onBookClick = onBookClick,
                onBookPreview = onBookPreview,
                fragment = fragment,
                lifecycle = lifecycle
            )
        }
    }
}
*/

@Composable
private fun DiscoverySuiteLegacyRankedWidget(
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        books.take(widget.displayLimit.coerceAtMost(8)).forEachIndexed { index, book ->
            DiscoverySuiteRankedBookRow(
                rank = index + 1,
                book = book,
                renderConfig = renderConfig,
                onBookClick = onBookClick,
                onBookPreview = onBookPreview
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverySuiteLegacyCoverBookItem(
    book: SearchBook,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val palette = renderConfig.palette
    Column(
        modifier = Modifier
            .width(82.dp)
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookPreview(book) }
            )
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(palette.actionRadius)),
            factory = { context -> CoverImageView(context) },
            update = { view -> view.load(book, AppConfig.loadCoverOnlyWifi, fragment, lifecycle) },
            onRelease = { it.releaseComposeImage() }
        )
        Text(
            text = book.name,
            modifier = Modifier.padding(top = 7.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.titleFontFamily,
            color = palette.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val meta = listOf(book.author, book.originName)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 11.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverySuiteRankedBookRow(
    rank: Int,
    book: SearchBook,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit
) {
    val palette = renderConfig.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.actionRadius))
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookPreview(book) }
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (rank <= 3) palette.accent.copy(alpha = 0.16f)
                    else palette.secondaryText.copy(alpha = 0.08f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = rank.toString(),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.titleFontFamily,
                color = if (rank <= 3) palette.accent else palette.secondaryText
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = palette.titleFontFamily,
                color = palette.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(book.author, book.originName, book.latestChapterTitle.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                fontSize = 12.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverySuiteBookRow(
    book: SearchBook,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit
) {
    val palette = renderConfig.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.actionRadius))
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookPreview(book) }
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = book.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.titleFontFamily,
            color = palette.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOf(book.author, book.originName)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            fontSize = 12.sp,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
