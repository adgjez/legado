package io.legado.app.ui.main.compose.discovery

import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook

@Stable
data class DiscoveryTag(
    val key: String,
    val title: String,
    val enabled: Boolean = true,
)

@Stable
data class DiscoveryUiState(
    val sources: List<BookSourcePart> = emptyList(),
    val selectedSourceUrl: String? = null,
    val tags: List<DiscoveryTag> = emptyList(),
    val selectedTagKey: String? = null,
    val books: List<SearchBook> = emptyList(),
    val isLoadingSources: Boolean = false,
    val isLoadingBooks: Boolean = false,
    val errorMessage: String? = null,
    val emptyMessage: String = "暂无发现内容",
    val contentPaddingTop: Dp = Dp.Unspecified,
    val contentPaddingBottom: Dp = Dp.Unspecified,
) {
    val currentSource: BookSourcePart?
        get() = sources.firstOrNull { it.bookSourceUrl == selectedSourceUrl }

    val currentTag: DiscoveryTag?
        get() = tags.firstOrNull { it.key == selectedTagKey }
}

@Stable
data class DiscoveryCallbacks(
    val onSourceClick: (BookSourcePart) -> Unit = {},
    val onSourceLongClick: (BookSourcePart) -> Unit = {},
    val onTagClick: (DiscoveryTag) -> Unit = {},
    val onBookClick: (SearchBook) -> Unit = {},
    val onBookLongClick: (SearchBook) -> Unit = {},
    val onRefresh: (BookSourcePart?, DiscoveryTag?) -> Unit = { _, _ -> },
    val onSearchInSource: (BookSourcePart) -> Unit = {},
)
