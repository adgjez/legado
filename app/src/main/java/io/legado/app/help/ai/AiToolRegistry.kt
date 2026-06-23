package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import org.json.JSONObject

data class AiResolvedTool(
    val name: String,
    val definition: JSONObject,
    val execute: suspend (JSONObject?) -> String
)

object AiToolRegistry {

    data class ToolMeta(
        val name: String,
        val label: String,
        val group: String
    )

    val characterCompanionToolNames = setOf(
        "query_bookshelf",
        "get_bookshelf_book_info",
        "list_book_chapters",
        "search_book_chapter_content",
        "read_book_chapter_content",
        "generate_image"
    )

    val defaultEnabledTools = setOf(
        "query_bookshelf",
        "get_bookshelf_book_info",
        "manage_bookshelf_group",
        "manage_bookshelf_tag",
        "set_bookshelf_book_group",
        "set_bookshelf_book_tags",
        "list_book_chapters",
        "read_book_chapter_content",
        "query_read_records",
        "list_book_sources",
        "search_book_source",
        "search_web_tavily",
        "create_book_source",
        "get_book_source",
        "update_book_source",
        "fetch_source_html",
        "debug_book_source",
        "reading_ajax",
        "reading_webview",
        "capture_web_requests",
        "generate_image",
        "list_book_characters",
        "upsert_book_character",
        "delete_book_character",
        "list_book_character_relations",
        "upsert_book_character_relation",
        "delete_book_character_relation",
        "list_ai_gallery_images",
        "set_book_character_avatar_from_gallery",
        "generate_book_character_avatar",
        "generate_video",
        "list_ai_gallery_videos",
        "get_ai_gallery_video",
        "set_book_character_avatar_from_video_gallery",
        "generate_book_character_short_video",
        "generate_book_chapter_video",
        "generate_book_chapter_video_range",
        "get_app_settings",
        "set_app_setting",
        "set_app_settings_batch",
        "list_world_books",
        "upsert_world_book",
        "delete_world_book",
        "upsert_world_book_entry",
        "delete_world_book_entry",
        "list_world_book_bindings",
        "upsert_world_book_binding",
        "delete_world_book_binding",
        "import_world_book_json",
        "export_world_book_json"
    )

    private val nativeToolLabels = mapOf(
        "query_bookshelf" to "查询书架书籍",
        "get_bookshelf_book_info" to "读取书籍详情",
        "manage_bookshelf_group" to "管理书架分组",
        "manage_bookshelf_tag" to "管理书架标签",
        "set_bookshelf_book_group" to "设置书籍分组",
        "set_bookshelf_book_tags" to "设置书籍标签",
        "query_read_records" to "查询阅读记录",
        "list_book_chapters" to "读取章节目录",
        "read_book_chapter_content" to "读取章节正文",
        "list_book_sources" to "列出书源",
        "search_book_source" to "搜索书源内容",
        "create_book_source" to "创建书源",
        "get_book_source" to "读取书源详情",
        "update_book_source" to "更新书源",
        "fetch_source_html" to "抓取网页源码",
        "debug_book_source" to "调试书源规则",
        "reading_ajax" to "阅读网络请求",
        "reading_webview" to "阅读 WebView",
        "capture_web_requests" to "抓包网络请求",
        "search_web_tavily" to "联网搜索",
        "generate_image" to "生成图片",
        "list_book_characters" to "读取角色资料",
        "upsert_book_character" to "新增或更新角色",
        "delete_book_character" to "删除角色",
        "list_book_character_relations" to "读取角色关系网",
        "upsert_book_character_relation" to "新增或更新角色关系",
        "delete_book_character_relation" to "删除角色关系",
        "list_ai_gallery_images" to "读取 AI 图片库",
        "set_book_character_avatar_from_gallery" to "设置角色图库头像",
        "generate_book_character_avatar" to "生成角色头像",
        "generate_video" to "生成 AI 视频",
        "list_ai_gallery_videos" to "读取 AI 视频库",
        "get_ai_gallery_video" to "读取 AI 视频详情",
        "set_book_character_avatar_from_video_gallery" to "从视频库设置角色头像",
        "generate_book_character_short_video" to "生成角色短视频",
        "generate_book_chapter_video" to "章节生成视频",
        "generate_book_chapter_video_range" to "批量章节视频",
        "get_app_settings" to "读取应用设置",
        "set_app_setting" to "修改应用设置",
        "set_app_settings_batch" to "批量修改设置",
        "list_world_books" to "列出世界书",
        "upsert_world_book" to "新增或更新世界书",
        "delete_world_book" to "删除世界书",
        "upsert_world_book_entry" to "新增或更新世界书条目",
        "delete_world_book_entry" to "删除世界书条目",
        "list_world_book_bindings" to "列出世界书绑定",
        "upsert_world_book_binding" to "新增或更新世界书绑定",
        "delete_world_book_binding" to "删除世界书绑定",
        "import_world_book_json" to "导入世界书 JSON",
        "export_world_book_json" to "导出世界书 JSON"
    )

    private val nativeToolGroups = mapOf(
        "query_bookshelf" to "书架",
        "get_bookshelf_book_info" to "书架",
        "manage_bookshelf_group" to "书架",
        "manage_bookshelf_tag" to "书架",
        "set_bookshelf_book_group" to "书架",
        "set_bookshelf_book_tags" to "书架",
        "query_read_records" to "书架",
        "list_book_chapters" to "阅读",
        "read_book_chapter_content" to "阅读",
        "list_book_sources" to "书源",
        "search_book_source" to "书源",
        "create_book_source" to "书源",
        "get_book_source" to "书源",
        "update_book_source" to "书源",
        "fetch_source_html" to "书源",
        "debug_book_source" to "书源",
        "reading_ajax" to "阅读网络",
        "reading_webview" to "阅读网络",
        "capture_web_requests" to "阅读网络",
        "search_web_tavily" to "联网搜索",
        "generate_image" to "AI 生图",
        "list_book_characters" to "角色资料",
        "upsert_book_character" to "角色资料",
        "delete_book_character" to "角色资料",
        "list_book_character_relations" to "角色资料",
        "upsert_book_character_relation" to "角色资料",
        "delete_book_character_relation" to "角色资料",
        "list_ai_gallery_images" to "AI 图片库",
        "set_book_character_avatar_from_gallery" to "角色资料",
        "generate_book_character_avatar" to "角色资料",
        "generate_video" to "AI 视频",
        "list_ai_gallery_videos" to "AI 视频",
        "get_ai_gallery_video" to "AI 视频",
        "set_book_character_avatar_from_video_gallery" to "角色资料",
        "generate_book_character_short_video" to "AI 视频",
        "generate_book_chapter_video" to "AI 视频",
        "generate_book_chapter_video_range" to "AI 视频",
        "get_app_settings" to "设置",
        "set_app_setting" to "设置",
        "set_app_settings_batch" to "设置",
        "list_world_books" to "世界书",
        "upsert_world_book" to "世界书",
        "delete_world_book" to "世界书",
        "upsert_world_book_entry" to "世界书",
        "delete_world_book_entry" to "世界书",
        "list_world_book_bindings" to "世界书",
        "upsert_world_book_binding" to "世界书",
        "delete_world_book_binding" to "世界书",
        "import_world_book_json" to "世界书",
        "export_world_book_json" to "世界书"
    )

    fun groupLabelOfTool(name: String): String {
        return when {
            name.startsWith("mcp_") -> "MCP 工具"
            else -> nativeToolGroups[name] ?: "其他"
        }
    }

    fun displayNameOfTool(name: String): String {
        return when {
            name.startsWith("mcp_") -> name.removePrefix("mcp_").ifBlank { name }
            else -> nativeToolLabels[name] ?: name
        }
    }

    fun metaOfTool(name: String): ToolMeta {
        return ToolMeta(
            name = name,
            label = displayNameOfTool(name),
            group = groupLabelOfTool(name)
        )
    }

    private fun nativeResolvedTools(): List<AiResolvedTool> {
        val tools = AiBookshelfTool.resolvedTools().toMutableList()
        tools += AiLibraryTool.resolvedTools()
        tools += AiTavilyTool.resolvedTools()
        tools += AiBookSourceTool.resolvedTools()
        tools += AiReadingNetworkTool.resolvedTools()
        tools += AiSettingsTool.resolvedTools()
        tools += AiImageTool.resolvedTools()
        tools += AiBookCharacterTool.resolvedTools()
        tools += AiVideoTool.resolvedTools()
        tools += AiWorldBookTool.resolvedTools()
        return tools.distinctBy { it.name }
    }

    suspend fun resolveAllToolNamesForManage(): List<String> {
        val dynamic = mutableSetOf<String>()
        dynamic += nativeResolvedTools().map { it.name }
        dynamic += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers).map { it.name }
        dynamic += AppConfig.aiEnabledToolNames
        dynamic += defaultEnabledTools
        return dynamic.toList().sorted()
    }

    suspend fun resolveAvailableTools(): List<AiResolvedTool> {
        val tools = nativeResolvedTools().toMutableList()
        tools += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers)
        val enabled = AppConfig.aiEnabledToolNames.ifEmpty { defaultEnabledTools }
        return tools
            .distinctBy { it.name }
            .filter { it.name in enabled }
    }

    suspend fun resolveAllTools(): List<AiResolvedTool> {
        val tools = nativeResolvedTools().toMutableList()
        tools += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers)
        return tools.distinctBy { it.name }
    }

    fun resolveNativeTools(names: Set<String>): List<AiResolvedTool> {
        return nativeResolvedTools()
            .filter { it.name in names }
    }

    suspend fun resolveMcpTools(serverIds: Set<String>): List<AiResolvedTool> {
        if (serverIds.isEmpty()) return emptyList()
        val servers = AppConfig.aiMcpServerList.filter { server ->
            server.enabled && server.id in serverIds
        }
        if (servers.isEmpty()) return emptyList()
        return AiMcpClient.resolveTools(servers)
    }
}
