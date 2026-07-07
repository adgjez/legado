package io.legado.app.help.ai.backends.image

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
import java.util.Base64 as JvmBase64
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Agnes 图像后端（移植 ArcReel `image_backends/agnes.py` + `agnes_shared.py`）。
 *
 * 走 apihub 网关上的 OpenAI 兼容 `/images/generations` 同步端点：单次 POST 直接返回图片
 * URL 或 base64，立即落地为本地资产。T2I 与 I2I 共用同一端点，I2I 把参考图随请求体下发。
 *
 * - 端点：POST `{base}/images/generations`（base 归一化为 `{host}/v1`）
 * - 请求体：`{model, prompt, n:1, size:"{w}x{h}", image?:[data URI 列表]}`
 * - **参考图编码：data URI 列表**（[ImageCodec.toDataUri]）——I2I 时 `image` 字段为 data URI 数组
 * - 尺寸：aspect_size（比例优先、清晰度其次，8 整除，长边 2048 收口，默认短边 1440 即 2K 档）
 * - 响应：`data[0].url` 优先下载；url 缺失/下载失败降级 `data[0].b64_json` 解码写盘；两者皆空报错
 * - 鉴权：Bearer
 * - 不发 response_format（上游 litellm 网关对该参数报 UnsupportedParamsError）
 *
 * 单步同步（无 poll）。生命周期 1a 忠实：[generate] 自管 submit+persist。
 */
class AgnesImageBackend(private val cfg: AiImageProviderConfig) : ImageBackend {

    override val typeId: String = AiImageProviderConfig.TYPE_AGNES
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
        ).use { resp -> resp.body?.string() ?: error("agnes image submit 响应体为空") }

        persistImage(respBody, request.outputPath)
        return ImageGenerationResult(
            imagePath = request.outputPath,
            provider = typeId,
            model = model,
            usageTokens = extractUsageTokens(respBody)
        )
    }

    /**
     * 构造 Agnes 图像请求体。
     *
     * - `{model, prompt, n:1, size:"{w}x{h}"}`
     * - I2I：参考图 → `image` 字段（data URI 列表）
     * - 不发 response_format（上游 litellm 网关报 UnsupportedParamsError）
     */
    internal fun buildPayload(request: ImageGenerationRequest): String {
        val (width, height) = resolveDimensions(request)
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt)
            addProperty("n", 1)
            addProperty("size", "${width}x${height}")
        }
        if (request.referenceImages.isNotEmpty()) {
            val arr = JsonArray()
            request.referenceImages.forEach { ref ->
                arr.add(ImageCodec.toDataUri(File(ref.path)))
            }
            root.add("image", arr)
        }
        return root.toString()
    }

    /**
     * 按「比例优先、清晰度其次」算出 (宽, 高)。
     *
     * 比例永远来自 aspectRatio；imageSize（档位词 / 自定义 宽*高 / None）只决定清晰度短边，
     * 自定义值剥离其自带比例（取 min）。结果被 8 整除、长边受 2048 收口。
     * 移植 ArcReel aspect_size.py：合法尺寸 = (aw·roundTo·t, ah·roundTo·t)，
     * t 使短边最接近 shortEdge，再受 maxLongEdge 夹取，下限 t≥1。
     */
    internal fun resolveDimensions(request: ImageGenerationRequest): Pair<Int, Int> {
        val short = resolutionToShortEdge(request.imageSize)
        return aspectSize(request.aspectRatio, short)
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
     * - null/空 → 1440（2K 档默认）
     * - 档位词（512px/1K/2K/4K，大小写不敏感）→ 查表
     * - 自定义 "宽x高"（分隔符 x、X、×、星号）→ min(宽,高)，剥离自带比例
     * - 纯数字 → 直接当短边
     * - 无法解析 → 1440
     */
    internal fun resolutionToShortEdge(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return DEFAULT_SHORT
        val s = resolution.trim()
        val norm = IMAGE_TIER_SHORT_EDGE
        norm[s.lowercase()]?.let { return it }
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

    /** 归一化 base URL：剥 `/v1` 后缀后补 `/v1`；空回落 Agnes apihub 默认 base。 */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        if (base.endsWith(V1_SUFFIX)) base = base.dropLast(V1_SUFFIX.length)
        return base + V1_SUFFIX
    }

    /**
     * 把 images/generations 响应落地为本地文件。
     *
     * 优先 URL 下载；URL 缺失或下载失败时降级到同响应内 base64 解码写盘，
     * 避免一次已计费的成功生成因下载环节失败而无法落盘；两者皆空即报错。
     */
    private suspend fun persistImage(respBody: String, outputPath: File) {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("agnes image 响应非 JSON：$respBody") }
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
        error("agnes image 响应缺少 url/b64_json：$respBody")
    }

    private fun extractFirstStr(root: JsonObject, key: String): String? {
        val data = root.getAsJsonArray("data") ?: return null
        for (i in 0 until data.size()) {
            val item = data[i].asJsonObject
            // takeIf { !it.isJsonNull }：Gson 的 JsonNull.asString 抛 UnsupportedOperationException
            // （不是返回 null），对齐 ArcReel _extract_first_str 的 isinstance(value, str) 过滤
            val value = item.get(key)?.takeIf { !it.isJsonNull }?.asString
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun extractUsageTokens(respBody: String): Long? {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }.getOrNull() ?: return null
        val usage = root.getAsJsonObject("usage") ?: return null
        return runCatching {
            usage.get("total_tokens")?.takeIf { !it.isJsonNull }?.asLong
        }.getOrNull()
    }

    /** 解码 base64 图片并写盘。容忍 data URI 前缀（剥 `data:...;base64,` 再解码）。 */
    private fun writeBase64Image(b64: String, outputPath: File) {
        val payload = if (b64.startsWith("data:") && b64.contains(",")) {
            b64.substringAfter(",")
        } else b64
        // 用 java.util.Base64（脱离 Android 运行时 stub，对齐 ImageCodec 的编码侧修复）
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
        private const val DEFAULT_MODEL = "agnes-image-2.1-flash"
        private const val DEFAULT_BASE = "https://apihub.agnes-ai.com"
        private const val V1_SUFFIX = "/v1"
        private const val IMAGE_ENDPOINT = "/images/generations"

        private const val ROUND_TO = 8
        private const val MAX_LONG_EDGE = 2048
        private const val DEFAULT_SHORT = 1440

        // 图片档位 → 短边像素（移植 ArcReel IMAGE_TIER_SHORT_EDGE）
        internal val IMAGE_TIER_SHORT_EDGE: Map<String, Int> = mapOf(
            "512px" to 512, "1k" to 1024, "2k" to 1440, "4k" to 2160
        )

        // 自定义 "宽x高" 分隔符：英文 x、X、星号、全角叉号
        internal val WH_RE = Regex("""^\s*(\d+)\s*[xX×*]\s*(\d+)\s*$""")

        init {
            // 注册到 Registry（P3a）
            ImageBackendRegistry.register(AiImageProviderConfig.TYPE_AGNES) { cfg -> AgnesImageBackend(cfg) }
        }
    }
}
