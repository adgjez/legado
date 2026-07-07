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

/**
 * MiniMax（海螺）图像后端（移植 ArcReel `image_backends/minimax.py` + `minimax_shared.py`）。
 *
 * 走挂在 OpenAI 兼容 text base 上的原生图像端点：同步 `POST /image_generation` 直接返回图片
 * URL 或 base64，立即落地为本地资产。T2I 与 I2I 共用同一端点，I2I 把参考图随请求体下发
 * （`subject_reference` 字段，单脸参考，仅取首张）。
 *
 * - 端点：POST `{base}/image_generation`（base 归一化：剥 `/v1` 后补 `/v1`，空回落海螺 host）
 * - 请求体：`{model, prompt, width, height, response_format:"url", n:1, prompt_optimizer:false, seed?, subject_reference?}`
 *   - T2I 与 I2I 共用，I2I 多 `subject_reference` 字段
 *   - `response_format` 固定 `"url"`；`prompt_optimizer:false`
 *   - 输出为独立的 `width`/`height` 字段（非 `size` 字符串）
 * - **参考图编码：data URI**（[ImageCodec.toDataUri]）
 *   - **单脸参考**：`subject_reference` 仅取首张参考图（[_SUBJECT_REF_LIMIT]=1），多张截断为首张
 *   - 结构为 `[{type:"character", image_file:<dataURI>}]`（注意是 `image_file` 字符串字段，非数组）
 *   - **fail-loud**：首张缺失/不可读抛 `error("minimax 参考图文件不可读: $path")`
 * - 尺寸：round_to=8、max_long_edge=2048、max_ratio=4.0、min_edge=512/max_edge=2048，默认短边 1440
 * - 响应：
 *   1. 先查 `base_resp.status_code != 0` 抛错
 *   2. 优先 `$.data.image_urls[0]` 下载
 *   3. URL 缺失降级 `$.data.image_base64` 解码写盘（容忍 data URI 前缀）
 *   4. 两者皆空抛错
 * - 鉴权：Bearer
 * - **无 usage token**
 * - 默认 model：`image-01`；默认 base：`https://api.minimaxi.com/v1`
 * - 能力：恒 `{T2I, I2I}`
 *
 * 单步同步（无 poll）。生命周期 1a 忠实：[generate] 自管 submit+persist。
 */
class MiniMaxImageBackend(private val cfg: AiImageProviderConfig) : ImageBackend {

    override val typeId: String = AiImageProviderConfig.TYPE_MINIMAX
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
        ).use { resp -> resp.body?.string() ?: error("minimax image submit 响应体为空") }

        persistImage(respBody, request.outputPath)
        return ImageGenerationResult(
            imagePath = request.outputPath,
            provider = typeId,
            model = model,
            usageTokens = null
        )
    }

    /**
     * 构造 MiniMax 图像请求体。
     *
     * - `{model, prompt, width, height, response_format:"url", n:1, prompt_optimizer:false, seed?}`
     * - I2I：参考图 → `subject_reference:[{type:"character", image_file:<dataURI>}]`（单脸，仅首张）
     *   - 多张截断为首张；首张缺失/不可读 **fail-loud**
     *   - `image_file` 为字符串字段（data URI），非数组
     * - 输出为独立的 `width`/`height`（被 8 整除，长边 2048 收口，每边夹 [512, 2048]）
     */
    internal fun buildPayload(request: ImageGenerationRequest): String {
        val (width, height) = resolveDimensions(request)
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt)
            addProperty("width", width)
            addProperty("height", height)
            addProperty("response_format", "url")
            addProperty("n", 1)
            addProperty("prompt_optimizer", false)
        }
        request.seed?.let { root.addProperty("seed", it) }

        if (request.referenceImages.isNotEmpty()) {
            // 单脸参考：仅取首张（_SUBJECT_REF_LIMIT=1），多张截断
            val first = request.referenceImages.take(_SUBJECT_REF_LIMIT).first()
            val file = File(first.path)
            if (!file.exists()) {
                error("minimax 参考图文件不可读: ${first.path}")
            }
            val dataUri = ImageCodec.toDataUri(file)
            val subjectRef = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "character")
                    addProperty("image_file", dataUri)
                })
            }
            root.add("subject_reference", subjectRef)
        }
        return root.toString()
    }

    /**
     * 按「比例优先、清晰度其次」算出 (宽, 高)。
     *
     * 比例永远来自 aspectRatio；imageSize 只决定清晰度短边。短边先夹 `>=512`，
     * 结果被 8 整除、长边受 2048 收口，每边再经 [clampEdge] 夹进 `[512, 2048]` 兜底
     * （[512, 2048] 上下限等价 max_ratio=4.0 约束：2048/512=4.0）。
     */
    internal fun resolveDimensions(request: ImageGenerationRequest): Pair<Int, Int> {
        val short = resolutionToShortEdge(request.imageSize).coerceAtLeast(MIN_EDGE)  // 先夹 >= 512
        val (w, h) = aspectSize(request.aspectRatio, short)
        return clampEdge(w) to clampEdge(h)  // 每边再夹 [512, 2048]
    }

    private fun aspectSize(aspectRatio: String, shortEdge: Int): Pair<Int, Int> {
        val (aw, ah) = parseAspectRatio(aspectRatio)
        // max_ratio=4.0 约束由 clampEdge([512,2048]) 兜底（2048/512=4.0）
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

    private fun clampEdge(edge: Int): Int = edge.coerceIn(MIN_EDGE, MAX_EDGE)

    /**
     * 把分辨率规范化成「短边像素」。
     *
     * - null/空 → 1440（2K 档默认）
     * - 档位词（512px/1K/2K/4K，大小写不敏感）→ 查表
     * - 自定义 "宽x高"（分隔符 x、X、×、星号）→ min(宽,高)，剥离自带比例
     * - 纯数字 → 直接当短边
     * - 无法解析 → 1440
     */
    internal fun resolutionToShortEdge(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return DEFAULT_SHORT
        val s = resolution.trim()
        IMAGE_TIER_SHORT_EDGE[s.lowercase()]?.let { return it }
        // 自定义 "宽x高"
        val m = WH_RE.matchEntire(s)
        if (m != null) {
            val w = m.groupValues[1].toIntOrNull() ?: return DEFAULT_SHORT
            val h = m.groupValues[2].toIntOrNull() ?: return DEFAULT_SHORT
            return minOf(w, h)
        }
        // 纯数字
        s.toIntOrNull()?.let { return it }
        return DEFAULT_SHORT
    }

    /** 归一化 base URL：剥 `/v1` 后缀后补 `/v1`；空回落海螺 host。 */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        if (base.endsWith("/v1")) base = base.dropLast(3)
        return base + "/v1"
    }

    /**
     * `base_resp.status_code != 0` → 错误描述；0/缺失 → null。
     *
     * MiniMax 错误形态：`{base_resp:{status_code, status_msg}}`。
     */
    internal fun baseRespError(root: JsonObject): String? {
        val base = root.getAsJsonObject("base_resp") ?: return null
        val code = base.get("status_code")?.let { runCatching { it.asInt }.getOrNull() } ?: return null
        if (code != 0) {
            val msg = base.get("status_msg")?.asString ?: ""
            return "minimax base_resp status_code=$code: $msg".trim()
        }
        return null
    }

    /**
     * 把 image_generation 响应落地为本地文件。
     *
     * 1. 先查 base_resp.status_code != 0 抛错
     * 2. 优先 `$.data.image_urls[0]` 下载；URL 缺失或下载失败降级 base64
     * 3. URL 缺失降级 `$.data.image_base64` 解码写盘（容忍 data URI 前缀）
     * 4. 两者皆空报错
     */
    private suspend fun persistImage(respBody: String, outputPath: File) {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("minimax image 响应非 JSON：$respBody") }
        baseRespError(root)?.let { error(it) }

        val data = root.getAsJsonObject("data")
        val url = data?.getAsJsonArray("image_urls")?.firstOrNull()?.asString
        if (!url.isNullOrBlank()) {
            runCatching { VideoBackendHttp.downloadVideo(url, outputPath) }
                .onSuccess { return }
                .onFailure { /* 降级到 image_base64 */ }
        }
        val b64 = data?.get("image_base64")?.asString
        if (!b64.isNullOrBlank()) {
            writeBase64Image(b64, outputPath)
            return
        }
        error("minimax image 响应缺少 image_urls/image_base64：$respBody")
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
        private const val DEFAULT_MODEL = "image-01"
        private const val DEFAULT_BASE = "https://api.minimaxi.com/v1"
        private const val IMAGE_ENDPOINT = "/image_generation"

        private const val ROUND_TO = 8
        private const val MAX_LONG_EDGE = 2048
        private const val MIN_EDGE = 512
        private const val MAX_EDGE = 2048
        private const val DEFAULT_SHORT = 1440

        // 单脸参考上限：subject_reference 仅取首张
        internal const val _SUBJECT_REF_LIMIT = 1

        // 图片档位 → 短边像素（与 AgnesImageBackend 一致）
        internal val IMAGE_TIER_SHORT_EDGE: Map<String, Int> = mapOf(
            "512px" to 512, "1k" to 1024, "2k" to 1440, "4k" to 2160
        )

        // 自定义 "宽x高" 分隔符：英文 x、X、星号、全角叉号
        internal val WH_RE = Regex("""^\s*(\d+)\s*[xX×*]\s*(\d+)\s*$""")

        init {
            // 注册到 Registry（P3c）
            ImageBackendRegistry.register(AiImageProviderConfig.TYPE_MINIMAX) { cfg -> MiniMaxImageBackend(cfg) }
        }
    }
}
