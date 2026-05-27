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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook

@Composable
fun DiscoveryRoute(
    state: DiscoveryUiState,
    modifier: Modifier = Modifier,
    callbacks: DiscoveryCallbacks = DiscoveryCallbacks(),
) {
    DiscoveryScreen(state = state, modifier = modifier, callbacks = callbacks)
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
                text = "Discovery",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.currentSource?.bookSourceName ?: "Select a source",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRefresh) {
            Text("Refresh")
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
                text = "Sources",
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
            DiscoveryMessage(text = "No discovery source")
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
                text = source.bookSourceGroup?.takeIf { it.isNotBlank() } ?: "No group",
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
                        text = state.currentSource?.getDisPlayNameGroup() ?: "No source selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.currentTag?.title ?: "All tags",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.currentSource?.let { source ->
                    TextButton(onClick = { callbacks.onSearchInSource(source) }) {
                        Text("Search")
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
                        text = book.name.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = book.author.ifBlank { "Unknown author" },
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
