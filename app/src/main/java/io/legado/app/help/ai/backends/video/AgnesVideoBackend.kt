package io.legado.app.help.ai.backends.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.MediaGenerator
import io.legado.app.help.ai.backends.ResumeExpiredError
import io.legado.app.help.ai.backends.VideoBackend
import io.legado.app.help.ai.backends.VideoBackendHttp
import io.legado.app.help.ai.backends.VideoBackendRegistry
import io.legado.app.help.ai.backends.VideoCapability
import io.legado.app.help.ai.backends.VideoCapabilities
import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.help.ai.backends.VideoGenerationResult
import io.legado.app.help.ai.backends.VideoProgress
import io.legado.app.help.ai.backends.compress.AspectSize
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
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException

/**
 * Agnes 视频后端。
 *
 * 移植 ArcReel `video_backends/agnes.py`：
 * - base 归一化为 `{host}/v1`（[normalizeBaseUrl]，对齐 ArcReel `agnes_base_url`）
 * - 端点：POST `{base}/videos`（提交），GET `{base}/videos/{task_id}`（轮询）
 * - 请求体：`{model, prompt, width, height, num_frames, frame_rate, image?, extra_body:{image:[], mode?}}`
 * - **参考图编码：裸 base64**（[ImageCodec.toBareBase64]，剥 `data:` 前缀，无换行）
 *   ← 这是修复 `Incorrect padding` 的关键
 * - 响应：`$.task_id`（回落 `$.id`）→ 轮询至 `status=completed` → 成片 URL 取 `$.url`（回落 `$.remixed_from_video_id`）
 *
 * 关键纪律（ArcReel 原话）：「带 `data:` 前缀会在生成期触发 padding 错误」。
 * Android 默认 `Base64.DEFAULT` 每 76 字符插换行也触发同样错误，故 [ImageCodec.toBareBase64]
 * 用无换行 encoder（等价 `Base64.NO_WRAP`）。
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class AgnesVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_AGNES
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
        val client = buildClient(cfg.validPollTimeout())
        return try {
            val videoUrl = pollUntilDone(client, jobId) {}
            VideoBackendHttp.downloadVideo(videoUrl, request.outputPath)
            VideoGenerationResult(
                videoPath = request.outputPath,
                provider = typeId,
                model = model,
                durationSeconds = request.durationSeconds,
                videoUri = videoUrl,
                taskId = jobId,
                // Agnes 视频恒无声（ArcReel agnes.py:440 generate_audio=False）
                generateAudio = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // resume 路径：任务过期/不存在（404/task_not_found/expired）→ ResumeExpiredError
            // 便于 worker 区分「过期」与「真错误」（对齐共享层契约，同 Ark/DashScope/NewApi/Sora）
            if (isAgnesNotFound(e)) {
                throw ResumeExpiredError("Agnes 任务已过期或不存在（provider=$typeId, jobId=$jobId）：${e.message}")
            }
            throw e
        }
    }

    private suspend fun submitPollDownload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val client = buildClient(cfg.validSubmitTimeout())
        val body = buildSubmitBody(request, compressed)
        val base = normalizeBaseUrl(cfg.baseUrl)
        val submitUrl = cfg.submitUrl.ifBlank { base + SUBMIT_PATH }

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
            // Agnes 视频恒无声（ArcReel agnes.py:440 generate_audio=False）
            generateAudio = false
        )
    }

    /**
     * 构造 Agnes 请求体（对齐 ArcReel agnes.py:_build_payload）。
     *
     * Fail-loud 校验（照搬 ArcReel agnes.py:284-319）：
     * - duration ∉ [1,18] → error（防静默截帧+错记计费）
     * - 参考图 + 首/尾帧同时给 → error（agnes 不支持混合）
     * - 尾帧无首帧 → error
     * - 参考图 > 4 → error
     *
     * 参考图分发（对齐 ArcReel agnes.py:294-305）：
     * - 首帧 + 尾帧 → `extra_body.image=[start_b64, end_b64]` + `extra_body.mode="keyframes"`（不写顶层 image）
     * - 仅首帧 → 顶层 `image=<start_b64>`（字符串）
     * - 仅参考图 → `extra_body.image=[b64,...]`（数组，无 mode）
     *
     * Agnes 视频无音频能力：**不**下发 with_audio / generate_audio 字段（ArcReel 明确纪律）。
     */
    internal fun buildSubmitBody(request: VideoGenerationRequest, compressed: List<CompressedRef>): String {
        // Fail-loud 校验（对齐 ArcReel _reject_out_of_range_duration）
        require(request.durationSeconds in 1..MAX_DURATION_SECONDS) {
            "video_duration_not_supported: ${request.durationSeconds} 不在 [1, $MAX_DURATION_SECONDS]"
        }

        val firstFrame = compressed.firstOrNull { it.label == "first_frame" }
        val lastFrame = compressed.firstOrNull { it.label == "last_frame" }
        val refImages = compressed.filter { it.label != "first_frame" && it.label != "last_frame" }

        // 参考图与首/尾帧互斥（agnes 不支持混合）
        if (refImages.isNotEmpty() && (firstFrame != null || lastFrame != null)) {
            error("video_reference_images_with_frames_unsupported: agnes 不支持参考图与首/尾帧同时使用")
        }
        // 尾帧需首帧（keyframes 模式要求两端）
        if (lastFrame != null && firstFrame == null) {
            error("video_end_image_requires_start_image: agnes 尾帧必须配首帧（keyframes 模式）")
        }
        // 参考图上限
        require(refImages.size <= MAX_REFERENCE_IMAGES) {
            "video_reference_images_exceeded: ${refImages.size} > $MAX_REFERENCE_IMAGES"
        }

        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("prompt", request.prompt)

        // 尺寸：用 AspectSize（8 整除 + 1920 长边收口 + VIDEO_TIER 档位）
        val shortEdge = AspectSize.resolutionToShortEdge(
            request.resolution,
            AspectSize.VIDEO_TIER_SHORT_EDGE,
            DEFAULT_SHORT_EDGE
        )
        val (w, h) = AspectSize.aspectSize(
            request.aspectRatio, shortEdge,
            roundTo = ROUND_TO, maxLongEdge = MAX_LONG_EDGE
        )
        root.addProperty("width", w)
        root.addProperty("height", h)
        root.addProperty("num_frames", computeNumFrames(request.durationSeconds))
        root.addProperty("frame_rate", FPS)

        // 参考图分发
        if (firstFrame != null && lastFrame != null) {
            // keyframes 模式：首+尾帧 → extra_body.image=[start, end] + mode=keyframes（不写顶层 image）
            val extra = JsonObject()
            val arr = JsonArray()
            arr.add(ImageCodec.toBareBase64(firstFrame.path))
            arr.add(ImageCodec.toBareBase64(lastFrame.path))
            extra.add("image", arr)
            extra.addProperty("mode", "keyframes")
            root.add("extra_body", extra)
        } else if (firstFrame != null) {
            // 仅首帧 → 顶层 image 字符串
            root.addProperty("image", ImageCodec.toBareBase64(firstFrame.path))
        } else if (refImages.isNotEmpty()) {
            // 仅参考图 → extra_body.image 数组（无 mode）
            val extra = JsonObject()
            val arr = JsonArray()
            refImages.forEach { arr.add(ImageCodec.toBareBase64(it.path)) }
            extra.add("image", arr)
            root.add("extra_body", extra)
        }

        request.seed?.let { root.addProperty("seed", it) }
        // Agnes 视频无音频能力，不下发 with_audio / generate_audio

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
        // task_id 优先，回落 id（对齐 ArcReel _extract_task_id）
        val taskId = root.get("task_id")?.takeIf { !it.isJsonNull }?.asString
            ?: root.get("id")?.takeIf { !it.isJsonNull }?.asString
            ?: error("agnes submit 响应缺 task_id/id：$respBody")
        return taskId
    }

    private suspend fun pollUntilDone(
        client: OkHttpClient,
        taskId: String,
        onProgress: (VideoProgress) -> Unit
    ): String {
        val base = normalizeBaseUrl(cfg.baseUrl)
        val pollUrl = cfg.resolvePollUrl(taskId).ifBlank {
            base + POLL_PATH.replace("{taskId}", taskId)
        }
        val maxWait = cfg.validPollTimeout().milliseconds
        val interval = cfg.validPollInterval().milliseconds.coerceAtLeast(1.seconds)

        val result = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            // 终态（completed/failed/cancelled/expired）都视为 done，由下方统一抛带 status 的错误，
            // 便于 resumeVideo 识别 expired/404 → ResumeExpiredError（对齐 Ark）
            isDone = { it.status == "completed" || it.status in FAILED_STATUSES },
            isFailed = { false },
            pollInterval = interval,
            maxWait = maxWait,
            // Agnes：poll 404 视为任务不存在（终态，不重试），让 resume 路径识别为 ResumeExpiredError
            retryIf = { e ->
                !e.message.orEmpty().lowercase().contains("http 404") &&
                    VideoBackendHttp.shouldRetryPoll(e)
            },
            label = "agnes poll taskId=$taskId",
            onProgress = onProgress
        )
        if (result.status != "completed") {
            error("agnes 任务失败 status=${result.status}：${result.raw}")
        }
        return result.videoUrl ?: error("agnes poll completed 但无 url/remixed_from_video_id：${result.raw}")
    }

    private fun doPoll(client: OkHttpClient, url: String): AgnesPollResult {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // 读 body 再抛（live 验证：Agnes 过期任务返回 HTTP 400 + body {"code":"task_not_exist"}，
                // 需把 body 带入 error message 供 resumeVideo 的 isAgnesNotFound 识别）
                val body = runCatching { resp.body?.string() }.getOrNull()
                error("agnes poll HTTP ${resp.code} ${resp.message}${body?.let { " body=$it" }.orEmpty()}")
            }
            resp.body?.string() ?: error("agnes poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("agnes poll 响应非 JSON：$respBody") }
        val status = root.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
        // 成片 URL 优先取 url（T2V/I2V 完成后返回下载直链）；
        // remixed_from_video_id 仅 remix 场景有值且回落（live 验证：T2V 完成态 url 有值、remixed_from_video_id=null）
        val videoUrl = root.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?: root.get("remixed_from_video_id")?.takeIf { !it.isJsonNull }?.asString
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

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private fun computeNumFrames(durationSeconds: Int): Int {
        // 对齐 ArcReel _duration_to_num_frames：秒 × fps(24) 后对齐到最近的 8n+1，上限 441
        val target = max(1, durationSeconds) * FPS
        val n = (target - 1).toFloat() / FRAME_STEP
        val numFrames = FRAME_STEP * Math.round(n) + 1
        return numFrames.coerceIn(1, MAX_NUM_FRAMES)
    }

    private data class AgnesPollResult(val status: String, val videoUrl: String?, val raw: String)

    /** Agnes 任务「不存在/已过期」判定（对齐 Ark isArkNotFound）。 */
    private fun isAgnesNotFound(e: Throwable): Boolean {
        val msg = e.message.orEmpty().lowercase()
        // live 验证：Agnes 过期/不存在任务返回 HTTP 400 + body {"code":"task_not_exist"}
        return msg.contains("http 404") ||
            msg.contains("task_not_found") ||
            msg.contains("tasknotfound") ||
            msg.contains("task_not_exist") ||
            msg.contains("tasknotexist") ||
            msg.contains("not found") ||
            msg.contains("expired")
    }

    companion object {
        private const val DEFAULT_MODEL = "agnes-video-v2.0"
        private const val SUBMIT_PATH = "/videos"
        private const val POLL_PATH = "/videos/{taskId}"
        private val FAILED_STATUSES = setOf("failed", "cancelled", "canceled", "error", "expired")
        // 对齐 ArcReel agnes.py：fps=24，num_frames 须形如 8n+1，上限 441（≈18.4s）
        private const val FPS = 24
        private const val FRAME_STEP = 8
        private const val MAX_NUM_FRAMES = 441
        // 对齐 ArcReel agnes.py:_reject_out_of_range_duration：duration 上限 18s
        private const val MAX_DURATION_SECONDS = 18
        // 尺寸（对齐 ArcReel _resolve_size：round_to=8, max_long_edge=1920, 默认短边 720p）
        private const val ROUND_TO = 8
        private const val MAX_LONG_EDGE = 1920
        private const val DEFAULT_SHORT_EDGE = 720
        // 参考图上限（对齐 ArcReel agnes.py:293-299）
        private const val MAX_REFERENCE_IMAGES = 4

        /**
         * base 归一化为 `{host}/v1`（对齐 ArcReel `agnes_base_url`）：
         * 用户填 host 或带 `/v1` 后缀都收敛到 `{host}/v1`，缺省回落默认 apihub host。
         */
        internal fun normalizeBaseUrl(configured: String): String {
            val raw = configured.trim().trimEnd('/').ifBlank { DEFAULT_BASE_HOST }
            return if (raw.endsWith("/v1")) raw else "$raw/v1"
        }

        private const val DEFAULT_BASE_HOST = "https://apihub.agnes-ai.com"

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
