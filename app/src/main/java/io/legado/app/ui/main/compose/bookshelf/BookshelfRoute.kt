package io.legado.app.ui.main.compose.bookshelf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@Composable
fun BookshelfRoute(
    modifier: Modifier = Modifier,
    initialGroupId: Long? = null,
    isRefreshing: Boolean = false,
    scrollController: BookshelfScrollController = rememberBookshelfScrollController(),
    callbacks: BookshelfCallbacks = BookshelfCallbacks(),
) {
    val rawGroups by produceState<List<BookGroup>>(initialValue = emptyList()) {
        appDb.bookGroupDao.flowAll()
            .flowOn(Dispatchers.IO)
            .collectLatest { value = it }
    }
    val allBooks by produceState<List<Book>>(initialValue = emptyList()) {
        appDb.bookDao.flowAll()
            .map { books -> books.filterNot { it.isNotShelf } }
            .flowOn(Dispatchers.Default)
            .collectLatest { value = it }
    }
    val groups = remember(rawGroups, allBooks) {
        visibleBookshelfGroups(rawGroups, allBooks).ifEmpty {
            rawGroups.firstOrNull { it.groupId == BookGroup.IdAll }?.let(::listOf).orEmpty()
        }
    }
    var selectedGroupId by remember(initialGroupId) {
        mutableStateOf(initialGroupId)
    }
    LaunchedEffect(groups, initialGroupId) {
        if (groups.isEmpty()) {
            selectedGroupId = null
            return@LaunchedEffect
        }
        if (selectedGroupId != null && groups.any { it.groupId == selectedGroupId }) {
            return@LaunchedEffect
        }
        selectedGroupId = initialGroupId
            ?.takeIf { target -> groups.any { it.groupId == target } }
            ?: groups.getOrNull(AppConfig.saveTabPosition.coerceIn(0, groups.lastIndex))?.groupId
            ?: groups.first().groupId
    }
    val selectedGroup = remember(groups, selectedGroupId) {
        groups.firstOrNull { it.groupId == selectedGroupId }
    }
    val selectedBooks by produceState<List<Book>>(
        initialValue = emptyList(),
        key1 = selectedGroupId,
        key2 = selectedGroup?.bookSort,
    ) {
        val groupId = selectedGroupId ?: return@produceState
        appDb.bookDao.flowByGroup(groupId)
            .map { books -> sortBookshelfBooks(books, selectedGroup?.getRealBookSort() ?: AppConfig.bookshelfSort) }
            .flowOn(Dispatchers.Default)
            .collectLatest { value = it }
    }

    BookshelfScreen(
        state = BookshelfUiState(
            groups = groups,
            selectedGroupId = selectedGroupId,
            books = selectedBooks,
            layoutMode = bookshelfLayoutMode(AppConfig.bookshelfLayout),
            isRefreshing = isRefreshing,
            showUnread = AppConfig.showUnread,
            showLastUpdateTime = AppConfig.showLastUpdateTime,
            contentPaddingTop = 8.dp,
            contentPaddingBottom = 88.dp,
        ),
        modifier = modifier,
        scrollController = scrollController,
        callbacks = callbacks.copy(
            onGroupClick = { group ->
                selectedGroupId = group.groupId
                groups.indexOfFirst { it.groupId == group.groupId }
                    .takeIf { it >= 0 }
                    ?.let { AppConfig.saveTabPosition = it }
                callbacks.onGroupClick(group)
            },
        ),
    )
}
