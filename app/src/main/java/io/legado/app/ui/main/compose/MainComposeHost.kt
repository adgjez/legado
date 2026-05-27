package io.legado.app.ui.main.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.compose.bookshelf.BookshelfCallbacks
import io.legado.app.ui.main.compose.bookshelf.BookshelfRoute
import io.legado.app.ui.main.compose.bookshelf.rememberBookshelfScrollController
import io.legado.app.ui.main.compose.discovery.DiscoveryCallbacks
import io.legado.app.ui.main.compose.discovery.DiscoveryRoute
import io.legado.app.ui.main.compose.discovery.DiscoveryUiState
import io.legado.app.ui.main.compose.my.MyRoute
import io.legado.app.ui.main.compose.my.MyRouteCallbacks
import io.legado.app.ui.main.compose.readrecord.ReadRecordActions
import io.legado.app.ui.main.compose.readrecord.ReadRecordRoute
import io.legado.app.ui.main.compose.rss.RssCallbacks
import io.legado.app.ui.main.compose.rss.RssRoute
import io.legado.app.ui.main.compose.rss.RssUiState

@Immutable
data class MainComposeHostActions(
    val onOpenSearch: () -> Unit = {},
    val onOpenAi: () -> Unit = {},
    val onOpenBook: (Book) -> Unit = {},
    val onOpenBookInfo: (Book) -> Unit = {},
    val onRefreshBooks: (List<Book>) -> Unit = {},
    val onOpenSearchBook: (SearchBook) -> Unit = {},
    val onOpenRssArticle: (RssArticle) -> Unit = {},
    val onOpenRssSource: (RssSource) -> Unit = {},
    val onOpenBookUrl: (String) -> Unit = {},
    val onOpenMyItem: (String) -> Unit = {},
    val onReadRecordMore: () -> Unit = {}
)

@Composable
fun MainComposeHost(
    selectedTab: MainComposeTab,
    updateCount: Int,
    onTabSelected: (MainComposeTab) -> Unit,
    actions: MainComposeHostActions,
    modifier: Modifier = Modifier
) {
    val tabs = rememberMainComposeTabs()
    val safeSelectedTab = selectedTab.takeIf { it in tabs } ?: tabs.first()
    val bookshelfScrollController = rememberBookshelfScrollController()

    MaterialTheme {
        MainComposeRoute(
            state = MainComposeUiState(
                selectedTab = safeSelectedTab,
                tabs = tabs,
                bottomBarMode = AppConfig.bottomBarLayoutMode.takeUnless { it == "sidebar" } ?: "floating",
                effectMode = AppConfig.bottomBarEffectMode,
                glassLevel = (
                    if (AppConfig.bottomBarEffectMode == "frosted") {
                        AppConfig.frostedGlassLevel
                    } else {
                        AppConfig.liquidGlassLevel
                    }
                    ) / 100f,
                isNight = AppConfig.isNightTheme,
                updateCount = updateCount
            ),
            actions = MainComposeActions(
                onSelectTab = onTabSelected,
                onReselectTab = { tab ->
                    when (tab) {
                        MainComposeTab.Bookshelf -> bookshelfScrollController.scrollToTop()
                        else -> Unit
                    }
                },
                onSearch = actions.onOpenSearch,
                onSearchLongClick = actions.onOpenAi
            ),
            modifier = modifier
        ) { tab ->
            when (tab) {
                MainComposeTab.Bookshelf -> BookshelfRoute(
                    scrollController = bookshelfScrollController,
                    callbacks = BookshelfCallbacks(
                        onBookClick = actions.onOpenBook,
                        onBookLongClick = actions.onOpenBookInfo,
                        onRefresh = { _, books -> actions.onRefreshBooks(books) }
                    )
                )

                MainComposeTab.Discovery -> DiscoveryRoute(
                    state = DiscoveryUiState(contentPaddingBottom = 88.dp),
                    callbacks = DiscoveryCallbacks(
                        onBookClick = actions.onOpenSearchBook,
                        onBookLongClick = actions.onOpenSearchBook
                    )
                )

                MainComposeTab.Rss -> RssRoute(
                    state = RssUiState(contentPaddingBottom = 88.dp),
                    callbacks = RssCallbacks(
                        onArticleClick = actions.onOpenRssArticle,
                        onArticleLongClick = actions.onOpenRssArticle,
                        onOpenLegacyWebSource = actions.onOpenRssSource
                    )
                )

                MainComposeTab.ReadRecord -> ReadRecordRoute(
                    actions = ReadRecordActions(
                        onOpenBook = { actions.onOpenBookUrl(it.id) },
                        onBookLongClick = { actions.onOpenBookUrl(it.id) },
                        onRankItemClick = { actions.onOpenBookUrl(it.id) },
                        onMoreClick = actions.onReadRecordMore
                    )
                )

                MainComposeTab.My -> MyRoute(
                    callbacks = MyRouteCallbacks(
                        onItemClick = actions.onOpenMyItem
                    )
                )
            }
        }
    }
}

@Composable
private fun rememberMainComposeTabs(): List<MainComposeTab> {
    return remember(
        AppConfig.showDiscovery,
        AppConfig.showRSS,
        AppConfig.showReadRecord,
        AppConfig.mergeDiscoveryRss
    ) {
        buildList {
            add(MainComposeTab.Bookshelf)
            val discoveryVisible = AppConfig.showDiscovery
            val rssVisible = AppConfig.showRSS
            if (discoveryVisible) {
                add(MainComposeTab.Discovery)
            }
            if (rssVisible && !(AppConfig.mergeDiscoveryRss && discoveryVisible)) {
                add(MainComposeTab.Rss)
            }
            if (AppConfig.showReadRecord) {
                add(MainComposeTab.ReadRecord)
            }
            add(MainComposeTab.My)
        }
    }
}
