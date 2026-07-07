package io.legado.app.help.ai.backends.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.MediaGenerator
import io.legado.app.help.ai.backends.VideoBackend
import io.legado.app.help.ai.backends.VideoBackendHttp
import io.legado.app.help.ai.backends.VideoBackendRegistry
import io.legado.app.help.ai.backends.VideoCapability
import io.legado.app.help.ai.backends.VideoCapabilities
import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.help.ai.backends.VideoGenerationResult
import io.legado.app.help.ai.backends.VideoProgress
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.ImageCodec
import io.legado.app.help.ai.backends.compress.PayloadLimits
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.help.ai.backends.compress.ReferenceSpec
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Grok（xAI）视频后端（移植 ArcReel `video_backends/grok.py` + `grok_shared.py`）。
 *
 * ArcReel 走 xAI SDK 的 `video.generate`（同步型，SDK 内部封装 submit+poll）；
 * Kotlin 侧无等价 SDK，按 spec 设计表实现 REST 等价：submit `POST /v1/video_generations`
 * 取 `$.id` → 轮询 `GET /v1/video_generations/{id}` 至 `status=completed` 取 `$.url` → 下载本地。
 *
 * **参考图编码：data URI**（[ImageCodec.toDataUri]）——`image_url`（首帧）与
 * `reference_image_urls`（参考图数组）均用 data URI（对齐 ArcReel `_encode_to_data_uri`）。
 *
 * 关键纪律：首帧（`image_url`）与参考图（`reference_image_urls`）**可叠加**
 * （[videoCapabilities.referenceImagesWithStartFrame]=true）——xAI 以独立形参同时下传两者。
 *
 * 请求体：`{model, prompt, duration, aspect_ratio, resolution?, image_url?, reference_image_urls?}`
 *
 * resume 不支持（同步型 API，无 job_id 可接续，与 ArcReel 一致）。
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class GrokVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_GROK
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<VideoCapability> = setOf(
        VideoCapability.TEXT_TO_VIDEO,
        VideoCapability.IMAGE_TO_VIDEO
    )
    override val videoCapabilities: VideoCapabilities
        get() = videoCapabilitiesForModel(model)

    override suspend fun generate(
        request: VideoGenerationRequest,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val refs = buildReferenceSpecs(request)
        val limits = PayloadLimits(totalMaxBytes = 8 * 1024 * 1024L, singleMaxBytes = 4 * 1024 * 1024L)
        return MediaGenerator.runWithReferenceCompression(refs, limits) { compressed ->
            submitPollDownload(request, compressed, onProgress)
        }
    }

    override suspend fun resumeVideo(jobId: String, request: VideoGenerationRequest): VideoGenerationResult {
        // 同步型 API，无 job_id 可接续（与 ArcReel 一致）
        throw NotImplementedError("GrokVideoBackend 不支持 resume_video（同步型 API）")
    }

    private suspend fun submitPollDownload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val client = buildClient(cfg.validSubmitTimeout())
        val body = buildPayload(request, compressed)
        val base = normalizeBaseUrl(cfg.baseUrl)
        val submitUrl = cfg.submitUrl.ifBlank { base + SUBMIT_PATH }

        val videoId = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseVideoId(resp) }

        onProgress(VideoProgress("submitted", "videoId=$videoId"))
        val maxWait = maxOf(900L, request.durationSeconds * 60L).seconds
        val interval = 5.seconds.coerceAtLeast(cfg.validPollInterval().milliseconds)
        val pollUrl = cfg.resolvePollUrl(videoId).ifBlank {
            base + POLL_PATH.replace("{videoId}", videoId)
        }

        val final = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            isDone = { it.status in DONE_STATUSES },
            isFailed = { it.status == "failed" },
            pollInterval = interval,
            maxWait = maxWait,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "grok poll videoId=$videoId",
            onProgress = onProgress
        )

        val videoUrl = final.url ?: error("grok 任务完成但缺 url：${final.raw}")
        VideoBackendHttp.downloadVideo(videoUrl, request.outputPath)

        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = request.durationSeconds,
            videoUri = videoUrl,
            taskId = videoId,
            generateAudio = request.generateAudio
        )
    }

    /**
     * 构造 Grok 请求体。
     *
     * - `{model, prompt, duration, aspect_ratio, resolution?}`
     * - 首帧（startImage）→ `image_url`（data URI）
     * - 参考图（referenceImages）→ `reference_image_urls`（data URI 数组）
     * - 两者可叠加（with_start_frame=true）：同时存在时两个字段都下传
     */
    internal fun buildPayload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>
    ): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt)
            addProperty("duration", request.durationSeconds)
            addProperty("aspect_ratio", request.aspectRatio)
        }
        request.resolution?.let { root.addProperty("resolution", it) }

        // 首帧 → image_url（data URI）；与参考图可叠加
        compressed.firstOrNull { it.label == "first_frame" }?.let { ff ->
            root.addProperty("image_url", ImageCodec.toDataUri(ff.path))
        }
        // 参考图 → reference_image_urls（data URI 数组）；超上限截断
        val refs = compressed.filter { it.label != "first_frame" && it.label != "last_frame" }
        if (refs.isNotEmpty()) {
            val limited = if (refs.size > MAX_REFERENCE_IMAGES) refs.take(MAX_REFERENCE_IMAGES) else refs
            val arr = JsonArray()
            limited.forEach { arr.add(ImageCodec.toDataUri(it.path)) }
            root.add("reference_image_urls", arr)
        }
        return root.toString()
    }

    /** 归一化 base URL：补 https:// + 去尾斜杠；空回落 xAI host。 */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
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

    private fun parseVideoId(resp: Response): String {
        val respBody = resp.body?.string() ?: error("grok submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("grok submit 响应非 JSON：$respBody") }
        val id = root.get("id")?.takeIf { !it.isJsonNull }?.asString
            ?: error("grok submit 响应缺 id：$respBody")
        return id
    }

    private fun doPoll(client: OkHttpClient, url: String): GrokPollResult {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("grok poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("grok poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("grok poll 响应非 JSON：$respBody") }
        val status = root.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
        val url = root.get("url")?.takeIf { !it.isJsonNull }?.asString
        return GrokPollResult(status, url, respBody)
    }

    private fun buildReferenceSpecs(request: VideoGenerationRequest): List<ReferenceSpec> {
        val specs = mutableListOf<ReferenceSpec>()
        // Grok 声明首帧 + 参考图叠加；尾帧不建模
        request.startImage?.let { specs.add(ReferenceSpec(it, "first_frame", RefRole.FRAME)) }
        request.referenceImages?.forEachIndexed { i, f ->
            specs.add(ReferenceSpec(f, "ref_$i", RefRole.ARRAY))
        }
        return specs
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private data class GrokPollResult(
        val status: String, val url: String?, val raw: String
    )

    companion object {
        private const val DEFAULT_MODEL = "grok-imagine-video"
        private const val DEFAULT_BASE = "https://api.x.ai"
        private const val SUBMIT_PATH = "/v1/video_generations"
        private const val POLL_PATH = "/v1/video_generations/{videoId}"
        private const val MAX_REFERENCE_IMAGES = 7
        private val DONE_STATUSES = setOf("completed", "failed")

        /**
         * 参考图级能力（移植 ArcReel grok.py）：reference_images=true, max=7,
         * with_start_frame=true（首帧 image_url 与参考图 reference_image_urls 可叠加）。
         */
        fun videoCapabilitiesForModel(@Suppress("UNUSED_PARAMETER") model: String): VideoCapabilities {
            return VideoCapabilities(
                firstFrame = true,
                lastFrame = false,
                referenceImages = true,
                maxReferenceImages = MAX_REFERENCE_IMAGES,
                referenceImagesWithStartFrame = true
            )
        }

        init {
            // 注册到 Registry（P2d）
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_GROK) { cfg -> GrokVideoBackend(cfg) }
        }
    }
}
