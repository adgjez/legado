package io.legado.app.help.ai.backends.video

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
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.ImageCodec
import io.legado.app.help.ai.backends.compress.PayloadLimits
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.help.ai.backends.compress.ReferenceSpec
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sora 视频后端（移植 ArcReel `video_backends/openai.py`）。
 *
 * 关键纪律：参考图用 **原始字节 multipart**（非 base64、非 data URI）——
 * ArcReel `_encode_start_image` 返回 `(filename, raw_bytes, mime)` 元组，
 * 经 OpenAI SDK 转 multipart `input_reference` 字段。Kotlin 无等价 SDK，
 * 手动构造 [MultipartBody]：每个参考图作为 `input_reference` part，
 * body 用 [File.asRequestBody] 直接流式上传原始字节。
 *
 * - 端点：POST `/v1/videos`（submit，multipart），GET `/v1/videos/{id}`（poll），
 *   GET `/v1/videos/{id}/content`（download，返回字节流非 URL）
 * - 默认 model：`sora-2`（`sora-2-pro` 加 1080p 档）
 * - 响应：`$.id` → poll `$.status` ∈ {queued,in_progress,completed,failed,expired}
 *   → download `GET /v1/videos/{id}/content` 取字节流写文件
 * - 参考图槽位：首帧与参考图**共享单 `input_reference` 槽位**（单张 part / 多张 list），
 *   故 [VideoCapabilities.referenceImages]=true, max=1, withStartFrame=false
 * - `expired` 状态：generate 抛 IllegalStateException；resume 抛 ResumeExpiredError
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class SoraVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_SORA
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
        val final = pollUntilDone(client, jobId, request.durationSeconds, onProgress = {}) { status ->
            // resume 路径：expired 抛 ResumeExpiredError
            if (status == "expired") throw ResumeExpiredError("任务已过期（provider=$typeId, jobId=$jobId）")
        }
        downloadContent(client, final.id, request.outputPath)
        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = final.seconds ?: request.durationSeconds,
            taskId = final.id
        )
    }

    private suspend fun submitPollDownload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val client = buildClient(cfg.validSubmitTimeout())
        val body = buildMultipartBody(request, compressed)
        val submitUrl = cfg.submitUrl.ifBlank { cfg.baseUrl.trimEnd('/') + SUBMIT_PATH }

        val videoId = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseVideoId(resp) }

        onProgress(VideoProgress("submitted", "videoId=$videoId"))
        val final = pollUntilDone(client, videoId, request.durationSeconds, onProgress) { status ->
            // generate 路径：expired 抛 IllegalStateException
            if (status == "expired") throw IllegalStateException("Sora 视频已过期：$videoId")
        }
        downloadContent(client, final.id, request.outputPath)
        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = final.seconds ?: request.durationSeconds,
            taskId = final.id,
            generateAudio = request.generateAudio
        )
    }

    /**
     * 构造 Sora multipart 请求体。
     *
     * - 文本字段：`model`、`prompt`、`seconds`（字符串）、`size`（从 aspectRatio+resolution 解析）
     * - 参考图：每个 CompressedRef 作为重复的 `input_reference` part，body = 原始字节流
     *   （[File.asRequestBody]，非 base64）——这是 ArcReel `_encode_start_image` 的 Kotlin 等价
     * - seed 可选
     */
    internal fun buildMultipartBody(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>
    ): MultipartBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("prompt", request.prompt)
            .addFormDataPart("seconds", request.durationSeconds.toString())
            .addFormDataPart("size", resolveSize(request.aspectRatio, request.resolution))

        request.seed?.let { builder.addFormDataPart("seed", it.toString()) }

        // 参考图：首帧 + 参考图共享 input_reference 槽位（单 part / 多 part）
        compressed.forEach { ref ->
            val mime = ImageCodec.mimeByExtension(ref.path.extension).toMediaType()
            val requestBody = ref.path.asRequestBody(mime)
            builder.addFormDataPart(
                "input_reference",
                ref.path.name,
                requestBody
            )
        }
        return builder.build()
    }

    private fun doSubmit(client: OkHttpClient, url: String, body: MultipartBody): Response {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body)
            .build()
        return client.newCall(req).execute()
    }

    private fun parseVideoId(resp: Response): String {
        val respBody = resp.body?.string() ?: error("sora submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("sora submit 响应非 JSON：$respBody") }
        val id = root.get("id")?.takeIf { !it.isJsonNull }?.asString
            ?: error("sora submit 响应缺 id：$respBody")
        return id
    }

    /**
     * 轮询直到 completed/failed/expired。
     *
     * @param onExpiredStatus expired 状态的分流回调（generate 抛 IllegalStateException，resume 抛 ResumeExpiredError）
     */
    private suspend fun pollUntilDone(
        client: OkHttpClient,
        videoId: String,
        durationSeconds: Int,
        onProgress: (VideoProgress) -> Unit,
        onExpiredStatus: (String) -> Unit
    ): SoraVideoStatus {
        val pollUrl = cfg.resolvePollUrl(videoId).ifBlank {
            cfg.baseUrl.trimEnd('/') + POLL_PATH.replace("{videoId}", videoId)
        }
        val maxWait = maxOf(600L, durationSeconds * 30L).seconds
        val interval = 5.seconds

        return VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            isDone = { it.status in DONE_STATUSES },
            isFailed = { it.status == "failed" },
            pollInterval = interval,
            maxWait = maxWait,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "sora poll videoId=$videoId",
            onProgress = onProgress
        ).also { final ->
            if (final.status == "expired") onExpiredStatus("expired")
        }
    }

    private fun doPoll(client: OkHttpClient, url: String): SoraVideoStatus {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("sora poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("sora poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("sora poll 响应非 JSON：$respBody") }
        val status = root.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
        val seconds = runCatching { root.get("seconds")?.asString?.toIntOrNull() }.getOrNull()
        return SoraVideoStatus(root.get("id")?.takeIf { !it.isJsonNull }?.asString ?: "", status, seconds, respBody)
    }

    /**
     * 下载视频内容——Sora 用 `GET /v1/videos/{id}/content` 取**字节流**（非 URL），
     * 直接写文件。这是 ArcReel `videos.download_content(id).content` 的 REST 等价。
     */
    private suspend fun downloadContent(client: OkHttpClient, videoId: String, outputPath: File) {
        val url = cfg.baseUrl.trimEnd('/') + DOWNLOAD_PATH.replace("{videoId}", videoId)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("sora download HTTP ${resp.code} ${resp.message}")
                val body = resp.body ?: error("sora download 响应体为空")
                body.byteStream().use { input ->
                    outputPath.parentFile?.mkdirs()
                    outputPath.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun buildReferenceSpecs(request: VideoGenerationRequest): List<ReferenceSpec> {
        // 首帧与参考图共享 input_reference 槽位，全用 ARRAY 角色（压缩管线统一处理）
        val specs = mutableListOf<ReferenceSpec>()
        request.startImage?.let { specs.add(ReferenceSpec(it, "first_frame", RefRole.FRAME)) }
        request.referenceImages?.forEachIndexed { i, f ->
            specs.add(ReferenceSpec(f, "ref_$i", RefRole.ARRAY))
        }
        // endImage 不支持（Sora 无尾帧能力）
        return specs
    }

    /**
     * size 解析（移植 ArcReel `_resolve_size`）。
     *
     * - sora-2(base)：仅 720p（720x1280 / 1280x720）
     * - sora-2-pro（model 名含 "pro"）：加 1080p 档
     * - 按比例最接近选档；缺 resolution 默认 720p（不擅自升档避免超额计费）
     */
    internal fun resolveSize(aspectRatio: String, resolution: String?): String {
        val ratio = parseAspectRatio(aspectRatio)
        val landscape = ratio.first >= ratio.second
        val candidates = mutableListOf<Pair<String, Pair<Int, Int>>>()
        candidates.add("720x1280" to (720 to 1280))
        candidates.add("1280x720" to (1280 to 720))
        if (model.lowercase().contains("pro")) {
            candidates.add("1080x1920" to (1080 to 1920))
            candidates.add("1920x1080" to (1920 to 1080))
        }
        // 过滤方向匹配
        val directional = candidates.filter { (it.second.first >= it.second.second) == landscape }
        // 若指定 resolution，按维度数值匹配（"1080"/"1080p" → 任一维度==1080）
        // 注：不能 startsWith——"1920x1080" 不以 "1080" 开头
        val byRes = if (resolution != null) {
            val resNum = resolution.trimEnd('p', 'P').toIntOrNull()
            val matchFn: (Pair<String, Pair<Int, Int>>) -> Boolean = if (resNum != null) {
                { it.second.first == resNum || it.second.second == resNum }
            } else {
                { it.first.startsWith(resolution, ignoreCase = true) }
            }
            directional.firstOrNull(matchFn) ?: directional.firstOrNull()
        } else {
            // 选比例最接近的
            directional.minByOrNull {
                val cr = it.second.first.toDouble() / it.second.second
                kotlin.math.abs(cr - ratio.first.toDouble() / ratio.second)
            }
        }
        return byRes?.first ?: if (landscape) "1280x720" else "720x1280"
    }

    private fun parseAspectRatio(ratio: String): Pair<Int, Int> {
        val parts = ratio.split(":")
        if (parts.size != 2) return 9 to 16
        return (parts[0].toIntOrNull() ?: 9) to (parts[1].toIntOrNull() ?: 16)
    }

    private fun buildClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private data class SoraVideoStatus(
        val id: String,
        val status: String,
        val seconds: Int?,
        val raw: String
    )

    companion object {
        private const val DEFAULT_MODEL = "sora-2"
        private const val SUBMIT_PATH = "/v1/videos"
        private const val POLL_PATH = "/v1/videos/{videoId}"
        private const val DOWNLOAD_PATH = "/v1/videos/{videoId}/content"
        private val DONE_STATUSES = setOf("completed", "failed", "expired")

        /**
         * 参考图级能力（移植 ArcReel openai.py）：
         * reference_images=true, max=1, withStartFrame=false——
         * 首帧与参考图共享 input_reference 单槽位，叠加语义未定义故不可叠加。
         */
        fun videoCapabilitiesForModel(@Suppress("UNUSED_PARAMETER") model: String): VideoCapabilities {
            return VideoCapabilities(
                firstFrame = true,
                lastFrame = false,
                referenceImages = true,
                maxReferenceImages = 1,
                referenceImagesWithStartFrame = false
            )
        }

        init {
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_SORA) { cfg -> SoraVideoBackend(cfg) }
        }
    }
}
