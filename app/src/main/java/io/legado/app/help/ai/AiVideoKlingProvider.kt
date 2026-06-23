package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.json.JSONObject

/**
 * 可灵 (Kling) 风格 Provider。
 *
 * submit: POST {baseUrl}/v1/videos/text2video
 * poll:   GET  {baseUrl}/v1/videos/{task_id}
 *
 * 响应字段：
 *  - data.task_id
 *  - data.task_status: submitted / processing / succeeded / failed
 *  - data.task_result.videos[0].url
 *  - data.task_result.videos[0].cover_url
 *  - data.task_result.videos[0].duration
 *  - data.task_status_msg
 */
class AiVideoKlingProvider(
    override val config: AiVideoProviderConfig
) : AiVideoProvider {

    private val baseUrl: String = config.baseUrl.trim().trimEnd('/').let { url ->
        when {
            url.isBlank() -> ""
            url.endsWith("/v1") -> url
            url.endsWith("/v1/videos") -> url.removeSuffix("/videos")
            else -> "$url/v1"
        }
    }

    override suspend fun submit(prompt: String, params: VideoGenerationParams): String {
        val url = "$baseUrl/videos/text2video"
        val providerDefaults = runCatching {
            JSONObject(config.defaultParamsJson.ifBlank { "{}" })
        }.getOrDefault(JSONObject())
        val effectivePrompt = config.stylePrompt.trim().let { style ->
            when {
                style.isBlank() -> prompt
                prompt.isBlank() -> style
                else -> "$style\n\n$prompt"
            }
        }
        val payload = JSONObject().apply {
            put("model_name", config.model.ifBlank { "kling-v1" })
            put("prompt", effectivePrompt)
            // 先填入供应商默认参数（低优先级）
            providerDefaults.keys().forEach { key ->
                put(key, providerDefaults.get(key))
            }
            if (params.negativePrompt.isNotBlank()) {
                put("negative_prompt", params.negativePrompt)
            }
            if (params.durationSec > 0) {
                put("duration", params.durationSec.toString())
            } else if (!has("duration")) {
                put("duration", "5")
            }
            if (params.aspectRatio.isNotBlank()) {
                put("aspect_ratio", params.aspectRatio)
            } else if (!has("aspect_ratio")) {
                put("aspect_ratio", "16:9")
            }
            if (!params.firstFrame.isNullOrBlank()) {
                put("image", params.firstFrame)
            }
            if (!has("mode")) put("mode", "std")
            // 最后填入调用方扩展参数（最高优先级）
            params.extra.keys().forEach { key ->
                put(key, params.extra.get(key))
            }
        }
        val headers = mutableMapOf<String, String>()
        if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey.trim()}"
        headers.putAll(AiChatService.parseCustomHeaders(config.headers))
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/json"
        val response = AiVideoApi.postJson(url, payload, headers, config.validTimeout())
        val json = response.toJsonObjectOrNull() ?: error("Submit returned non-JSON: $response")
        val taskId = json.optJSONObject("data")?.optString("task_id")?.takeIf { it.isNotBlank() }
            ?: error("No task_id in submit response: $response")
        return taskId
    }

    override suspend fun poll(externalTaskId: String): VideoPollResult {
        val url = "$baseUrl/videos/$externalTaskId"
        val headers = mutableMapOf<String, String>()
        if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey.trim()}"
        headers.putAll(AiChatService.parseCustomHeaders(config.headers))
        headers["Accept"] = "application/json"
        val response = AiVideoApi.getJson(url, headers, config.validTimeout())
        val json = response.toJsonObjectOrNull() ?: error("Empty poll response")
        val data = json.optJSONObject("data")
        val statusRaw = data?.optString("task_status") ?: json.optString("status")
        val status = when (statusRaw.lowercase()) {
            "submitted" -> VideoStatus.PENDING
            "processing" -> VideoStatus.RUNNING
            "succeeded" -> VideoStatus.SUCCESS
            "failed" -> VideoStatus.FAILED
            else -> VideoStatus.from(statusRaw)
        }
        val result = data?.optJSONObject("task_result")
        val firstVideo = result?.optJSONArray("videos")?.optJSONObject(0)
        val videoUrl = firstVideo?.optString("url")?.takeIf { it.isNotBlank() }
        val coverUrl = firstVideo?.optString("cover_url")?.takeIf { it.isNotBlank() }
        val duration = firstVideo?.optString("duration")?.toLongOrNull()?.let { it * 1000L } ?: 0L
        val failReason = data?.optString("task_status_msg")?.takeIf { it.isNotBlank() }
            ?: json.optString("error").takeIf { it.isNotBlank() }
        val progress = when (status) {
            VideoStatus.SUCCESS -> 100
            VideoStatus.RUNNING -> 50
            VideoStatus.PENDING -> 0
            else -> 0
        }
        return VideoPollResult(
            status = status,
            progress = progress,
            videoUrl = videoUrl,
            coverUrl = coverUrl,
            durationMs = duration,
            failReason = failReason,
            raw = json
        )
    }
}
