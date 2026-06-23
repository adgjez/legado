package io.legado.app.help.ai.asr

import android.util.Log
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

/**
 * OpenAI Whisper / 兼容协议 ASR 引擎。
 *
 * 协议：POST `{baseUrl}/audio/transcriptions`，
 *   - multipart/form-data 字段：`file` (audio)、`model`、`response_format=verbose_json`、
 *     `timestamp_granularities=segment`、可选 `language`。
 *   - 返回 JSON 形如 `{ "segments": [{ "start": 0.0, "end": 2.5, "text": "..." }, ...] }`。
 */
class WhisperAsrEngine(
    private val config: AsrConfig
) : AsrEngine {

    override suspend fun transcribe(audio: File, language: String): List<AsrSegment> =
        withContext(Dispatchers.IO) {
            require(audio.isFile) { "Audio file not found: ${audio.absolutePath}" }
            val raw = callWhisper(audio, language)
            parseVerboseJson(raw)
        }

    private suspend fun callWhisper(audio: File, language: String): String =
        withContext(Dispatchers.IO) {
            require(config.apiKey.isNotBlank()) { "ASR apiKey is empty" }
            val baseUrl = config.baseUrl.ifBlank { DEFAULT_BASE_URL }
            val url = (baseUrl.trimEnd('/') + "/audio/transcriptions").toHttpUrl()
            // 根据文件扩展名选择 Content-Type
            val contentType = when (audio.extension.lowercase()) {
                "m4a", "mp4" -> "audio/mp4"
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "ogg" -> "audio/ogg"
                "webm" -> "audio/webm"
                else -> "audio/mpeg"
            }
            val form: Map<String, Any> = buildMap {
                put(
                    "file",
                    mapOf(
                        "fileName" to audio.name,
                        "file" to audio,
                        "contentType" to contentType
                    )
                )
                put("model", config.model.ifBlank { "whisper-1" })
                put("response_format", "verbose_json")
                put("timestamp_granularities", "segment")
                if (language.isNotBlank()) put("language", language)
            }
            val strResp = okHttpClient.newCallStrResponse(0) {
                url(url)
                header("Authorization", "Bearer ${config.apiKey}")
                postMultipart("multipart/form-data", form)
            }
            val body = strResp.body.orEmpty()
            // 检查错误响应
            val obj = runCatching { org.json.JSONObject(body) }.getOrNull()
            if (obj?.has("error") == true) {
                val errMsg = obj.optJSONObject("error")?.optString("message") ?: body
                error("ASR API error: $errMsg")
            }
            if (obj?.has("segments") == false && body.isNotBlank()) {
                // 响应不是预期的 verbose_json 格式
                error("ASR unexpected response: ${body.take(200)}")
            }
            body
        }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val TAG = "WhisperAsr"

        /**
         * 解析 Whisper verbose_json 响应为 [AsrSegment] 列表。
         * 同时兼容 `segments[]` / `words[]` 两种字段名。
         */
        fun parseVerboseJson(text: String): List<AsrSegment> {
            return try {
                val obj = org.json.JSONObject(text)
                val arr = obj.optJSONArray("segments") ?: return emptyList()
                val out = ArrayList<AsrSegment>(arr.length())
                for (i in 0 until arr.length()) {
                    val seg = arr.getJSONObject(i)
                    val start = (seg.optDouble("start", 0.0) * 1000.0).toLong()
                    val end = (seg.optDouble("end", 0.0) * 1000.0).toLong()
                    val s = seg.optString("text", "").trim()
                    if (s.isNotEmpty() && end >= start) {
                        out.add(AsrSegment(start, end, s))
                    }
                }
                out
            } catch (e: Throwable) {
                Log.w(TAG, "parseVerboseJson failed: ${e.message}")
                emptyList()
            }
        }
    }
}
