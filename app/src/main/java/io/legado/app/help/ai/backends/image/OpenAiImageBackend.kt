package io.legado.app.help.ai.backends.image

import java.util.Base64 as JvmBase64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.ImageBackend
import io.legado.app.help.ai.backends.ImageBackendRegistry
import io.legado.app.help.ai.backends.ImageCapability
import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ImageGenerationResult
import io.legado.app.help.ai.backends.VideoBackendHttp
import io.legado.app.ui.main.ai.AiImageProviderConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * OpenAI（gpt-image）图像后端（移植 ArcReel `image_backends/openai.py`）。
 *
 * **关键：T2I 与 I2I 用不同端点 + 不同请求体形态**：
 * - T2I：`POST /images/generations`，JSON `{model, prompt, n:1, size, quality?}`
 * - I2I：`POST /images/edits`，**multipart/form-data**（参考图原始字节文件句柄列表，非 base64）
 *
 * ArcReel Python 走 OpenAI SDK（`images.generate` / `images.edit`），SDK 内部把 image 文件列表
 * 转成 multipart `image[]` 多字段；Kotlin 无 SDK，直接构造 multipart 等价。
 *
 * - 尺寸：aspect_size（比例优先、清晰度其次，**16 整除**，长边 3840 收口，比例 1:3~3:1）
 * - quality：image_size 档位 → quality 映射（`512px`→low / `1K`→medium / `2K`、`4K`→high）；
 *   自定义 WxH 或 None 不下传 quality
 * - 响应：`data[0].b64_json` 优先解码写盘；b64_json 缺失降级 `data[0].url` 下载；+ usage token
 * - 默认 model：`gpt-image-2`
 * - 参考图上限 16（ArcReel `_MAX_REFERENCE_IMAGES`）
 * - 鉴权：Bearer
 *
 * 单步同步（无 poll）。生命周期 1a 忠实：[generate] 自管 submit+persist。
 */
class OpenAiImageBackend(private val cfg: AiImageProviderConfig) : ImageBackend {

    override val typeId: String = AiImageProviderConfig.TYPE_OPENAI
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<ImageCapability> = setOf(
        ImageCapability.TEXT_TO_IMAGE,
        ImageCapability.IMAGE_TO_IMAGE
    )

    override suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult {
        val client = buildClient(cfg.validTimeout())
        val hasRefs = request.referenceImages.isNotEmpty()
        val respBody = if (hasRefs) {
            submitEdit(client, request)
        } else {
            submitGenerate(client, request)
        }
        persistImage(respBody, request.outputPath)
        return ImageGenerationResult(
            imagePath = request.outputPath,
            provider = typeId,
            model = model,
            usageTokens = extractUsageTokens(respBody)
        )
    }

    /**
     * T2I：`POST /images/generations`，JSON `{model, prompt, n:1, size, quality?}`。
     *
     * 尺寸与 quality 由 [resolveOpenAiParams] 按「比例优先、清晰度其次」算出。
     * 不下传 n 之外的 response_format（gpt-image-2 默认返 b64_json）。
     */
    internal suspend fun submitGenerate(client: OkHttpClient, request: ImageGenerationRequest): String {
        val params = resolveOpenAiParams(request.imageSize, request.aspectRatio)
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt)
            addProperty("n", 1)
            addProperty("size", params["size"])
            params["quality"]?.let { addProperty("quality", it) }
        }
        val url = normalizeBaseUrl(cfg.baseUrl) + GENERATIONS_PATH
        return VideoBackendHttp.submitPost(
            postFn = { doJsonPost(client, url, root.toString()) },
            provider = typeId
        ).use { resp -> resp.body?.string() ?: error("openai image generate 响应体为空") }
    }

    /**
     * I2I：`POST /images/edits`，**multipart/form-data**（参考图原始字节）。
     *
     * ArcReel Python `images.edit(image=[file1, file2, ...])`——SDK 把列表转成 multipart 的多个
     * `image[]` 字段。Kotlin 侧直接构造 MultipartBody：每张参考图一个 `image[]` part，
     * 文件名 `ref_{i}.jpg`，Content-Type `image/jpeg`。
     *
     * I2I 与 T2I 对称下传 size/quality（ArcReel 注释原话：「images.edit 不带 size 会丢比例」）。
     */
    internal suspend fun submitEdit(client: OkHttpClient, request: ImageGenerationRequest): String {
        val params = resolveOpenAiParams(request.imageSize, request.aspectRatio)
        val refs = request.referenceImages.take(MAX_REFERENCE_IMAGES)

        // ArcReel _open_refs：单张 FileNotFoundError warn 跳过；但全部不可读则 fail-loud
        // （ImageCapabilityError image_endpoint_mismatch_no_i2i: all reference images failed to open），
        // 不静默退化为 T2I 继续提交（用户提交 i2i 却无有效素材应是错误而非默默 fallback）
        val openedRefs = refs.mapIndexedNotNull { i, ref ->
            val file = File(ref.path)
            if (file.exists()) i to file else null
        }
        if (openedRefs.isEmpty()) {
            error("openai image i2i 所有参考图均不可读（model=$model），不应静默退化为 t2i")
        }

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("prompt", request.prompt)
            .addFormDataPart("n", "1")
            .addFormDataPart("size", params["size"]!!)
            .apply { params["quality"]?.let { addFormDataPart("quality", it) } }
            .apply {
                openedRefs.forEach { (i, file) ->
                    val bytes = file.readBytes()
                    addFormDataPart(
                        name = "image[]",
                        filename = "ref_$i.jpg",
                        body = bytes.toRequestBody("image/jpeg".toMediaType())
                    )
                }
            }
            .build()

        val url = normalizeBaseUrl(cfg.baseUrl) + EDITS_PATH
        return VideoBackendHttp.submitPost(
            postFn = { doMultipartPost(client, url, multipart) },
            provider = typeId
        ).use { resp -> resp.body?.string() ?: error("openai image edit 响应体为空") }
    }

    /**
     * 按「比例优先、清晰度其次」算出 {size, quality?}。
     *
     * 比例永远来自 aspectRatio；imageSize（档位 / 自定义 WxH / None）只决定清晰度短边，
     * 自定义 WxH 剥离其自带比例（取 min 当短边）。imageSize=None 时按默认 720P 短边兜底。
     *
     * - round_to=16（gpt-image-2 要求宽高均被 16 整除）
     * - max_long_edge=3840（4K 上限；>2560 进入实验性区间，此处仅 size 计算，警告由调用方/上游判定）
     * - max_ratio=3.0（1:3~3:1，超出仅 size 仍算，由 API 判定）
     */
    internal fun resolveOpenAiParams(imageSize: String?, aspectRatio: String): Map<String, String> {
        val short = resolutionToShortEdge(imageSize)
        val (w, h) = aspectSize(aspectRatio, short)
        val params = mutableMapOf("size" to "${w}x${h}")
        qualityFor(imageSize)?.let { params["quality"] = it }
        return params
    }

    private fun aspectSize(aspectRatio: String, shortEdge: Int): Pair<Int, Int> {
        val (aw, ah) = parseAspectRatio(aspectRatio)
        val shortComp = minOf(aw, ah)
        val longComp = maxOf(aw, ah)
        val shortUnit = ROUND_TO * shortComp
        var t = maxOf(1, (shortEdge.toDouble() / shortUnit).roundToInt())
        val maxTLong = MAX_LONG_EDGE / (ROUND_TO * longComp)
        t = minOf(t, maxOf(1, maxTLong))
        t = maxOf(1, t)
        return aw * ROUND_TO * t to ah * ROUND_TO * t
    }

    private fun parseAspectRatio(ratio: String): Pair<Int, Int> {
        val parts = ratio.split(":")
        if (parts.size != 2) return 9 to 16
        val w = parts[0].toIntOrNull() ?: 9
        val h = parts[1].toIntOrNull() ?: 16
        if (w <= 0 || h <= 0) return 9 to 16
        val g = gcd(w, h)
        return w / g to h / g
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /**
     * 把分辨率规范化成「短边像素」。
     *
     * - null/空 → 720（默认档位，gpt-image-2 默认 720P）
     * - 档位词（512px/1K/2K/4K，大小写不敏感）→ 查表
     * - 自定义 "宽x高"（分隔符 x、X、×、星号）→ min(宽,高)，剥离自带比例
     * - 纯数字 → 直接当短边
     * - 无法解析 → 720
     */
    internal fun resolutionToShortEdge(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return DEFAULT_SHORT
        val s = resolution.trim()
        IMAGE_TIER_SHORT_EDGE[s.lowercase()]?.let { return it }
        val m = WH_RE.matchEntire(s)
        if (m != null) {
            val w = m.groupValues[1].toIntOrNull() ?: return DEFAULT_SHORT
            val h = m.groupValues[2].toIntOrNull() ?: return DEFAULT_SHORT
            return minOf(w, h)
        }
        s.toIntOrNull()?.let { return it }
        return DEFAULT_SHORT
    }

    /** image_size 档位 → quality（OpenAI gpt-image 系列）。自定义 WxH / None → null。 */
    internal fun qualityFor(imageSize: String?): String? {
        if (imageSize.isNullOrBlank()) return null
        return QUALITY_MAP[imageSize.trim().lowercase()]
    }

    /** 归一化 base URL：去尾斜杠；空回落 OpenAI 官方 base。 */
    internal fun normalizeBaseUrl(raw: String): String {
        val base = raw.trim().trimEnd('/')
        return if (base.isEmpty()) DEFAULT_BASE else base
    }

    private suspend fun persistImage(respBody: String, outputPath: File) {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("openai image 响应非 JSON：$respBody") }
        val data = root.getAsJsonArray("data")
        if (data == null || data.size() == 0) {
            error("openai image 响应 data 为空（model=$model），可能触发内容安全过滤或上游服务异常")
        }
        val item = data[0].asJsonObject
        // b64_json 优先；b64_json 缺失降级 url（与 Agnes 的 url 优先相反——gpt-image 默认返 b64_json）
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
        error("openai image 响应 data[0] 既无 b64_json 也无 url：$respBody")
    }

    private fun extractUsageTokens(respBody: String): Long? {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }.getOrNull() ?: return null
        val usage = root.getAsJsonObject("usage") ?: return null
        // gpt-image usage 字段：total_tokens / input_tokens / output_tokens
        return runCatching { usage.get("total_tokens")?.asLong }.getOrNull()
            ?: runCatching { (usage.get("input_tokens")?.asLong ?: 0L) + (usage.get("output_tokens")?.asLong ?: 0L) }.getOrNull()
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

    private fun doJsonPost(client: OkHttpClient, url: String, body: String): Response {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute()
    }

    private fun doMultipartPost(client: OkHttpClient, url: String, body: MultipartBody): Response {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body)
            .build()
        return client.newCall(req).execute()
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        private const val DEFAULT_MODEL = "gpt-image-2"
        private const val DEFAULT_BASE = "https://api.openai.com/v1"
        private const val GENERATIONS_PATH = "/images/generations"
        private const val EDITS_PATH = "/images/edits"
        internal const val MAX_REFERENCE_IMAGES = 16

        // gpt-image-2 要求宽高均被 16 整除；最大 4K=3840；比例 1:3~3:1
        private const val ROUND_TO = 16
        private const val MAX_LONG_EDGE = 3840
        private const val DEFAULT_SHORT = 720

        // 图片档位 → 短边像素（与 aspect_size.py 一致）
        internal val IMAGE_TIER_SHORT_EDGE: Map<String, Int> = mapOf(
            "512px" to 512, "1k" to 1024, "2k" to 1440, "4k" to 2160
        )

        // image_size 档位 → quality（移植 ArcReel OPENAI_IMAGE_QUALITY_MAP）
        internal val QUALITY_MAP: Map<String, String> = mapOf(
            "512px" to "low", "1k" to "medium", "2k" to "high", "4k" to "high"
        )

        // 自定义 "宽x高" 分隔符：英文 x、X、星号、全角叉号
        internal val WH_RE = Regex("""^\s*(\d+)\s*[xX×*]\s*(\d+)\s*$""")

        init {
            // 注册到 Registry（P3b）
            ImageBackendRegistry.register(AiImageProviderConfig.TYPE_OPENAI) { cfg -> OpenAiImageBackend(cfg) }
        }
    }
}
