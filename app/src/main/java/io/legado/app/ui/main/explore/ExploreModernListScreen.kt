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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItemStyle
import io.legado.app.ui.main.bookshelf.compose.BookshelfListPalette
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.releaseComposeImage
import io.legado.app.ui.widget.image.CoverImageView

@Composable
fun ExploreModernListScreen(
    books: List<SearchBook>,
    listItemStyle: Int,
    topPadding: Dp,
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
    val listState = rememberLazyListState()
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

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    LaunchedEffect(canScrollBackward) {
        onCanScrollBackwardChanged(canScrollBackward)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 8.dp,
            top = topPadding + 8.dp,
            end = 8.dp,
            bottom = 86.dp
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (listItemStyle == BookshelfListItemStyle.RoundedCard) 10.dp else 2.dp
        )
    ) {
        items(
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
                onClick = onBookClick
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
    onClick: (SearchBook) -> Unit
) {
    val palette = renderConfig.palette
    val rounded = listItemStyle == BookshelfListItemStyle.RoundedCard
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (rounded) {
                    Modifier.appSettingPanelBackground(
                        normalColor = palette.rowColor,
                        panelImage = renderConfig.panelImage,
                        borderColor = palette.borderColor,
                        radiusPx = palette.panelRadiusPx
                    )
                } else {
                    Modifier.clip(RoundedCornerShape(2.dp))
                }
            )
            .heightIn(min = if (rounded) 154.dp else 112.dp)
            .combinedClickable(onClick = { onClick(book) })
            .padding(
                horizontal = if (rounded) 12.dp else 8.dp,
                vertical = if (rounded) 10.dp else 5.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExploreCoverBlock(
            book = book,
            inBookshelf = inBookshelf,
            width = if (rounded) 94.dp else 78.dp,
            cornerRadius = if (rounded) palette.actionRadius else 2.dp,
            palette = palette,
            fragment = fragment,
            lifecycle = lifecycle
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
    lifecycle: Lifecycle
) {
    Box(modifier = Modifier.width(width)) {
        AndroidView(
            modifier = Modifier
                .width(width)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(cornerRadius)),
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
        Spacer(modifier = Modifier.height(if (rounded) 6.dp else 4.dp))
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
        Text(
            text = book.trimIntro(context),
            color = palette.secondaryText,
            fontSize = 12.sp,
            fontFamily = palette.bodyFontFamily,
            maxLines = if (rounded) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
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
            .padding(top = 3.dp),
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
