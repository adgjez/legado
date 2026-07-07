package io.legado.app.help.ai.backends.video

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
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * NewAPI 视频后端（移植 ArcReel `video_backends/newapi.py`）。
 *
 * 对接 NewAPI 的 `/video/generations` 统一端点（靠请求体 `model` 字段分发 Sora/Kling/即梦/Wan/Veo 等）：
 * - submit `POST {baseUrl}/video/generations` 取 `$.task_id`
 * - poll `GET {baseUrl}/video/generations/{task_id}` 至 `status` ∈ {completed,failed,expired}
 * - 终态取 `$.url` 下载
 *
 * **参考图编码：data URI**（[ImageCodec.toDataUri]）——仅支持首帧（`image` 字段），
 * 多张参考图不支持（[videoCapabilities.referenceImages]=false），与 ArcReel 一致。
 *
 * 尺寸：比例优先、清晰度其次——短边来自 resolution（720/1080/4k，缺省 720），
 * 比例精确来自 aspectRatio，对齐 8 的倍数（主流视频模型通用；修复 1080 不被 16 整除）。
 *
 * expired 状态分流：generate 路径抛 IllegalStateException；resume 路径抛 [ResumeExpiredError]。
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class NewApiVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_NEWAPI
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
            submitPollDownload(request, compressed, onProgress, isResume = false)
        }
    }

    override suspend fun resumeVideo(jobId: String, request: VideoGenerationRequest): VideoGenerationResult {
        val client = buildClient(cfg.validPollTimeout())
        return pollAndBuild(client, jobId, request, isResume = true)
    }

    private suspend fun submitPollDownload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>,
        onProgress: (VideoProgress) -> Unit,
        isResume: Boolean
    ): VideoGenerationResult {
        val client = buildClient(cfg.validSubmitTimeout())
        val body = buildSubmitBody(request, compressed)
        val submitUrl = cfg.submitUrl.ifBlank { cfg.baseUrl.trimEnd('/') + SUBMIT_PATH }

        val taskId = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseTaskId(resp) }

        onProgress(VideoProgress("submitted", "taskId=$taskId"))
        return pollAndBuild(client, taskId, request, isResume = isResume, onProgress = onProgress)
    }

    /**
     * 构造 NewAPI 请求体。
     *
     * - `{model, prompt, width, height, duration(int), n:1, seed?, image?}`
     * - 首帧（startImage）→ `image` 字段（data URI）
     * - 参考图（referenceImages）→ 不支持，忽略（[videoCapabilities.referenceImages]=false）
     */
    internal fun buildSubmitBody(request: VideoGenerationRequest, compressed: List<CompressedRef>): String {
        val (width, height) = resolveSize(request.resolution, request.aspectRatio)
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt)
            addProperty("width", width)
            addProperty("height", height)
            addProperty("duration", request.durationSeconds)
            addProperty("n", 1)
        }
        request.seed?.let { root.addProperty("seed", it) }
        compressed.firstOrNull { it.label == "first_frame" }?.let { ff ->
            root.addProperty("image", ImageCodec.toDataUri(ff.path))
        }
        return root.toString()
    }

    /**
     * 比例优先、清晰度其次：短边来自 resolution（720/1080/4k，缺省 720），
     * 比例精确来自 aspectRatio，对齐 8 的倍数。
     */
    internal fun resolveSize(resolution: String?, aspectRatio: String): Pair<Int, Int> {
        val short = resolutionToShortEdge(resolution)
        val (rw, rh) = parseAspectRatio(aspectRatio)
        // 短边对齐短轴，长边按比例放大，整体 round 到 8 的倍数
        return if (rw >= rh) {
            // 横屏：宽是长边
            val h = short
            val w = (short.toDouble() * rw / rh).roundToInt().roundTo8()
            w to h.roundTo8()
        } else {
            val w = short
            val h = (short.toDouble() * rh / rw).roundToInt().roundTo8()
            w.roundTo8() to h
        }
    }

    private fun resolutionToShortEdge(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return 720
        return when (resolution.lowercase().trim().trimEnd('p')) {
            "720" -> 720
            "1080" -> 1080
            "4k", "2160" -> 2160
            else -> resolution.toIntOrNull() ?: 720
        }
    }

    private fun parseAspectRatio(ratio: String): Pair<Int, Int> {
        val parts = ratio.split(":")
        if (parts.size != 2) return 9 to 16
        return (parts[0].toIntOrNull() ?: 9) to (parts[1].toIntOrNull() ?: 16)
    }

    private fun Int.roundTo8(): Int = (this + 4) / 8 * 8

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
        val respBody = resp.body?.string() ?: error("newapi submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("newapi submit 响应非 JSON：$respBody") }
        val taskId = root.get("task_id")?.asString
            ?: error("newapi submit 响应缺 task_id：$respBody")
        return taskId
    }

    private suspend fun pollAndBuild(
        client: OkHttpClient,
        taskId: String,
        request: VideoGenerationRequest,
        isResume: Boolean,
        onProgress: (VideoProgress) -> Unit = {}
    ): VideoGenerationResult {
        val pollUrl = cfg.resolvePollUrl(taskId).ifBlank {
            cfg.baseUrl.trimEnd('/') + POLL_PATH.replace("{taskId}", taskId)
        }
        val maxWait = maxOf(600L, request.durationSeconds * 30L).seconds
        val interval = cfg.validPollInterval().milliseconds.coerceAtLeast(1.seconds)

        val final = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            isDone = { it.status in DONE_STATUSES },
            isFailed = { it.status == "failed" },
            pollInterval = interval,
            maxWait = maxWait,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "newapi poll taskId=$taskId",
            onProgress = onProgress
        )

        if (final.status == "expired") {
            // expired 分流：generate 抛 IllegalStateException；resume 抛 ResumeExpiredError
            if (isResume) throw ResumeExpiredError(jobId = taskId, provider = typeId)
            error("newapi 任务已过期：$taskId")
        }

        val videoUrl = final.url ?: error("newapi 任务完成但缺 url：${final.raw}")
        VideoBackendHttp.downloadVideo(videoUrl, request.outputPath)

        val meta = final.metadata
        val durationSeconds = meta?.duration?.toIntOrNull() ?: request.durationSeconds
        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = durationSeconds,
            videoUri = videoUrl,
            taskId = taskId,
            seed = meta?.seed
        )
    }

    private fun doPoll(client: OkHttpClient, url: String): NewApiPollResult {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("newapi poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("newapi poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("newapi poll 响应非 JSON：$respBody") }
        val status = root.get("status")?.asString ?: "unknown"
        val url = runCatching { root.get("url")?.asString }.getOrNull()
        val metadata = root.getAsJsonObject("metadata")?.let {
            NewApiMetadata(
                duration = it.get("duration")?.asString,
                seed = runCatching { it.get("seed")?.asLong }.getOrNull()
            )
        }
        return NewApiPollResult(status, url, metadata, respBody)
    }

    private fun buildReferenceSpecs(request: VideoGenerationRequest): List<ReferenceSpec> {
        // NewAPI 仅支持首帧，多张参考图不支持（ArcReel 原样忽略 reference_images）
        val specs = mutableListOf<ReferenceSpec>()
        request.startImage?.let { specs.add(ReferenceSpec(it, "first_frame", RefRole.FRAME)) }
        return specs
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private data class NewApiPollResult(
        val status: String, val url: String?, val metadata: NewApiMetadata?, val raw: String
    )

    private data class NewApiMetadata(val duration: String?, val seed: Long?)

    companion object {
        private const val DEFAULT_MODEL = "kling-v1"
        private const val SUBMIT_PATH = "/video/generations"
        private const val POLL_PATH = "/video/generations/{taskId}"
        private val DONE_STATUSES = setOf("completed", "failed", "expired")

        /**
         * 参考图级能力（移植 ArcReel newapi.py）：仅 first_frame=true，
         * 无尾帧/参考图/叠加（reference_images=false, max=0, with_start_frame=false）。
         */
        fun videoCapabilitiesForModel(@Suppress("UNUSED_PARAMETER") model: String): VideoCapabilities {
            return VideoCapabilities(
                firstFrame = true,
                lastFrame = false,
                referenceImages = false,
                maxReferenceImages = 0,
                referenceImagesWithStartFrame = false
            )
        }

        init {
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_NEWAPI) { cfg -> NewApiVideoBackend(cfg) }
        }
    }
}
