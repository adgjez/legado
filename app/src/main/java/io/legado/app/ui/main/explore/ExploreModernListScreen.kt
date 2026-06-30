package io.legado.app.ui.main.explore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.compose.BookListCardSurface
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItemStyle
import io.legado.app.ui.main.bookshelf.compose.BookshelfListPalette
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.compose.SearchBookPreviewOverlay
import io.legado.app.ui.widget.compose.SearchBookPreviewState
import io.legado.app.ui.widget.compose.releaseComposeImage
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.BookIntroUtils

@Composable
fun ExploreModernListScreen(
    books: List<SearchBook>,
    layoutMode: Int,
    listItemStyle: Int,
    topPaddingPx: Int,
    scrollToTopSignal: Int,
    isLoading: Boolean,
    hasMore: Boolean,
    isInBookshelf: (SearchBook) -> Boolean,
    onBookClick: (SearchBook) -> Unit,
    onLoadMore: () -> Unit,
    onCanScrollBackwardChanged: (Boolean) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle,
    modifier: Modifier = Modifier
) {
    if (layoutMode == 3) {
        ExploreModernGridScreen(
            books = books,
            topPaddingPx = topPaddingPx,
            scrollToTopSignal = scrollToTopSignal,
            isLoading = isLoading,
            hasMore = hasMore,
            isInBookshelf = isInBookshelf,
            onBookClick = onBookClick,
            onLoadMore = onLoadMore,
            onCanScrollBackwardChanged = onCanScrollBackwardChanged,
            fragment = fragment,
            lifecycle = lifecycle,
            modifier = modifier
        )
        return
    }
    val listState = rememberLazyListState()
    val topPadding = with(LocalDensity.current) { topPaddingPx.toDp() }
    val shouldLoadMore by remember(books, hasMore, isLoading) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            books.isNotEmpty() && hasMore && !isLoading && lastVisible >= books.lastIndex - 3
        }
    }
    val canScrollBackward by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                    listState.firstVisibleItemScrollOffset > 0
        }
    }
    val renderConfig = rememberBookshelfListRenderConfig()
    var previewState by remember { mutableStateOf<SearchBookPreviewState?>(null) }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = topPadding)
            .clipToBounds()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 86.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (listItemStyle == BookshelfListItemStyle.RoundedCard) 4.dp else 2.dp)
        ) {
            lazyColumnItems(
                items = books,
                key = { book -> "${book.origin}|${book.bookUrl}|${book.name}|${book.author}" },
                contentType = { "discover_book_$listItemStyle" }
            ) { book ->
                ExploreBookListItem(
                    book = book,
                    inBookshelf = isInBookshelf(book),
                    listItemStyle = listItemStyle,
                    renderConfig = renderConfig,
                    fragment = fragment,
                    lifecycle = lifecycle,
                    onClick = onBookClick,
                    onPreview = { bounds ->
                        previewState = SearchBookPreviewState(book, bounds)
                    }
                )
            }
            if (isLoading && books.isNotEmpty()) {
                item(key = "discover_loading_footer", contentType = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = renderConfig.palette.accent
                        )
                    }
                }
            }
        }
        SearchBookPreviewOverlay(
            state = previewState,
            renderConfig = renderConfig,
            fragment = fragment,
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

@Composable
private fun ExploreModernGridScreen(
    books: List<SearchBook>,
    topPaddingPx: Int,
    scrollToTopSignal: Int,
    isLoading: Boolean,
    hasMore: Boolean,
    isInBookshelf: (SearchBook) -> Boolean,
    onBookClick: (SearchBook) -> Unit,
    onLoadMore: () -> Unit,
    onCanScrollBackwardChanged: (Boolean) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val topPadding = with(LocalDensity.current) { topPaddingPx.toDp() }
    val shouldLoadMore by remember(books, hasMore, isLoading) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            books.isNotEmpty() && hasMore && !isLoading && lastVisible >= books.lastIndex - 6
        }
    }
    val canScrollBackward by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 ||
                    gridState.firstVisibleItemScrollOffset > 0
        }
    }
    val renderConfig = rememberBookshelfListRenderConfig()
    var previewState by remember { mutableStateOf<SearchBookPreviewState?>(null) }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    LaunchedEffect(canScrollBackward) {
        onCanScrollBackwardChanged(canScrollBackward)
    }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            if (AppConfig.isEInkMode) {
                gridState.scrollToItem(0)
            } else {
                gridState.animateScrollToItem(0)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = topPadding)
            .clipToBounds()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 86.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = books,
                key = { book -> "${book.origin}|${book.bookUrl}|${book.name}|${book.author}" },
                contentType = { "discover_grid_book" }
            ) { book ->
                ExploreGridBookItem(
                    book = book,
                    inBookshelf = isInBookshelf(book),
                    renderConfig = renderConfig,
                    fragment = fragment,
                    lifecycle = lifecycle,
                    onClick = onBookClick,
                    onPreview = { bounds ->
                        previewState = SearchBookPreviewState(book, bounds)
                    }
                )
            }
            if (isLoading && books.isNotEmpty()) {
                item(key = "discover_grid_loading_footer", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = renderConfig.palette.accent
                        )
                    }
                }
            }
        }
        SearchBookPreviewOverlay(
            state = previewState,
            renderConfig = renderConfig,
            fragment = fragment,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreGridBookItem(
    book: SearchBook,
    inBookshelf: Boolean,
    renderConfig: BookshelfListRenderConfig,
    fragment: Fragment,
    lifecycle: Lifecycle,
    onClick: (SearchBook) -> Unit,
    onPreview: (Rect?) -> Unit
) {
    val palette = renderConfig.palette
    var coverBounds by remember(book.bookUrl, book.origin, book.coverUrl) { mutableStateOf<Rect?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(book) },
                onLongClick = { onPreview(coverBounds) }
            )
    ) {
        Box {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(palette.actionRadius))
                    .onGloballyPositioned { coordinates ->
                        coverBounds = coordinates.boundsInRoot()
                    },
                factory = { context -> CoverImageView(context) },
                update = { view ->
                    view.load(book, AppConfig.loadCoverOnlyWifi, fragment, lifecycle)
                },
                onRelease = { it.releaseComposeImage() }
            )
            if (inBookshelf) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(palette.accent)
                        .size(10.dp)
                )
            }
        }
        Text(
            text = book.name,
            color = palette.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.titleFontFamily,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreBookListItem(
    book: SearchBook,
    inBookshelf: Boolean,
    listItemStyle: Int,
    renderConfig: BookshelfListRenderConfig,
    fragment: Fragment,
    lifecycle: Lifecycle,
    onClick: (SearchBook) -> Unit,
    onPreview: (Rect?) -> Unit
) {
    val palette = renderConfig.palette
    val rounded = listItemStyle == BookshelfListItemStyle.RoundedCard
    var coverBounds by remember(book.bookUrl, book.origin, book.coverUrl) { mutableStateOf<Rect?>(null) }
    BookListCardSurface(
        rounded = rounded,
        compact = false,
        renderConfig = renderConfig,
        onClick = { onClick(book) },
        onLongClick = { onPreview(coverBounds) }
    ) { metrics ->
        ExploreCoverBlock(
            book = book,
            inBookshelf = inBookshelf,
            width = metrics.coverWidth,
            cornerRadius = metrics.cornerRadius,
            palette = palette,
            fragment = fragment,
            lifecycle = lifecycle,
            onCoverBoundsChanged = { coverBounds = it }
        )
        Spacer(modifier = Modifier.width(12.dp))
        ExploreBookTextContent(
            book = book,
            rounded = rounded,
            palette = palette,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ExploreCoverBlock(
    book: SearchBook,
    inBookshelf: Boolean,
    width: Dp,
    cornerRadius: Dp,
    palette: BookshelfListPalette,
    fragment: Fragment,
    lifecycle: Lifecycle,
    onCoverBoundsChanged: (Rect) -> Unit
) {
    Box(modifier = Modifier.width(width)) {
        AndroidView(
            modifier = Modifier
                .width(width)
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(cornerRadius))
                .onGloballyPositioned { coordinates ->
                    onCoverBoundsChanged(coordinates.boundsInRoot())
                },
            factory = { context ->
                CoverImageView(context)
            },
            update = { view ->
                view.load(book, AppConfig.loadCoverOnlyWifi, fragment, lifecycle)
            },
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
}

@Composable
private fun ExploreBookTextContent(
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
        Spacer(modifier = Modifier.height(if (rounded) 5.dp else 3.dp))
        ExploreMetaLine(
            iconRes = R.drawable.ic_author,
            text = context.getString(R.string.author_show, book.author),
            palette = palette
        )
        ExploreMetaLine(
            iconRes = R.drawable.ic_book_last,
            text = book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let {
                context.getString(R.string.lasted_show, it)
            },
            palette = palette
        )
        book.trimIntroOrNull(context)?.let { intro ->
            Text(
                text = intro,
                color = palette.secondaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = if (rounded) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (rounded) 5.dp else 3.dp)
            )
        }
        if (rounded) {
            val kinds = remember(book.kind) { book.getKindList() }
            if (kinds.isNotEmpty()) {
                ExploreTagChips(
                    tags = kinds,
                    palette = palette,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ExploreMetaLine(
    iconRes: Int,
    text: String?,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier
) {
    if (text.isNullOrBlank()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = palette.secondaryText,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            color = palette.secondaryText,
            fontSize = 13.sp,
            fontFamily = palette.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ExploreTagChips(
    tags: List<String>,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tags.take(3).forEach { tag ->
            Text(
                text = tag,
                color = palette.accent,
                fontSize = 11.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(palette.actionRadius))
                    .background(palette.accent.copy(alpha = 0.12f))
                    .widthIn(max = 84.dp)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

private fun SearchBook.trimIntroOrNull(context: android.content.Context): String? {
    val introText = BookIntroUtils.listIntro(intro) ?: return null
    return context.getString(R.string.intro_show, introText)
}
