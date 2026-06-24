package io.legado.app.ui.main.explore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig

@Composable
fun DiscoverySuiteHomeScreen(
    selectedSuite: DiscoverySuite?,
    widgetBooks: Map<String, List<SearchBook>>,
    loadingWidgetIds: Set<String>,
    scrollToTopSignal: Int,
    onSearchClick: () -> Unit,
    onSuiteClick: () -> Unit,
    onCreateSuiteClick: () -> Unit,
    onAddWidgetClick: () -> Unit,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit,
    onCanScrollBackwardChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val canScrollBackward by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        DiscoverySuiteSearchBar(
            selectedSuiteLabel = selectedSuite?.displayName
                ?: context.getString(R.string.discovery_suite_no_suite),
            onSearchClick = onSearchClick,
            onSuiteClick = onSuiteClick
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            content = {
                if (selectedSuite == null) {
                    item(key = "suite_empty") {
                        DiscoverySuiteEmptyState(
                            title = context.getString(R.string.discovery_suite_empty_title),
                            summary = context.getString(R.string.discovery_suite_empty_summary),
                            action = context.getString(R.string.discovery_suite_create),
                            onActionClick = onCreateSuiteClick
                        )
                    }
                } else if (selectedSuite.widgets.isEmpty()) {
                    item(key = "suite_no_widgets") {
                        DiscoverySuiteEmptyState(
                            title = context.getString(R.string.discovery_suite_no_widgets_title),
                            summary = context.getString(R.string.discovery_suite_no_widgets_summary),
                            action = context.getString(R.string.discovery_suite_add_widget),
                            onActionClick = onAddWidgetClick
                        )
                    }
                } else {
                    item(key = "suite_header") {
                        Text(
                            text = selectedSuite.displayName,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    selectedSuite.widgets.forEach { widget ->
                        item(key = widget.id) {
                            DiscoverySuiteWidgetSection(
                                widget = widget,
                                books = widgetBooks[widget.id].orEmpty(),
                                isLoading = widget.id in loadingWidgetIds,
                                onBookClick = onBookClick,
                                onBookPreview = onBookPreview
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun DiscoverySuiteSearchBar(
    selectedSuiteLabel: String,
    onSearchClick: () -> Unit,
    onSuiteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                .clickable(onClick = onSearchClick)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = LocalContext.current.getString(R.string.search),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            modifier = Modifier
                .height(44.dp)
                .clickable(onClick = onSuiteClick),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = selectedSuiteLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun DiscoverySuiteEmptyState(
    title: String,
    summary: String,
    action: String,
    onActionClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            modifier = Modifier
                .height(42.dp)
                .clickable(onClick = onActionClick),
            shape = RoundedCornerShape(21.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = action,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun DiscoverySuiteWidgetSection(
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    isLoading: Boolean,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = widget.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when {
                isLoading -> Text(
                    text = LocalContext.current.getString(R.string.discovery_suite_widget_loading),
                    style = MaterialTheme.typography.bodyMedium
                )
                books.isEmpty() -> Text(
                    text = LocalContext.current.getString(R.string.discovery_suite_widget_pending),
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> books.take(widget.displayLimit).forEach { book ->
                    DiscoverySuiteBookRow(
                        book = book,
                        onBookClick = onBookClick,
                        onBookPreview = onBookPreview
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverySuiteBookRow(
    book: SearchBook,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookPreview(book) }
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = book.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOf(book.author, book.originName)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
