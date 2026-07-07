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
import kotlin.time.Duration.Companion.seconds

/**
 * Vidu（生数科技）图像后端（移植 ArcReel `image_backends/vidu.py` + `vidu_shared.py`）。
 *
 * 走 Vidu 开放平台单一图像端点（T2I 与 I2I 共用）：submit `POST /reference2image` 取
 * `$.task_id` → 轮询 `GET /tasks/{task_id}/creations` 至 `state=success` 取
 * `$.creations[0].url` → 下载本地。
 *
 * - 鉴权：**`Authorization: Token {apiKey}`**（Vidu 用 `Token` 前缀，非 `Bearer`）
 * - 默认 model：`viduq2`；默认 base：`https://api.vidu.cn/ent/v2`
 * - 请求体：`{model, prompt, images?:[], aspect_ratio?, resolution?, seed?}`
 *   - T2I 与 I2I 共用，I2I 多 `images` 数组
 *   - prompt 截断到 2000 字符
 *   - `aspect_ratio` / `resolution` 经白名单校验（不在白名单丢弃）
 * - **参考图编码：data URI 列表**（[ImageCodec.toDataUri]），参考图上限 7 张超限截断
 * - 响应：submit 缺 `$.task_id` 抛错；顶层 `$.credits` 兜底取 usage token（credits=0 合法）；
 *   poll `state=success` 终态、`state=failed` 失败；succeed 后 `$.creations[0].url` 下载
 * - 能力（模型驱动）：`viduq2` → `{T2I, I2I}`；`viduq1` → `{I2I}`（仅图生图）；
 *   未登记 model 回落 `{T2I, I2I}`；generate 开头校验 has_refs 与能力匹配，不匹配抛错
 *
 * poll 间隔 3s、max wait 600s 硬编码（image config 无 validPollInterval）。
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class ViduImageBackend(private val cfg: AiImageProviderConfig) : ImageBackend {

    override val typeId: String = AiImageProviderConfig.TYPE_VIDU
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<ImageCapability> = resolveCaps(model)

    override suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult {
        // 能力校验：has_refs 与能力匹配，不匹配抛错
        val hasRefs = request.referenceImages.isNotEmpty()
        if (!hasRefs && ImageCapability.TEXT_TO_IMAGE !in capabilities) {
            error("vidu model=$model 仅支持图生图，未提供参考图（mismatch_no_t2i）")
        }
        if (hasRefs && ImageCapability.IMAGE_TO_IMAGE !in capabilities) {
            error("vidu model=$model 不支持图生图，但提供了参考图（mismatch_no_i2i）")
        }

        val client = buildClient(cfg.validTimeout())
        val body = buildPayload(request)
        val base = normalizeBaseUrl(cfg.baseUrl)
        val submitUrl = base + SUBMIT_PATH

        val (taskId, submitCredits) = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseTaskId(resp) }

        val (imageUrl, pollCredits) = pollUntilDone(client, taskId, base)
        VideoBackendHttp.downloadVideo(imageUrl, request.outputPath)

        // credits：poll 优先，兜底 submit 顶层 credits；credits=0 合法，null 不填
        val actualCredits = pollCredits ?: submitCredits
        return ImageGenerationResult(
            imagePath = request.outputPath,
            provider = typeId,
            model = model,
            usageTokens = actualCredits
        )
    }

    /**
     * 构造 Vidu 图像请求体。
     *
     * - `{model, prompt, images?:[], aspect_ratio?, resolution?, seed?}`
     * - prompt 截断到 2000 字符
     * - I2I 多 `images` 数组：**data URI 列表**（[ImageCodec.toDataUri]），参考图上限 7 张超限截断
     * - `aspect_ratio` / `resolution` 经白名单校验（不在白名单丢弃）
     */
    internal fun buildPayload(request: ImageGenerationRequest): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt.take(PROMPT_MAX))
        }
        if (request.referenceImages.isNotEmpty()) {
            val arr = JsonArray()
            encodeReferences(request.referenceImages).forEach { arr.add(it) }
            root.add("images", arr)
        }
        // aspect_ratio 白名单校验（不在白名单丢弃）
        if (request.aspectRatio.isNotBlank() && request.aspectRatio in aspectWhitelist()) {
            root.addProperty("aspect_ratio", request.aspectRatio)
        }
        // resolution 白名单校验（不在白名单丢弃）；resolution 取自 imageSize
        val resolution = request.imageSize
        if (!resolution.isNullOrBlank() && resolution in resolutionWhitelist()) {
            root.addProperty("resolution", resolution)
        }
        request.seed?.let { root.addProperty("seed", it) }
        return root.toString()
    }

    /**
     * 参考图 → data URI 列表（截断到 7 张上限）。
     */
    private fun encodeReferences(refs: List<ReferenceImage>): List<String> {
        if (refs.isEmpty()) return emptyList()
        val limited = if (refs.size > MAX_REFERENCE_IMAGES) refs.take(MAX_REFERENCE_IMAGES) else refs
        return limited.map { ref -> ImageCodec.toDataUri(File(ref.path)) }
    }

    /**
     * 模型驱动能力（官方一手核实）：
     * - `viduq2` → `{T2I, I2I}`
     * - `viduq1` → `{I2I}`（仅图生图）
     * - 未登记 model 回落 `{T2I, I2I}`
     */
    internal fun resolveCaps(modelId: String): Set<ImageCapability> {
        val m = modelId.trim().lowercase()
        return when (m) {
            "viduq1" -> setOf(ImageCapability.IMAGE_TO_IMAGE)
            "viduq2" -> setOf(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_TO_IMAGE)
            else -> setOf(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_TO_IMAGE)
        }
    }

    /** aspect_ratio 白名单：viduq1 取 [VIDUQ1_ASPECT_RATIOS]，其余取 [VIDUQ2_ASPECT_RATIOS]。 */
    internal fun aspectWhitelist(): Set<String> =
        if (model.trim().lowercase() == "viduq1") VIDUQ1_ASPECT_RATIOS else VIDUQ2_ASPECT_RATIOS

    /** resolution 白名单：viduq1 取 [VIDUQ1_RESOLUTIONS]，其余取 [VIDUQ2_RESOLUTIONS]。 */
    internal fun resolutionWhitelist(): Set<String> =
        if (model.trim().lowercase() == "viduq1") VIDUQ1_RESOLUTIONS else VIDUQ2_RESOLUTIONS

    /** 归一化 base URL：补 https:// + 去尾斜杠；空回落 Vidu 开放平台 base。 */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
        return base
    }

    private fun doSubmit(client: OkHttpClient, url: String, body: String): Response {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Token ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute()
    }

    /** 解析 submit 响应：返回 (task_id, 顶层 credits 兜底)。缺 task_id 抛错。 */
    private fun parseTaskId(resp: Response): Pair<String, Long?> {
        val respBody = resp.body?.string() ?: error("vidu image submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("vidu image submit 响应非 JSON：$respBody") }
        val taskId = root.get("task_id")?.takeIf { !it.isJsonNull }?.asString
            ?: error("vidu image submit 响应缺 task_id：$respBody")
        val credits = extractCredits(root)
        return taskId to credits
    }

    private suspend fun pollUntilDone(client: OkHttpClient, taskId: String, base: String): Pair<String, Long?> {
        val pollUrl = base + POLL_PATH.replace("{taskId}", taskId)
        val result = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            isDone = { it.state == STATE_SUCCESS },
            isFailed = { it.state == STATE_FAILED },
            pollInterval = POLL_INTERVAL,
            maxWait = MAX_WAIT,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "vidu image poll taskId=$taskId",
            onProgress = {}  // image 接口无 onProgress
        )
        if (result.state == STATE_FAILED) {
            error("vidu image 任务失败 state=failed：${result.raw}")
        }
        val imageUrl = result.imageUrl ?: error("vidu image poll success 但无 creations[0].url：${result.raw}")
        return imageUrl to result.credits
    }

    private fun doPoll(client: OkHttpClient, url: String): ViduPollResult {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Token ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("vidu image poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("vidu image poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("vidu image poll 响应非 JSON：$respBody") }
        val state = root.get("state")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
        val imageUrl = root.getAsJsonArray("creations")?.firstOrNull()?.asJsonObject?.get("url")?.takeIf { !it.isJsonNull }?.asString
        val credits = extractCredits(root)
        return ViduPollResult(state, imageUrl, credits, respBody)
    }

    /** 提取顶层 credits：缺失/null → null；credits=0 合法（返回 0L）。 */
    private fun extractCredits(root: JsonObject): Long? {
        val el = root.get("credits") ?: return null
        if (el.isJsonNull) return null
        return runCatching { el.asLong }.getOrNull()
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private data class ViduPollResult(
        val state: String,
        val imageUrl: String?,
        val credits: Long?,
        val raw: String
    )

    companion object {
        private const val DEFAULT_MODEL = "viduq2"
        private const val DEFAULT_BASE = "https://api.vidu.cn/ent/v2"
        private const val SUBMIT_PATH = "/reference2image"
        private const val POLL_PATH = "/tasks/{taskId}/creations"

        private const val STATE_SUCCESS = "success"
        private const val STATE_FAILED = "failed"

        internal const val MAX_REFERENCE_IMAGES = 7
        private const val PROMPT_MAX = 2000

        // viduq2 aspect_ratio / resolution 白名单
        internal val VIDUQ2_ASPECT_RATIOS: Set<String> =
            setOf("16:9", "9:16", "1:1", "3:4", "4:3", "21:9", "2:3", "3:2", "auto")
        internal val VIDUQ2_RESOLUTIONS: Set<String> = setOf("1080p", "2K", "4K")

        // viduq1 aspect_ratio / resolution 白名单
        internal val VIDUQ1_ASPECT_RATIOS: Set<String> =
            setOf("16:9", "9:16", "1:1", "3:4", "4:3")
        internal val VIDUQ1_RESOLUTIONS: Set<String> = setOf("1080p")

        private val POLL_INTERVAL = 3.seconds
        private val MAX_WAIT = 600.seconds

        init {
            // 注册到 Registry（P3c）
            ImageBackendRegistry.register(AiImageProviderConfig.TYPE_VIDU) { cfg -> ViduImageBackend(cfg) }
        }
    }
}
