package io.legado.app.help.ai.backends.image

import java.util.Base64 as JvmBase64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.ImageBackend
import io.legado.app.help.ai.backends.ImageBackendRegistry
import io.legado.app.help.ai.backends.ImageCapability
import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ImageGenerationResult
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
 * Grok（xAI）图像后端（移植 ArcReel `image_backends/grok.py` + `grok_shared.py`）。
 *
 * 走 xAI REST 等价端点（SDK `image.sample` 的 REST 形态）：同步 `POST /v1/images/generations`
 * 直接返回图片 URL 或 base64，立即落地为本地资产。T2I 与 I2I 共用同一端点，I2I 把参考图随
 * 请求体下发（`image_urls` 字段，data URI 数组）。
 *
 * - 端点：POST `{base}/v1/images/generations`（base 归一化：补 `https://` + 去尾斜杠，空回落 xAI host）
 * - 请求体：`{model, prompt, aspect_ratio, resolution?, image_urls?:[data URI]}`
 *   - T2I 与 I2I 共用，I2I 多 `image_urls` 字段
 *   - `resolution` 可选（request.imageSize 非空时下传）
 *   - `aspect_ratio` 直接透传
 * - **参考图编码：data URI 列表**（[ImageCodec.toDataUri]）——I2I 时 `image_urls` 为 data URI 数组
 *   - **不 fail-loud**：文件不存在跳过（与 OpenAiImageBackend.submitEdit 同款）
 * - 响应：`data[0].url` 优先下载；url 缺失/下载失败降级 `data[0].b64_json` 解码写盘；两者皆空报错
 * - 鉴权：Bearer
 * - **无 usage token**
 * - 默认 model：`grok-imagine-image`；默认 base：`https://api.x.ai`
 * - 能力：恒 `{T2I, I2I}`
 *
 * 单步同步（无 poll）。生命周期 1a 忠实：[generate] 自管 submit+persist。
 */
class GrokImageBackend(private val cfg: AiImageProviderConfig) : ImageBackend {

    override val typeId: String = AiImageProviderConfig.TYPE_GROK
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<ImageCapability> = setOf(
        ImageCapability.TEXT_TO_IMAGE,
        ImageCapability.IMAGE_TO_IMAGE
    )

    override suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult {
        val client = buildClient(cfg.validTimeout())
        val body = buildPayload(request)
        val base = normalizeBaseUrl(cfg.baseUrl)
        val submitUrl = base + IMAGE_ENDPOINT

        val respBody = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> resp.body?.string() ?: error("grok image submit 响应体为空") }

        persistImage(respBody, request.outputPath)
        return ImageGenerationResult(
            imagePath = request.outputPath,
            provider = typeId,
            model = model,
            usageTokens = null
        )
    }

    /**
     * 构造 Grok 图像请求体。
     *
     * - `{model, prompt, aspect_ratio, resolution?}`
     * - I2I：参考图 → `image_urls`（data URI 数组），文件不存在跳过（不 fail-loud）
     * - `resolution` 仅在 request.imageSize 非空时下传
     * - `aspect_ratio` 直接透传
     */
    internal fun buildPayload(request: ImageGenerationRequest): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt)
            addProperty("aspect_ratio", request.aspectRatio)
        }
        request.imageSize?.takeIf { it.isNotBlank() }?.let {
            root.addProperty("resolution", it)
        }
        if (request.referenceImages.isNotEmpty()) {
            val arr = JsonArray()
            request.referenceImages.forEach { ref ->
                val file = File(ref.path)
                if (!file.exists()) return@forEach  // 跳过不存在的参考图（不 fail-loud）
                arr.add(ImageCodec.toDataUri(file))
            }
            root.add("image_urls", arr)
        }
        return root.toString()
    }

    /** 归一化 base URL：补 `https://` + 去尾斜杠；空回落 xAI host。 */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
        return base
    }

    /**
     * 把 images/generations 响应落地为本地文件。
     *
     * 优先 URL 下载；URL 缺失或下载失败时降级到同响应内 base64 解码写盘，
     * 避免一次已计费的成功生成因下载环节失败而无法落盘；两者皆空即报错。
     */
    private suspend fun persistImage(respBody: String, outputPath: File) {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("grok image 响应非 JSON：$respBody") }
        val url = extractFirstStr(root, "url")
        val b64 = extractFirstStr(root, "b64_json")
        if (url != null) {
            runCatching { VideoBackendHttp.downloadVideo(url, outputPath) }
                .onSuccess { return }
                .onFailure {
                    if (b64 == null) throw it
                    // url 下载失败但有 b64，降级
                }
        }
        if (b64 != null) {
            writeBase64Image(b64, outputPath)
            return
        }
        error("grok image 响应缺少 url/b64_json：$respBody")
    }

    private fun extractFirstStr(root: JsonObject, key: String): String? {
        val data = root.getAsJsonArray("data") ?: return null
        for (i in 0 until data.size()) {
            val item = data[i].asJsonObject
            val value = item.get(key)?.takeIf { !it.isJsonNull }?.asString
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** 解码 base64 图片并写盘。容忍 data URI 前缀（剥 `data:...;base64,` 再解码）。 */
    private fun writeBase64Image(b64: String, outputPath: File) {
        val payload = if (b64.startsWith("data:") && b64.contains(",")) {
            b64.substringAfter(",")
        } else b64
        val bytes = JvmBase64.getDecoder().decode(payload)
        outputPath.parentFile?.mkdirs()
        outputPath.writeBytes(bytes)
    }

    private fun doSubmit(client: OkHttpClient, url: String, body: String): Response {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute()
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        private const val DEFAULT_MODEL = "grok-imagine-image"
        private const val DEFAULT_BASE = "https://api.x.ai"
        private const val IMAGE_ENDPOINT = "/v1/images/generations"

        init {
            // 注册到 Registry（P3c）
            ImageBackendRegistry.register(AiImageProviderConfig.TYPE_GROK) { cfg -> GrokImageBackend(cfg) }
        }
    }
}
