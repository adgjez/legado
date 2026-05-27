package io.legado.app.ui.main.compose.rss

import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource

@Stable
data class RssCategory(
    val key: String,
    val title: String,
    val enabled: Boolean = true,
)

@Stable
data class RssUiState(
    val sources: List<RssSource> = emptyList(),
    val selectedSourceUrl: String? = null,
    val categories: List<RssCategory> = emptyList(),
    val selectedCategoryKey: String? = null,
    val articles: List<RssArticle> = emptyList(),
    val isLoadingSources: Boolean = false,
    val isLoadingArticles: Boolean = false,
    val errorMessage: String? = null,
    val emptyMessage: String = "暂无订阅文章",
    val contentPaddingTop: Dp = Dp.Unspecified,
    val contentPaddingBottom: Dp = Dp.Unspecified,
) {
    val currentSource: RssSource?
        get() = sources.firstOrNull { it.sourceUrl == selectedSourceUrl }

    val currentCategory: RssCategory?
        get() = categories.firstOrNull { it.key == selectedCategoryKey }

    val legacyWebSource: RssSource?
        get() = currentSource?.takeIf { it.ruleArticles.isNullOrBlank() }
}

@Stable
data class RssCallbacks(
    val onSourceClick: (RssSource) -> Unit = {},
    val onSourceLongClick: (RssSource) -> Unit = {},
    val onCategoryClick: (RssCategory) -> Unit = {},
    val onArticleClick: (RssArticle) -> Unit = {},
    val onArticleLongClick: (RssArticle) -> Unit = {},
    val onRefresh: (RssSource?, RssCategory?) -> Unit = { _, _ -> },
    val onOpenLegacyWebSource: (RssSource) -> Unit = {},
    val onSearchInSource: (RssSource) -> Unit = {},
)
