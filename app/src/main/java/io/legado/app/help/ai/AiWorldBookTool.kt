package io.legado.app.help.ai

import org.json.JSONArray
import org.json.JSONObject

object AiWorldBookTool {

    private const val TOOL_LIST_WORLD_BOOKS = "list_world_books"
    private const val TOOL_UPSERT_WORLD_BOOK = "upsert_world_book"
    private const val TOOL_DELETE_WORLD_BOOK = "delete_world_book"
    private const val TOOL_UPSERT_WORLD_BOOK_ENTRY = "upsert_world_book_entry"
    private const val TOOL_DELETE_WORLD_BOOK_ENTRY = "delete_world_book_entry"

    fun resolvedTools(): List<AiResolvedTool> {
        return listOf(
            AiResolvedTool(
                name = TOOL_LIST_WORLD_BOOKS,
                definition = listWorldBooksDefinition(),
                execute = { args -> AiWorldBookManager.listWorldBooks(args) }
            ),
            AiResolvedTool(
                name = TOOL_UPSERT_WORLD_BOOK,
                definition = upsertWorldBookDefinition(),
                execute = { args -> AiWorldBookManager.upsertWorldBook(args) }
            ),
            AiResolvedTool(
                name = TOOL_DELETE_WORLD_BOOK,
                definition = deleteWorldBookDefinition(),
                execute = { args -> AiWorldBookManager.deleteWorldBook(args) }
            ),
            AiResolvedTool(
                name = TOOL_UPSERT_WORLD_BOOK_ENTRY,
                definition = upsertWorldBookEntryDefinition(),
                execute = { args -> AiWorldBookManager.upsertWorldBookEntry(args) }
            ),
            AiResolvedTool(
                name = TOOL_DELETE_WORLD_BOOK_ENTRY,
                definition = deleteWorldBookEntryDefinition(),
                execute = { args -> AiWorldBookManager.deleteWorldBookEntry(args) }
            )
        )
    }

    private fun listWorldBooksDefinition() = functionDefinition(
        name = TOOL_LIST_WORLD_BOOKS,
        description = "列出 AI 世界书配置。世界书用于长期设定、角色背景、写作规则、酒馆设定和固定知识注入。",
        properties = JSONObject()
            .put("includeEntries", JSONObject().put("type", "boolean").put("description", "是否返回条目内容"))
    )

    private fun upsertWorldBookDefinition() = functionDefinition(
        name = TOOL_UPSERT_WORLD_BOOK,
        description = "新增或更新世界书。scope=global 全局生效，book 按 bookKey 生效，session 按 sessionId 生效。",
        properties = JSONObject()
            .put("id", JSONObject().put("type", "string"))
            .put("name", JSONObject().put("type", "string"))
            .put("description", JSONObject().put("type", "string"))
            .put("scope", JSONObject().put("type", "string").put("enum", JSONArray(listOf("global", "book", "session"))))
            .put("bookKey", JSONObject().put("type", "string"))
            .put("enabled", JSONObject().put("type", "boolean"))
            .put("order", JSONObject().put("type", "integer")),
        required = listOf("name")
    )

    private fun deleteWorldBookDefinition() = functionDefinition(
        name = TOOL_DELETE_WORLD_BOOK,
        description = "删除世界书。",
        properties = JSONObject()
            .put("id", JSONObject().put("type", "string")),
        required = listOf("id")
    )

    private fun upsertWorldBookEntryDefinition() = functionDefinition(
        name = TOOL_UPSERT_WORLD_BOOK_ENTRY,
        description = "新增或更新世界书条目。keys 命中时注入，constant=true 时常驻注入，excludeKeys 命中时不注入。",
        properties = JSONObject()
            .put("worldBookId", JSONObject().put("type", "string"))
            .put("entry", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject()
                    .put("id", JSONObject().put("type", "string"))
                    .put("title", JSONObject().put("type", "string"))
                    .put("content", JSONObject().put("type", "string"))
                    .put("keys", stringArraySchema())
                    .put("secondaryKeys", stringArraySchema())
                    .put("excludeKeys", stringArraySchema())
                    .put("enabled", JSONObject().put("type", "boolean"))
                    .put("constant", JSONObject().put("type", "boolean"))
                    .put("priority", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100))
                    .put("order", JSONObject().put("type", "integer"))
                )
                put("required", JSONArray(listOf("title", "content")))
                put("additionalProperties", false)
            }),
        required = listOf("worldBookId", "entry")
    )

    private fun deleteWorldBookEntryDefinition() = functionDefinition(
        name = TOOL_DELETE_WORLD_BOOK_ENTRY,
        description = "删除世界书条目。",
        properties = JSONObject()
            .put("worldBookId", JSONObject().put("type", "string"))
            .put("entryId", JSONObject().put("type", "string")),
        required = listOf("worldBookId", "entryId")
    )

    private fun functionDefinition(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String> = emptyList()
    ) = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                if (required.isNotEmpty()) put("required", JSONArray(required))
                put("additionalProperties", false)
            })
        })
    }

    private fun stringArraySchema(): JSONObject {
        return JSONObject()
            .put("type", "array")
            .put("items", JSONObject().put("type", "string"))
    }
}
