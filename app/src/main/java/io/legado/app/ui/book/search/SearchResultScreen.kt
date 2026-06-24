package io.legado.app.ui.book.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.compose.BookListCardSurface
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItemStyle
import io.legado.app.ui.main.bookshelf.compose.BookshelfListPalette
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.compose.ComposeLazyListFastScroller
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.releaseComposeImage
import io.legado.app.ui.widget.image.CoverImageView

/**
 * 搜索结果列表的 Compose 实现，复用与发现页一致的 [BookListCardSurface] 卡片骨架与
 * [rememberBookshelfListRenderConfig] 主题色板，使搜索结果与发现/书架视觉统一、随主题走。
 */
@Composable
fun SearchResultScreen(
    books: List<SearchBook>,
    isLoading: Boolean,
    hasMore: Boolean,
    scrollToTopSignal: Int,
    bookshelfTick: Int,
    isInBookshelf: (SearchBook) -> Boolean,
    lifecycle: Lifecycle,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    LegadoComposeTheme {
        val renderConfig = rememberBookshelfListRenderConfig()
        val palette = renderConfig.palette
        val rounded = AppConfig.bookshelfListItemStyle == BookshelfListItemStyle.RoundedCard
        val listState = rememberLazyListState()

        val shouldLoadMore by remember(books, hasMore, isLoading) {
            derivedStateOf {
                if (!hasMore || isLoading || books.isEmpty()) return@derivedStateOf false
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                last >= books.lastIndex - 2
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore) onLoadMore()
        }
        LaunchedEffect(scrollToTopSignal) {
            if (scrollToTopSignal > 0) listState.scrollToItem(0)
        }

        Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 86.dp),
                verticalArrangement = Arrangement.spacedBy(if (rounded) 4.dp else 2.dp)
            ) {
                itemsIndexed(
                    items = books,
                    key = { index, book -> searchResultItemKey(index, book) }
                ) { _, book ->
                    val inBookshelf = remember(book.bookUrl, bookshelfTick) { isInBookshelf(book) }
                    SearchResultItem(
                        book = book,
                        inBookshelf = inBookshelf,
                        rounded = rounded,
                        renderConfig = renderConfig,
                        palette = palette,
                        lifecycle = lifecycle,
                        onClick = { onBookClick(book) },
                        onLongClick = { onBookLongClick(book) }
                    )
                }
            }
            ComposeLazyListFastScroller(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

private fun searchResultItemKey(index: Int, book: SearchBook): String {
    return "${book.bookUrl}|${book.origin}|${book.name}|${book.author}|$index"
}

@Composable
private fun SearchResultItem(
    book: SearchBook,
    inBookshelf: Boolean,
    rounded: Boolean,
    renderConfig: io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig,
    palette: BookshelfListPalette,
    lifecycle: Lifecycle,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    BookListCardSurface(
        rounded = rounded,
        compact = false,
        renderConfig = renderConfig,
        onClick = onClick,
        onLongClick = onLongClick
    ) { metrics ->
        Box(modifier = Modifier.width(metrics.coverWidth)) {
            AndroidView(
                modifier = Modifier
                    .width(metrics.coverWidth)
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(metrics.cornerRadius)),
                factory = { context -> CoverImageView(context) },
                update = { view -> view.load(book, AppConfig.loadCoverOnlyWifi, null, lifecycle) },
                onRelease = { it.releaseComposeImage() }
            )
            if (inBookshelf) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(palette.accent)
                        .size(10.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        SearchResultText(
            book = book,
            rounded = rounded,
            palette = palette,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchResultText(
    book: SearchBook,
    rounded: Boolean,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = book.name,
                color = palette.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = palette.titleFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val originCount = book.origins.size
            if (originCount > 1) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.accent.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = originCount.toString(),
                        color = palette.accent,
                        fontSize = 11.sp,
                        fontFamily = palette.bodyFontFamily,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = context.getString(R.string.author_show, book.author),
            color = palette.secondaryText,
            fontSize = 13.sp,
            fontFamily = palette.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val kinds = remember(book.kind) { book.getKindList() }
        if (rounded && kinds.isNotEmpty()) {
            Spacer(modifier = Modifier.size(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                kinds.take(4).forEach { kind ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.accent.copy(alpha = 0.10f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = kind,
                            color = palette.secondaryText,
                            fontSize = 11.sp,
                            fontFamily = palette.bodyFontFamily,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        val lasted = book.latestChapterTitle
        if (!lasted.isNullOrEmpty()) {
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = context.getString(R.string.lasted_show, lasted),
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val intro = book.intro?.trim()
        if (!intro.isNullOrEmpty()) {
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = intro,
                color = palette.primaryText,
                fontSize = 13.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = if (rounded) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
