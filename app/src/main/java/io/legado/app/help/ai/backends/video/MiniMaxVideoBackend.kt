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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * MiniMax（海螺）视频后端（移植 ArcReel `video_backends/minimax.py` + `minimax_shared.py`）。
 *
 * 走原生视频端点，轮询而非 callback：submit `POST /video_generation` 取 `$.task_id` →
 * 轮询 `GET /query/video_generation?task_id=` 至 `status=Success` 取 `$.file_id` →
 * `GET /files/retrieve?file_id=` 取 `$.file.download_url` → 下载本地。
 *
 * 覆盖 MiniMax-Hailuo-2.3（t2v+i2v）、Hailuo-2.3-Fast（仅 i2v）、S2V-01（单脸参考生视频 R2V）。
 *
 * **参考图编码：data URI**（[ImageCodec.toDataUri]）——`first_frame_image`（Hailuo 首帧）/
 * `subject_reference[].image[]`（S2V 单脸参考）均接受 URL 或 data URI。
 *
 * - Hailuo 系列走 `first_frame_image` 首帧；resolution ∈ {768P,1080P}、duration ∈ {6,10}（1080P 仅 6s）
 * - Fast 仅图生视频，无首帧的文生视频请求被能力拒绝
 * - S2V-01 走 `subject_reference:[{type:character, image:[dataURI]}]` 单脸字段，固定输出、
 *   不传 resolution/duration，无参考图即 fail-loud
 * - 鉴权：Bearer
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+retrieve+download。
 */
class MiniMaxVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_MINIMAX
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<VideoCapability> = capabilitiesForModel(model)
    override val videoCapabilities: VideoCapabilities
        get() = videoCapabilitiesForModel(model)

    override suspend fun generate(
        request: VideoGenerationRequest,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val refs = buildReferenceSpecs(request)
        val limits = PayloadLimits(totalMaxBytes = 8 * 1024 * 1024L, singleMaxBytes = 4 * 1024 * 1024L)
        return MediaGenerator.runWithReferenceCompression(refs, limits) { compressed ->
            submitPollRetrieveDownload(request, compressed, onProgress)
        }
    }

    override suspend fun resumeVideo(jobId: String, request: VideoGenerationRequest): VideoGenerationResult {
        val client = buildClient(cfg.validPollTimeout())
        return pollRetrieveDownload(client, jobId, request, onProgress = {})
    }

    private suspend fun submitPollRetrieveDownload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val client = buildClient(cfg.validSubmitTimeout())
        val body = buildPayload(request, compressed)
        val base = normalizeBaseUrl(cfg.baseUrl)
        val submitUrl = cfg.submitUrl.ifBlank { base + SUBMIT_PATH }

        val taskId = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseTaskId(resp) }

        onProgress(VideoProgress("submitted", "taskId=$taskId"))
        return pollRetrieveDownload(client, taskId, request, onProgress)
    }

    private suspend fun pollRetrieveDownload(
        client: OkHttpClient,
        taskId: String,
        request: VideoGenerationRequest,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val base = normalizeBaseUrl(cfg.baseUrl)
        val queryUrl = base + QUERY_PATH // 拼 query 参数见 doPoll
        val maxWait = maxOf(900L, request.durationSeconds * 60L).seconds
        val interval = 10.seconds.coerceAtLeast(cfg.validPollInterval().milliseconds)

        val final = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, queryUrl, taskId) },
            isDone = { it.terminal },
            isFailed = { it.failureReason != null },
            pollInterval = interval,
            maxWait = maxWait,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "minimax poll taskId=$taskId",
            onProgress = onProgress
        )

        final.failureReason?.let { error(it) }
        val fileId = final.fileId ?: error("MiniMax 任务完成但缺 file_id：${final.raw}")

        // retrieve download_url（幂等 GET，可重试）
        val downloadUrl = retrieveDownloadUrl(client, base + RETRIEVE_PATH, fileId)
        VideoBackendHttp.downloadVideo(downloadUrl, request.outputPath)

        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = request.durationSeconds,
            videoUri = downloadUrl,
            taskId = taskId,
            generateAudio = request.generateAudio
        )
    }

    /**
     * 构造 MiniMax 请求体。
     *
     * - S2V-01：`{model, prompt, subject_reference:[{type:character, image:[dataURI]}]}`（单脸参考，固定输出）
     * - Hailuo 系列：`{model, prompt, duration, resolution(upper), first_frame_image?: dataURI}`
     *   - 无首帧 = 文生视频意图；Fast（仅 i2v）拒绝
     *   - resolution × duration 必须在白名单内（768p:{6,10}, 1080p:{6}），越界 fail-loud
     */
    internal fun buildPayload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>
    ): String {
        if (model == S2V_MODEL) return buildS2vPayload(request, compressed)

        val resolution = (request.resolution ?: "768p").lowercase()
        val duration = request.durationSeconds
        val hasStartImage = compressed.any { it.label == "first_frame" }

        // 无首帧 = 文生视频意图；Fast（仅 i2v）拒绝
        if (!hasStartImage && VideoCapability.TEXT_TO_VIDEO !in capabilities) {
            error("MiniMax model=$model 不支持文生视频（仅图生视频）")
        }

        val allowedDurations = RESOLUTION_DURATIONS[resolution]
        if (allowedDurations == null || duration !in allowedDurations) {
            val supported = allowedDurations?.sorted()?.joinToString(", ")?.let { "$it s" } ?: "无"
            error("MiniMax model=$model 不支持 resolution=${resolution.uppercase()} duration=${duration}s（支持：$supported）")
        }

        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt)
            addProperty("duration", duration)
            addProperty("resolution", resolution.uppercase())
        }
        compressed.firstOrNull { it.label == "first_frame" }?.let { ff ->
            // fail-loud：声明了首帧图却缺失/不可读在压缩管线已拦截；此处仅编码
            root.addProperty("first_frame_image", ImageCodec.toDataUri(ff.path))
        }
        return root.toString()
    }

    /** S2V-01：把 reference_images[0] 映射成单脸 subject_reference。无参考图即 fail-loud。 */
    private fun buildS2vPayload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>
    ): String {
        val refs = compressed.filter { it.label != "first_frame" && it.label != "last_frame" }
        if (refs.isEmpty()) {
            error("MiniMax S2V-01 需要参考图（reference_images，单张人脸）")
        }
        // 防御性仅取首张人脸图（编排层已按 max=1 裁剪）
        val face = refs.first()
        val dataUri = ImageCodec.toDataUri(face.path)
        val subjectRef = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "character")
                val imgArr = JsonArray().apply { add(dataUri) }
                add("image", imgArr)
            })
        }
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt)
            add("subject_reference", subjectRef)
        }
        return root.toString()
    }

    /**
     * 归一化 base URL：剥除 `/v1` 后缀后补 `/v1`。容忍用户填 host 或带 `/v1` 的完整 base；空回落国内站。
     */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        if (base.endsWith("/v1")) base = base.removeSuffix("/v1")
        return base + "/v1"
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
        val respBody = resp.body?.string() ?: error("minimax submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("minimax submit 响应非 JSON：$respBody") }
        baseRespError(root)?.let { error(it) }
        val taskId = root.get("task_id")?.asString
            ?: error("minimax submit 响应缺 task_id：$respBody")
        return taskId
    }

    private fun doPoll(client: OkHttpClient, baseUrl: String, taskId: String): MiniMaxPollResult {
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("task_id", taskId)
            ?.build()
            ?: error("minimax poll url 非法：$baseUrl")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("minimax poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("minimax poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("minimax poll 响应非 JSON：$respBody") }
        baseRespError(root)?.let { error(it) }
        val status = root.get("status")?.asString ?: "unknown"
        val fileId = root.get("file_id")?.asString
        return MiniMaxPollResult(status, fileId, respBody)
    }

    private fun retrieveDownloadUrl(client: OkHttpClient, baseUrl: String, fileId: String): String {
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("file_id", fileId)
            ?.build()
            ?: error("minimax retrieve url 非法：$baseUrl")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("minimax retrieve HTTP ${resp.code} ${resp.message}")
            val respBody = resp.body?.string() ?: error("minimax retrieve 响应体为空")
            val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
                .getOrElse { error("minimax retrieve 响应非 JSON：$respBody") }
            baseRespError(root)?.let { error(it) }
            root.getAsJsonObject("file")?.get("download_url")?.asString
                ?: error("minimax retrieve 响应缺 file.download_url：$respBody")
        }
    }

    /** `base_resp.status_code != 0` → 错误描述；0/缺失 → null。 */
    private fun baseRespError(root: JsonObject): String? {
        val base = root.getAsJsonObject("base_resp") ?: return null
        val code = base.get("status_code")?.let { runCatching { it.asInt }.getOrNull() } ?: return null
        if (code != 0) {
            val msg = base.get("status_msg")?.asString ?: ""
            return "MiniMax base_resp status_code=$code: $msg".trim()
        }
        return null
    }

    private fun buildReferenceSpecs(request: VideoGenerationRequest): List<ReferenceSpec> {
        val specs = mutableListOf<ReferenceSpec>()
        if (model == S2V_MODEL) {
            // S2V-01 走单脸 subject_reference（reference_images[0]），不接受首帧
            request.referenceImages?.forEachIndexed { i, f ->
                specs.add(ReferenceSpec(f, "ref_$i", RefRole.ARRAY))
            }
        } else {
            // Hailuo 系列走 first_frame_image 首帧；不建模尾帧/参考图
            request.startImage?.let { specs.add(ReferenceSpec(it, "first_frame", RefRole.FRAME)) }
        }
        return specs
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private data class MiniMaxPollResult(
        val status: String, val fileId: String?, val raw: String
    ) {
        val terminal: Boolean get() = status in TERMINAL_STATUSES
        val failureReason: String? get() = if (status == STATUS_FAIL) "MiniMax 任务失败 status=Fail" else null
    }

    companion object {
        private const val DEFAULT_MODEL = "MiniMax-Hailuo-2.3"
        private const val DEFAULT_BASE = "https://api.minimaxi.com/v1"
        private const val SUBMIT_PATH = "/video_generation"
        private const val QUERY_PATH = "/query/video_generation"
        private const val RETRIEVE_PATH = "/files/retrieve"

        private const val HAILUO_MODEL = "MiniMax-Hailuo-2.3"
        private const val HAILUO_FAST_MODEL = "MiniMax-Hailuo-2.3-Fast"
        private const val S2V_MODEL = "S2V-01"

        private const val STATUS_SUCCESS = "Success"
        private const val STATUS_FAIL = "Fail"
        private val TERMINAL_STATUSES = setOf(STATUS_SUCCESS, STATUS_FAIL)

        // (分辨率小写 → 允许的时长集合)：1080P 仅 6s，768P 支持 6s/10s
        internal val RESOLUTION_DURATIONS: Map<String, Set<Int>> = mapOf(
            "768p" to setOf(6, 10),
            "1080p" to setOf(6)
        )

        private val TV = VideoCapability.TEXT_TO_VIDEO
        private val IV = VideoCapability.IMAGE_TO_VIDEO

        // 按 model id 派发端点级能力：Hailuo 文+图；Fast 仅图；S2V 既非 t2v 也非 i2v（subject_reference 驱动）
        private val MODEL_CAPABILITIES: Map<String, Set<VideoCapability>> = mapOf(
            HAILUO_MODEL to setOf(TV, IV),
            HAILUO_FAST_MODEL to setOf(IV),
            S2V_MODEL to emptySet()
        )

        /** 未知 model（代理中转自定义命名）按通用文+图生视频处理。 */
        private val DEFAULT_CAPABILITIES: Set<VideoCapability> = setOf(TV, IV)

        internal fun capabilitiesForModel(model: String): Set<VideoCapability> =
            MODEL_CAPABILITIES[model.trim()] ?: DEFAULT_CAPABILITIES

        /**
         * 参考图级能力（移植 ArcReel minimax.py）：
         * - S2V-01：单脸参考生视频，first_frame=false + reference_images=true, max=1
         * - Hailuo 系列：首帧图生视频 first_frame=true
         */
        fun videoCapabilitiesForModel(model: String): VideoCapabilities {
            return if (model.trim() == S2V_MODEL) {
                VideoCapabilities(firstFrame = false, referenceImages = true, maxReferenceImages = 1)
            } else {
                VideoCapabilities(firstFrame = true)
            }
        }

        init {
            // 注册到 Registry（P2d）
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_MINIMAX) { cfg -> MiniMaxVideoBackend(cfg) }
        }
    }
}
