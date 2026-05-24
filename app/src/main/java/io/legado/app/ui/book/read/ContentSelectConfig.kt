package io.legado.app.ui.book.read

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefString
import java.net.URLEncoder

object ContentSelectConfig {

    const val ACTION_WEB_SEARCH = "web_search"
    const val ACTION_REPLACE = "replace"
    const val ACTION_COPY = "copy"
    const val ACTION_BOOKMARK = "bookmark"
    const val ACTION_ALOUD = "aloud"
    const val ACTION_DICT = "dict"
    const val ACTION_ASK_AI = "ask_ai"

    private val oldDefaultActions = setOf(
        ACTION_REPLACE,
        ACTION_COPY,
        ACTION_BOOKMARK,
        ACTION_ALOUD,
        ACTION_DICT,
        ACTION_ASK_AI
    )

    val defaultActions = setOf(
        ACTION_WEB_SEARCH,
        ACTION_REPLACE,
        ACTION_COPY,
        ACTION_BOOKMARK,
        ACTION_ALOUD,
        ACTION_DICT,
        ACTION_ASK_AI
    )

    val defaultOpenValues = listOf("", ACTION_WEB_SEARCH, ACTION_DICT, ACTION_ASK_AI)
    private val removedActionIds = setOf("generate_image")

    data class SearchEngine(
        val id: String = "",
        val name: String = "",
        val url: String = ""
    )

    val defaultSearchEngines = listOf(
        SearchEngine("bing", "Bing", "https://www.bing.com/search?q={query}"),
        SearchEngine("baidu", "Baidu", "https://www.baidu.com/s?wd={query}"),
        SearchEngine("google", "Google", "https://www.google.com/search?q={query}")
    )

    fun selectedActionIds(context: Context): Set<String> {
        val saved = context.getPrefStringSet(PreferKey.contentSelectActions, null)
            ?.filterNot { it in removedActionIds }
            ?.toSet()
            ?: return defaultActions
        return if (saved == oldDefaultActions) defaultActions else saved
    }

    fun searchEngines(context: Context): List<SearchEngine> {
        val raw = context.getPrefString(PreferKey.contentSelectSearchEngines).orEmpty()
        val parsed = raw.takeIf { it.isNotBlank() }?.let {
            runCatching {
                GSON.fromJson(it, Array<SearchEngine>::class.java).toList()
            }.getOrNull()
        }
        return sanitizeSearchEngines(parsed.orEmpty()).ifEmpty { defaultSearchEngines }
    }

    fun saveSearchEngines(context: Context, engines: List<SearchEngine>) {
        val normalized = sanitizeSearchEngines(engines).ifEmpty { defaultSearchEngines }
        context.putPrefString(PreferKey.contentSelectSearchEngines, GSON.toJson(normalized))
        val currentId = context.getPrefString(PreferKey.contentSelectSearchEngineId).orEmpty()
        if (normalized.none { it.id == currentId }) {
            context.putPrefString(PreferKey.contentSelectSearchEngineId, normalized.first().id)
        }
    }

    fun resetSearchEngines(context: Context) {
        saveSearchEngines(context, defaultSearchEngines)
    }

    fun currentSearchEngine(context: Context): SearchEngine {
        val engines = searchEngines(context)
        val currentId = context.getPrefString(PreferKey.contentSelectSearchEngineId).orEmpty()
        return engines.firstOrNull { it.id == currentId } ?: engines.first()
    }

    fun selectSearchEngine(context: Context, id: String) {
        if (searchEngines(context).any { it.id == id }) {
            context.putPrefString(PreferKey.contentSelectSearchEngineId, id)
        }
    }

    fun buildSearchUrl(engine: SearchEngine, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val template = engine.url.trim()
        if (template.contains("{query}")) {
            return template.replace("{query}", encoded)
        }
        val separator = when {
            template.endsWith("?") || template.endsWith("&") -> ""
            template.contains("?") -> "&"
            else -> "?"
        }
        return "$template${separator}q=$encoded"
    }

    private fun sanitizeSearchEngines(engines: List<SearchEngine>): List<SearchEngine> {
        return engines.mapNotNull { engine ->
            val id = engine.id.trim()
            val name = engine.name.trim()
            val url = engine.url.trim()
            if (id.isBlank() || name.isBlank() || !url.startsWith("http", ignoreCase = true)) {
                null
            } else {
                SearchEngine(id, name, url)
            }
        }.distinctBy { it.id }
    }
}
