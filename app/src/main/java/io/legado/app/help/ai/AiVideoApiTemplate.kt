package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.json.JSONObject

/**
 * 视频 API 模板 — 定义特定供应商的 API 请求/响应格式差异。
 *
 * 不同视频 API 在 submit payload、status response、download url 等环节
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

    /**
     * 构建 submit 请求体。
     * @param prompt        用户提示词
     * @param model         模型名
     * @param inputImage    首帧图片 base64 data URL，可能为 null
     * @param tailImage     尾帧图片 base64 data URL，可能为 null
     * @param referenceImage 参考图 base64 data URL，可能为 null
     * @param extraParams   额外 JSON 参数
     * @param provider      当前供应商配置
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

    /**
     * 解析 submit 响应，返回任务 ID。
     * 默认从 id / task_id / taskId 字段提取。
     */
    fun parseSubmitResult(json: JSONObject): String {
        return json.optString("id")
            .ifBlank { json.optString("task_id") }
            .ifBlank { json.optString("taskId") }
    }

    /**
     * 解析 status 响应，返回任务状态。
     * 默认从 status 字段读取。
     */
    fun parseStatus(json: JSONObject): String {
        return json.optString("status", "processing")
    }

    /**
     * 解析 status 响应，返回进度百分比 (0-100)，-1 表示未知。
     */
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

    /**
     * 解析 status 响应，提取视频下载 URL。
     */
    fun parseDownloadUrl(json: JSONObject): String?

    /**
     * 解析 status 响应，提取预览图 URL。
     */
    fun parsePreviewUrl(json: JSONObject): String?

    companion object {
        /** 所有已注册模板 */
        val ALL: List<AiVideoApiTemplate> = listOf(
            DefaultVideoTemplate,
            AgnesVideoTemplate
        )

        /** 根据 key 查找模板 */
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
        prompt: String,
        model: String,
        inputImage: String?,
        tailImage: String?,
        referenceImage: String?,
        extraParams: JSONObject,
        provider: AiVideoProviderConfig
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

object AgnesVideoTemplate : AiVideoApiTemplate {
    override val key = "agnes_video_2.0"
    override val displayName = "Agnes AI Video 2.0"
    override val description = "Agnes AI 视频模型，支持 1080P 音画同出"

    override fun buildSubmitPayload(
        prompt: String,
        model: String,
        inputImage: String?,
        tailImage: String?,
        referenceImage: String?,
        extraParams: JSONObject,
        provider: AiVideoProviderConfig
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("prompt", prompt)
        val negative = provider.negativePrompt.ifBlank { extraParams.optString("negative_prompt") }
        negative.takeIf { it.isNotBlank() }?.let { put("negative_prompt", it) }
        // Agnes AI 使用 image_url 字段而非 input_image
        if (inputImage != null) {
            put("image_url", inputImage)
        }
        tailImage?.let { put("tail_image", it) }
        referenceImage?.let { put("reference_image", it) }
        // 合并额外参数
        mergeJson(
            extraParams,
            setOf("model", "prompt", "negative_prompt", "image_url", "input_image", "tail_image", "reference_image")
        )
    }

    override fun parseDownloadUrl(json: JSONObject): String? {
        // Agnes AI 响应格式: { output: { video_url: "..." } } 或标准格式
        json.optJSONObject("output")?.let { output ->
            output.optString("video_url").takeIf { it.isNotBlank() }?.let { return it }
        }
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
    "mp4", "webm", "mov", "gif"
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