package io.legado.app.ui.main.compose.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn

@Composable
fun DiscoveryRoute(
    state: DiscoveryUiState = DiscoveryUiState(),
    modifier: Modifier = Modifier,
    callbacks: DiscoveryCallbacks = DiscoveryCallbacks(),
) {
    val sources by produceState<List<BookSourcePart>>(initialValue = state.sources) {
        appDb.bookSourceDao.flowExplore()
            .flowOn(Dispatchers.IO)
            .collectLatest { value = it }
    }
    var selectedSourceUrl by rememberSaveable(state.selectedSourceUrl) {
        mutableStateOf(state.selectedSourceUrl)
    }
    LaunchedEffect(sources) {
        if (sources.isEmpty()) {
            selectedSourceUrl = null
            return@LaunchedEffect
        }
        if (selectedSourceUrl == null || sources.none { it.bookSourceUrl == selectedSourceUrl }) {
            selectedSourceUrl = sources.first().bookSourceUrl
        }
    }
    val routeState = remember(state, sources, selectedSourceUrl) {
        state.copy(
            sources = sources,
            selectedSourceUrl = selectedSourceUrl,
            isLoadingSources = false,
            emptyMessage = "当前发现页只接入了本地源列表，内容加载会在后续迁移接上"
        )
    }
    DiscoveryScreen(state = routeState, modifier = modifier, callbacks = callbacks.copy(
        onSourceClick = { source ->
            selectedSourceUrl = source.bookSourceUrl
            callbacks.onSourceClick(source)
        }
    ))
}

@Composable
fun DiscoveryScreen(
    state: DiscoveryUiState,
    modifier: Modifier = Modifier,
    callbacks: DiscoveryCallbacks = DiscoveryCallbacks(),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = state.contentPaddingTop.orZero() + 10.dp,
            end = 16.dp,
            bottom = state.contentPaddingBottom.orZero() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("discovery-header") {
            DiscoveryHeader(
                state = state,
                onRefresh = { callbacks.onRefresh(state.currentSource, state.currentTag) },
            )
        }
        item("discovery-sources") {
            DiscoverySourceStrip(
                sources = state.sources,
                selectedSourceUrl = state.selectedSourceUrl,
                isLoading = state.isLoadingSources,
                callbacks = callbacks,
            )
        }
        if (state.currentSource != null || state.tags.isNotEmpty()) {
            item("discovery-current") {
                DiscoveryCurrentPanel(state = state, callbacks = callbacks)
            }
        }
        if (!state.errorMessage.isNullOrBlank()) {
            item("discovery-error") {
                DiscoveryMessage(text = state.errorMessage)
            }
        }
        when {
            state.isLoadingBooks -> item("discovery-loading") {
                DiscoveryLoading()
            }
            state.books.isEmpty() -> item("discovery-empty") {
                DiscoveryMessage(text = state.emptyMessage)
            }
            else -> items(
                items = state.books,
                key = { it.bookUrl.ifBlank { it.primaryStr() } },
            ) { book ->
                DiscoveryBookRow(book = book, callbacks = callbacks)
            }
        }
    }
}

@Composable
private fun DiscoveryHeader(
    state: DiscoveryUiState,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "发现",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.currentSource?.bookSourceName ?: "选择书源",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRefresh) {
            Text("刷新")
        }
    }
}

@Composable
private fun DiscoverySourceStrip(
    sources: List<BookSourcePart>,
    selectedSourceUrl: String?,
    isLoading: Boolean,
    callbacks: DiscoveryCallbacks,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "书源",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .height(18.dp)
                        .width(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        if (sources.isEmpty()) {
            DiscoveryMessage(text = "暂无发现书源")
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 8.dp),
            ) {
                items(items = sources, key = { it.bookSourceUrl }) { source ->
                    DiscoverySourceChip(
                        source = source,
                        selected = source.bookSourceUrl == selectedSourceUrl,
                        callbacks = callbacks,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverySourceChip(
    source: BookSourcePart,
    selected: Boolean,
    callbacks: DiscoveryCallbacks,
) {
    Surface(
        modifier = Modifier.combinedClickable(
            onClick = { callbacks.onSourceClick(source) },
            onLongClick = { callbacks.onSourceLongClick(source) },
        ),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .width(168.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = source.bookSourceName.ifBlank { source.bookSourceUrl },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = source.bookSourceGroup?.takeIf { it.isNotBlank() } ?: "未分组",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiscoveryCurrentPanel(
    state: DiscoveryUiState,
    callbacks: DiscoveryCallbacks,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.currentSource?.getDisPlayNameGroup() ?: "未选择书源",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.currentTag?.title ?: "全部分类",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.currentSource?.let { source ->
                    TextButton(onClick = { callbacks.onSearchInSource(source) }) {
                        Text("搜索")
                    }
                }
            }
            if (state.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.tags.forEach { tag ->
                        DiscoveryTagChip(
                            tag = tag,
                            selected = tag.key == state.selectedTagKey,
                            onClick = { callbacks.onTagClick(tag) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryTagChip(
    tag: DiscoveryTag,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = tag.enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = tag.title,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoveryBookRow(
    book: SearchBook,
    callbacks: DiscoveryCallbacks,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { callbacks.onBookClick(book) },
                onLongClick = { callbacks.onBookLongClick(book) },
            ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.name.ifBlank { "未命名" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = book.author.ifBlank { "未知作者" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = book.originName.ifBlank { book.origin },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val summary = listOfNotNull(
                book.kind?.takeIf { it.isNotBlank() },
                book.wordCount?.takeIf { it.isNotBlank() },
                book.latestChapterTitle?.takeIf { it.isNotBlank() },
            ).joinToString(" / ")
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            book.intro?.takeIf { it.isNotBlank() }?.let { intro ->
                Text(
                    text = intro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DiscoveryLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DiscoveryMessage(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun Dp.orZero(): Dp {
    return if (this == Dp.Unspecified) 0.dp else this
}
