package io.legado.app.help.ai.backends.video

import com.google.gson.JsonArray
import com.google.gson.JsonElement
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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * V2 视频后端（移植 ArcReel `video_backends/v2_video_generations.py`）。
 *
 * 通用「流派 C」`/v2/video/generations` 中转协议（aimlapi / xAI / getimg.ai / APIMart /
 * CometAPI 等事实标准）：单端点 + model 字段切换。
 * - submit `POST {root}/v2/video/generations` 取 generation_id（多路径容错提取）
 * - poll `GET {root}/v2/video/generations?generation_id={id}` 至终态（状态串归一化）
 * - 终态取视频 URL（多路径容错提取）下载
 *
 * **参考图编码：data URI**（[ImageCodec.toDataUri]）：
 * - 首帧 → `image_url`
 * - 尾帧 → `last_image_url`（generic 取最常见的键；Veo 用 last_image_url / Kling 用 tail_image_url）
 * - 参考数组 → `image_urls`
 *
 * [root] 由 [normalizeRoot] 从 baseUrl 归一化：补 https:// + 去尾斜杠 + 去末尾版本段（/v1、/v2beta），
 * 后续显式拼 `/v2/video/generations`。
 *
 * 状态串归一化（[normalizeStatus]）：覆盖 aimlapi 官方枚举（queued/generating/completed/error）
 * 并并入跨厂商同义词；未知串当 running 继续轮询。终态 succeeded/failed。
 *
 * 视频 URL / task_id / status 各用一张多路径优先级表逐个试取，容忍各家回包结构差异。
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class V2VideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_V2
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<VideoCapability> = setOf(
        VideoCapability.TEXT_TO_VIDEO,
        VideoCapability.IMAGE_TO_VIDEO,
        VideoCapability.SEED_CONTROL
    )
    override val videoCapabilities: VideoCapabilities
        get() = videoCapabilitiesForModel(model)

    private val root: String = normalizeRoot(cfg.baseUrl)

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
        return pollAndBuild(client, jobId, request, onProgress = {})
    }

    private suspend fun submitPollDownload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val client = buildClient(cfg.validSubmitTimeout())
        val body = buildRequestBody(request, compressed)
        val submitUrl = cfg.submitUrl.ifBlank { root + SUBMIT_PATH }

        val generationId = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseGenerationId(resp) }

        onProgress(VideoProgress("submitted", "generationId=$generationId"))
        return pollAndBuild(client, generationId, request, onProgress)
    }

    /**
     * 构造 V2 canonical 请求体；缺省字段一律省略。
     *
     * 图像走 base64 data URI 内嵌（与 newapi 一致）：首帧 `image_url`、尾帧 `last_image_url`、
     * 参考数组 `image_urls`。首帧与参考数组为共存字段（with_start_frame=true）。
     */
    internal fun buildRequestBody(request: VideoGenerationRequest, compressed: List<CompressedRef>): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", request.prompt)
            addProperty("duration", request.durationSeconds)
        }
        // 画幅恒有值（默认 9:16），表达项目朝向意图；不识别该字段的中转站会忽略
        if (request.aspectRatio.isNotBlank()) root.addProperty("aspect_ratio", request.aspectRatio)
        request.resolution?.takeIf { it.isNotBlank() }?.let { root.addProperty("resolution", it) }
        request.seed?.let { root.addProperty("seed", it) }

        compressed.firstOrNull { it.label == "first_frame" }?.let { ff ->
            root.addProperty("image_url", ImageCodec.toDataUri(ff.path))
        }
        compressed.firstOrNull { it.label == "last_frame" }?.let { lf ->
            root.addProperty("last_image_url", ImageCodec.toDataUri(lf.path))
        }
        val refs = compressed.filter { it.label != "first_frame" && it.label != "last_frame" }
        if (refs.isNotEmpty()) {
            val arr = JsonArray()
            refs.forEach { arr.add(ImageCodec.toDataUri(it.path)) }
            root.add("image_urls", arr)
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

    private fun parseGenerationId(resp: Response): String {
        val respBody = resp.body?.string() ?: error("v2 submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody) }
            .getOrElse { error("v2 submit 响应非 JSON：$respBody") }
        return firstStrByPaths(root, TASK_ID_PATHS)
            ?: error("v2 submit 响应未能从已知路径提取 task_id：$respBody")
    }

    private suspend fun pollAndBuild(
        client: OkHttpClient,
        generationId: String,
        request: VideoGenerationRequest,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val maxWait = maxOf(600L, request.durationSeconds * 30L).seconds
        val interval = cfg.validPollInterval().milliseconds.coerceAtLeast(1.seconds)

        val final = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, generationId) },
            isDone = { normalizeStatus(firstStrByPaths(it, STATUS_PATHS)) == "succeeded" },
            isFailed = { extractFailure(it) != null },
            pollInterval = interval,
            maxWait = maxWait,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "v2 poll generationId=$generationId",
            onProgress = onProgress
        )

        val failure = extractFailure(final)
        if (failure != null) error(failure)
        val videoUrl = firstStrByPaths(final, VIDEO_URL_PATHS)
            ?: error("v2 任务完成但未能从已知路径提取视频 URL：$final")
        VideoBackendHttp.downloadVideo(videoUrl, request.outputPath)
        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = request.durationSeconds,
            videoUri = videoUrl,
            seed = request.seed,
            taskId = generationId
        )
    }

    private fun doPoll(client: OkHttpClient, generationId: String): JsonElement {
        // GET {root}/v2/video/generations?generation_id={id}
        val base = (cfg.pollUrlTemplate.ifBlank { root + SUBMIT_PATH }).toHttpUrl()
        val url = base.newBuilder().setQueryParameter("generation_id", generationId).build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("v2 poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("v2 poll 响应体为空")
        }
        return runCatching { JsonParser.parseString(respBody) }
            .getOrElse { error("v2 poll 响应非 JSON：$respBody") }
    }

    private fun extractFailure(state: JsonElement): String? {
        if (normalizeStatus(firstStrByPaths(state, STATUS_PATHS)) != "failed") return null
        val err = dig(state, "error")
        val msg = when {
            err is JsonObject -> err.get("message")?.takeIf { !it.isJsonNull }?.asString ?: err.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
            err?.isJsonPrimitive == true && err.asString.isNotBlank() -> err.asString
            else -> "unknown"
        }
        return "V2 视频生成失败：$msg"
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

    companion object {
        private const val DEFAULT_MODEL = "kling-v1"
        private const val SUBMIT_PATH = "/v2/video/generations"
        private const val DEFAULT_MAX_REFERENCE_IMAGES = 4

        // 多路径优先级表（配 [dig] 逐层走 key / list 下标）。取自流派 C 各家回包结构并集。
        // internal：单测需验证优先级表本身（顶层 url 优先于 data.task_result.videos[0].url 等）
        internal val VIDEO_URL_PATHS: Array<Array<Any>> = arrayOf(
            arrayOf("video", "url"),
            arrayOf("assets", "video"),
            arrayOf("output", "video_url"),
            arrayOf("content", "video_url"),
            arrayOf("data", "task_result", "videos", 0, "url"),
            arrayOf("url")
        )
        internal val TASK_ID_PATHS: Array<Array<Any>> = arrayOf(
            arrayOf("generation_id"),
            arrayOf("id"),
            arrayOf("task_id"),
            arrayOf("data", "task_id"),
            arrayOf("request_id"),
            arrayOf("data", "taskId")
        )
        internal val STATUS_PATHS: Array<Array<Any>> = arrayOf(
            arrayOf("status"),
            arrayOf("state"),
            arrayOf("data", "status"),
            arrayOf("data", "state"),
            arrayOf("output", "status")
        )

        // 状态串 → canonical（lowercase 后查表）。覆盖 aimlapi 官方枚举 + 跨厂商同义词。
        private val STATUS_SYNONYMS: Map<String, String> = mapOf(
            "completed" to "succeeded", "succeeded" to "succeeded", "succeed" to "succeeded",
            "success" to "succeeded",
            "failed" to "failed", "fail" to "failed", "error" to "failed", "expired" to "failed",
            "canceled" to "failed", "cancelled" to "failed",
            "generating" to "running", "in_progress" to "running", "running" to "running",
            "processing" to "running",
            "queued" to "queued", "queueing" to "queued", "preparing" to "queued",
            "submitted" to "queued", "pending" to "queued", "created" to "queued"
        )

        /** raw 状态值 → canonical：queued | running | succeeded | failed。未知串当 running。 */
        internal fun normalizeStatus(raw: String?): String {
            if (raw.isNullOrBlank()) return "running"
            return STATUS_SYNONYMS[raw.trim().lowercase()] ?: "running"
        }

        /** 按 path 逐层走 dict key / list 下标，任一层缺失返回 null。 */
        internal fun dig(payload: JsonElement?, vararg path: Any): JsonElement? {
            var cur: JsonElement? = payload ?: return null
            for (seg in path) {
                cur = when {
                    cur == null || cur.isJsonNull -> return null
                    seg is Int -> {
                        if (!cur.isJsonArray) return null
                        val arr = cur.asJsonArray
                        if (seg < 0 || seg >= arr.size()) null else arr[seg]
                    }
                    seg is String -> {
                        if (!cur.isJsonObject) return null
                        cur.asJsonObject.get(seg)
                    }
                    else -> return null
                }
            }
            return cur
        }

        /** 按优先级逐个试取第一个非空字符串值（int 容忍并 str 化）。 */
        internal fun firstStrByPaths(payload: JsonElement?, paths: Array<Array<Any>>): String? {
            for (path in paths) {
                val val_ = dig(payload, *path) ?: continue
                when {
                    val_.isJsonPrimitive && val_.asString.isNotBlank() -> return val_.asString.trim()
                    val_.isJsonPrimitive && val_.asNumber != null -> return val_.toString()
                }
            }
            return null
        }

        /**
         * 归一化为 root 形态：补 https:// + 去尾斜杠 + 去末尾版本段（/v1、/v2beta 等）。
         * 后续显式拼 `/v2/video/generations`。无 scheme 的纯域名先补 https://。
         */
        internal fun normalizeRoot(baseUrl: String): String {
            var s = baseUrl.trim().trimEnd('/')
            if (s.isNotEmpty() && !s.contains("://")) s = "https://$s"
            // 去末尾版本段 /v1 /v2 /v2beta /v1.0 等
            s = Regex("/v\\d+(?:\\.\\d+)?[a-zA-Z]*$").replace(s, "")
            return s
        }

        /**
         * 参考图级能力（移植 ArcReel v2_video_generations.py）：
         * all true, max=4, with_start_frame=true——
         * 协议 body 中 image_url（首帧）与 image_urls（参考数组）为共存字段。
         */
        fun videoCapabilitiesForModel(@Suppress("UNUSED_PARAMETER") model: String): VideoCapabilities {
            return VideoCapabilities(
                firstFrame = true,
                lastFrame = true,
                referenceImages = true,
                maxReferenceImages = DEFAULT_MAX_REFERENCE_IMAGES,
                referenceImagesWithStartFrame = true
            )
        }

        init {
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_V2) { cfg -> V2VideoBackend(cfg) }
        }
    }
}
