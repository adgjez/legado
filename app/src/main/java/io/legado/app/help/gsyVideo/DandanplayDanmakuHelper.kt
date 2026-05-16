package io.legado.app.help.gsyVideo

import android.util.Base64
import io.legado.app.help.http.get
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object DandanplayDanmakuHelper {

    data class Episode(
        val episodeId: Long,
        val animeTitle: String,
        val episodeTitle: String
    ) {
        val label: String
            get() = listOf(animeTitle, episodeTitle)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
                .ifBlank { episodeId.toString() }
    }

    suspend fun searchEpisodes(
        baseUrl: String,
        keyword: String,
        appId: String,
        appSecret: String
    ): List<Episode> {
        val path = "/api/v2/search/episodes"
        val response = okHttpClient.newCallStrResponse {
            get(apiUrl(baseUrl, path), mapOf("anime" to keyword, "keyword" to keyword))
            signDandanplay(appId, appSecret, path)
        }.body.orEmpty()
        return parseEpisodes(response)
    }

    suspend fun fetchXml(
        baseUrl: String,
        episodeId: Long,
        appId: String,
        appSecret: String
    ): String {
        val path = "/api/v2/comment/$episodeId"
        val response = okHttpClient.newCallStrResponse {
            get(apiUrl(baseUrl, path), mapOf("withRelated" to "true", "chConvert" to "1"))
            signDandanplay(appId, appSecret, path)
        }.body.orEmpty()
        return if (response.trimStart().startsWith("<")) response else toBiliXml(response)
    }

    suspend fun fetchCompatibleXml(template: String, bookName: String, chapterTitle: String): String {
        val url = template
            .replace("{book}", bookName)
            .replace("{title}", bookName)
            .replace("{chapter}", chapterTitle)
            .replace("{episode}", chapterTitle)
        return okHttpClient.newCallStrResponse { url(url) }.body.orEmpty()
    }

    private fun apiUrl(baseUrl: String, path: String): String {
        return baseUrl.trimEnd('/') + path
    }

    private fun okhttp3.Request.Builder.signDandanplay(
        appId: String,
        appSecret: String,
        path: String
    ) {
        val timestamp = System.currentTimeMillis() / 1000
        val signature = dandanplaySignature(appId, timestamp, path, appSecret)
        addHeader("X-AppId", appId)
        addHeader("X-Signature", signature)
        addHeader("X-Timestamp", timestamp.toString())
        addHeader("Accept", "application/json, text/xml, */*")
    }

    private fun dandanplaySignature(
        appId: String,
        timestamp: Long,
        path: String,
        appSecret: String
    ): String {
        val data = "$appId$timestamp$path$appSecret"
        val hash = MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun parseEpisodes(raw: String): List<Episode> {
        val result = arrayListOf<Episode>()
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: runCatching { JSONArray(raw) }.getOrNull()
            ?: return emptyList()
        walkJson(root) { obj ->
            val id = obj.optLongAny("episodeId", "EpisodeId", "commentId", "CommentId", "id")
            if (id > 0) {
                val anime = obj.optStringAny("animeTitle", "AnimeTitle", "anime", "title", "Title")
                val episode = obj.optStringAny("episodeTitle", "EpisodeTitle", "episode", "name", "Name")
                result += Episode(id, anime, episode)
            }
        }
        return result.distinctBy { it.episodeId }
    }

    private fun toBiliXml(raw: String): String {
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: runCatching { JSONArray(raw) }.getOrNull()
            ?: return raw
        val items = arrayListOf<Pair<String, String>>()
        walkJson(root) { obj ->
            val text = obj.optStringAny("m", "text", "Text", "comment", "Comment")
            if (text.isBlank()) return@walkJson
            val p = obj.optStringAny("p", "pvalue", "P")
                .ifBlank { buildP(obj) }
            items += p to text
        }
        if (items.isEmpty()) return raw
        return buildString {
            append("<i>")
            items.forEach { (p, text) ->
                append("<d p=\"")
                append(xmlEscape(p))
                append("\">")
                append(xmlEscape(text))
                append("</d>")
            }
            append("</i>")
        }
    }

    private fun buildP(obj: JSONObject): String {
        val time = obj.optDoubleAny("time", "Time", "position", "Position").coerceAtLeast(0.0)
        val mode = obj.optIntAny("mode", "Mode").takeIf { it > 0 } ?: 1
        val color = obj.optIntAny("color", "Color").takeIf { it > 0 } ?: 16777215
        return "$time,$mode,25,$color,0,0,0,0"
    }

    private fun walkJson(value: Any, block: (JSONObject) -> Unit) {
        when (value) {
            is JSONObject -> {
                block(value)
                val keys = value.keys()
                while (keys.hasNext()) {
                    walkJson(value.opt(keys.next()), block)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    walkJson(value.opt(i), block)
                }
            }
        }
    }

    private fun JSONObject.optStringAny(vararg keys: String): String {
        keys.forEach { key ->
            opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        }
        return ""
    }

    private fun JSONObject.optLongAny(vararg keys: String): Long {
        keys.forEach { key ->
            val value = opt(key) ?: return@forEach
            when (value) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun JSONObject.optIntAny(vararg keys: String): Int {
        return optLongAny(*keys).toInt()
    }

    private fun JSONObject.optDoubleAny(vararg keys: String): Double {
        keys.forEach { key ->
            val value = opt(key) ?: return@forEach
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        return 0.0
    }

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
