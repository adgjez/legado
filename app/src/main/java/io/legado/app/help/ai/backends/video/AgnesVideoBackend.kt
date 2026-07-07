package io.legado.app.help.ai.backends.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.AmbiguousSubmitError
import io.legado.app.help.ai.backends.compress.ImageCodec
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
 * Agnes 视频后端。
 *
 * 移植 ArcReel `video_backends/agnes.py`：
 * - 端点：POST `/v1/videos/generations`（提交），GET `/v1/videos/generations/{id}`（轮询）
 * - 请求体：`{model, prompt, width, height, num_frames, frame_rate, image?, extra_body:{image:[], mode?}}`
 * - **参考图编码：裸 base64**（[ImageCodec.toBareBase64]，剥 `data:` 前缀，NO_WRAP 无换行）
 *   ← 这是修复 `Incorrect padding` 的关键
 * - 响应：`$.id` → 轮询 → `$.video_url`
 *
 * 关键纪律（ArcReel 原话）：「带 `data:` 前缀会在生成期触发 padding 错误」。
 * Android 默认 `Base64.DEFAULT` 每 76 字符插换行也触发同样错误，故 [ImageCodec.toBareBase64]
 * 用 `Base64.NO_WRAP`。
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class AgnesVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_AGNES
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<VideoCapability> = setOf(
        VideoCapability.TEXT_TO_VIDEO,
        VideoCapability.IMAGE_TO_VIDEO,
        VideoCapability.GENERATE_AUDIO,
        VideoCapability.SEED_CONTROL
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
        val client = buildClient(cfg.validPollTimeout())
        val videoUrl = pollUntilDone(client, jobId) {}
        VideoBackendHttp.downloadVideo(videoUrl, request.outputPath)
        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = request.durationSeconds,
            videoUri = videoUrl,
            taskId = jobId
        )
    }

    private suspend fun submitPollDownload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val client = buildClient(cfg.validSubmitTimeout())
        val body = buildSubmitBody(request, compressed)
        val submitUrl = cfg.submitUrl.ifBlank { cfg.baseUrl.trimEnd('/') + SUBMIT_PATH }

        val taskId = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseTaskId(resp) }

        onProgress(VideoProgress("submitted", "taskId=$taskId"))
        val videoUrl = pollUntilDone(client, taskId, onProgress)
        VideoBackendHttp.downloadVideo(videoUrl, request.outputPath)
        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = request.durationSeconds,
            videoUri = videoUrl,
            taskId = taskId,
            generateAudio = request.generateAudio
        )
    }

    /**
     * 构造 Agnes 请求体。
     *
     * - 首帧（startImage）→ `image` 字段（裸 base64 字符串）
     * - 参考图（referenceImages）→ `extra_body.image` 数组（裸 base64 字符串列表）
     * - 尾帧（endImage）→ 不支持（agnes 无尾帧能力，理论上 videoCapabilities.lastFrame=true 但
     *   实际 API 不接受 image_tail；这里把 endImage 也塞 extra_body.image 尾部作为参考）
     */
    internal fun buildSubmitBody(request: VideoGenerationRequest, compressed: List<CompressedRef>): String {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("prompt", request.prompt)

        // 尺寸：aspectRatio → width/height
        val (w, h) = parseAspectRatio(request.aspectRatio)
        root.addProperty("width", w)
        root.addProperty("height", h)
        root.addProperty("num_frames", computeNumFrames(request.durationSeconds))
        root.addProperty("frame_rate", 24)

        // 首帧 → image 字段（裸 base64）
        val firstFrame = compressed.firstOrNull { it.label == "first_frame" }
        if (firstFrame != null) {
            root.addProperty("image", ImageCodec.toBareBase64(firstFrame.path))
        }

        // 参考图 + 尾帧 → extra_body.image 数组（裸 base64 列表）
        val refImages = compressed.filter { it.label != "first_frame" }
        if (refImages.isNotEmpty()) {
            val extraBody = JsonObject()
            val imageArr = JsonArray()
            refImages.forEach { ref ->
                imageArr.add(ImageCodec.toBareBase64(ref.path))
            }
            extraBody.add("image", imageArr)
            root.add("extra_body", extraBody)
        }

        // 可选参数
        request.seed?.let { root.addProperty("seed", it) }
        if (request.generateAudio) {
            // agnes 默认生成音频，extra_body 已有则合并
            val extra = root.getAsJsonObject("extra_body") ?: JsonObject().also { root.add("extra_body", it) }
            extra.addProperty("with_audio", true)
        }

        return root.toString()
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
        val respBody = resp.body?.string() ?: error("agnes submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("agnes submit 响应非 JSON：$respBody") }
        val id = root.get("id")?.asString
            ?: error("agnes submit 响应缺 id：$respBody")
        return id
    }

    private suspend fun pollUntilDone(
        client: OkHttpClient,
        taskId: String,
        onProgress: (VideoProgress) -> Unit
    ): String {
        val pollUrl = cfg.resolvePollUrl(taskId).ifBlank {
            cfg.baseUrl.trimEnd('/') + POLL_PATH.replace("{taskId}", taskId)
        }
        val maxWait = cfg.validPollTimeout().milliseconds
        val interval = cfg.validPollInterval().milliseconds.coerceAtLeast(1.seconds)

        val result = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            isDone = { it.status == "succeeded" },
            isFailed = { it.status in FAILED_STATUSES },
            pollInterval = interval,
            maxWait = maxWait,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "agnes poll taskId=$taskId",
            onProgress = onProgress
        )
        return result.videoUrl ?: error("agnes poll succeeded 但无 video_url：${result.raw}")
    }

    private fun doPoll(client: OkHttpClient, url: String): AgnesPollResult {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("agnes poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("agnes poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("agnes poll 响应非 JSON：$respBody") }
        val status = root.get("status")?.asString ?: "unknown"
        val videoUrl = runCatching { root.get("video_url")?.asString }.getOrNull()
        return AgnesPollResult(status, videoUrl, respBody)
    }

    private fun buildReferenceSpecs(request: VideoGenerationRequest): List<ReferenceSpec> {
        val specs = mutableListOf<ReferenceSpec>()
        request.startImage?.let { specs.add(ReferenceSpec(it, "first_frame", RefRole.FRAME)) }
        request.endImage?.let { specs.add(ReferenceSpec(it, "last_frame", RefRole.FRAME)) }
        request.referenceImages?.forEachIndexed { i, f ->
            specs.add(ReferenceSpec(f, "ref_$i", RefRole.ARRAY))
        }
        return specs
    }

    private fun parseAspectRatio(ratio: String): Pair<Int, Int> {
        val parts = ratio.split(":")
        if (parts.size != 2) return 1080 to 1920
        val w = parts[0].toIntOrNull() ?: 9
        val h = parts[1].toIntOrNull() ?: 16
        // 归一化到 1080 长边
        return when {
            w >= h -> (1080 to (1080 * h / w))
            else -> ((1080 * w / h) to 1080)
        }
    }

    private fun computeNumFrames(durationSeconds: Int): Int = (durationSeconds * 24).coerceIn(24, 240)

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private data class AgnesPollResult(val status: String, val videoUrl: String?, val raw: String)

    companion object {
        private const val DEFAULT_MODEL = "agnes-video-v2.0"
        private const val SUBMIT_PATH = "/v1/videos/generations"
        private const val POLL_PATH = "/v1/videos/generations/{taskId}"
        private val FAILED_STATUSES = setOf("failed", "cancelled", "error")

        /**
         * 参考图级能力（移植 ArcReel agnes.py）：all true, max=4, with_start_frame=false。
         */
        fun videoCapabilitiesForModel(@Suppress("UNUSED_PARAMETER") model: String): VideoCapabilities {
            return VideoCapabilities(
                firstFrame = true,
                lastFrame = true,
                referenceImages = true,
                maxReferenceImages = 4,
                referenceImagesWithStartFrame = false
            )
        }

        init {
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_AGNES) { cfg -> AgnesVideoBackend(cfg) }
        }
    }
}
