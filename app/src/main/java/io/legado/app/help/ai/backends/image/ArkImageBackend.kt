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
 * Ark（火山方舟）Seedream 图像后端（移植 ArcReel `image_backends/ark.py` + `ark_shared.py`）。
 *
 * ArcReel 走 Ark SDK 的 `images.generate`（同步）；Kotlin 侧无等价 SDK，按 spec 设计表
 * 实现 REST 等价：POST `{base}/images/generations` → 解析 `data[0]` 落盘。
 *
 * - 端点：POST `{base}/images/generations`（base 缺省 `https://ark.cn-beijing.volces.com/api/v3`）
 * - 请求体：`{model, prompt, size, image?:str|[], seed?}`
 *   - **size**：caller 显式传 [ImageGenerationRequest.imageSize] 优先；否则按 Seedream 尺寸表
 *     从 aspectRatio 推导（2K 档 4.x/5.x / 1K 档 seedream-3）
 *   - **image**：单张传 data URI 字符串，多张传 data URI 列表（与 Agnes image 数组形态不同）
 * - **参考图编码：data URI**（[ImageCodec.toDataUri]）
 * - 响应：`data[0].b64_json` 优先解码写盘；b64_json 缺失降级 `data[0].url` 下载
 *   （与 Agnes 的 url 优先相反——Ark SDK 默认返 b64_json）
 * - 鉴权：Bearer
 *
 * 单步同步（无 poll）。生命周期 1a 忠实：[generate] 自管 submit+persist。
 */
class ArkImageBackend(private val cfg: AiImageProviderConfig) : ImageBackend {

    override val typeId: String = AiImageProviderConfig.TYPE_ARK
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
        ).use { resp -> resp.body?.string() ?: error("ark image submit 响应体为空") }

        persistImage(respBody, request.outputPath)
        return ImageGenerationResult(
            imagePath = request.outputPath,
            provider = typeId,
            model = model,
            usageTokens = extractUsageTokens(respBody)
        )
    }

    /**
     * 构造 Ark 图像请求体。
     *
     * - `{model, prompt, size, image?:str|[], seed?}`
     * - size：imageSize 优先；否则按 Seedream 尺寸表从 aspectRatio 推导
     * - image：单张传 data URI 字符串，多张传 data URI 列表
     */
    internal fun buildPayload(request: ImageGenerationRequest): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt)
            addProperty("size", resolveSize(model, request.imageSize, request.aspectRatio))
        }
        if (request.referenceImages.isNotEmpty()) {
            val uris = request.referenceImages.map { ImageCodec.toDataUri(File(it.path)) }
            // 单张传字符串，多张传列表（与 Agnes image 始终数组形态不同）
            when (uris.size) {
                1 -> root.addProperty("image", uris[0])
                else -> {
                    val arr = JsonArray()
                    uris.forEach { arr.add(it) }
                    root.add("image", arr)
                }
            }
        }
        request.seed?.let { root.addProperty("seed", it) }
        return root.toString()
    }

    /**
     * 解析 Seedream `size` 参数。
     *
     * - caller 显式传 [imageSize]（如 "2K"/"4K"/具体尺寸）→ 直接用
     * - 否则按模型族选尺寸表：seedream-3 → 1K 表；默认（4.x/5.x）→ 2K 表
     * - 未识别比例回退到分辨率 keyword（"1K"/"2K"，由模型按 prompt 自适应）
     */
    internal fun resolveSize(modelId: String, imageSize: String?, aspectRatio: String): String {
        if (!imageSize.isNullOrBlank()) return imageSize
        val mid = (modelId ?: "").lowercase()
        val sizeMap = if (mid.contains("seedream-3")) SEEDREAM_1K_SIZE_MAP else SEEDREAM_2K_SIZE_MAP
        val fallback = if (mid.contains("seedream-3")) "1K" else "2K"
        return sizeMap[aspectRatio] ?: fallback
    }

    /** 归一化 base URL：补 https:// + 去尾斜杠；空回落 Ark API v3 base。 */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
        return base
    }

    /**
     * 把 images/generations 响应落地为本地文件。
     *
     * 优先 b64_json 解码写盘；b64_json 缺失降级 url 下载
     * （与 Agnes 的 url 优先相反——Ark SDK 默认返 b64_json）。
     * 两者皆空即报错。
     */
    private suspend fun persistImage(respBody: String, outputPath: File) {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("ark image 响应非 JSON：$respBody") }
        val data = root.getAsJsonArray("data")
        if (data == null || data.size() == 0) {
            error("ark image 响应 data 为空（model=$model），可能触发内容安全过滤或上游服务异常")
        }
        val item = data[0].asJsonObject
        val b64 = item.get("b64_json")?.takeIf { !it.isJsonNull }?.asString
        if (!b64.isNullOrBlank()) {
            writeBase64Image(b64, outputPath)
            return
        }
        val url = item.get("url")?.takeIf { !it.isJsonNull }?.asString
        if (!url.isNullOrBlank()) {
            VideoBackendHttp.downloadVideo(url, outputPath)
            return
        }
        error("ark image 响应 data[0] 既无 b64_json 也无 url：$respBody")
    }

    private fun extractUsageTokens(respBody: String): Long? {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }.getOrNull() ?: return null
        val usage = root.getAsJsonObject("usage") ?: return null
        return runCatching { usage.get("total_tokens")?.asLong }.getOrNull()
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
        private const val DEFAULT_MODEL = "doubao-seedream-5-0-lite-260128"
        private const val DEFAULT_BASE = "https://ark.cn-beijing.volces.com/api/v3"
        private const val IMAGE_ENDPOINT = "/images/generations"

        // Seedream 4.x/5.x 2K 档尺寸表（官方推荐宽高像素值）
        internal val SEEDREAM_2K_SIZE_MAP: Map<String, String> = mapOf(
            "1:1" to "2048x2048", "4:3" to "2304x1728", "3:4" to "1728x2304",
            "16:9" to "2848x1600", "9:16" to "1600x2848",
            "3:2" to "2496x1664", "2:3" to "1664x2496", "21:9" to "3136x1344"
        )

        // Seedream 3.0-t2i 1K 档尺寸表（单边 ∈ [512, 2048]）
        internal val SEEDREAM_1K_SIZE_MAP: Map<String, String> = mapOf(
            "1:1" to "1024x1024", "4:3" to "1152x864", "3:4" to "864x1152",
            "16:9" to "1280x720", "9:16" to "720x1280",
            "3:2" to "1248x832", "2:3" to "832x1248", "21:9" to "1512x648"
        )

        init {
            // 注册到 Registry（P3a）
            ImageBackendRegistry.register(AiImageProviderConfig.TYPE_ARK) { cfg -> ArkImageBackend(cfg) }
        }
    }
}
