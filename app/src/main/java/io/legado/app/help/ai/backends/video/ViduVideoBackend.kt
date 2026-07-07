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
 * Vidu（生数科技）视频后端（移植 ArcReel `video_backends/vidu.py` + `vidu_shared.py`）。
 *
 * 按 request 字段分派到 4 个 Vidu API 端点：
 * - `reference_images` 非空 → `POST /reference2video`（多图参考）
 * - `start_image + end_image` → `POST /start-end2video`（首尾帧）
 * - `start_image` → `POST /img2video`（首帧）
 * - 都无 → `POST /text2video`（文生视频）
 *
 * submit 取 `$.task_id` → 轮询 `GET /tasks/{id}/creations` 至 `state=success` 取
 * `$.creations[0].url` → 下载本地。
 *
 * **参考图编码：data URI**（[ImageCodec.toDataUri]）——`images` 数组接受 URL 或 data URI。
 *
 * - 鉴权：`Authorization: Token {apiKey}`（Vidu 用 `Token` 前缀，非 `Bearer`）
 * - images 形态按端点：reference2video=参考图数组；start-end2video=[首帧,尾帧]；
 *   img2video=[首帧]；text2video 无 images
 * - `aspect_ratio` 仅 text2video / reference2video 接受
 * - `audio` 仅 q3 系列模型接受（[Q3_MODELS]）
 *
 * 参考图与首帧在 Vidu 上是互斥模式（见参考图即切 reference2video，首帧被丢弃），
 * 故 [videoCapabilities.referenceImagesWithStartFrame]=false。
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。resume 暂不支持（与 ArcReel 一致）。
 */
class ViduVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_VIDU
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<VideoCapability> = buildSet {
        add(VideoCapability.TEXT_TO_VIDEO)
        add(VideoCapability.IMAGE_TO_VIDEO)
        add(VideoCapability.SEED_CONTROL)
        if (model in Q3_MODELS) add(VideoCapability.GENERATE_AUDIO)
    }
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
        // 与 ArcReel 一致：poll 完全内联在 generate，resume 暂不支持
        throw NotImplementedError("ViduVideoBackend 暂不支持 resume_video")
    }

    private suspend fun submitPollDownload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val client = buildClient(cfg.validSubmitTimeout())
        val (endpoint, body) = buildRequest(request, compressed)
        val base = normalizeBaseUrl(cfg.baseUrl)
        val submitUrl = cfg.submitUrl.ifBlank { base + endpoint }

        val taskId = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseTaskId(resp) }

        onProgress(VideoProgress("submitted", "taskId=$taskId endpoint=$endpoint"))
        val maxWait = maxOf(900L, request.durationSeconds * 90L).seconds
        val interval = 5.seconds.coerceAtLeast(cfg.validPollInterval().milliseconds)

        val pollUrl = cfg.resolvePollUrl(taskId).ifBlank {
            base + POLL_PATH.replace("{taskId}", taskId)
        }
        val final = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            isDone = { it.state == STATE_SUCCESS },
            isFailed = { it.state == STATE_FAILED },
            pollInterval = interval,
            maxWait = maxWait,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "vidu poll taskId=$taskId",
            onProgress = onProgress
        )

        final.failureReason?.let { error(it) }
        val videoUrl = final.videoUrl ?: error("Vidu 任务完成但缺 creations[0].url：${final.raw}")
        VideoBackendHttp.downloadVideo(videoUrl, request.outputPath)

        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = request.durationSeconds,
            videoUri = videoUrl,
            taskId = taskId,
            generateAudio = if (model in Q3_MODELS) request.generateAudio else false
        )
    }

    /**
     * 按 request 字段选择端点。
     *
     * - refs 非空 → /reference2video
     * - start+end → /start-end2video
     * - start → /img2video
     * - 都无 → /text2video
     */
    internal fun selectEndpoint(
        hasRefs: Boolean,
        hasStart: Boolean,
        hasEnd: Boolean
    ): String = when {
        hasRefs -> REFERENCE2VIDEO
        hasStart && hasEnd -> START_END2VIDEO
        hasStart -> IMG2VIDEO
        else -> TEXT2VIDEO
    }

    /**
     * 构造 Vidu 请求体（endpoint + body）。
     *
     * - body：`{model, prompt, duration, resolution?, seed?, audio?(q3), aspect_ratio?(text2video/ref2video), images?}`
     * - prompt 截断：reference2video 上限 2000，其他 5000
     * - images 按端点形态填充（data URI 数组）
     */
    internal fun buildRequest(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>
    ): Pair<String, String> {
        val refs = compressed.filter { it.label != "first_frame" && it.label != "last_frame" }
        val firstFrame = compressed.firstOrNull { it.label == "first_frame" }
        val lastFrame = compressed.firstOrNull { it.label == "last_frame" }
        val endpoint = selectEndpoint(refs.isNotEmpty(), firstFrame != null, lastFrame != null)

        val promptMax = if (endpoint == REFERENCE2VIDEO) PROMPT_MAX_REFERENCE else PROMPT_MAX_TEXT
        val prompt = (request.prompt ?: "").take(promptMax)

        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", prompt)
            addProperty("duration", request.durationSeconds)
        }
        val resolution = request.resolution ?: DEFAULT_RESOLUTION
        root.addProperty("resolution", resolution)
        request.seed?.let { root.addProperty("seed", it) }
        // audio 仅 q3 系列接受
        if (model in Q3_MODELS) {
            root.addProperty("audio", request.generateAudio)
        }
        // aspect_ratio 仅 text2video / reference2video 接受
        if (endpoint in ENDPOINTS_WITH_ASPECT_RATIO && request.aspectRatio.isNotBlank()) {
            root.addProperty("aspect_ratio", request.aspectRatio)
        }

        // images 按端点形态填充（data URI 数组）
        val images = buildImages(endpoint, refs, firstFrame, lastFrame)
        if (images.size() > 0) root.add("images", images)

        return endpoint to root.toString()
    }

    private fun buildImages(
        endpoint: String,
        refs: List<CompressedRef>,
        firstFrame: CompressedRef?,
        lastFrame: CompressedRef?
    ): JsonArray {
        val images = JsonArray()
        when (endpoint) {
            REFERENCE2VIDEO -> {
                val limited = if (refs.size > MAX_REFERENCE_IMAGES) refs.take(MAX_REFERENCE_IMAGES) else refs
                limited.forEach { images.add(ImageCodec.toDataUri(it.path)) }
            }
            START_END2VIDEO -> {
                firstFrame?.let { images.add(ImageCodec.toDataUri(it.path)) }
                lastFrame?.let { images.add(ImageCodec.toDataUri(it.path)) }
            }
            IMG2VIDEO -> {
                firstFrame?.let { images.add(ImageCodec.toDataUri(it.path)) }
            }
        }
        return images
    }

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

    private fun parseTaskId(resp: Response): String {
        val respBody = resp.body?.string() ?: error("vidu submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("vidu submit 响应非 JSON：$respBody") }
        val taskId = root.get("task_id")?.asString
            ?: error("vidu submit 响应缺 task_id：$respBody")
        return taskId
    }

    private fun doPoll(client: OkHttpClient, url: String): ViduPollResult {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Token ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("vidu poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("vidu poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("vidu poll 响应非 JSON：$respBody") }
        val state = root.get("state")?.asString ?: "unknown"
        val videoUrl = root.getAsJsonArray("creations")?.firstOrNull()?.asJsonObject?.get("url")?.asString
        return ViduPollResult(state, videoUrl, respBody)
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

    private data class ViduPollResult(
        val state: String, val videoUrl: String?, val raw: String
    ) {
        val failureReason: String? get() = if (state == STATE_FAILED) "Vidu 任务失败 state=failed" else null
    }

    companion object {
        private const val DEFAULT_MODEL = "viduq3-turbo"
        private const val DEFAULT_BASE = "https://api.vidu.cn/ent/v2"
        private const val DEFAULT_RESOLUTION = "720p"

        private const val TEXT2VIDEO = "/text2video"
        private const val IMG2VIDEO = "/img2video"
        private const val START_END2VIDEO = "/start-end2video"
        private const val REFERENCE2VIDEO = "/reference2video"
        private const val POLL_PATH = "/tasks/{taskId}/creations"

        private const val STATE_SUCCESS = "success"
        private const val STATE_FAILED = "failed"

        private const val MAX_REFERENCE_IMAGES = 7
        private const val PROMPT_MAX_TEXT = 5000
        private const val PROMPT_MAX_REFERENCE = 2000

        // q3 系列默认开启音视频直出，audio 仅传 q3 时才生效
        internal val Q3_MODELS: Set<String> = setOf(
            "viduq3-pro", "viduq3-turbo", "viduq3-pro-fast", "viduq3", "viduq3-mix"
        )

        // aspect_ratio 仅 text2video / reference2video 接受
        internal val ENDPOINTS_WITH_ASPECT_RATIO: Set<String> = setOf(TEXT2VIDEO, REFERENCE2VIDEO)

        /**
         * 参考图级能力（移植 ArcReel vidu.py）：恒为 firstFrame/lastFrame/refs=true, max=7,
         * withStartFrame=false（参考图与首帧在 Vidu 上互斥——见参考图即切 reference2video，首帧被丢弃）。
         */
        fun videoCapabilitiesForModel(@Suppress("UNUSED_PARAMETER") model: String): VideoCapabilities {
            return VideoCapabilities(
                firstFrame = true,
                lastFrame = true,
                referenceImages = true,
                maxReferenceImages = MAX_REFERENCE_IMAGES,
                referenceImagesWithStartFrame = false
            )
        }

        init {
            // 注册到 Registry（P2d）
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_VIDU) { cfg -> ViduVideoBackend(cfg) }
        }
    }
}
