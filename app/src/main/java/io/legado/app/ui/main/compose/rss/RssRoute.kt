package io.legado.app.ui.main.compose.rss

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
import androidx.compose.material3.Button
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
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource

@Composable
fun RssRoute(
    state: RssUiState,
    modifier: Modifier = Modifier,
    callbacks: RssCallbacks = RssCallbacks(),
) {
    RssScreen(state = state, modifier = modifier, callbacks = callbacks)
}

@Composable
fun RssScreen(
    state: RssUiState,
    modifier: Modifier = Modifier,
    callbacks: RssCallbacks = RssCallbacks(),
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
        item("rss-header") {
            RssHeader(
                state = state,
                onRefresh = { callbacks.onRefresh(state.currentSource, state.currentCategory) },
            )
        }
        item("rss-sources") {
            RssSourceStrip(
                sources = state.sources,
                selectedSourceUrl = state.selectedSourceUrl,
                isLoading = state.isLoadingSources,
                callbacks = callbacks,
            )
        }
        if (state.currentSource != null || state.categories.isNotEmpty()) {
            item("rss-current") {
                RssCurrentPanel(state = state, callbacks = callbacks)
            }
        }
        if (!state.errorMessage.isNullOrBlank()) {
            item("rss-error") {
                RssMessage(text = state.errorMessage)
            }
        }
        state.legacyWebSource?.let { source ->
            item("rss-legacy-web-${source.sourceUrl}") {
                RssLegacyWebSourcePanel(
                    source = source,
                    onOpenLegacyWebSource = callbacks.onOpenLegacyWebSource,
                )
            }
        }
        when {
            state.isLoadingArticles -> item("rss-loading") {
                RssLoading()
            }
            state.articles.isEmpty() && state.legacyWebSource == null -> item("rss-empty") {
                RssMessage(text = state.emptyMessage)
            }
            state.legacyWebSource == null -> items(
                items = state.articles,
                key = { "${it.origin}:${it.sort}:${it.link}" },
            ) { article ->
                RssArticleRow(article = article, callbacks = callbacks)
            }
        }
    }
}

@Composable
private fun RssHeader(
    state: RssUiState,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "RSS",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.currentSource?.sourceName ?: "Select a source",
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
private fun RssSourceStrip(
    sources: List<RssSource>,
    selectedSourceUrl: String?,
    isLoading: Boolean,
    callbacks: RssCallbacks,
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
            RssMessage(text = "No RSS source")
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 8.dp),
            ) {
                items(items = sources, key = { it.sourceUrl }) { source ->
                    RssSourceChip(
                        source = source,
                        selected = source.sourceUrl == selectedSourceUrl,
                        callbacks = callbacks,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RssSourceChip(
    source: RssSource,
    selected: Boolean,
    callbacks: RssCallbacks,
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
                text = source.sourceName.ifBlank { source.sourceUrl },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = source.sourceGroup?.takeIf { it.isNotBlank() } ?: "No group",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RssCurrentPanel(
    state: RssUiState,
    callbacks: RssCallbacks,
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
                        text = state.currentSource?.getDisplayNameGroup() ?: "No source selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.currentCategory?.title ?: "All categories",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.currentSource?.takeIf { !it.searchUrl.isNullOrBlank() }?.let { source ->
                    TextButton(onClick = { callbacks.onSearchInSource(source) }) {
                        Text("Search")
                    }
                }
            }
            if (state.categories.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.categories.forEach { category ->
                        RssCategoryChip(
                            category = category,
                            selected = category.key == state.selectedCategoryKey,
                            onClick = { callbacks.onCategoryClick(category) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RssCategoryChip(
    category: RssCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = category.enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = category.title,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RssLegacyWebSourcePanel(
    source: RssSource,
    onOpenLegacyWebSource: (RssSource) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Legacy web RSS",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "This module does not embed WebView.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { onOpenLegacyWebSource(source) }) {
                Text("Open")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RssArticleRow(
    article: RssArticle,
    callbacks: RssCallbacks,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { callbacks.onArticleClick(article) },
                onLongClick = { callbacks.onArticleLongClick(article) },
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
                Text(
                    text = article.title.ifBlank { article.link },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (article.read) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = article.group,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            article.pubDate?.takeIf { it.isNotBlank() }?.let { pubDate ->
                Text(
                    text = pubDate,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            article.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
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
private fun RssLoading() {
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
private fun RssMessage(text: String) {
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
