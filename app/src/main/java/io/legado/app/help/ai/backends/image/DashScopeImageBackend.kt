package io.legado.app.help.ai.backends.image

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
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * DashScope（阿里百炼）图像后端（移植 ArcReel `image_backends/dashscope.py` + `dashscope_shared.py`）。
 *
 * 走原生 multimodal-generation 同步端点：单次 `POST /services/aigc/multimodal-generation/generation`
 * 直接返回生成图 URL，立即下载落地。T2I 与 I2I 共用同一端点，I2I 把参考图以 data URI 放进
 * `messages[0].content` 数组（image part 在前、text prompt 在最后）。
 *
 * - 端点：`POST {base}/services/aigc/multimodal-generation/generation`（base 归一化为 `{host}/api/v1`）
 * - 鉴权：Bearer（同步，无需 `X-DashScope-Async`）
 * - 请求体：`{model, input:{messages:[{role:"user", content:[...]}]}, parameters:{n, watermark, prompt_extend, size, seed?}}`
 *   - **content**：I2I 时先放若干 `{image: <dataURI>}`，最后放 `{text: prompt}`；T2I 时仅 `{text: prompt}`
 *   - **size 分隔符是星号 `*`**（如 `"1152*2048"`，非 `x`）
 * - **参考图编码：data URI**（[ImageCodec.toDataUri]）——qwen 系上限 3 张、wan 系上限 9 张（按 model 前缀 `wan` 判定），超限截断；任一不可读即 fail-loud
 * - 响应：`$.output.results[0].url` 优先（降级 `image_url`）→ [VideoBackendHttp.downloadVideo] 落盘；无 url 且 `$.code` 非空即报错（`{code, message, request_id}` 错误形态）；无 usage token
 * - 能力：edit 系（model 含 `qwen-image-edit`）仅 I2I；其余（qwen-image-2.0 / wan 系）{T2I, I2I}
 * - 尺寸：按 model 族三分支（wan 总像素约束 + 4K 门控 / edit 长边收口 / fusion 总像素约束），全部 round_to=16
 *
 * 单步同步（无 poll）。生命周期 1a 忠实：[generate] 自管 submit+download。
 */
class DashScopeImageBackend(private val cfg: AiImageProviderConfig) : ImageBackend {

    override val typeId: String = AiImageProviderConfig.TYPE_DASHSCOPE
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<ImageCapability> = resolveCaps(model)

    override suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult {
        val client = buildClient(cfg.validTimeout())
        val body = buildPayload(request)
        val base = normalizeBaseUrl(cfg.baseUrl)
        val submitUrl = base + IMAGE_ENDPOINT

        val respBody = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> resp.body?.string() ?: error("dashscope image submit 响应体为空") }

        val imageUrl = extractImageUrl(respBody)
        VideoBackendHttp.downloadVideo(imageUrl, request.outputPath)
        return ImageGenerationResult(
            imagePath = request.outputPath,
            provider = typeId,
            model = model,
            usageTokens = null  // dashscope image 无 usage token
        )
    }

    /**
     * 构造 DashScope multimodal-generation 请求体。
     *
     * - content：I2I 时先放若干 `{image: <dataURI>}`（参考图截断到上限），最后放 `{text: prompt}`；T2I 时仅 `{text: prompt}`
     * - parameters：`{n:1, watermark:false, prompt_extend:false, size:"{w}*{h}", seed?}`
     * - size 分隔符是星号 `*`
     *
     * fail-loud：任一保留的参考图文件不存在/不可读即中止，不静默跳过。
     */
    internal fun buildPayload(request: ImageGenerationRequest): String {
        val (width, height) = resolveSize(request)
        val content = JsonArray()
        encodeReferences(request.referenceImages).forEach { dataUri ->
            val imgPart = JsonObject()
            imgPart.addProperty("image", dataUri)
            content.add(imgPart)
        }
        val textPart = JsonObject()
        textPart.addProperty("text", request.prompt)
        content.add(textPart)

        val message = JsonObject().apply {
            addProperty("role", "user")
            add("content", content)
        }
        val messages = JsonArray().apply { add(message) }
        val input = JsonObject().apply { add("messages", messages) }

        val parameters = JsonObject().apply {
            addProperty("n", 1)
            addProperty("watermark", false)
            addProperty("prompt_extend", false)
            addProperty("size", "${width}*${height}")
        }
        request.seed?.let { parameters.addProperty("seed", it) }

        val root = JsonObject().apply {
            addProperty("model", model)
            add("input", input)
            add("parameters", parameters)
        }
        return root.toString()
    }

    /**
     * 参考图 → data URI 列表（截断到 model 族上限）。
     *
     * - qwen 系上限 3、wan 系上限 9（按 model 前缀 `wan` 判定），超限截断
     * - fail-loud：保留集中任一文件不存在/不可读抛 `error("dashscope 参考图文件不可读: $path")`
     */
    private fun encodeReferences(refs: List<ReferenceImage>): List<String> {
        if (refs.isEmpty()) return emptyList()
        val limit = if (model.lowercase().startsWith("wan")) _WAN_REF_LIMIT else _QWEN_REF_LIMIT
        val limited = if (refs.size > limit) refs.take(limit) else refs
        return limited.map { ref ->
            val file = File(ref.path)
            if (!file.exists() || !file.canRead()) {
                error("dashscope 参考图文件不可读: ${ref.path}")
            }
            ImageCodec.toDataUri(file)
        }
    }

    /** edit 系（model 含 `qwen-image-edit`）仅 I2I；其余 {T2I, I2I}。 */
    internal fun resolveCaps(modelId: String): Set<ImageCapability> {
        val m = modelId.lowercase()
        return if (m.contains("qwen-image-edit")) setOf(ImageCapability.IMAGE_TO_IMAGE)
        else setOf(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_TO_IMAGE)
    }

    /**
     * 按「比例优先、清晰度其次」算出 (宽, 高)，按 model 族三分支，全部 round_to=16。
     *
     * - wan 系：max_total_pixels 约束（标准 2048*2048 / 4K 4096*4096），4K 仅 wan2.7-image-pro T2I 支持
     * - edit 系：max_long_edge=2048 收口
     * - fusion 系：max_total_pixels=2048*2048 约束
     */
    internal fun resolveSize(request: ImageGenerationRequest): Pair<Int, Int> {
        val family = resolveFamily(model)
        val shortEdge = resolutionToShortEdge(request.imageSize, familyDefaultShort(family))
        val (aw, ah) = parseAspectRatio(request.aspectRatio)
        return when (family) {
            Family.WAN -> aspectSizeWan(aw, ah, shortEdge, request)
            Family.EDIT -> aspectSizeEdit(aw, ah, shortEdge)
            Family.FUSION -> aspectSizeFusion(aw, ah, shortEdge)
        }
    }

    /** model 族判定：含 `wan` → WAN；含 `qwen-image-edit` → EDIT；其余 → FUSION。 */
    internal fun resolveFamily(modelId: String): Family {
        val m = modelId.lowercase()
        return when {
            m.contains("wan") -> Family.WAN
            m.contains("qwen-image-edit") -> Family.EDIT
            else -> Family.FUSION
        }
    }

    private fun familyDefaultShort(family: Family): Int = when (family) {
        Family.WAN -> DEFAULT_SHORT_WAN
        Family.EDIT -> DEFAULT_SHORT_EDIT
        Family.FUSION -> DEFAULT_SHORT_FUSION
    }

    /**
     * wan 系尺寸：标准档 max_total_pixels=2048*2048；4K 档（仅 wan2.7-image-pro T2I）4096*4096。
     *
     * 4K 档检测：imageSize 为 "4k"（大小写不敏感）或自定义 WxH 总像素 > 2048*2048。
     * 非 pro 或 I2I 请求 4K 档即 fail-loud。
     */
    private fun aspectSizeWan(aw: Int, ah: Int, shortEdge: Int, request: ImageGenerationRequest): Pair<Int, Int> {
        val is4k = is4kTier(request.imageSize)
        if (is4k) {
            val isProT2i = model.lowercase().contains("wan2.7-image-pro") && request.referenceImages.isEmpty()
            if (!isProT2i) error("dashscope 4K 仅 wan2.7-image-pro T2I 支持")
        }
        val maxTotal = if (is4k) MAX_TOTAL_PIXELS_4K else MAX_TOTAL_PIXELS_STANDARD
        return aspectSizePixelCapped(aw, ah, shortEdge, maxTotal, MAX_RATIO_WAN)
    }

    /** edit 系尺寸：max_long_edge=2048 收口，max_ratio=4.0。 */
    private fun aspectSizeEdit(aw: Int, ah: Int, shortEdge: Int): Pair<Int, Int> {
        val shortComp = minOf(aw, ah)
        val longComp = maxOf(aw, ah)
        val ratio = longComp.toDouble() / shortComp
        if (ratio > MAX_RATIO_EDIT) error("dashscope 宽高比 ${aw}:${ah} 超过最大 $MAX_RATIO_EDIT")
        return aspectSizeLongEdgeCapped(aw, ah, shortEdge, MAX_LONG_EDGE_EDIT)
    }

    /** fusion 系尺寸：max_total_pixels=2048*2048 约束，max_ratio=8.0。 */
    private fun aspectSizeFusion(aw: Int, ah: Int, shortEdge: Int): Pair<Int, Int> =
        aspectSizePixelCapped(aw, ah, shortEdge, MAX_TOTAL_PIXELS_STANDARD, MAX_RATIO_FUSION)

    /**
     * 总像素约束版 aspect_size（wan/fusion）：比例优先、清晰度决定短边，round_to=16，
     * 再约束 W*H <= maxTotalPixels（超则减小 t），max_ratio 超即 fail-loud。
     */
    private fun aspectSizePixelCapped(
        aw: Int, ah: Int, shortEdge: Int, maxTotalPixels: Long, maxRatio: Double
    ): Pair<Int, Int> {
        val shortComp = minOf(aw, ah)
        val longComp = maxOf(aw, ah)
        val ratio = longComp.toDouble() / shortComp
        if (ratio > maxRatio) error("dashscope 宽高比 ${aw}:${ah} 超过最大 $maxRatio")
        val shortUnit = ROUND_TO * shortComp
        var t = maxOf(1, (shortEdge.toDouble() / shortUnit).roundToInt())
        // W*H = aw*ah*ROUND_TO^2*t^2 <= maxTotalPixels → t <= sqrt(maxTotalPixels/(aw*ah*ROUND_TO^2))
        val maxTPixels = sqrt(maxTotalPixels.toDouble() / (aw * ah * ROUND_TO * ROUND_TO)).toInt()
        t = minOf(t, maxOf(1, maxTPixels))
        t = maxOf(1, t)
        return aw * ROUND_TO * t to ah * ROUND_TO * t
    }

    /** 长边收口版 aspect_size（edit）：比例优先、清晰度决定短边，round_to=16，长边受 maxLongEdge 夹取。 */
    private fun aspectSizeLongEdgeCapped(aw: Int, ah: Int, shortEdge: Int, maxLongEdge: Int): Pair<Int, Int> {
        val shortComp = minOf(aw, ah)
        val longComp = maxOf(aw, ah)
        val shortUnit = ROUND_TO * shortComp
        var t = maxOf(1, (shortEdge.toDouble() / shortUnit).roundToInt())
        val maxTLong = maxLongEdge / (ROUND_TO * longComp)
        t = minOf(t, maxOf(1, maxTLong))
        t = maxOf(1, t)
        return aw * ROUND_TO * t to ah * ROUND_TO * t
    }

    /**
     * 4K 档检测：imageSize 为 "4k"（大小写不敏感）或自定义 WxH 总像素 > 2048*2048。
     */
    internal fun is4kTier(imageSize: String?): Boolean {
        if (imageSize == null) return false
        val s = imageSize.trim()
        if (s.equals("4k", ignoreCase = true)) return true
        val m = WH_RE.matchEntire(s)
        if (m != null) {
            val w = m.groupValues[1].toIntOrNull() ?: return false
            val h = m.groupValues[2].toIntOrNull() ?: return false
            return w.toLong() * h.toLong() > MAX_TOTAL_PIXELS_STANDARD
        }
        return false
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
     * - null/空 → [defaultShort]（family 默认短边）
     * - 档位词（512px/1K/2K/4K，大小写不敏感）→ 查表
     * - 自定义 "宽x高"（分隔符 x、X、×、星号）→ min(宽,高)，剥离自带比例
     * - 纯数字 → 直接当短边
     * - 无法解析 → [defaultShort]
     */
    internal fun resolutionToShortEdge(resolution: String?, defaultShort: Int): Int {
        if (resolution.isNullOrBlank()) return defaultShort
        val s = resolution.trim()
        IMAGE_TIER_SHORT_EDGE[s.lowercase()]?.let { return it }
        val m = WH_RE.matchEntire(s)
        if (m != null) {
            val w = m.groupValues[1].toIntOrNull() ?: return defaultShort
            val h = m.groupValues[2].toIntOrNull() ?: return defaultShort
            return minOf(w, h)
        }
        s.toIntOrNull()?.let { return it }
        return defaultShort
    }

    /**
     * 归一化 base URL：剥除 `/compatible-mode/v1` / `/api/v1` 已知后缀后补 `/api/v1`
     * （原生端点 base）。容忍用户填 host 或带后缀的完整 base；空回落百炼默认 host。
     */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        KNOWN_SUFFIXES.forEach { suf -> if (base.endsWith(suf)) base = base.removeSuffix(suf) }
        return base + "/api/v1"
    }

    /**
     * 从 multimodal-generation 响应提取生成图 URL。
     *
     * - `$.output.results[0].url` 优先，降级 `$.output.results[0].image_url`
     * - results 为空/无 url 且 `$.code` 非空 → 报错（`{code, message, request_id}` 错误形态）
     * - 否则报「缺少 results[0].url」
     */
    internal fun extractImageUrl(respBody: String): String {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("dashscope image 响应非 JSON：$respBody") }
        val output = root.getAsJsonObject("output")
        val results = output?.getAsJsonArray("results")
        if (results != null && results.size() > 0) {
            val item = results[0].asJsonObject
            val url = item.get("url")?.asString
            if (!url.isNullOrBlank()) return url
            val imageUrl = item.get("image_url")?.asString
            if (!imageUrl.isNullOrBlank()) return imageUrl
        }
        if (root.get("code") != null) {
            val msg = root.get("message")?.asString ?: ""
            error("dashscope image 生成失败 code=${root.get("code")}: $msg".trim())
        }
        error("dashscope image 响应缺少 results[0].url：$respBody")
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

    /** model 族（wan / edit / fusion）——决定尺寸算法分支与默认短边。 */
    internal enum class Family { WAN, EDIT, FUSION }

    companion object {
        private const val DEFAULT_MODEL = "qwen-image-2.0"
        private const val DEFAULT_BASE = "https://dashscope.aliyuncs.com"
        private const val IMAGE_ENDPOINT = "/services/aigc/multimodal-generation/generation"
        private val KNOWN_SUFFIXES = listOf("/compatible-mode/v1", "/api/v1")

        private const val ROUND_TO = 16
        private const val MAX_LONG_EDGE_EDIT = 2048
        private const val MAX_RATIO_WAN = 8.0
        private const val MAX_RATIO_EDIT = 4.0
        private const val MAX_RATIO_FUSION = 8.0
        private const val MAX_TOTAL_PIXELS_STANDARD = 2048L * 2048L
        private const val MAX_TOTAL_PIXELS_4K = 4096L * 4096L

        private const val DEFAULT_SHORT_WAN = 1440
        private const val DEFAULT_SHORT_EDIT = 2048
        private const val DEFAULT_SHORT_FUSION = 2048

        // 参考图上限：qwen 系 3、wan 系 9（移植 ArcReel dashscope_shared.py）
        internal const val _QWEN_REF_LIMIT = 3
        internal const val _WAN_REF_LIMIT = 9

        // 图片档位 → 短边像素
        internal val IMAGE_TIER_SHORT_EDGE: Map<String, Int> = mapOf(
            "512px" to 512, "1k" to 1024, "2k" to 1440, "4k" to 2160
        )

        // 自定义 "宽x高" 分隔符：英文 x、X、星号、全角叉号
        internal val WH_RE = Regex("""^\s*(\d+)\s*[xX×*]\s*(\d+)\s*$""")

        init {
            // 注册到 Registry（P3c）
            ImageBackendRegistry.register(AiImageProviderConfig.TYPE_DASHSCOPE) { cfg -> DashScopeImageBackend(cfg) }
        }
    }
}
