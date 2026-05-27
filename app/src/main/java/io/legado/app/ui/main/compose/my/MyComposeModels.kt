package io.legado.app.ui.main.compose.my

import androidx.compose.runtime.Immutable

@Immutable
data class MyPageUiState(
    val title: String = "Me",
    val searchQuery: String = "",
    val sections: List<MySettingSectionUi> = defaultMySettingSections(),
    val emptyHint: String = "No matching settings"
)

@Immutable
data class MySettingSectionUi(
    val id: String,
    val title: String,
    val items: List<MySettingItemUi>
)

@Immutable
data class MySettingItemUi(
    val key: String,
    val title: String,
    val summary: String = "",
    val badge: String = "",
    val keywords: List<String> = emptyList()
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val normalized = query.trim().lowercase()
        return title.lowercase().contains(normalized) ||
            summary.lowercase().contains(normalized) ||
            key.lowercase().contains(normalized) ||
            keywords.any { it.lowercase().contains(normalized) }
    }
}

@Immutable
data class MyPageActions(
    val onSearchQueryChange: (String) -> Unit = {},
    val onItemClick: (MySettingItemUi) -> Unit = {},
    val onHelpClick: () -> Unit = {}
)

fun defaultMySettingSections(): List<MySettingSectionUi> = listOf(
    MySettingSectionUi(
        id = "source",
        title = "Sources",
        items = listOf(
            MySettingItemUi("bookSourceManage", "Book sources", "Manage and validate book sources", keywords = listOf("book source")),
            MySettingItemUi("rssSourceManage", "RSS sources", "Manage RSS and discovery subscriptions"),
            MySettingItemUi("replaceManage", "Replace rules", "Clean and replace reading content"),
            MySettingItemUi("dictRuleManage", "Dictionary rules", "Configure dictionary lookup rules"),
            MySettingItemUi("txtTocRuleManage", "TXT TOC rules", "Detect local book chapters")
        )
    ),
    MySettingSectionUi(
        id = "reading",
        title = "Reading",
        items = listOf(
            MySettingItemUi("bookmark", "Bookmarks", "View bookmarks from all books"),
            MySettingItemUi("readRecord", "Read records", "Read time, recent books and rankings"),
            MySettingItemUi("cacheManage", "Cache manager", "Clean and manage book cache"),
            MySettingItemUi("coverConfig", "Cover settings", "Cover display and cache options")
        )
    ),
    MySettingSectionUi(
        id = "appearance",
        title = "Appearance",
        items = listOf(
            MySettingItemUi("theme_setting", "Theme settings", "Night mode, accent color and font"),
            MySettingItemUi("theme_manage", "Theme manager", "Import, edit and apply themes"),
            MySettingItemUi("navigation_bar_manage", "Navigation bar", "Configure bottom navigation"),
            MySettingItemUi("bubble_manage", "Bubble manager", "Reading bubbles and style assets")
        )
    ),
    MySettingSectionUi(
        id = "system",
        title = "System",
        items = listOf(
            MySettingItemUi("setting", "Other settings", "General settings and experiments"),
            MySettingItemUi("web_dav_setting", "Backup and sync", "WebDAV, restore and sync settings"),
            MySettingItemUi("ai_setting", "AI settings", "Model, network and tool options"),
            MySettingItemUi("book_info_manage", "Book info", "Book metadata and rules"),
            MySettingItemUi("fileManage", "File manager", "Manage local files"),
            MySettingItemUi("about", "About", "Version, help and open source info"),
            MySettingItemUi("exit", "Exit", "Close the app")
        )
    )
)
