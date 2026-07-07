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
 * Kling（可灵）图像后端（移植 ArcReel `image_backends/kling.py` + `kling_shared.py`）。
 *
 * 走可灵原生图像端点：submit `POST /v1/images/generations` 取 `$.data.task_id` →
 * 轮询 `GET /v1/images/generations/{task_id}` 至 `task_status=succeed` 取
 * `$.data.task_result.images[0].url` → 下载本地。
 *
 * - 鉴权：Bearer（`Authorization: Bearer {apiKey}`）
 * - 默认 model：`kling-image-o1`；默认 base：`https://api.klingai.com`
 * - 请求体：`{model_name, prompt, aspect_ratio, n:1, image?:[]}`（T2I 与 I2I 共用，I2I 多 image 数组）
 *   - **不下传 resolution**
 *   - **参考图编码：裸 base64 列表**（[ImageCodec.toBareBase64]，无 `data:` 前缀）
 *   - 参考图上限 10 张，超限截断；任一不可读即 fail-loud
 * - 响应：submit 顶层 `code != 0` 抛错（code 可能 int 或 string，归一化 int 比较）；
 *   poll succeed 后取 `$.data.task_result.images[0].url` 下载；**无 usage token**
 * - 能力：恒 `{T2I, I2I}`
 *
 * poll 间隔 5s、max wait 600s 硬编码（image config 无 validPollInterval）。
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class KlingImageBackend(private val cfg: AiImageProviderConfig) : ImageBackend {

    override val typeId: String = AiImageProviderConfig.TYPE_KLING
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<ImageCapability> = setOf(
        ImageCapability.TEXT_TO_IMAGE,
        ImageCapability.IMAGE_TO_IMAGE
    )

    override suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult {
        val client = buildClient(cfg.validTimeout())
        val body = buildPayload(request)
        val base = normalizeBaseUrl(cfg.baseUrl)
        val submitUrl = base + SUBMIT_PATH

        val taskId = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseTaskId(resp) }

        val imageUrl = pollUntilDone(client, taskId, base)
        VideoBackendHttp.downloadVideo(imageUrl, request.outputPath)
        return ImageGenerationResult(
            imagePath = request.outputPath,
            provider = typeId,
            model = model,
            usageTokens = null  // kling image 无 usage token
        )
    }

    /**
     * 构造 Kling 图像请求体。
     *
     * - `{model_name, prompt, aspect_ratio, n:1, image?:[]}`（T2I 与 I2I 共用）
     * - model_name 直接用 [cfg.model]（或默认值）
     * - **不下传 resolution**
     * - I2I 多 `image` 数组：**裸 base64 列表**（[ImageCodec.toBareBase64]，无 `data:` 前缀），
     *   参考图上限 10 张超限截断；任一不可读即 fail-loud
     */
    internal fun buildPayload(request: ImageGenerationRequest): String {
        val root = JsonObject().apply {
            addProperty("model_name", model)
            addProperty("prompt", request.prompt)
            addProperty("aspect_ratio", request.aspectRatio)
            addProperty("n", 1)
        }
        if (request.referenceImages.isNotEmpty()) {
            val arr = JsonArray()
            encodeReferences(request.referenceImages).forEach { arr.add(it) }
            root.add("image", arr)
        }
        return root.toString()
    }

    /**
     * 参考图 → 裸 base64 列表（截断到 10 张上限）。
     *
     * fail-loud：保留集中任一文件不存在/不可读抛 `error("kling 参考图文件不可读: $path")`。
     */
    private fun encodeReferences(refs: List<ReferenceImage>): List<String> {
        if (refs.isEmpty()) return emptyList()
        val limited = if (refs.size > MAX_REFERENCE_IMAGES) refs.take(MAX_REFERENCE_IMAGES) else refs
        return limited.map { ref ->
            val file = File(ref.path)
            if (!file.exists() || !file.canRead()) {
                error("kling 参考图文件不可读: ${ref.path}")
            }
            ImageCodec.toBareBase64(file)
        }
    }

    /** 归一化 base URL：去尾斜杠；空回落可灵默认 base。 */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        return base
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

    private fun parseTaskId(resp: Response): String {
        val respBody = resp.body?.string() ?: error("kling image submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("kling image submit 响应非 JSON：$respBody") }
        // 顶层 code 硬错误（鉴权失败/参数非法）优先暴露
        responseError(root)?.let { error(it) }
        val taskId = root.getAsJsonObject("data")?.get("task_id")?.takeIf { !it.isJsonNull }?.asString
            ?: error("kling image submit 响应缺 data.task_id：$respBody")
        return taskId
    }

    /** 顶层 code != 0 → 错误描述；0/缺失 → null。bearer/中转可能把 code 序列化成字符串，归一化 int 比较。 */
    internal fun responseError(root: JsonObject): String? {
        val codeEl = root.get("code") ?: return null
        val code: Int? = runCatching { codeEl.asInt }.getOrNull()
            ?: runCatching { codeEl.asString.trim().toInt() }.getOrNull()
        if (code == null) {
            return "Kling API code=${codeEl}: ${root.get("message")}"
        }
        return if (code != 0) "Kling API code=$code: ${root.get("message")}" else null
    }

    private suspend fun pollUntilDone(client: OkHttpClient, taskId: String, base: String): String {
        val pollUrl = base + POLL_PATH.replace("{taskId}", taskId)
        val result = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            isDone = { it.status == "succeed" },
            isFailed = { it.status == "failed" },
            pollInterval = POLL_INTERVAL,
            maxWait = MAX_WAIT,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "kling image poll taskId=$taskId",
            onProgress = {}  // image 接口无 onProgress
        )
        return result.imageUrl ?: error("kling image poll succeed 但无 image url：${result.raw}")
    }

    private fun doPoll(client: OkHttpClient, url: String): KlingPollResult {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("kling image poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("kling image poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("kling image poll 响应非 JSON：$respBody") }
        responseError(root)?.let { error(it) }
        val status = root.getAsJsonObject("data")?.get("task_status")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
        val imageUrl = root.getAsJsonObject("data")?.getAsJsonObject("task_result")
            ?.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.get("url")?.takeIf { !it.isJsonNull }?.asString
        return KlingPollResult(status, imageUrl, respBody)
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private data class KlingPollResult(val status: String, val imageUrl: String?, val raw: String)

    companion object {
        private const val DEFAULT_MODEL = "kling-image-o1"
        private const val DEFAULT_BASE = "https://api.klingai.com"
        private const val SUBMIT_PATH = "/v1/images/generations"
        private const val POLL_PATH = "/v1/images/generations/{taskId}"

        internal const val MAX_REFERENCE_IMAGES = 10

        private val POLL_INTERVAL = 5.seconds
        private val MAX_WAIT = 600.seconds

        init {
            // 注册到 Registry（P3c）
            ImageBackendRegistry.register(AiImageProviderConfig.TYPE_KLING) { cfg -> KlingImageBackend(cfg) }
        }
    }
}
