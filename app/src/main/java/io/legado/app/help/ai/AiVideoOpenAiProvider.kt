package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.json.JSONObject

/**
 * OpenAI 兼容的 sora-1.0 视频生成 Provider。
 *
 * submit: POST {baseUrl}/videos （body 包含 model、prompt、size 等）
 * poll:   GET  {baseUrl}/videos/{task_id}
 *
 * 响应字段（容忍不同兼容实现的字段名）：
 *  - id / task_id
 *  - status: queued / in_progress / completed / failed / cancelled
 *  - progress: 0-100
 *  - video.url
 *  - video.cover_url
 *  - failure / error.message
 */
class AiVideoOpenAiProvider(
    override val config: AiVideoProviderConfig
) : AiVideoProvider {

    private val baseUrl: String = config.baseUrl.trim().trimEnd('/').let { url ->
        when {
            url.isBlank() -> ""
            url.endsWith("/v1") -> url
            url.endsWith("/videos") -> url.removeSuffix("/videos")
            url.contains("/v1/") -> url.substringBefore("/v1/") + "/v1"
            else -> "$url/v1"
        }
    }

    private fun authHeader(): Pair<String, String> =
        "Authorization" to "Bearer ${config.apiKey.trim()}"

    override suspend fun submit(prompt: String, params: VideoGenerationParams): String {
        val url = "$baseUrl/videos"
        val payload = JSONObject().apply {
            put("model", config.model.ifBlank { "sora-1.0" })
            put("prompt", prompt)
            if (params.negativePrompt.isNotBlank()) {
                put("negative_prompt", params.negativePrompt)
            }
            if (params.firstFrame.isNullOrBlank().not()) {
                put("first_frame", params.firstFrame)
            }
            if (params.lastFrame.isNullOrBlank().not()) {
                put("last_frame", params.lastFrame)
            }
            if (params.width > 0 && params.height > 0) {
                put("size", "${params.width}x${params.height}")
            } else if (params.aspectRatio.isNotBlank()) {
                put("aspect_ratio", params.aspectRatio)
            }
            if (params.durationSec > 0) {
                put("duration", params.durationSec)
            }
            if (params.seed >= 0) {
                put("seed", params.seed)
            }
            // 透传扩展参数
            params.extra.keys().forEach { key ->
                if (!has(key)) put(key, params.extra.get(key))
            }
        }
        val headers = mutableMapOf<String, String>()
        if (config.apiKey.isNotBlank()) headers[authHeader().first] = authHeader().second
        // 解析并追加自定义 headers
        headers.putAll(AiChatService.parseCustomHeaders(config.headers))
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/json"
        val response = AiVideoApi.postJson(url, payload, headers, config.validTimeout())
        return parseTaskId(response)
            ?: error("Submit returned no task id: $response")
    }

    override suspend fun poll(externalTaskId: String): VideoPollResult {
        val url = "$baseUrl/videos/$externalTaskId"
        val headers = mutableMapOf<String, String>()
        if (config.apiKey.isNotBlank()) headers[authHeader().first] = authHeader().second
        headers.putAll(AiChatService.parseCustomHeaders(config.headers))
        headers["Accept"] = "application/json"
        val response = AiVideoApi.getJson(url, headers, config.validTimeout())
        return parsePoll(response)
    }

    override suspend fun cancel(externalTaskId: String): Boolean {
        val url = "$baseUrl/videos/$externalTaskId/cancel"
        val headers = mutableMapOf<String, String>()
        if (config.apiKey.isNotBlank()) headers[authHeader().first] = authHeader().second
        headers.putAll(AiChatService.parseCustomHeaders(config.headers))
        headers["Accept"] = "application/json"
        return runCatching {
            AiVideoApi.postRaw(url, "{}", headers = headers, timeoutMs = config.validTimeout())
            true
        }.getOrDefault(false)
    }

    private fun parseTaskId(body: String): String? {
        val json = body.toJsonObjectOrNull() ?: return null
        return json.optString("id").takeIf { it.isNotBlank() }
            ?: json.optString("task_id").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("data")?.optString("id")?.takeIf { it.isNotBlank() }
    }

    private fun parsePoll(body: String): VideoPollResult {
        val json = body.toJsonObjectOrNull() ?: error("Empty poll response")
        val status = VideoStatus.from(json.optString("status"))
        val progress = when (val v = json.opt("progress")) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> when (status) {
                VideoStatus.SUCCESS -> 100
                VideoStatus.RUNNING -> 50
                VideoStatus.PENDING -> 0
                else -> 0
            }
        }
        val video = json.optJSONObject("video")
        val videoUrl = video?.optString("url")?.takeIf { it.isNotBlank() }
            ?: json.optString("video_url").takeIf { it.isNotBlank() }
            ?: json.optJSONArray("data")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
        val coverUrl = video?.optString("cover_url")?.takeIf { it.isNotBlank() }
            ?: video?.optString("thumbnail_url")?.takeIf { it.isNotBlank() }
            ?: json.optString("cover_url").takeIf { it.isNotBlank() }
        val durationMs = video?.optLong("duration_ms", 0L) ?: 0L
        val width = video?.optInt("width", 0) ?: 0
        val height = video?.optInt("height", 0) ?: 0
        val sizeBytes = video?.optLong("size_bytes", 0L) ?: 0L
        val failReason = json.optString("failure").takeIf { it.isNotBlank() }
            ?: json.optString("error").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        return VideoPollResult(
            status = status,
            progress = progress.coerceIn(0, 100),
            videoUrl = videoUrl,
            coverUrl = coverUrl,
            durationMs = durationMs,
            width = width,
            height = height,
            sizeBytes = sizeBytes,
            failReason = failReason,
            raw = json
        )
    }
}
