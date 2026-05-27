package io.legado.app.ui.main.compose.bookshelf

import androidx.compose.runtime.Stable
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup

@Stable
sealed interface BookshelfLayoutMode {
    data object List : BookshelfLayoutMode
    data class Grid(val columns: Int) : BookshelfLayoutMode
}

@Stable
data class BookshelfUiState(
    val groups: List<BookGroup> = emptyList(),
    val selectedGroupId: Long? = null,
    val books: List<Book> = emptyList(),
    val layoutMode: BookshelfLayoutMode = BookshelfLayoutMode.List,
    val isRefreshing: Boolean = false,
    val showUnread: Boolean = true,
    val showLastUpdateTime: Boolean = false,
    val contentPaddingTop: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    val contentPaddingBottom: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
)

@Stable
data class BookshelfCallbacks(
    val onBookClick: (Book) -> Unit = {},
    val onBookLongClick: (Book) -> Unit = {},
    val onGroupClick: (BookGroup) -> Unit = {},
    val onRefresh: (BookGroup?, List<Book>) -> Unit = { _, _ -> },
)
