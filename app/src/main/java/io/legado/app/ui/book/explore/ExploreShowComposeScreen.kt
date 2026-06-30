package io.legado.app.ui.book.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.compose.ComposeLazyListFastScroller
import io.legado.app.ui.widget.compose.SearchBookPreviewOverlay
import io.legado.app.ui.widget.compose.SearchBookPreviewState
import io.legado.app.ui.widget.compose.releaseComposeImage
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.BookIntroUtils

@Composable
fun ExploreShowComposeScreen(
    books: List<SearchBook>,
    isLoading: Boolean,
    isLoadingPrevious: Boolean,
    hasMore: Boolean,
    hasPrevious: Boolean,
    errorMessage: String?,
    previousErrorMessage: String?,
    scrollToTopSignal: Int,
    keepPositionAfterPrependSignal: Int,
    prependedItemCount: Int,
    bookshelfTick: Int,
    isInBookshelf: (SearchBook) -> Boolean,
    lifecycle: Lifecycle,
    onBookClick: (SearchBook) -> Unit,
    onLoadMore: () -> Unit,
    onLoadPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    val renderConfig = rememberBookshelfListRenderConfig()
    val palette = renderConfig.palette
    val rounded = AppConfig.bookshelfListItemStyle == BookshelfListItemStyle.RoundedCard
    val listState = rememberLazyListState()
    var previewState by remember { mutableStateOf<SearchBookPreviewState?>(null) }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val shouldLoadMore by remember(books, hasMore, isLoading, isLoadingPrevious) {
        derivedStateOf {
            if (!hasMore || isLoading || isLoadingPrevious || books.isEmpty()) {
                return@derivedStateOf false
            }
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= books.lastIndex - 2
        }
    }
    val shouldLoadPrevious by remember(books, hasPrevious, isLoading, isLoadingPrevious) {
        derivedStateOf {
            hasPrevious &&
                !isLoading &&
                !isLoadingPrevious &&
                books.isNotEmpty() &&
                listState.isScrollInProgress &&
                listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    LaunchedEffect(shouldLoadPrevious) {
        if (shouldLoadPrevious) onLoadPrevious()
    }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) listState.scrollToItem(0)
    }
    LaunchedEffect(keepPositionAfterPrependSignal) {
        if (keepPositionAfterPrependSignal > 0 && prependedItemCount > 0) {
            listState.scrollToItem(prependedItemCount)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = bottomInset + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (rounded) 4.dp else 2.dp)
        ) {
            if (isLoadingPrevious || previousErrorMessage != null) {
                item(key = "explore_show_previous_status", contentType = "status") {
                    ExploreShowStatusRow(
                        loading = isLoadingPrevious,
                        message = previousErrorMessage?.let { stringResource(R.string.load_error_retry) },
                        palette = palette,
                        onClick = onLoadPrevious
                    )
                }
            }
            itemsIndexed(
                items = books,
                key = { index, book -> exploreShowItemKey(index, book) },
                contentType = { _, _ -> "explore_show_book" }
            ) { _, book ->
                val inBookshelf = remember(book.bookUrl, bookshelfTick) { isInBookshelf(book) }
                ExploreShowBookItem(
                    book = book,
                    inBookshelf = inBookshelf,
                    rounded = rounded,
                    renderConfig = renderConfig,
                    palette = palette,
                    lifecycle = lifecycle,
                    onClick = { onBookClick(book) },
                    onPreview = { bounds ->
                        previewState = SearchBookPreviewState(book, bounds)
                    }
                )
            }
            if (isLoading || errorMessage != null) {
                item(key = "explore_show_footer_status", contentType = "status") {
                    ExploreShowStatusRow(
                        loading = isLoading,
                        message = errorMessage?.let { stringResource(R.string.load_error_retry) },
                        palette = palette,
                        onClick = onLoadMore
                    )
                }
            }
        }
        if (books.isEmpty() && !isLoading && !isLoadingPrevious && errorMessage == null) {
            Text(
                text = stringResource(R.string.explore_empty),
                color = palette.secondaryText,
                fontSize = 14.sp,
                fontFamily = palette.bodyFontFamily,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }
        ComposeLazyListFastScroller(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
        SearchBookPreviewOverlay(
            state = previewState,
            renderConfig = renderConfig,
            fragment = null,
            lifecycle = lifecycle,
            onDismissed = { previewState = null },
            onOpen = { book ->
                previewState = null
                onBookClick(book)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun exploreShowItemKey(index: Int, book: SearchBook): String {
    return "${book.origin}|${book.bookUrl}|${book.name}|${book.author}|$index"
}

@Composable
private fun ExploreShowBookItem(
    book: SearchBook,
    inBookshelf: Boolean,
    rounded: Boolean,
    renderConfig: BookshelfListRenderConfig,
    palette: BookshelfListPalette,
    lifecycle: Lifecycle,
    onClick: () -> Unit,
    onPreview: (Rect?) -> Unit
) {
    var coverBounds by remember(book.bookUrl, book.origin, book.coverUrl) { mutableStateOf<Rect?>(null) }
    BookListCardSurface(
        rounded = rounded,
        compact = false,
        renderConfig = renderConfig,
        onClick = onClick,
        onLongClick = { onPreview(coverBounds) }
    ) { metrics ->
        Box(modifier = Modifier.width(metrics.coverWidth)) {
            AndroidView(
                modifier = Modifier
                    .width(metrics.coverWidth)
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(metrics.cornerRadius))
                    .onGloballyPositioned { coordinates ->
                        coverBounds = coordinates.boundsInRoot()
                    },
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
        ExploreShowBookText(
            book = book,
            rounded = rounded,
            palette = palette,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ExploreShowBookText(
    book: SearchBook,
    rounded: Boolean,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Text(
            text = book.name,
            color = palette.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.titleFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.size(if (rounded) 5.dp else 2.dp))
        Text(
            text = context.getString(R.string.author_show, book.author),
            color = palette.secondaryText,
            fontSize = 13.sp,
            fontFamily = palette.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val kinds = remember(book.kind) { book.getKindList() }
        if (kinds.isNotEmpty()) {
            ExploreShowKindChips(
                kinds = kinds,
                rounded = rounded,
                palette = palette,
                modifier = Modifier.padding(top = if (rounded) 4.dp else 2.dp)
            )
        }
        val lasted = book.latestChapterTitle
        if (!lasted.isNullOrBlank()) {
            Spacer(modifier = Modifier.size(if (rounded) 5.dp else 3.dp))
            Text(
                text = context.getString(R.string.lasted_show, lasted),
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val intro = remember(book.intro) { BookIntroUtils.listIntro(book.intro) }
        if (!intro.isNullOrBlank()) {
            Spacer(modifier = Modifier.size(if (rounded) 7.dp else 4.dp))
            Text(
                text = intro,
                color = if (rounded) palette.secondaryText else palette.primaryText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ExploreShowKindChips(
    kinds: List<String>,
    rounded: Boolean,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (rounded) 6.dp else 4.dp)
    ) {
        kinds.take(if (rounded) 4 else 3).forEach { kind ->
            Text(
                text = kind,
                color = palette.accent,
                fontSize = 11.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(if (rounded) palette.actionRadius else 6.dp))
                    .background(palette.accent.copy(alpha = 0.12f))
                    .widthIn(max = if (rounded) 84.dp else 72.dp)
                    .padding(
                        horizontal = if (rounded) 8.dp else 6.dp,
                        vertical = if (rounded) 3.dp else 1.dp
                    )
            )
        }
    }
}

@Composable
private fun ExploreShowStatusRow(
    loading: Boolean,
    message: String?,
    palette: BookshelfListPalette,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (loading || message == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = palette.accent
            )
        } else if (message != null) {
            Text(
                text = message,
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontFamily = palette.bodyFontFamily
            )
        }
    }
}
