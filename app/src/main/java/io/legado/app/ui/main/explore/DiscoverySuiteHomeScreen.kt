package io.legado.app.ui.main.explore

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.compose.appSettingPanelBackground

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
    val renderConfig = rememberBookshelfListRenderConfig()
    val palette = renderConfig.palette
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
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        DiscoverySuiteSearchBar(
            selectedSuiteLabel = selectedSuite?.displayName
                ?: context.getString(R.string.discovery_suite_no_suite),
            renderConfig = renderConfig,
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
                            renderConfig = renderConfig,
                            onActionClick = onCreateSuiteClick
                        )
                    }
                } else if (selectedSuite.widgets.isEmpty()) {
                    item(key = "suite_no_widgets") {
                        DiscoverySuiteEmptyState(
                            title = context.getString(R.string.discovery_suite_no_widgets_title),
                            summary = context.getString(R.string.discovery_suite_no_widgets_summary),
                            action = context.getString(R.string.discovery_suite_add_widget),
                            renderConfig = renderConfig,
                            onActionClick = onAddWidgetClick
                        )
                    }
                } else {
                    item(key = "suite_header") {
                        Text(
                            text = selectedSuite.displayName,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = palette.titleFontFamily,
                            color = palette.primaryText
                        )
                    }
                    selectedSuite.widgets.forEach { widget ->
                        item(key = widget.id) {
                            DiscoverySuiteWidgetSection(
                                widget = widget,
                                books = widgetBooks[widget.id].orEmpty(),
                                isLoading = widget.id in loadingWidgetIds,
                                renderConfig = renderConfig,
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
    renderConfig: BookshelfListRenderConfig,
    onSearchClick: () -> Unit,
    onSuiteClick: () -> Unit
) {
    val palette = renderConfig.palette
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
                .clip(RoundedCornerShape(palette.actionRadius))
                .appSettingPanelBackground(
                    normalColor = palette.rowColor,
                    panelImage = renderConfig.panelImage,
                    borderColor = palette.borderColor,
                    radiusPx = palette.panelRadiusPx
                )
                .clickable(onClick = onSearchClick)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = LocalContext.current.getString(R.string.search),
                color = palette.secondaryText,
                fontSize = 15.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(palette.actionRadius))
                .appSettingPanelBackground(
                    normalColor = palette.rowColor,
                    panelImage = renderConfig.panelImage,
                    borderColor = palette.borderColor,
                    radiusPx = palette.panelRadiusPx
                )
                .clickable(onClick = onSuiteClick)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = selectedSuiteLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    fontFamily = palette.bodyFontFamily,
                    color = palette.accent
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
    renderConfig: BookshelfListRenderConfig,
    onActionClick: () -> Unit
) {
    val palette = renderConfig.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.titleFontFamily,
            color = palette.primaryText
        )
        Text(
            text = summary,
            fontSize = 14.sp,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText
        )
        Box(
            modifier = Modifier
                .height(42.dp)
                .clip(RoundedCornerShape(palette.actionRadius))
                .clickable(onClick = onActionClick)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = action,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    fontFamily = palette.bodyFontFamily,
                    color = palette.accent
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
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit
) {
    val palette = renderConfig.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(palette.panelRadius))
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = palette.panelRadiusPx
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = widget.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.titleFontFamily,
                color = palette.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when {
                isLoading -> Text(
                    text = LocalContext.current.getString(R.string.discovery_suite_widget_loading),
                    fontSize = 14.sp,
                    fontFamily = palette.bodyFontFamily,
                    color = palette.secondaryText
                )
                books.isEmpty() -> Text(
                    text = LocalContext.current.getString(R.string.discovery_suite_widget_pending),
                    fontSize = 14.sp,
                    fontFamily = palette.bodyFontFamily,
                    color = palette.secondaryText
                )
                else -> books.take(widget.displayLimit).forEach { book ->
                    DiscoverySuiteBookRow(
                        book = book,
                        renderConfig = renderConfig,
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
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook) -> Unit
) {
    val palette = renderConfig.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.actionRadius))
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookPreview(book) }
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = book.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.titleFontFamily,
            color = palette.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOf(book.author, book.originName)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            fontSize = 12.sp,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
