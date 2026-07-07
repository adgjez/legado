package io.legado.app.help.ai.backends.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.compress.ImageCodec
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
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.PayloadLimits
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.help.ai.backends.compress.ReferenceSpec
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Ark（火山引擎 Seedance）视频 backend。
 *
 * 移植 ArcReel `video_backends/ark.py`：
 * - 端点：POST `/contents/generations/tasks`（submit），GET `/contents/generations/tasks/{id}`（poll）
 * - 请求体：`content[]{type:text/image_url, image_url:{url}, role}`，参考图用 **data URI**
 * - 模型：Seedance 1.5 / 2.0；`_is_seedance_2` 判定走不同能力分支
 * - 响应：`$.id` → poll → `$.status` → `$.content.video_url`
 *
 * 关键纪律：参考图用 [ImageCodec.toDataUri]（NO_WRAP base64，无换行）避免 Incorrect padding。
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class ArkVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_ARK
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<VideoCapability> = buildSet {
        add(VideoCapability.TEXT_TO_VIDEO)
        add(VideoCapability.IMAGE_TO_VIDEO)
        add(VideoCapability.GENERATE_AUDIO)
        add(VideoCapability.SEED_CONTROL)
        // FLEX_TIER（service_tier 参数）仅 seedance-1.x 等老模型支持；seedance-2-0/2.0 系列
        // 上游在 r2v 下会 400 拒绝该参数，必须从能力集中剔除。
        if (!isSeedance2(model)) add(VideoCapability.FLEX_TIER)
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
        // resume：直接用 jobId 轮询（submit 已完成）。任务不存在/已过期
        // （404/task_not_found/expired）转 ResumeExpiredError，worker 据此区分「过期」与「真错误」。
        return try {
            val client = buildClient(cfg.validPollTimeout())
            val videoUrl = pollUntilDone(client, jobId, onProgress = {})
            downloadVideoWithRetry(videoUrl, request.outputPath)
            VideoGenerationResult(
                videoPath = request.outputPath,
                provider = typeId,
                model = model,
                durationSeconds = request.durationSeconds,
                videoUri = videoUrl,
                taskId = jobId,
                generateAudio = request.generateAudio
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isArkNotFound(e)) {
                throw ResumeExpiredError("Ark 任务已过期或不存在（provider=$typeId, jobId=$jobId）：${e.message}")
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
        downloadVideoWithRetry(videoUrl, request.outputPath)
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

    internal fun buildSubmitBody(request: VideoGenerationRequest, compressed: List<CompressedRef>): String {
        val root = JsonObject()
        root.addProperty("model", model)
        val content = JsonArray()
        // 文本 prompt：ArcReel 文本条目仅 type+text，无 role
        content.add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", request.prompt)
        })
        // 参考图：首帧/尾帧/参考图统一走 image_url，role 在顶层区分（Ark 要求）
        // - FRAME：首帧=first_frame，尾帧=last_frame（label 由 buildReferenceSpecs 注入）
        // - ARRAY：参考图=reference_image
        compressed.forEach { ref ->
            content.add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", ImageCodec.toDataUri(ref.path))
                })
                addProperty("role", when (ref.role) {
                    RefRole.ARRAY -> "reference_image"
                    RefRole.FRAME -> ref.label
                })
            })
        }
        root.add("content", content)
        // 必填参数（移植 ark.py create_params）
        root.addProperty("ratio", request.aspectRatio)
        root.addProperty("duration", request.durationSeconds)
        // generate_audio 是顶层布尔，非 Agnes 的 extra_body.with_audio
        root.addProperty("generate_audio", request.generateAudio)
        root.addProperty("watermark", false)
        // 可选参数
        request.resolution?.let { root.addProperty("resolution", it) }
        // seedance-2 不接受 service_tier；仅声明 FLEX_TIER 能力时下发
        if (VideoCapability.FLEX_TIER in capabilities) {
            root.addProperty("service_tier", request.serviceTier)
        }
        request.seed?.let { root.addProperty("seed", it) }
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
        val respBody = resp.body?.string() ?: error("ark submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("ark submit 响应非 JSON：$respBody") }
        // Ark 返回 { "id": "..." }
        val id = root.get("id")?.takeIf { !it.isJsonNull }?.asString
            ?: error("ark submit 响应缺 id：$respBody")
        return id
    }

    private suspend fun pollUntilDone(
        client: OkHttpClient,
        taskId: String,
        onProgress: (VideoProgress) -> Unit
    ): String {
        val pollUrl = cfg.resolvePollUrl(taskId).ifBlank {
            normalizeBaseUrl(cfg.baseUrl) + POLL_PATH.replace("{taskId}", taskId)
        }
        // poll 间隔/max_wait 分档（移植 ark.py:_poll_until_done）：
        // - seedance-2（default tier）：interval=10s, max=600s
        // - 非 seedance-2（flex tier）：interval=60s, max=3600s
        val seedance2 = isSeedance2(model)
        val interval = if (seedance2) 10.seconds else 60.seconds
        val maxWait = if (seedance2) 600.seconds else 3600.seconds

        val result = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            // 终态（succeeded/failed/expired）都视为 done，由下方统一抛带 status 的错误，
            // 便于 resumeVideo 识别 expired → ResumeExpiredError
            isDone = { it.status == "succeeded" || it.status in FAILED_STATUSES },
            isFailed = { false },
            pollInterval = interval,
            maxWait = maxWait,
            // Ark：poll 404 视为任务不存在（终态，不重试），让 resume 路径识别为 ResumeExpiredError
            retryIf = { e ->
                !e.message.orEmpty().lowercase().contains("http 404") &&
                    VideoBackendHttp.shouldRetryPoll(e)
            },
            label = "ark poll taskId=$taskId",
            onProgress = onProgress
        )
        if (result.status != "succeeded") {
            error("ark 任务失败 status=${result.status}：${result.raw}")
        }
        return result.videoUrl ?: error("ark poll succeeded 但无 video_url：${result.raw}")
    }

    private fun doPoll(client: OkHttpClient, url: String): ArkPollResult {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("ark poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("ark poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("ark poll 响应非 JSON：$respBody") }
        val status = root.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
        // content.video_url 在 succeeded 时存在
        val videoUrl = runCatching {
            root.getAsJsonObject("content")?.get("video_url")?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
        return ArkPollResult(status, videoUrl, respBody)
    }

    private fun buildReferenceSpecs(request: VideoGenerationRequest): List<ReferenceSpec> {
        val specs = mutableListOf<ReferenceSpec>()
        // 首帧（startImage）→ FRAME，label=first_frame（buildSubmitBody 透传为 role）
        request.startImage?.let { specs.add(ReferenceSpec(it, "first_frame", RefRole.FRAME)) }
        // 尾帧（endImage）→ FRAME，label=last_frame
        request.endImage?.let { specs.add(ReferenceSpec(it, "last_frame", RefRole.FRAME)) }
        // 参考图（referenceImages）→ ARRAY，buildSubmitBody 统一映射为 role=reference_image
        request.referenceImages?.forEachIndexed { i, f ->
            specs.add(ReferenceSpec(f, "ref_$i", RefRole.ARRAY))
        }
        return specs
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 下载视频并针对 Ark succeeded 后 URL 未就绪（HTTP 400 video_not_ready）瞬态重试
     * （移植 ark.py:_download_video_with_retry）。其余错误由内层 [VideoBackendHttp.downloadVideo] 处理。
     */
    private suspend fun downloadVideoWithRetry(videoUrl: String, outputPath: File) {
        var lastError: Throwable? = null
        for (attempt in 0 until VideoBackendHttp.DOWNLOAD_MAX_ATTEMPTS) {
            try {
                VideoBackendHttp.downloadVideo(videoUrl, outputPath)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                if (!isVideoNotReady400(e) || attempt == VideoBackendHttp.DOWNLOAD_MAX_ATTEMPTS - 1) {
                    throw e
                }
                delay(VideoBackendHttp.DOWNLOAD_BACKOFF_MS.getOrNull(attempt) ?: VideoBackendHttp.DOWNLOAD_BACKOFF_MS.last())
            }
        }
        throw lastError ?: error("下载视频失败：$videoUrl")
    }

    /** Ark video_not_ready 判定：downloadVideo 在 400 时抛 "下载视频失败：HTTP 400 ..."。 */
    private fun isVideoNotReady400(e: Throwable): Boolean {
        return e.message.orEmpty().lowercase().contains("http 400")
    }

    /** Ark 任务「不存在/已过期」判定（移植 ark.py:_is_ark_not_found）。 */
    private fun isArkNotFound(e: Throwable): Boolean {
        val msg = e.message.orEmpty().lowercase()
        return msg.contains("http 404") ||
            msg.contains("task_not_found") ||
            msg.contains("tasknotfound") ||
            msg.contains("expired")
    }

    private data class ArkPollResult(val status: String, val videoUrl: String?, val raw: String)

    companion object {
        private const val DEFAULT_MODEL = "doubao-seedance-1-5-pro-251215"
        private const val SUBMIT_PATH = "/contents/generations/tasks"
        private const val POLL_PATH = "/contents/generations/tasks/{taskId}"
        private const val ARK_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
        private val FAILED_STATUSES = setOf("failed", "cancelled", "error", "expired")

        /** Seedance 2.0 模型判定：精确匹配已验证的 seedance-2-0 / seedance-2.0（含 fast 变体）。 */
        private fun isSeedance2(model: String): Boolean {
            val m = model.lowercase()
            return m.contains("seedance-2-0") || m.contains("seedance-2.0")
        }

        /** base_url 归一化：缺省回落 ARK_BASE_URL（移植 ark_shared.create_ark_client）。 */
        private fun normalizeBaseUrl(raw: String): String {
            return raw.trim().trimEnd('/').ifBlank { ARK_BASE_URL }
        }

        /**
         * 参考图级能力（移植 ArcReel ark.py）：
         * - Seedance 2.0：last_frame=true, reference_images=true, max=9
         * - Seedance 1.5 及其他：默认（first_frame=true）
         */
        fun videoCapabilitiesForModel(model: String): VideoCapabilities {
            return if (isSeedance2(model)) {
                VideoCapabilities(
                    firstFrame = true,
                    lastFrame = true,
                    referenceImages = true,
                    maxReferenceImages = 9,
                    referenceImagesWithStartFrame = false
                )
            } else {
                VideoCapabilities(firstFrame = true)
            }
        }

        init {
            // 注册到 Registry（P2a）
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_ARK) { cfg -> ArkVideoBackend(cfg) }
        }
    }
}
