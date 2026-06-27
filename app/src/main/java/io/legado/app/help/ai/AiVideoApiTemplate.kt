package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视频 API 模板 — 定义特定供应商的 API 请求/响应格式差异。
 *
 * 不同视频 API 在 submit URL、payload 格式、status URL、响应解析等环节
 * 存在格式差异。模板封装这些差异，使 [AiVideoService] 无需为每个供应商
 * 编写特化代码。
 */
interface AiVideoApiTemplate {

    /** 模板唯一标识，对应 [AiVideoProviderConfig.template] */
    val key: String

    /** 模板显示名称 */
    val displayName: String

    /** 简短描述 */
    val description: String

    /** 构建 submit 请求 URL */
    fun buildSubmitUrl(baseUrl: String, provider: AiVideoProviderConfig): String {
        val clean = baseUrl.trimEnd('/')
        val endpoint = provider.submitEndpoint.trim()
        return if (endpoint.isBlank()) "$clean/videos/generations"
        else "$clean/${endpoint.trimStart('/')}"
    }

    /** 构建 status 查询 URL */
    fun buildStatusUrl(baseUrl: String, remoteTaskId: String, provider: AiVideoProviderConfig): String {
        val clean = baseUrl.trimEnd('/')
        val raw = provider.statusEndpoint.trim()
        val path = if (raw.isBlank()) "videos/generations/${remoteTaskId.trim()}"
        else raw.replace("{id}", remoteTaskId.trim()).replace("{taskId}", remoteTaskId.trim())
        return "$clean/${path.trimStart('/')}"
    }

    /** 构建 cancel URL */
    fun buildCancelUrl(baseUrl: String, remoteTaskId: String, provider: AiVideoProviderConfig): String {
        return "${buildStatusUrl(baseUrl, remoteTaskId, provider)}/cancel"
    }

    /**
     * 构建 submit 请求体。
     */
    fun buildSubmitPayload(
        prompt: String,
        model: String,
        inputImage: String?,
        tailImage: String?,
        referenceImage: String?,
        extraParams: JSONObject,
        provider: AiVideoProviderConfig
    ): JSONObject

    /** 解析 submit 响应，返回任务 ID */
    fun parseSubmitResult(json: JSONObject): String {
        return json.optString("id")
            .ifBlank { json.optString("task_id") }
            .ifBlank { json.optString("taskId") }
    }

    /** 解析 status 响应，返回任务状态 */
    fun parseStatus(json: JSONObject): String {
        return json.optString("status", "processing")
    }

    /** 解析 status 响应，返回进度百分比 (0-100)，-1 表示未知 */
    fun parseProgress(json: JSONObject): Int {
        val direct = json.opt("progress")
        val parsed = when (direct) {
            is Number -> direct.toDouble()
            is String -> direct.toDoubleOrNull()
            else -> null
        }
        if (parsed != null) {
            val value = if (parsed > 1.0) parsed.toInt() else (parsed * 100).toInt()
            if (value in 0..100) return value
        }
        return -1
    }

    /** 解析 status 响应，提取视频下载 URL */
    fun parseDownloadUrl(json: JSONObject): String?

    /** 解析 status 响应，提取预览图 URL */
    fun parsePreviewUrl(json: JSONObject): String?

    companion object {
        val ALL: List<AiVideoApiTemplate> = listOf(
            DefaultVideoTemplate,
            AgnesVideoTemplate
        )

        fun find(key: String): AiVideoApiTemplate? =
            ALL.firstOrNull { it.key == key }
    }
}

// ── 默认 OpenAI 兼容模板 ──

object DefaultVideoTemplate : AiVideoApiTemplate {
    override val key = "default"
    override val displayName = "通用 OpenAI"
    override val description = "标准 OpenAI 兼容视频 API"

    override fun buildSubmitPayload(
        prompt: String, model: String,
        inputImage: String?, tailImage: String?, referenceImage: String?,
        extraParams: JSONObject, provider: AiVideoProviderConfig
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("prompt", prompt)
        val negative = provider.negativePrompt.ifBlank { extraParams.optString("negative_prompt") }
        negative.takeIf { it.isNotBlank() }?.let { put("negative_prompt", it) }
        inputImage?.let { put("input_image", it) }
        tailImage?.let { put("tail_image", it) }
        referenceImage?.let { put("reference_image", it) }
        mergeJson(extraParams, setOf("model", "prompt", "negative_prompt", "input_image", "tail_image", "reference_image"))
    }

    override fun parseDownloadUrl(json: JSONObject): String? = videoFromOpenAiJson(json)
    override fun parsePreviewUrl(json: JSONObject): String? = findPreviewUrl(json)
}

// ── Agnes AI Video 模板 ──

/**
 * Agnes AI Video API 完整适配。
 *
 * Submit:  POST /v1/videos
 * Status:  GET /agnesapi?video_id=<VIDEO_ID>&model_name=<MODEL>
 * Legacy:  GET /v1/videos/<TASK_ID>
 *
 * Payload: model, prompt, height, width, num_frames, frame_rate,
 *          image (URL), extra_body.image[], extra_body.mode
 *
 * num_frames: 8n+1, ≤441. seconds = num_frames / frame_rate.
 *
 * Response: { video_id, status, remixed_from_video_id (download URL) }
 */
object AgnesVideoTemplate : AiVideoApiTemplate {
    override val key = "agnes_video_2.0"
    override val displayName = "Agnes AI Video 2.0"
    override val description = "Agnes AI 视频模型，支持 1080P 音画同出"

    // ── URL 构建 ──

    override fun buildSubmitUrl(baseUrl: String, provider: AiVideoProviderConfig): String {
        val clean = baseUrl.trimEnd('/')
        val endpoint = provider.submitEndpoint.trim()
        // 默认端点或空白 → 使用 Agnes 专用路径 /videos
        return if (endpoint.isBlank() || endpoint == "/videos/generations") "$clean/videos"
        else "$clean/${endpoint.trimStart('/')}"
    }

    override fun buildStatusUrl(baseUrl: String, remoteTaskId: String, provider: AiVideoProviderConfig): String {
        val clean = baseUrl.trimEnd('/')
        val raw = provider.statusEndpoint.trim()
        return when {
            // 用户自定义了非默认端点，照用
            raw.isNotBlank() && raw != "/videos/generations/{id}" -> {
                val path = raw.replace("{id}", remoteTaskId).replace("{taskId}", remoteTaskId)
                "$clean/${path.trimStart('/')}"
            }
            // 使用 Agnes 专用接口
            else -> {
                val model = provider.model.trim().ifBlank { "agnes-video-v2.0" }
                "$clean/agnesapi?video_id=${remoteTaskId.trim()}&model_name=$model"
            }
        }
    }

    override fun buildCancelUrl(baseUrl: String, remoteTaskId: String, provider: AiVideoProviderConfig): String {
        return buildStatusUrl(baseUrl, remoteTaskId, provider) // no cancel endpoint, just poll
    }

    // ── Submit Payload ──

    override fun buildSubmitPayload(
        prompt: String, model: String,
        inputImage: String?, tailImage: String?, referenceImage: String?,
        extraParams: JSONObject, provider: AiVideoProviderConfig
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("prompt", prompt)

        // 解析尺寸参数
        val size = parseSize(extraParams)
        put("width", size.first)
        put("height", size.second)

        // 帧数: 遵循 8n+1 规则，默认 121 (5秒 @24fps)
        val numFrames = extraParams.optInt("num_frames", 121)
            .coerceAtMost(441)
        put("num_frames", numFrames)

        // 帧率: 默认 24
        val frameRate = extraParams.optInt("frame_rate", 24)
        put("frame_rate", frameRate)

        // 图生视频: image 字段传图片 URL
        inputImage?.takeIf { it.isNotBlank() }?.let { img ->
            put("image", if (img.startsWith("http")) img else img)
        }

        // 尾帧/参考图: 通过 extra_body 传递
        val extraBody = extraParams.optJSONObject("extra_body")?.let { JSONObject(it.toString()) }
            ?: JSONObject()
        tailImage?.takeIf { it.isNotBlank() }?.let {
            extraBody.put("tail_image", it)
        }
        referenceImage?.takeIf { it.isNotBlank() }?.let {
            extraBody.put("reference_image", it)
        }

        // 关键帧模式
        if (tailImage != null && tailImage.isNotBlank()) {
            extraBody.put("mode", "keyframes")
        }

        if (extraBody.length() > 0) {
            put("extra_body", extraBody)
        }

        // 合并其余自定义参数
        mergeJson(extraParams, setOf(
            "model", "prompt", "width", "height", "num_frames", "frame_rate",
            "image", "extra_body", "size", "negative_prompt", "input_image", "tail_image", "reference_image"
        ))
    }

    /** 解析尺寸: 从 extraParams.size 或 width/height 字段 */
    private fun parseSize(params: JSONObject): Pair<Int, Int> {
        // 尝试 "size" 字段 (如 "1280x768")
        params.optString("size").takeIf { it.isNotBlank() }?.let { raw ->
            val parts = raw.split("x", "X", "*", "×").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size >= 2) return parts[0] to parts[1]
        }
        val w = params.optInt("width", 1152)
        val h = params.optInt("height", 768)
        return w to h
    }

    // ── 响应解析 ──

    override fun parseSubmitResult(json: JSONObject): String {
        return json.optString("video_id")
            .ifBlank { json.optString("task_id") }
            .ifBlank { json.optString("id") }
    }

    override fun parseDownloadUrl(json: JSONObject): String? {
        // Agnes AI: 视频 URL 在 remixed_from_video_id 字段
        json.optString("remixed_from_video_id").takeIf {
            it.isNotBlank() && it.startsWith("http")
        }?.let { return it }
        // 也检查 output.video_url
        json.optJSONObject("output")?.let { output ->
            output.optString("video_url").takeIf { it.isNotBlank() && it.startsWith("http") }?.let { return it }
        }
        // 回退到通用解析
        return videoFromOpenAiJson(json)
    }

    override fun parsePreviewUrl(json: JSONObject): String? {
        json.optJSONObject("output")?.let { output ->
            output.optString("preview_url").takeIf { it.isNotBlank() && isUrlOrData(it) }?.let { return it }
        }
        return findPreviewUrl(json)
    }
}

// ── 共享辅助 ──

private val VIDEO_URL_KEYS = listOf(
    "url", "video_url", "download_url", "output_url", "result", "video",
    "mp4", "webm", "mov", "gif", "remixed_from_video_id"
)

private val VIDEO_CONTAINER_KEYS = listOf("output", "videos", "data", "results", "items", "content")

private fun videoFromOpenAiJson(json: JSONObject): String? {
    for (key in VIDEO_URL_KEYS) {
        json.optString(key).takeIf { it.isNotBlank() && looksLikeVideoUrl(it) }?.let { return it }
    }
    for (key in VIDEO_CONTAINER_KEYS) {
        when (val value = json.opt(key)) {
            is String -> if (looksLikeVideoUrl(value)) return value
            is JSONObject -> videoFromOpenAiJson(value)?.let { return it }
            is JSONArray -> videoFromOpenAiArray(value)?.let { return it }
        }
    }
    return null
}

private fun videoFromOpenAiArray(array: JSONArray): String? {
    for (index in 0 until array.length()) {
        when (val item = array.opt(index)) {
            is JSONObject -> videoFromOpenAiJson(item)?.let { return it }
            is String -> if (looksLikeVideoUrl(item)) return item
        }
    }
    return null
}

private fun looksLikeVideoUrl(text: String): Boolean {
    val value = text.trim()
    if (value.isBlank()) return false
    return value.startsWith("http", true) || value.startsWith("data:video", true)
}

private fun findPreviewUrl(json: JSONObject): String? {
    for (key in listOf("preview_url", "preview_image", "preview", "thumbnail_url", "thumbnail", "cover_url", "cover")) {
        json.optString(key).takeIf { it.isNotBlank() && isUrlOrData(it) }?.let { return it }
    }
    for (key in VIDEO_CONTAINER_KEYS) {
        json.optJSONObject(key)?.let { findPreviewUrl(it)?.let { url -> return url } }
    }
    return null
}

private fun isUrlOrData(text: String): Boolean =
    text.startsWith("http", true) || text.startsWith("data:", true)

private fun JSONObject.mergeJson(extra: JSONObject, ignored: Set<String> = emptySet()) {
    extra.keys().forEach { key ->
        if (key !in ignored) put(key, extra.opt(key))
    }
}