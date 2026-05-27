package io.legado.app.ui.main.compose.bookshelf

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class BookshelfScrollController internal constructor(
    private val coroutineScope: CoroutineScope,
) {
    private var listState: LazyListState? = null
    private var gridState: LazyGridState? = null

    var canScrollToTop by mutableStateOf(false)
        private set

    fun scrollToTop(animated: Boolean = !AppConfig.isEInkMode) {
        coroutineScope.launch {
            val currentListState = listState
            val currentGridState = gridState
            when {
                currentListState != null && animated -> currentListState.animateScrollToItem(0)
                currentListState != null -> currentListState.scrollToItem(0)
                currentGridState != null && animated -> currentGridState.animateScrollToItem(0)
                currentGridState != null -> currentGridState.scrollToItem(0)
            }
        }
    }

    suspend fun scrollToTopImmediately() {
        listState?.scrollToItem(0)
        gridState?.scrollToItem(0)
    }

    internal fun attachList(state: LazyListState) {
        listState = state
        gridState = null
    }

    internal fun attachGrid(state: LazyGridState) {
        gridState = state
        listState = null
    }

    internal fun updateCanScrollToTop(value: Boolean) {
        canScrollToTop = value
    }
}

@Composable
fun rememberBookshelfScrollController(): BookshelfScrollController {
    val coroutineScope = rememberCoroutineScope()
    return remember(coroutineScope) {
        BookshelfScrollController(coroutineScope)
    }
}
