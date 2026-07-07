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
import kotlin.time.Duration.Companion.seconds

/**
 * Veo 视频后端（移植 ArcReel `video_backends/gemini.py`）。
 *
 * **关键决策：REST 兜底**（spec 第 7.4 节）。ArcReel Python 用 Google GenAI SDK
 * 的 `models.generate_videos` + `Image(image_bytes=..., mime_type=...)` 对象；
 * Android 无等价 SDK（google-genai Python 包无 Kotlin/Android 对应），故走 REST API。
 *
 * REST 端点（Gemini v1beta）：
 * - submit：`POST /v1beta/models/{model}:predictLongRunning`
 *   body `instances[0]` 含 prompt + image（inlineData.data base64）/ reference_images
 * - poll：`GET /v1beta/{operation_name}`（operation.name 是 LRO 名）
 * - download：operation.response.generatedVideos[0].video.gcsUri 或 videoBytes
 *
 * 参考图编码：REST 形态用 `inlineData.data` base64（非 SDK Image 对象）——
 * 这是 SDK `Image(image_bytes=...)` 的 REST 等价（SDK 内部就是 inlineData）。
 * 用 [ImageCodec.toBareBase64]（NO_WRAP 无换行）避免 padding 问题。
 *
 * - 默认 model：`veo-3.1`（spec 标注；ArcReel 委托 cost_calculator.DEFAULT_VIDEO_MODEL）
 * - 能力：T2V/I2V/NEGATIVE_PROMPT/VIDEO_EXTEND；参考图 lastFrame=true, max=3
 * - 带参考图时 durationSeconds 必须为 8（ArcReel 注释，否则批量被拒）——此处不强制，由调用方负责
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class VeoVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_VEO
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    override val capabilities: Set<VideoCapability> = setOf(
        VideoCapability.TEXT_TO_VIDEO,
        VideoCapability.IMAGE_TO_VIDEO,
        VideoCapability.NEGATIVE_PROMPT,
        VideoCapability.VIDEO_EXTEND
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
        // jobId 是 LRO operation.name，直接 poll 直到 done
        val client = buildClient(cfg.validPollTimeout())
        val operation = pollUntilDone(client, jobId, onProgress = {}) {}
        return extractResultAndDownload(client, operation, request)
    }

    private suspend fun submitPollDownload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val client = buildClient(cfg.validSubmitTimeout())
        val body = buildSubmitBody(request, compressed)
        val submitUrl = cfg.submitUrl.ifBlank {
            cfg.baseUrl.trimEnd('/') + SUBMIT_PATH.replace("{model}", model)
        }

        val operationName = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseOperationName(resp) }

        onProgress(VideoProgress("submitted", "operation=$operationName"))
        val operation = pollUntilDone(client, operationName, onProgress) {}
        return extractResultAndDownload(client, operation, request)
    }

    /**
     * 构造 Gemini predictLongRunning 请求体。
     *
     * REST 形态（对应 SDK `GenerateVideosSource(prompt, image)` + `GenerateVideosConfig`）：
     * ```json
     * {
     *   "instances": [{
     *     "prompt": "...",
     *     "image": {"bytesBase64Encoded": "..."},           // 首帧（可选）
     *     "storageUri": "...",                                // 占位，P5 配置
     *   }],
     *   "parameters": {
     *     "aspectRatio": "9:16",
     *     "durationSeconds": "5",
     *     "resolution": "720p",                              // 可选
     *     "lastFrame": {"bytesBase64Encoded": "..."},         // 尾帧（可选）
     *     "referenceImages": [                               // 参考图（可选）
     *       {"image": {"bytesBase64Encoded": "..."}, "referenceType": "ASSET"}
     *     ],
     *     "generateAudio": true                              // 仅 vertex
     *   }
     * }
     * ```
     *
     * 参考图用 [ImageCodec.toBareBase64]（NO_WRAP base64）——SDK Image(image_bytes=)
     * 的 REST 等价是 inlineData.data / bytesBase64Encoded base64。
     */
    internal fun buildSubmitBody(request: VideoGenerationRequest, compressed: List<CompressedRef>): String {
        val root = JsonObject()

        val instances = JsonArray()
        val instance = JsonObject()
        instance.addProperty("prompt", request.prompt)

        // 首帧 → image.bytesBase64Encoded（裸 base64）
        val firstFrame = compressed.firstOrNull { it.label == "first_frame" }
        if (firstFrame != null) {
            val imageObj = JsonObject()
            imageObj.addProperty("bytesBase64Encoded", ImageCodec.toBareBase64(firstFrame.path))
            instance.add("image", imageObj)
        }

        instances.add(instance)
        root.add("instances", instances)

        // parameters
        val params = JsonObject()
        params.addProperty("aspectRatio", request.aspectRatio)
        params.addProperty("durationSeconds", request.durationSeconds.toString())
        request.resolution?.takeIf { it.isNotBlank() }?.let { params.addProperty("resolution", it) }
        request.seed?.let { params.addProperty("seed", it) }

        // 尾帧 → lastFrame.bytesBase64Encoded
        val lastFrame = compressed.firstOrNull { it.label == "last_frame" }
        if (lastFrame != null) {
            val lastObj = JsonObject()
            lastObj.addProperty("bytesBase64Encoded", ImageCodec.toBareBase64(lastFrame.path))
            params.add("lastFrame", lastObj)
        }

        // 参考图 → referenceImages[]（referenceType=ASSET）
        val refImages = compressed.filter { it.label != "first_frame" && it.label != "last_frame" }
        if (refImages.isNotEmpty()) {
            val refArr = JsonArray()
            refImages.forEach { ref ->
                val refObj = JsonObject()
                val imageObj = JsonObject()
                imageObj.addProperty("bytesBase64Encoded", ImageCodec.toBareBase64(ref.path))
                refObj.add("image", imageObj)
                refObj.addProperty("referenceType", "ASSET")
                refArr.add(refObj)
            }
            params.add("referenceImages", refArr)
        }

        if (request.generateAudio) {
            params.addProperty("generateAudio", true)
        }

        root.add("parameters", params)
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

    private fun parseOperationName(resp: Response): String {
        val respBody = resp.body?.string() ?: error("veo submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("veo submit 响应非 JSON：$respBody") }
        val name = root.get("name")?.asString
            ?: error("veo submit 响应缺 name（operation name）：$respBody")
        return name
    }

    /**
     * 轮询 LRO operation 直到 done=true。
     *
     * Veo 轮询间隔 20s（Google 官方推荐），max_wait 600s。
     * isFailed 恒 false（ArcReel 语义：poll 完成后才检查 operation.error）。
     */
    private suspend fun pollUntilDone(
        client: OkHttpClient,
        operationName: String,
        onProgress: (VideoProgress) -> Unit,
        onExpired: () -> Unit
    ): VeoOperation {
        val pollUrl = cfg.resolvePollUrl(operationName).ifBlank {
            cfg.baseUrl.trimEnd('/') + POLL_PATH.replace("{operationName}", operationName)
        }
        val result = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            isDone = { it.done },
            isFailed = { false },
            pollInterval = 20.seconds,
            maxWait = 600.seconds,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "veo poll operation=$operationName",
            onProgress = onProgress
        )
        // poll 完成后检查错误
        if (!result.done) error("veo poll 未完成但 pollWithRetry 返回：$result")
        if (result.error != null) error("veo 视频生成失败：${result.error}")
        if (result.response == null || result.response.generatedVideos.isNullOrEmpty()) {
            error("veo 视频生成失败：API 返回空结果")
        }
        return result
    }

    private fun doPoll(client: OkHttpClient, url: String): VeoOperation {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("veo poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("veo poll 响应体为空")
        }
        return parseOperation(respBody)
    }

    private fun parseOperation(respBody: String): VeoOperation {
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("veo poll 响应非 JSON：$respBody") }
        val done = root.get("done")?.asBoolean ?: false
        val name = root.get("name")?.asString ?: ""
        val error = root.getAsJsonObject("error")?.let { errObj ->
            errObj.get("message")?.asString
        }
        val response = root.getAsJsonObject("response")?.let { respObj ->
            val generatedVideos = respObj.getAsJsonArray("generatedVideos")?.map { v ->
                val videoObj = v.asJsonObject.getAsJsonObject("video")
                VeoGeneratedVideo(
                    gcsUri = videoObj?.get("gcsUri")?.asString,
                    videoBytes = videoObj?.get("videoBytes")?.asString,
                    uri = videoObj?.get("uri")?.asString
                )
            }
            VeoOperationResponse(generatedVideos)
        }
        return VeoOperation(name, done, error, response)
    }

    private suspend fun extractResultAndDownload(
        client: OkHttpClient,
        operation: VeoOperation,
        request: VideoGenerationRequest
    ): VideoGenerationResult {
        val video = operation.response?.generatedVideos?.firstOrNull()
            ?: error("veo 无 generatedVideos")
        val videoUri = video.uri ?: video.gcsUri
        // 优先 videoBytes（base64 解码直写），其次 uri（URL 下载）
        when {
            !video.videoBytes.isNullOrBlank() -> {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes = android.util.Base64.decode(
                        video.videoBytes, android.util.Base64.NO_WRAP
                    )
                    request.outputPath.parentFile?.mkdirs()
                    request.outputPath.writeBytes(bytes)
                }
            }
            !videoUri.isNullOrBlank() -> {
                VideoBackendHttp.downloadVideo(videoUri, request.outputPath)
            }
            else -> error("veo 视频生成成功但无法获取视频数据（无 videoBytes/uri）")
        }
        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = request.durationSeconds,
            videoUri = videoUri,
            taskId = operation.name,
            generateAudio = request.generateAudio
        )
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

    private data class VeoOperation(
        val name: String,
        val done: Boolean,
        val error: String?,
        val response: VeoOperationResponse?
    )

    private data class VeoOperationResponse(
        val generatedVideos: List<VeoGeneratedVideo>?
    )

    private data class VeoGeneratedVideo(
        val gcsUri: String?,
        val videoBytes: String?,
        val uri: String?
    )

    companion object {
        private const val DEFAULT_MODEL = "veo-3.1"
        private const val SUBMIT_PATH = "/v1beta/models/{model}:predictLongRunning"
        private const val POLL_PATH = "/v1beta/{operationName}"

        /**
         * 参考图级能力（移植 ArcReel gemini.py）：
         * last_frame=true, reference_images=true, max=3, withStartFrame=false。
         * 注：带参考图时 durationSeconds 必须为 8——与短镜头时长冲突会让请求批量被拒。
         */
        fun videoCapabilitiesForModel(@Suppress("UNUSED_PARAMETER") model: String): VideoCapabilities {
            return VideoCapabilities(
                firstFrame = true,
                lastFrame = true,
                referenceImages = true,
                maxReferenceImages = 3,
                referenceImagesWithStartFrame = false
            )
        }

        init {
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_VEO) { cfg -> VeoVideoBackend(cfg) }
        }
    }
}
