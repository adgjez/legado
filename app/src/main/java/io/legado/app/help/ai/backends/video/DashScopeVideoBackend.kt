package io.legado.app.help.ai.backends.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.MediaGenerator
import io.legado.app.help.ai.backends.VideoBackend
import io.legado.app.help.ai.backends.ResumeExpiredError
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
 * DashScope（阿里百炼）视频后端（移植 ArcReel `video_backends/dashscope.py` + `dashscope_shared.py`）。
 *
 * 走原生 video-synthesis 异步端点：submit `POST /services/aigc/video-generation/video-synthesis`
 * 取 `$.output.task_id` → 轮询 `GET /tasks/{id}` 至 `task_status=SUCCEEDED` 取
 * `$.output.video_url` → 下载本地。覆盖 happyhorse-1.0 与 wan2.7 系列的 t2v / i2v / r2v。
 *
 * **参考图编码：data URI**（[ImageCodec.toDataUri]）——百炼 `media[].url` 接受 URL 或 data URI。
 *
 * - media 类型：`first_frame`（caps.first_frame + startImage）/ `reference_image`（caps.reference_images）
 * - parameters：`resolution`（大写）/ `duration`（int）/ `watermark=false`；`ratio` 仅无首帧时下传
 *   （带首帧时上游按首帧定宽高比，传 ratio 会被 HappyHorse 拒绝）
 * - 鉴权：Bearer + submit 时额外带 `X-DashScope-Async: enable`（异步两步式必需）
 * - 音频恒开（无开关参数），统一声明 GENERATE_AUDIO
 *
 * 各模型能力按 [_PROFILES] 表驱动（6 档，官方一手核实）；未登记 model 回落通用默认 [_DEFAULT_PROFILE]
 * （first_frame=true）。profile 解析容忍代理中转的前后缀装饰（子串匹配），避免装饰名丢掉 r2v 的参考能力。
 *
 * expired：task_id 24h 过期查询返回 `task_status=UNKNOWN`——generate 抛 IllegalStateException，
 * resume 抛 [ResumeExpiredError]。
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class DashScopeVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_DASHSCOPE
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    private val profile: DashScopeProfile = lookupProfile(model)

    override val capabilities: Set<VideoCapability> = profile.capabilities
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
        val body = buildPayload(request, compressed)
        val base = normalizeBaseUrl(cfg.baseUrl)
        val submitUrl = cfg.submitUrl.ifBlank { base + SUBMIT_PATH }

        val taskId = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseTaskId(resp) }

        onProgress(VideoProgress("submitted", "taskId=$taskId"))
        return pollAndBuild(client, taskId, request, isResume = isResume, onProgress = onProgress)
    }

    private suspend fun pollAndBuild(
        client: OkHttpClient,
        taskId: String,
        request: VideoGenerationRequest,
        isResume: Boolean,
        onProgress: (VideoProgress) -> Unit = {}
    ): VideoGenerationResult {
        val base = normalizeBaseUrl(cfg.baseUrl)
        val pollUrl = cfg.resolvePollUrl(taskId).ifBlank { base + POLL_PATH.replace("{taskId}", taskId) }
        val maxWait = maxOf(900L, request.durationSeconds * 60L).seconds
        val interval = 15.seconds.coerceAtLeast(cfg.validPollInterval().milliseconds)

        val final = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            isDone = { it.terminal },
            isFailed = { it.failureReason != null && it.status != STATUS_UNKNOWN },
            pollInterval = interval,
            maxWait = maxWait,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "dashscope poll taskId=$taskId",
            onProgress = onProgress
        )

        // UNKNOWN = task_id 24h 过期；generate 抛 IllegalStateException，resume 抛 ResumeExpiredError
        if (final.status == STATUS_UNKNOWN) {
            if (isResume) throw ResumeExpiredError("任务已过期（provider=$typeId, jobId=$taskId）")
            error("DashScope task 已过期（UNKNOWN）：$taskId")
        }
        final.failureReason?.let { error(it) }

        val videoUrl = final.videoUrl ?: error("DashScope 任务完成但缺 video_url：${final.raw}")
        VideoBackendHttp.downloadVideo(videoUrl, request.outputPath)

        val billingDuration = final.billingDuration ?: request.durationSeconds
        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = billingDuration,
            videoUri = videoUrl,
            taskId = taskId,
            generateAudio = request.generateAudio
        )
    }

    /**
     * 构造 DashScope 请求体 + media 数组。
     *
     * - root：`{model, input:{prompt, media?}, parameters:{resolution, duration, watermark:false}}`
     * - media：`first_frame`（caps.first_frame + startImage）/ `reference_image`（caps.reference_images，r2v 必填）
     * - ratio 仅无首帧时下传（带首帧按首帧定宽高比，HappyHorse 会把 ratio 当非法参数拒绝）
     * - seed 可选
     *
     * fail-loud：r2v（声明 reference_images）未提供参考图即中止，不静默退化为无参考生成。
     */
    internal fun buildPayload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>
    ): String {
        val media = buildMedia(compressed)
        val input = JsonObject().apply {
            addProperty("prompt", request.prompt)
            if (media.size() > 0) add("media", media)
        }
        val parameters = JsonObject().apply {
            addProperty("resolution", (request.resolution ?: "720p").uppercase())
            addProperty("duration", request.durationSeconds)
            addProperty("watermark", false)
        }
        val hasFirstFrame = (0 until media.size()).any { media[it].asJsonObject.get("type")?.takeIf { !it.isJsonNull }?.asString == "first_frame" }
        if (request.aspectRatio.isNotBlank() && !hasFirstFrame) {
            parameters.addProperty("ratio", request.aspectRatio)
        }
        request.seed?.let { parameters.addProperty("seed", it) }

        val root = JsonObject().apply {
            addProperty("model", model)
            add("input", input)
            add("parameters", parameters)
        }
        return root.toString()
    }

    private fun buildMedia(compressed: List<CompressedRef>): JsonArray {
        val media = JsonArray()
        val caps = profile.videoCaps
        if (caps.firstFrame) {
            compressed.firstOrNull { it.label == "first_frame" }?.let { ff ->
                media.add(JsonObject().apply {
                    addProperty("type", "first_frame")
                    addProperty("url", ImageCodec.toDataUri(ff.path))
                })
            }
        }
        if (caps.referenceImages) {
            val refs = compressed.filter { it.label != "first_frame" && it.label != "last_frame" }
            // r2v 必须有参考图——fail-loud，不静默退化为无参考生成
            if (refs.isEmpty()) {
                error("DashScope model=$model 需要参考图（reference_images）")
            }
            val limited = if (refs.size > caps.maxReferenceImages) refs.take(caps.maxReferenceImages) else refs
            limited.forEach { ref ->
                media.add(JsonObject().apply {
                    addProperty("type", "reference_image")
                    addProperty("url", ImageCodec.toDataUri(ref.path))
                })
            }
        }
        return media
    }

    /**
     * 归一化 base URL：剥除 `/compatible-mode/v1` / `/api/v1` 已知后缀后补 `/api/v1`
     * （原生视频端点 base）。容忍用户填 host 或带后缀的完整 base；空回落北京 host。
     */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE
        KNOWN_SUFFIXES.forEach { suf ->
            if (base.endsWith(suf)) base = base.removeSuffix(suf)
        }
        return base + "/api/v1"
    }

    private fun doSubmit(client: OkHttpClient, url: String, body: String): Response {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .header("X-DashScope-Async", "enable")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute()
    }

    private fun parseTaskId(resp: Response): String {
        val respBody = resp.body?.string() ?: error("dashscope submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("dashscope submit 响应非 JSON：$respBody") }
        // 提交阶段顶层错误（如 InvalidApiKey）：{code, message, request_id} 无 output
        responseError(root)?.let { error(it) }
        val taskId = root.getAsJsonObject("output")?.get("task_id")?.takeIf { !it.isJsonNull }?.asString
            ?: error("dashscope submit 响应缺 output.task_id：$respBody")
        return taskId
    }

    private fun doPoll(client: OkHttpClient, url: String): DashScopePollResult {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("dashscope poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("dashscope poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("dashscope poll 响应非 JSON：$respBody") }
        responseError(root)?.let { error(it) }
        val output = root.getAsJsonObject("output")
        val status = output?.get("task_status")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
        val videoUrl = output?.get("video_url")?.takeIf { !it.isJsonNull }?.asString
        val billingDuration = root.getAsJsonObject("usage")?.get("duration")?.let { dur ->
            runCatching { dur.asString.trim().toInt() }.getOrNull()
                ?: runCatching { dur.asInt }.getOrNull()
        }
        return DashScopePollResult(status, videoUrl, billingDuration, respBody)
    }

    /** 顶层 code 非空即错误（提交阶段鉴权/参数非法）；output.code（FAILED/CANCELED）也暴露。 */
    private fun responseError(root: JsonObject): String? {
        val output = root.getAsJsonObject("output")
        val outStatus = output?.get("task_status")?.takeIf { !it.isJsonNull }?.asString
        if (outStatus in FAILURE_STATUSES) {
            val code = output.get("code")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
            val msg = output.get("message")?.takeIf { !it.isJsonNull }?.asString ?: ""
            return "DashScope 任务失败 status=$outStatus code=$code: $msg".trim()
        }
        if (outStatus == null && root.get("code")?.takeIf { !it.isJsonNull } != null) {
            val msg = root.get("message")?.takeIf { !it.isJsonNull }?.asString ?: ""
            return "DashScope 提交失败 code=${root.get("code")}: $msg".trim()
        }
        return null
    }

    private fun buildReferenceSpecs(request: VideoGenerationRequest): List<ReferenceSpec> {
        val specs = mutableListOf<ReferenceSpec>()
        // DashScope 仅声明首帧（i2v/wan2.7-r2v）；尾帧在一手 docs 未确权，不臆造
        request.startImage?.let { specs.add(ReferenceSpec(it, "first_frame", RefRole.FRAME)) }
        request.referenceImages?.forEachIndexed { i, f ->
            specs.add(ReferenceSpec(f, "ref_$i", RefRole.ARRAY))
        }
        return specs
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private data class DashScopePollResult(
        val status: String, val videoUrl: String?, val billingDuration: Int?, val raw: String
    ) {
        val terminal: Boolean get() = status in TERMINAL_STATUSES
        val failureReason: String? get() = if (status in FAILURE_STATUSES) "DashScope 任务失败 status=$status" else null
    }

    /** 单个 DashScope 视频模型的能力档（官方一手核实）。 */
    internal data class DashScopeProfile(
        val capabilities: Set<VideoCapability>,
        val videoCaps: VideoCapabilities
    )

    companion object {
        private const val DEFAULT_MODEL = "happyhorse-1.0-i2v"
        private const val DEFAULT_BASE = "https://dashscope.aliyuncs.com"
        private const val SUBMIT_PATH = "/services/aigc/video-generation/video-synthesis"
        private const val POLL_PATH = "/tasks/{taskId}"
        private val KNOWN_SUFFIXES = listOf("/compatible-mode/v1", "/api/v1")

        private const val STATUS_UNKNOWN = "UNKNOWN"
        private val TERMINAL_STATUSES = setOf("SUCCEEDED", "FAILED", "CANCELED", STATUS_UNKNOWN)
        private val FAILURE_STATUSES = setOf("FAILED", "CANCELED")

        private val TV = VideoCapability.TEXT_TO_VIDEO
        private val IV = VideoCapability.IMAGE_TO_VIDEO
        private val AUDIO = VideoCapability.GENERATE_AUDIO
        private val SEED = VideoCapability.SEED_CONTROL

        /**
         * 按 model id 派发能力档（6 档：happyhorse/wan2.7 × t2v/i2v/r2v）。
         *
         * - happyhorse-r2v 仅 reference_image（无首帧）；wan2.7-r2v 额外支持首帧叠加参考
         * - 音频恒开（无开关参数），统一声明 GENERATE_AUDIO
         */
        internal val _PROFILES: Map<String, DashScopeProfile> = mapOf(
            "happyhorse-1.0-t2v" to DashScopeProfile(
                setOf(TV, AUDIO, SEED),
                VideoCapabilities(firstFrame = false)
            ),
            "happyhorse-1.0-i2v" to DashScopeProfile(
                setOf(IV, AUDIO, SEED),
                VideoCapabilities(firstFrame = true)
            ),
            "happyhorse-1.0-r2v" to DashScopeProfile(
                setOf(IV, AUDIO, SEED),
                VideoCapabilities(firstFrame = false, referenceImages = true, maxReferenceImages = 9)
            ),
            "wan2.7-t2v" to DashScopeProfile(
                setOf(TV, AUDIO, SEED),
                VideoCapabilities(firstFrame = false)
            ),
            "wan2.7-i2v" to DashScopeProfile(
                setOf(IV, AUDIO, SEED),
                VideoCapabilities(firstFrame = true)
            ),
            "wan2.7-r2v" to DashScopeProfile(
                setOf(IV, AUDIO, SEED),
                // 带首帧的参考生视频是 wan2.7-r2v 的官方形态，故声明首帧叠加参考能力
                VideoCapabilities(
                    firstFrame = true,
                    referenceImages = true,
                    maxReferenceImages = 5,
                    referenceImagesWithStartFrame = true
                )
            )
        )

        /** 未知 model（代理中转自定义命名）按通用 i2v/t2v 处理，默认支持首帧。 */
        internal val _DEFAULT_PROFILE: DashScopeProfile = DashScopeProfile(
            setOf(TV, IV, AUDIO, SEED),
            VideoCapabilities()
        )

        /**
         * 按 model_id 解析能力档：先精确命中，再容忍代理中转的前后缀装饰（子串匹配）。
         *
         * infer_endpoint 用子串路由，此处也须子串容忍，否则 "proxy/happyhorse-1.0-r2v"
         * 这类装饰名会退回默认、丢掉 r2v 的参考能力，构造出错误 payload。
         */
        internal fun lookupProfile(model: String): DashScopeProfile {
            val normalized = model.trim().lowercase()
            if (normalized.isEmpty()) return _DEFAULT_PROFILE
            _PROFILES[normalized]?.let { return it }
            // 各 profile key 互不为子串，无歧义
            for ((known, profile) in _PROFILES) {
                if (normalized.contains(known)) return profile
            }
            return _DEFAULT_PROFILE
        }

        /** 参考图级能力（移植 ArcReel dashscope.py）。 */
        fun videoCapabilitiesForModel(model: String): VideoCapabilities = lookupProfile(model).videoCaps

        init {
            // 注册到 Registry（P2d）
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_DASHSCOPE) { cfg -> DashScopeVideoBackend(cfg) }
        }
    }
}
