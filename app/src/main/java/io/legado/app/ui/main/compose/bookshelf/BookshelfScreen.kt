package io.legado.app.ui.main.compose.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BookshelfScreen(
    state: BookshelfUiState,
    modifier: Modifier = Modifier,
    scrollController: BookshelfScrollController = rememberBookshelfScrollController(),
    callbacks: BookshelfCallbacks = BookshelfCallbacks(),
) {
    val selectedGroup = remember(state.groups, state.selectedGroupId) {
        state.groups.firstOrNull { it.groupId == state.selectedGroupId }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        BookshelfGroupsBar(
            groups = state.groups,
            selectedGroupId = state.selectedGroupId,
            onGroupClick = callbacks.onGroupClick,
        )
        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (val layout = state.layoutMode) {
                BookshelfLayoutMode.List -> BookshelfBookList(
                    state = state,
                    selectedGroup = selectedGroup,
                    scrollController = scrollController,
                    callbacks = callbacks,
                )
                is BookshelfLayoutMode.Grid -> BookshelfBookGrid(
                    state = state,
                    selectedGroup = selectedGroup,
                    columns = layout.columns.coerceAtLeast(2),
                    scrollController = scrollController,
                    callbacks = callbacks,
                )
            }
            if (state.books.isEmpty()) {
                BookshelfEmptyMessage(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun BookshelfGroupsBar(
    groups: List<BookGroup>,
    selectedGroupId: Long?,
    onGroupClick: (BookGroup) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it.groupId }) { group ->
            val selected = group.groupId == selectedGroupId
            Surface(
                modifier = Modifier
                    .height(34.dp)
                    .clickable { onGroupClick(group) },
                shape = RoundedCornerShape(17.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = group.displayName(),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookshelfBookList(
    state: BookshelfUiState,
    selectedGroup: BookGroup?,
    scrollController: BookshelfScrollController,
    callbacks: BookshelfCallbacks,
) {
    val listState = rememberLazyListState()
    val atTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    BookshelfListScrollBinding(listState, scrollController)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bookshelfPullRefresh(
                enabled = selectedGroup?.enableRefresh != false && state.books.isNotEmpty(),
                isRefreshing = state.isRefreshing,
                canPull = { atTop },
                onRefresh = { callbacks.onRefresh(selectedGroup, state.books) },
            ),
        state = listState,
        contentPadding = bookshelfContentPadding(state),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.books, key = { it.bookUrl }) { book ->
            BookshelfListItem(
                book = book,
                showUnread = state.showUnread,
                showLastUpdateTime = state.showLastUpdateTime,
                onClick = { callbacks.onBookClick(book) },
                onLongClick = { callbacks.onBookLongClick(book) },
            )
        }
    }
}

@Composable
private fun BookshelfBookGrid(
    state: BookshelfUiState,
    selectedGroup: BookGroup?,
    columns: Int,
    scrollController: BookshelfScrollController,
    callbacks: BookshelfCallbacks,
) {
    val gridState = rememberLazyGridState()
    val atTop by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }
    }
    BookshelfGridScrollBinding(gridState, scrollController)
    LazyVerticalGrid(
        modifier = Modifier
            .fillMaxSize()
            .bookshelfPullRefresh(
                enabled = selectedGroup?.enableRefresh != false && state.books.isNotEmpty(),
                isRefreshing = state.isRefreshing,
                canPull = { atTop },
                onRefresh = { callbacks.onRefresh(selectedGroup, state.books) },
            ),
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = bookshelfContentPadding(state),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.books, key = { it.bookUrl }) { book ->
            BookshelfGridItem(
                book = book,
                showUnread = state.showUnread,
                onClick = { callbacks.onBookClick(book) },
                onLongClick = { callbacks.onBookLongClick(book) },
            )
        }
    }
}

@Composable
private fun BookshelfListItem(
    book: Book,
    showUnread: Boolean,
    showLastUpdateTime: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .pointerInput(book.bookUrl) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() },
                )
            },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookshelfCover(
                book = book,
                modifier = Modifier
                    .width(72.dp)
                    .aspectRatio(3f / 4f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.name.ifBlank { "Untitled" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showUnread) {
                        UnreadBadge(count = book.unreadChapterCount())
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = book.author.ifBlank { book.originName },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showLastUpdateTime) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatBookTime(book.latestChapterTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookshelfGridItem(
    book: Book,
    showUnread: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .pointerInput(book.bookUrl) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            BookshelfCover(
                book = book,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            )
            if (showUnread) {
                UnreadBadge(
                    count = book.unreadChapterCount(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = book.name.ifBlank { "Untitled" },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.error, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.coerceAtMost(999).toString(),
            color = MaterialTheme.colorScheme.onError,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun BookshelfEmptyMessage(modifier: Modifier = Modifier) {
    Text(
        text = "No books",
        modifier = modifier.padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun BookshelfListScrollBinding(
    listState: LazyListState,
    scrollController: BookshelfScrollController,
) {
    LaunchedEffect(listState, scrollController) {
        scrollController.attachList(listState)
        snapshotFlow {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }.collect { scrollController.updateCanScrollToTop(it) }
    }
}

@Composable
private fun BookshelfGridScrollBinding(
    gridState: LazyGridState,
    scrollController: BookshelfScrollController,
) {
    LaunchedEffect(gridState, scrollController) {
        scrollController.attachGrid(gridState)
        snapshotFlow {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
        }.collect { scrollController.updateCanScrollToTop(it) }
    }
}

private fun Modifier.bookshelfPullRefresh(
    enabled: Boolean,
    isRefreshing: Boolean,
    canPull: () -> Boolean,
    onRefresh: () -> Unit,
): Modifier = composed {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    pointerInput(enabled, isRefreshing) {
        if (!enabled) return@pointerInput
        var pullDistance = 0f
        detectVerticalDragGestures(
            onDragStart = { pullDistance = 0f },
            onVerticalDrag = { change, dragAmount ->
                if (canPull() && dragAmount > 0) {
                    pullDistance += dragAmount
                    change.position
                }
            },
            onDragEnd = {
                if (!isRefreshing && pullDistance >= threshold) {
                    onRefresh()
                }
                pullDistance = 0f
            },
            onDragCancel = { pullDistance = 0f },
        )
    }
}

private fun bookshelfContentPadding(state: BookshelfUiState): PaddingValues {
    val top = state.contentPaddingTop.takeIf { it != Dp.Unspecified } ?: 0.dp
    val bottom = state.contentPaddingBottom.takeIf { it != Dp.Unspecified } ?: 0.dp
    return PaddingValues(start = 12.dp, top = top, end = 12.dp, bottom = bottom)
}

private fun formatBookTime(timestamp: Long): String {
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
