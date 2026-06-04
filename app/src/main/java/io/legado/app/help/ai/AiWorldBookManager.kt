package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.data.entities.AiMemoryItem
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiWorldBookBinding
import io.legado.app.ui.main.ai.AiWorldBookConfig
import io.legado.app.ui.main.ai.AiWorldBookEntry
import org.json.JSONArray
import org.json.JSONObject

data class AiWorldBookHit(
    val worldBook: AiWorldBookConfig,
    val entry: AiWorldBookEntry,
    val matchedKeys: List<String>,
    val binding: AiWorldBookBinding?,
    val score: Int
)

data class AiWorldBookContext(
    val hits: List<AiWorldBookHit> = emptyList()
) {
    val isNotEmpty: Boolean
        get() = hits.isNotEmpty()

    fun toSystemPrompt(maxChars: Int = 4_800): String {
        if (hits.isEmpty()) return ""
        val builder = StringBuilder()
        builder.append("世界书命中条目：这些是当前对话必须优先遵循的设定、背景、规则或长期资料。")
        builder.append("如果世界书和工具结果冲突，以工具结果为准；如果和用户本轮明确要求冲突，以用户本轮要求为准。\n")
        hits.forEach { hit ->
            val line = buildString {
                append("- [")
                append(hit.worldBook.name)
                append(" / ")
                append(hit.entry.title)
                append("]")
                if (hit.matchedKeys.isNotEmpty()) {
                    append(" keys=")
                    append(hit.matchedKeys.joinToString(","))
                }
                append(": ")
                append(hit.entry.content.replace(Regex("\\s+"), " ").trim())
            }
            if (builder.length + line.length + 1 > maxChars) return@forEach
            builder.append(line.take(1_200)).append('\n')
        }
        return builder.toString().trim()
    }

    fun toTraceJson(): JSONObject {
        val items = JSONArray()
        hits.forEach { hit ->
            items.put(
                JSONObject()
                    .put("worldBookId", hit.worldBook.id)
                    .put("worldBookName", hit.worldBook.name)
                    .put("entryId", hit.entry.id)
                    .put("entryTitle", hit.entry.title)
                    .put("keys", JSONArray(hit.matchedKeys))
                    .put("bindingType", hit.binding?.targetType.orEmpty())
                    .put("bindingKey", hit.binding?.targetKey.orEmpty())
                    .put("score", hit.score)
            )
        }
        return JSONObject()
            .put("count", hits.size)
            .put("items", items)
    }
}

object AiWorldBookManager {

    fun retrieve(
        context: AiMemoryContext?,
        messages: List<AiChatMessage>,
        maxEntries: Int = 12
    ): AiWorldBookContext {
        val hits = AppConfig.aiWorldBookList
            .asSequence()
            .filter { it.enabled && it.entries.isNotEmpty() }
            .flatMap { worldBook ->
                val activeBinding = worldBook.activeBinding(context) ?: return@flatMap emptySequence()
                worldBook.entries.asSequence()
                    .filter { it.enabled }
                    .mapNotNull { entry ->
                        val scanText = messages.scanText(entry.scanDepth)
                        val matchedKeys = entry.matchedKeys(scanText)
                        val excluded = entry.isExcluded(scanText)
                        val active = if (entry.constant) {
                            !excluded
                        } else {
                            matchedKeys.isNotEmpty() &&
                                    !excluded &&
                                    entry.matchesSecondary(scanText)
                        }
                        if (!active) {
                            null
                        } else {
                            AiWorldBookHit(
                                worldBook = worldBook,
                                entry = entry,
                                matchedKeys = matchedKeys,
                                binding = activeBinding,
                                score = entry.priority * 10 +
                                        matchedKeys.sumOf { it.length.coerceAtMost(30) } +
                                        if (entry.constant) 120 else 0
                            )
                        }
                    }
                    .sortedWith(compareByDescending<AiWorldBookHit> { it.score }.thenBy { it.entry.order })
                    .take(worldBook.maxEntries)
            }
            .sortedWith(compareByDescending<AiWorldBookHit> { it.score }.thenBy { it.entry.order })
            .take(maxEntries)
            .toList()
        return AiWorldBookContext(hits)
    }

    fun listWorldBooks(arguments: JSONObject?): String {
        val includeEntries = arguments?.optBoolean("includeEntries", false) == true
        val items = JSONArray()
        AppConfig.aiWorldBookList.forEach { worldBook ->
            items.put(worldBook.toJson(includeEntries))
        }
        return JSONObject()
            .put("ok", true)
            .put("count", items.length())
            .put("items", items)
            .toString()
    }

    fun upsertWorldBook(arguments: JSONObject?): String {
        if (arguments == null) return jsonError("missing arguments")
        val id = arguments.optString("id").trim()
        val old = AppConfig.aiWorldBookList.firstOrNull { it.id == id }
        val name = arguments.optString("name", old?.name.orEmpty()).trim()
        if (name.isBlank()) return jsonError("name is required")
        val updated = (old ?: AiWorldBookConfig(name = name)).copy(
            name = name,
            description = arguments.optString("description", old?.description.orEmpty()).trim(),
            scope = arguments.optString("scope", old?.scope ?: AiWorldBookConfig.SCOPE_GLOBAL)
                .takeIf {
                    it == AiWorldBookConfig.SCOPE_GLOBAL ||
                            it == AiWorldBookConfig.SCOPE_BOOK ||
                            it == AiWorldBookConfig.SCOPE_SESSION
                }
                ?: AiWorldBookConfig.SCOPE_GLOBAL,
            bookKey = arguments.optString("bookKey", old?.bookKey.orEmpty()).trim(),
            enabled = arguments.optBoolean("enabled", old?.enabled ?: true),
            order = arguments.optInt("order", old?.order ?: AppConfig.aiWorldBookList.size),
            entries = old?.entries ?: emptyList()
        )
        AppConfig.aiWorldBookList = AppConfig.aiWorldBookList
            .filterNot { it.id == updated.id }
            .plus(updated)
        return JSONObject()
            .put("ok", true)
            .put("item", updated.toJson(includeEntries = true))
            .toString()
    }

    fun deleteWorldBook(arguments: JSONObject?): String {
        val id = arguments?.optString("id")?.trim().orEmpty()
        if (id.isBlank()) return jsonError("id is required")
        val before = AppConfig.aiWorldBookList
        AppConfig.aiWorldBookList = before.filterNot { it.id == id }
        return JSONObject()
            .put("ok", before.any { it.id == id })
            .put("id", id)
            .toString()
    }

    fun upsertWorldBookEntry(arguments: JSONObject?): String {
        if (arguments == null) return jsonError("missing arguments")
        val worldBookId = arguments.optString("worldBookId").trim()
        if (worldBookId.isBlank()) return jsonError("worldBookId is required")
        val worldBooks = AppConfig.aiWorldBookList.toMutableList()
        val worldBookIndex = worldBooks.indexOfFirst { it.id == worldBookId }
        if (worldBookIndex < 0) return jsonError("world book not found")
        val worldBook = worldBooks[worldBookIndex]
        val entryJson = arguments.optJSONObject("entry") ?: arguments
        val entryId = entryJson.optString("id").trim()
        val old = worldBook.entries.firstOrNull { it.id == entryId }
        val title = entryJson.optString("title", old?.title.orEmpty()).trim()
        val content = entryJson.optString("content", old?.content.orEmpty()).trim()
        if (title.isBlank()) return jsonError("entry title is required")
        if (content.isBlank()) return jsonError("entry content is required")
        val updated = (old ?: AiWorldBookEntry(title = title, content = content)).copy(
            title = title,
            content = content.take(8_000),
            keys = entryJson.optStringArray("keys", old?.keys ?: emptyList()),
            secondaryKeys = entryJson.optStringArray("secondaryKeys", old?.secondaryKeys ?: emptyList()),
            excludeKeys = entryJson.optStringArray("excludeKeys", old?.excludeKeys ?: emptyList()),
            enabled = entryJson.optBoolean("enabled", old?.enabled ?: true),
            constant = entryJson.optBoolean("constant", old?.constant ?: false),
            priority = entryJson.optInt("priority", old?.priority ?: 50).coerceIn(0, 100),
            order = entryJson.optInt("order", old?.order ?: worldBook.entries.size)
        )
        worldBooks[worldBookIndex] = worldBook.copy(
            entries = worldBook.entries
                .filterNot { it.id == updated.id }
                .plus(updated)
        )
        AppConfig.aiWorldBookList = worldBooks
        return JSONObject()
            .put("ok", true)
            .put("worldBookId", worldBook.id)
            .put("entry", updated.toJson())
            .toString()
    }

    fun deleteWorldBookEntry(arguments: JSONObject?): String {
        val worldBookId = arguments?.optString("worldBookId")?.trim().orEmpty()
        val entryId = arguments?.optString("entryId")?.trim().orEmpty()
        if (worldBookId.isBlank() || entryId.isBlank()) return jsonError("worldBookId and entryId are required")
        var deleted = false
        AppConfig.aiWorldBookList = AppConfig.aiWorldBookList.map { worldBook ->
            if (worldBook.id != worldBookId) return@map worldBook
            val entries = worldBook.entries.filterNot { entry ->
                val hit = entry.id == entryId
                if (hit) deleted = true
                hit
            }
            worldBook.copy(entries = entries)
        }
        return JSONObject()
            .put("ok", deleted)
            .put("worldBookId", worldBookId)
            .put("entryId", entryId)
            .toString()
    }

    private fun AiWorldBookConfig.activeBinding(context: AiMemoryContext?): AiWorldBookBinding? {
        return bindings
            .filter { it.enabled }
            .firstOrNull { it.matches(context) }
    }

    private fun AiWorldBookBinding.matches(context: AiMemoryContext?): Boolean {
        return when (targetType) {
            AiWorldBookBinding.TARGET_GLOBAL -> true
            AiWorldBookBinding.TARGET_CHAT -> context?.scope == AiMemoryItem.SCOPE_GLOBAL
            AiWorldBookBinding.TARGET_READ_AI -> context?.scope == AiMemoryItem.SCOPE_BOOK
            AiWorldBookBinding.TARGET_BOOK -> context?.bookKey?.isNotBlank() == true &&
                    targetKey.isNotBlank() &&
                    context.bookKey == targetKey
            AiWorldBookBinding.TARGET_SESSION -> context?.sessionId?.isNotBlank() == true &&
                    targetKey.isNotBlank() &&
                    context.sessionId == targetKey
            else -> false
        }
    }

    private fun List<AiChatMessage>.scanText(scanDepth: Int): String {
        return takeLast(scanDepth.coerceAtLeast(1))
            .joinToString("\n") { it.content }
            .take(12_000)
    }

    private fun AiWorldBookEntry.matchedKeys(text: String): List<String> {
        if (keys.isEmpty()) return emptyList()
        return keys.filter { key -> text.matchesWorldBookKey(key, regexEnabled) }
            .take(maxMatches)
    }

    private fun AiWorldBookEntry.matchesSecondary(text: String): Boolean {
        if (secondaryKeys.isEmpty()) return true
        return secondaryKeys.any { text.matchesWorldBookKey(it, regexEnabled) }
    }

    private fun AiWorldBookEntry.isExcluded(text: String): Boolean {
        return excludeKeys.any { text.matchesWorldBookKey(it, regexEnabled) }
    }

    private fun String.matchesWorldBookKey(key: String, regexEnabled: Boolean): Boolean {
        if (isBlank() || key.isBlank()) return false
        return if (regexEnabled) {
            runCatching {
                Regex(key, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(this)
            }.getOrDefault(false)
        } else {
            contains(key, ignoreCase = true)
        }
    }

    private fun AiWorldBookConfig.toJson(includeEntries: Boolean): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("description", description)
            .put("scope", scope)
            .put("bookKey", bookKey)
            .put("enabled", enabled)
            .put("order", order)
            .put("entryCount", entries.size)
            .apply {
                if (includeEntries) {
                    put("entries", JSONArray().also { array ->
                        entries.forEach { array.put(it.toJson()) }
                    })
                }
            }
    }

    private fun AiWorldBookEntry.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("content", content)
            .put("keys", JSONArray(keys))
            .put("secondaryKeys", JSONArray(secondaryKeys))
            .put("excludeKeys", JSONArray(excludeKeys))
            .put("enabled", enabled)
            .put("constant", constant)
            .put("priority", priority)
            .put("order", order)
    }

    private fun JSONObject.optStringArray(name: String, fallback: List<String>): List<String> {
        val array = optJSONArray(name) ?: return fallback
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct().take(40)
    }

    private fun jsonError(message: String): String {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
            .toString()
    }
}
