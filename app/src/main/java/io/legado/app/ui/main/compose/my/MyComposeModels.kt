package io.legado.app.ui.main.compose.my

import androidx.compose.runtime.Immutable

@Immutable
data class MyPageUiState(
    val title: String = "我的",
    val searchQuery: String = "",
    val sections: List<MySettingSectionUi> = defaultMySettingSections(),
    val emptyHint: String = "没有匹配的设置"
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
        title = "规则与来源",
        items = listOf(
            MySettingItemUi("bookSourceManage", "书源管理", "管理和校验书源", keywords = listOf("book source", "书源")),
            MySettingItemUi("rssSourceManage", "订阅源管理", "管理 RSS 和发现订阅"),
            MySettingItemUi("replaceManage", "替换规则", "清理和替换正文内容"),
            MySettingItemUi("dictRuleManage", "字典规则", "配置字典查询规则"),
            MySettingItemUi("txtTocRuleManage", "TXT 目录规则", "识别本地书籍章节")
        )
    ),
    MySettingSectionUi(
        id = "reading",
        title = "阅读",
        items = listOf(
            MySettingItemUi("bookmark", "书签", "查看全部书籍的书签"),
            MySettingItemUi("readRecord", "阅读记录", "阅读时长、最近在读和排行"),
            MySettingItemUi("cacheManage", "缓存管理", "清理和管理书籍缓存"),
            MySettingItemUi("coverConfig", "封面设置", "封面显示和缓存选项")
        )
    ),
    MySettingSectionUi(
        id = "appearance",
        title = "界面",
        items = listOf(
            MySettingItemUi("theme_setting", "界面设置", "夜间模式、强调色和字体"),
            MySettingItemUi("theme_manage", "主题管理", "导入、编辑和应用主题"),
            MySettingItemUi("navigation_bar_manage", "底栏管理", "配置底部导航"),
            MySettingItemUi("bubble_manage", "气泡管理", "段评气泡和样式资源")
        )
    ),
    MySettingSectionUi(
        id = "system",
        title = "系统",
        items = listOf(
            MySettingItemUi("setting", "其他设置", "通用设置和实验功能"),
            MySettingItemUi("web_dav_setting", "备份与恢复", "WebDAV、对象存储、恢复和同步设置"),
            MySettingItemUi("ai_setting", "AI 设置", "模型、网络和工具选项"),
            MySettingItemUi("book_info_manage", "详情页管理", "书籍元数据和规则"),
            MySettingItemUi("fileManage", "文件管理", "管理本地文件"),
            MySettingItemUi("about", "关于", "版本、帮助和开源信息"),
            MySettingItemUi("exit", "退出", "关闭应用")
        )
    )
)
