package io.legado.app.help.ai.backends.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.ImageCodec
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
import kotlinx.coroutines.Dispatchers
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
        // resume：直接用 jobId 轮询（submit 已完成）
        val client = buildClient(cfg.validPollTimeout())
        val videoUrl = pollUntilDone(client, jobId, onProgress = {}) { /* no-op */ }
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
        val videoUrl = pollUntilDone(client, taskId, onProgress) { }
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

    internal fun buildSubmitBody(request: VideoGenerationRequest, compressed: List<CompressedRef>): String {
        val root = JsonObject()
        root.addProperty("model", model)
        val content = JsonArray()
        // 文本 prompt
        content.add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", request.prompt)
            addProperty("role", "user")
        })
        // 参考图：首帧/尾帧/参考图统一走 image_url，role 区分
        compressed.forEach { ref ->
            content.add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", ImageCodec.toDataUri(ref.path))
                })
                addProperty("role", ref.label.ifBlank { "user" })
            })
        }
        root.add("content", content)
        // 可选参数
        request.seed?.let { root.addProperty("seed", it) }
        if (request.generateAudio) {
            root.add("extra_body", JsonObject().apply {
                addProperty("with_audio", true)
            })
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
        val respBody = resp.body?.string() ?: error("ark submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("ark submit 响应非 JSON：$respBody") }
        // Ark 返回 { "id": "..." }
        val id = root.get("id")?.asString
            ?: error("ark submit 响应缺 id：$respBody")
        return id
    }

    private suspend fun pollUntilDone(
        client: OkHttpClient,
        taskId: String,
        onProgress: (VideoProgress) -> Unit,
        retryFallback: suspend () -> Unit
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
            label = "ark poll taskId=$taskId",
            onProgress = onProgress
        )
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
        val status = root.get("status")?.asString ?: "unknown"
        // content.video_url 在 succeeded 时存在
        val videoUrl = runCatching {
            root.getAsJsonObject("content")?.get("video_url")?.asString
        }.getOrNull()
        return ArkPollResult(status, videoUrl, respBody)
    }

    private fun buildReferenceSpecs(request: VideoGenerationRequest): List<ReferenceSpec> {
        val specs = mutableListOf<ReferenceSpec>()
        // 首帧（startImage）→ FRAME，role=user
        request.startImage?.let { specs.add(ReferenceSpec(it, "first_frame", RefRole.FRAME)) }
        // 尾帧（endImage）→ FRAME，role=last_frame
        request.endImage?.let { specs.add(ReferenceSpec(it, "last_frame", RefRole.FRAME)) }
        // 参考图（referenceImages）→ ARRAY，role=user
        request.referenceImages?.forEachIndexed { i, f ->
            specs.add(ReferenceSpec(f, "ref_$i", RefRole.ARRAY))
        }
        return specs
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private data class ArkPollResult(val status: String, val videoUrl: String?, val raw: String)

    companion object {
        private const val DEFAULT_MODEL = "doubao-seedance-2-0"
        private const val SUBMIT_PATH = "/contents/generations/tasks"
        private const val POLL_PATH = "/contents/generations/tasks/{taskId}"
        private val FAILED_STATUSES = setOf("failed", "cancelled", "error")

        /** Seedance 2.0 模型判定（模型名含 seedance-2 / seedance-2.0）。 */
        private fun isSeedance2(model: String): Boolean {
            val m = model.lowercase()
            return m.contains("seedance-2") || m.contains("seedance-2.0") || m.contains("seedance-2-")
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
            VideoBackendRegistry.register(TYPE_ARK) { cfg -> ArkVideoBackend(cfg) }
        }
    }
}
