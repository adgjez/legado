package io.legado.app.help.ai.backends.image

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.ImageBackend
import io.legado.app.help.ai.backends.ImageBackendRegistry
import io.legado.app.help.ai.backends.ImageCapability
import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ImageGenerationResult
import io.legado.app.help.ai.backends.ReferenceImage
import io.legado.app.help.ai.backends.VideoBackendHttp
import io.legado.app.help.ai.backends.compress.ImageCodec
import io.legado.app.ui.main.ai.AiImageProviderConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Gemini 图像后端（移植 ArcReel `image_backends/gemini.py`）。
 *
 * **关键决策：REST 兜底**（spec 第 7.4 节）。ArcReel Python 用 Google GenAI SDK 的
 * `models.generate_content` + `Image(image_bytes=...)` Bitmap 对象；Android 无等价 SDK
 * （google-genai Python 包无 Kotlin/Android 对应），故走 REST API。
 *
 * REST 端点（Gemini v1beta）：
 * - `POST /v1beta/models/{model}:generateContent`
 * - 鉴权：`x-goog-api-key` header（AI Studio API key；Vertex 需 service account，P3b 不做）
 *
 * 请求体形态（对应 SDK `generate_content(contents=[label, Image, ..., prompt], config)`）：
 * ```json
 * {
 *   "contents": [{
 *     "parts": [
 *       {"text": "label1"},
 *       {"inline_data": {"mime_type": "image/jpeg", "data": "base64..."}},
 *       {"text": "prompt"}
 *     ]
 *   }],
 *   "generationConfig": {
 *     "responseModalities": ["IMAGE"],
 *     "imageConfig": {"aspectRatio": "9:16"}
 *   }
 * }
 * ```
 *
 * 参考图用 `inline_data.data` base64（SDK `Image(image_bytes=...)` 的 REST 等价——
 * SDK 内部就是 inline_data；与 VeoVideoBackend 同款决策）。
 *
 * - 默认 model：`gemini-3.1-flash-image-preview`
 * - 参考图 label：ReferenceImage.label 优先；否则从文件名推断 stem——
 *   但跳过 `scene_`/`storyboard_`/`output_` 前缀的文件（ArcReel 行为：这些是生成产物，不应作为命名标签）
 * - 响应：`candidates[0].content.parts[i].inline_data.data` base64 → 解码写盘
 * - Gemini image 不返 token 用量，usageTokens 恒 null
 *
 * 单步同步（无 poll）。生命周期 1a 忠实：[generate] 自管 submit+persist。
 */
class GeminiImageBackend(private val cfg: AiImageProviderConfig) : ImageBackend {

    override val typeId: String = AiImageProviderConfig.TYPE_GEMINI
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<ImageCapability> = setOf(
        ImageCapability.TEXT_TO_IMAGE,
        ImageCapability.IMAGE_TO_IMAGE
    )

    override suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult {
        val client = buildClient(cfg.validTimeout())
        val body = buildPayload(request)
        val url = resolveEndpoint()
        val respBody = VideoBackendHttp.submitPost(
            postFn = { doPost(client, url, body) },
            provider = typeId
        ).use { resp -> resp.body?.string() ?: error("gemini image 响应体为空") }
        persistImage(respBody, request.outputPath)
        return ImageGenerationResult(
            imagePath = request.outputPath,
            provider = typeId,
            model = model,
            usageTokens = null  // Gemini image 不返 token 用量
        )
    }

    /**
     * 构造 Gemini generateContent 请求体。
     *
     * - 参考图：每张先加可选 `{text: label}` part（label 非空时），再加 `{inline_data: {mime_type, data}}` part
     * - prompt：最后加 `{text: prompt}` part
     * - generationConfig：`responseModalities=["IMAGE"]` + `imageConfig={aspectRatio, image_size?}`
     *
     * 跳过不存在的参考图文件（ArcReel 行为：`_open_refs` 里 FileNotFoundError 仅 warn 跳过）。
     */
    internal fun buildPayload(request: ImageGenerationRequest): String {
        val parts = JsonArray()
        request.referenceImages.forEach { ref ->
            val label = resolveLabel(ref)
            if (label != null) {
                val textPart = JsonObject()
                textPart.addProperty("text", label)
                parts.add(textPart)
            }
            val file = File(ref.path)
            if (file.exists()) {
                val inlinePart = JsonObject()
                val inlineData = JsonObject()
                inlineData.addProperty("mime_type", ImageCodec.mimeByExtension(file.extension))
                inlineData.addProperty("data", ImageCodec.toBareBase64(file))
                inlinePart.add("inline_data", inlineData)
                parts.add(inlinePart)
            }
        }
        // prompt 放最后
        val promptPart = JsonObject()
        promptPart.addProperty("text", request.prompt)
        parts.add(promptPart)

        val content = JsonObject()
        content.add("parts", parts)
        val contents = JsonArray().apply { add(content) }

        val imageConfig = JsonObject().apply {
            addProperty("aspectRatio", request.aspectRatio)
            request.imageSize?.takeIf { it.isNotBlank() }?.let { addProperty("imageSize", it) }
        }
        val generationConfig = JsonObject().apply {
            add("responseModalities", JsonArray().apply { add("IMAGE") })
            add("imageConfig", imageConfig)
        }

        val root = JsonObject()
        root.add("contents", contents)
        root.add("generationConfig", generationConfig)
        return root.toString()
    }

    /**
     * 解析参考图 label。
     *
     * 优先 ReferenceImage.label（非空 trim）；否则从文件名推断 stem——
     * 但跳过 `scene_`/`storyboard_`/`output_` 前缀的文件（ArcReel SKIP_NAME_PATTERNS）。
     * 两者皆无返回 null（不加 label part）。
     */
    internal fun resolveLabel(ref: ReferenceImage): String? {
        val explicit = ref.label.trim()
        if (explicit.isNotEmpty()) return explicit
        return extractNameFromPath(ref.path)
    }

    internal fun extractNameFromPath(path: String): String? {
        val stem = File(path).nameWithoutExtension
        if (stem.isEmpty()) return null
        for (pattern in SKIP_NAME_PATTERNS) {
            if (stem.startsWith(pattern)) return null
        }
        return stem
    }

    /** 归一化 base URL：剥 /v1beta 后缀与尾斜杠；空回落默认 Gemini API base。 */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        if (base.endsWith(V1BETA_SUFFIX)) base = base.dropLast(V1BETA_SUFFIX.length)
        return base
    }

    internal fun resolveEndpoint(): String {
        val base = normalizeBaseUrl(cfg.baseUrl)
        return base + GENERATE_CONTENT_PATH.replace("{model}", model)
    }

    private suspend fun persistImage(respBody: String, outputPath: File) {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("gemini image 响应非 JSON：$respBody") }
        val candidates = root.getAsJsonArray("candidates")
        if (candidates == null || candidates.size() == 0) {
            error("gemini image 响应 candidates 为空（model=$model），可能触发内容安全过滤或上游服务异常")
        }
        val parts = candidates[0].asJsonObject
            .getAsJsonObject("content")?.getAsJsonArray("parts")
        if (parts == null || parts.size() == 0) {
            error("gemini image 响应 candidates[0].content.parts 为空：$respBody")
        }
        // 遍历 parts 找第一个 inline_data（Gemini image 响应通常单 part 含 inline_data）
        for (part in parts) {
            val inlineData = part.asJsonObject.getAsJsonObject("inline_data")
                ?: part.asJsonObject.getAsJsonObject("inlineData")  // 兼容 camelCase
            if (inlineData != null) {
                val b64 = inlineData.get("data")?.asString
                if (!b64.isNullOrBlank()) {
                    writeBase64Image(b64, outputPath)
                    return
                }
            }
        }
        error("gemini image 响应 parts 无 inline_data：$respBody")
    }

    /** 解码 base64 图片并写盘。容忍 data URI 前缀（剥 `data:...;base64,` 再解码）。 */
    private fun writeBase64Image(b64: String, outputPath: File) {
        val payload = if (b64.startsWith("data:") && b64.contains(",")) {
            b64.substringAfter(",")
        } else b64
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        outputPath.parentFile?.mkdirs()
        outputPath.writeBytes(bytes)
    }

    private fun doPost(client: OkHttpClient, url: String, body: String): Response {
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", cfg.apiKey)  // Gemini AI Studio API key header
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute()
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        private const val DEFAULT_MODEL = "gemini-3.1-flash-image-preview"
        private const val DEFAULT_BASE = "https://generativelanguage.googleapis.com"
        private const val V1BETA_SUFFIX = "/v1beta"
        private const val GENERATE_CONTENT_PATH = "/v1beta/models/{model}:generateContent"

        // 跳过名称推断的文件名前缀（移植 ArcReel SKIP_NAME_PATTERNS）
        internal val SKIP_NAME_PATTERNS = listOf("scene_", "storyboard_", "output_")

        init {
            // 注册到 Registry（P3b）
            ImageBackendRegistry.register(AiImageProviderConfig.TYPE_GEMINI) { cfg -> GeminiImageBackend(cfg) }
        }
    }
}
