package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.json.JSONObject

/**
 * OpenAI 兼容的视频生成 Provider。
 *
 * 同时兼容标准 OpenAI sora 协议和 Agnes AI 协议：
 *
 * submit: POST {baseUrl}/videos
 *   - 标准 OpenAI: model/prompt/size/duration
 *   - Agnes AI:    model/prompt/width/height/num_frames/frame_rate
 *
 * poll（自动探测，先试 Agnes 推荐方式，失败回退标准方式）:
 *   - Agnes 推荐: GET {baseOrigin}/agnesapi?video_id=<id>
 *   - 标准回退:   GET {baseUrl}/videos/{task_id}
 *
 * 响应字段（容忍不同兼容实现的字段名）：
 *  - id / task_id / video_id
 *  - status: queued / in_progress / completed / failed / cancelled / succeeded / processing
 *  - progress: 0-100
 *  - video.url / video_url / data.url / url
 *  - video.cover_url / cover_url / thumbnail_url
 *  - failure / error.message
 */
class AiVideoOpenAiProvider(
    override val config: AiVideoProviderConfig
) : AiVideoProvider {

    /** 带 /v1 后缀的 baseUrl（用于 submit / cancel / 标准轮询） */
    private val baseUrl: String = config.baseUrl.trim().trimEnd('/').let { url ->
        when {
            url.isBlank() -> ""
            url.endsWith("/v1") -> url
            url.endsWith("/videos") -> url.removeSuffix("/videos")
            url.contains("/v1/") -> url.substringBefore("/v1/") + "/v1"
            else -> "$url/v1"
        }
    }

    /** 不带 /v1 的 origin（用于 Agnes 风格轮询 /agnesapi） */
    private val baseOrigin: String = baseUrl.removeSuffix("/v1").let { if (it.isBlank()) "" else it }

    private fun authHeader(): Pair<String, String> =
        "Authorization" to "Bearer ${config.apiKey.trim()}"

    private fun buildHeaders(): MutableMap<String, String> {
        val headers = mutableMapOf<String, String>()
        if (config.apiKey.isNotBlank()) headers[authHeader().first] = authHeader().second
        headers.putAll(AiChatService.parseCustomHeaders(config.headers))
        headers["Accept"] = "application/json"
        return headers
    }

    override suspend fun submit(prompt: String, params: VideoGenerationParams): String {
        val url = "$baseUrl/videos"
        val payload = JSONObject().apply {
            put("model", config.model.ifBlank { "sora-1.0" })
            put("prompt", prompt)
            if (params.negativePrompt.isNotBlank()) {
                put("negative_prompt", params.negativePrompt)
            }
            if (!params.firstFrame.isNullOrBlank()) {
                put("first_frame", params.firstFrame)
            }
            if (!params.lastFrame.isNullOrBlank()) {
                put("last_frame", params.lastFrame)
            }
            // 尺寸：同时发送 size 字符串和独立 width/height，兼容两种风格
            if (params.width > 0 && params.height > 0) {
                put("size", "${params.width}x${params.height}")
                put("width", params.width)
                put("height", params.height)
            } else if (params.aspectRatio.isNotBlank()) {
                put("aspect_ratio", params.aspectRatio)
            }
            // 时长：同时发送 duration（秒）和推算的 num_frames/frame_rate
            if (params.durationSec > 0) {
                put("duration", params.durationSec)
                val fps = 24
                put("num_frames", params.durationSec * fps + 1)
                put("frame_rate", fps)
            }
            if (params.seed >= 0) {
                put("seed", params.seed)
            }
            // 透传扩展参数（用户可通过 extra 覆盖以上任何字段）
            params.extra.keys().forEach { key ->
                if (!has(key)) put(key, params.extra.get(key))
            }
        }
        val headers = buildHeaders().apply { put("Content-Type", "application/json") }
        val response = AiVideoApi.postJson(url, payload, headers, config.validTimeout())
        return parseTaskId(response)
            ?: error("Submit returned no task id: $response")
    }

    override suspend fun poll(externalTaskId: String): VideoPollResult {
        val headers = buildHeaders()

        // 1. 先尝试 Agnes 推荐方式: GET {baseOrigin}/agnesapi?video_id=<id>
        val agnesUrl = "$baseOrigin/agnesapi?video_id=$externalTaskId"
        val agnesResult = runCatching {
            val resp = AiVideoApi.getJson(agnesUrl, headers, config.validTimeout())
            parsePoll(resp)
        }
        if (agnesResult.isSuccess) {
            val result = agnesResult.getOrNull()!!
            // 只有当响应确实包含有效状态时才采用，避免空响应被误判
            if (result.status != VideoStatus.PENDING || result.raw != null) {
                return result
            }
        }

        // 2. 回退到标准 OpenAI 方式: GET {baseUrl}/videos/{task_id}
        val stdUrl = "$baseUrl/videos/$externalTaskId"
        val response = AiVideoApi.getJson(stdUrl, headers, config.validTimeout())
        return parsePoll(response)
    }

    override suspend fun cancel(externalTaskId: String): Boolean {
        val url = "$baseUrl/videos/$externalTaskId/cancel"
        val headers = buildHeaders().apply { put("Content-Type", "application/json") }
        return runCatching {
            AiVideoApi.postRaw(url, "{}", headers = headers, timeoutMs = config.validTimeout())
            true
        }.getOrDefault(false)
    }

    private fun parseTaskId(body: String): String? {
        val json = body.toJsonObjectOrNull() ?: return null
        return json.optString("id").takeIf { it.isNotBlank() }
            ?: json.optString("task_id").takeIf { it.isNotBlank() }
            ?: json.optString("video_id").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("data")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: json.optJSONObject("data")?.optString("video_id")?.takeIf { it.isNotBlank() }
    }

    private fun parsePoll(body: String): VideoPollResult {
        val json = body.toJsonObjectOrNull() ?: error("Empty poll response")

        // 状态解析：兼容 Agnes (queued/processing/succeeded) 和 OpenAI (in_progress/completed)
        val status = VideoStatus.from(json.optString("status"))

        // 进度
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

        // 视频 URL：兼容多层嵌套
        val video = json.optJSONObject("video")
        val videoUrl = video?.optString("url")?.takeIf { it.isNotBlank() }
            ?: json.optString("video_url").takeIf { it.isNotBlank() }
            ?: json.optString("url").takeIf { it.isNotBlank() }
            ?: json.optJSONArray("data")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            ?: json.optJSONObject("data")?.optString("video_url")?.takeIf { it.isNotBlank() }
            ?: json.optJSONObject("data")?.optString("url")?.takeIf { it.isNotBlank() }

        // 封面 URL
        val coverUrl = video?.optString("cover_url")?.takeIf { it.isNotBlank() }
            ?: video?.optString("thumbnail_url")?.takeIf { it.isNotBlank() }
            ?: json.optString("cover_url").takeIf { it.isNotBlank() }
            ?: json.optString("thumbnail_url").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("data")?.optString("cover_url")?.takeIf { it.isNotBlank() }

        // 时长 / 尺寸
        val durationMs = video?.optLong("duration_ms", 0L) ?: 0L
        val width = video?.optInt("width", 0) ?: 0
        val height = video?.optInt("height", 0) ?: 0
        val sizeBytes = video?.optLong("size_bytes", 0L) ?: 0L

        // 失败原因
        val failReason = json.optString("failure").takeIf { it.isNotBlank() }
            ?: json.optString("error").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() && status == VideoStatus.FAILED }

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
