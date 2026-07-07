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
 * Kling 视频后端（移植 ArcReel `video_backends/kling.py` + `kling_shared.py`）。
 *
 * 走可灵原生视频端点：submit `POST /v1/videos/{subpath}` 取 `$.data.task_id` →
 * 轮询 `GET /v1/videos/{subpath}/{task_id}` 至 `task_status=succeed` 取
 * `$.data.task_result.videos[0].url` → 下载本地。
 *
 * **参考图编码：裸 base64**（[ImageCodec.toBareBase64]，无 `data:` 前缀）——
 * 可灵 `image` / `image_tail` / `image_list[].image` 接受 URL 或纯 base64，无 data URI 前缀。
 *
 * 鉴权：本实现走 **Bearer 模式**（`Authorization: Bearer {apiKey}`）——
 * legado 配置侧只有 `apiKey`，无 access_key/secret_key，故不实现 JWT 模式
 * （ArcReel 的 `KlingJWTManager` 动态签 token 在 app 侧无对应凭据）。
 *
 * 子路径派生（[buildPayload]）：
 * - 有 reference_images → `multi-image2video`（多图主体 R2V，需 caps.reference_images）
 * - 有 start_image → `image2video`（含可选尾帧 image_tail）
 * - 都无 → `text2video`（需 caps.text_to_video；kling-video-o1 不支持 t2v）
 *
 * 各模型能力按 [_KLING_VIDEO_CAPS] 表驱动（5 档，官方一手核实）；
 * 未登记 model（bearer 透传原生 model_name）回落保守默认 [_DEFAULT_CAPS]。
 *
 * 生命周期 1a 忠实：[generate] 自管 submit+poll+download。
 */
class KlingVideoBackend(private val cfg: AiVideoProviderConfig) : VideoBackend {

    override val typeId: String = AiVideoProviderConfig.TYPE_KLING
    override val model: String = cfg.model.ifBlank { DEFAULT_MODEL }
    private val caps: KlingModelCaps = lookupCaps(model)

    override val capabilities: Set<VideoCapability> = buildSet {
        if (caps.textToVideo) add(VideoCapability.TEXT_TO_VIDEO)
        if (caps.imageToVideo) add(VideoCapability.IMAGE_TO_VIDEO)
        if (caps.generateAudio) add(VideoCapability.GENERATE_AUDIO)
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
        // jobId 编码了 subpath:task_id:audio（submit 时持久化）——可灵查询端点按生成类型分路径，
        // resume 请求已无 start_image 可推断子路径，必须从 jobId 复原。
        val (subpath, taskId, persistedAudio) = decodeJobId(jobId)
        val generateAudio = persistedAudio ?: effectiveAudio(request)
        val client = buildClient(cfg.validPollTimeout())
        val videoUrl = pollUntilDone(client, subpath, taskId, request.durationSeconds, onProgress = {}) {}
        VideoBackendHttp.downloadVideo(videoUrl, request.outputPath)
        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = request.durationSeconds,
            videoUri = videoUrl,
            taskId = taskId,
            generateAudio = generateAudio
        )
    }

    private suspend fun submitPollDownload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>,
        onProgress: (VideoProgress) -> Unit
    ): VideoGenerationResult {
        val client = buildClient(cfg.validSubmitTimeout())
        val (subpath, body) = buildPayload(request, compressed)
        val generateAudio = effectiveAudio(request)
        val submitUrl = cfg.submitUrl.ifBlank { cfg.baseUrl.trimEnd('/') + SUBMIT_BASE + subpath }

        val taskId = VideoBackendHttp.submitPost(
            postFn = { doSubmit(client, submitUrl, body) },
            provider = typeId
        ).use { resp -> parseTaskId(resp) }

        onProgress(VideoProgress("submitted", "taskId=$taskId subpath=$subpath"))
        val videoUrl = pollUntilDone(client, subpath, taskId, request.durationSeconds, onProgress) {}
        VideoBackendHttp.downloadVideo(videoUrl, request.outputPath)
        return VideoGenerationResult(
            videoPath = request.outputPath,
            provider = typeId,
            model = model,
            durationSeconds = request.durationSeconds,
            videoUri = videoUrl,
            taskId = encodeJobId(subpath, taskId, generateAudio),
            generateAudio = generateAudio
        )
    }

    /**
     * 构造 Kling 请求体 + 派生子路径。
     *
     * - payload：`{model_name, prompt, mode, duration(字符串), aspect_ratio}` + 帧字段
     * - mode：resolution=4k → "4k"（仅 v3/v3-omni 可达）；否则 service_tier=pro → "pro" / "std"
     * - reference_images → `image_list:[{image:裸base64}]`（multi-image2video，需 caps.reference_images）
     * - start_image → `image:裸base64` + 可选 `image_tail:裸base64`（image2video）
     * - 都无 → text2video（需 caps.text_to_video）
     * - enable_audio 仅 text2video/image2video 且 caps.audio_param 时携带
     *
     * 生成时防御（fail-loud）：未声明参考图能力的 model 不得升级到 R2V 子路径；
     * 超上限参考图数同样拦截——否则发必然报错的请求且照常计费。
     */
    internal fun buildPayload(
        request: VideoGenerationRequest,
        compressed: List<CompressedRef>
    ): Pair<String, String> {
        val root = JsonObject().apply {
            addProperty("model_name", model)
            addProperty("prompt", request.prompt)
            addProperty("mode", resolveMode(request))
            addProperty("duration", request.durationSeconds.toString())
            addProperty("aspect_ratio", request.aspectRatio)
        }

        val refImages = compressed.filter { it.label != "first_frame" && it.label != "last_frame" }
        if (refImages.isNotEmpty()) {
            if (!caps.referenceImages) {
                error("可灵 model=$model 不支持多图参考（reference_images）")
            }
            if (refImages.size > caps.maxReferenceImages) {
                error("可灵 model=$model 参考图超上限：${refImages.size} > ${caps.maxReferenceImages}")
            }
            // 多图主体：image_list 为 [{image:base64}]（可灵原生 schema），无单首帧概念
            val arr = JsonArray()
            refImages.forEach { arr.add(JsonObject().apply { addProperty("image", ImageCodec.toBareBase64(it.path)) }) }
            root.add("image_list", arr)
            return MULTI_IMAGE2VIDEO to root.toString()
        }

        val firstFrame = compressed.firstOrNull { it.label == "first_frame" }
        val subpath: String = if (firstFrame == null) {
            if (!caps.textToVideo) {
                error("可灵 model=$model 不支持文生视频（无首帧/无参考）")
            }
            TEXT2VIDEO
        } else {
            root.addProperty("image", ImageCodec.toBareBase64(firstFrame.path))
            compressed.firstOrNull { it.label == "last_frame" }?.let { last ->
                root.addProperty("image_tail", ImageCodec.toBareBase64(last.path))
            }
            IMAGE2VIDEO
        }

        // enable_audio 仅 text2video/image2video 携带（multi-image2video 原生 schema 不含）；
        // v3 代默认有声，无能力 model 在此显式压制为 false，有能力的 v2-6(pro) 按需开启。
        if (caps.audioParam) {
            root.addProperty("enable_audio", effectiveAudio(request))
        }
        return subpath to root.toString()
    }

    /** 质量档 → mode：resolution=4k 独立成档（仅 v3/v3-omni 可达），否则 service_tier→std/pro。 */
    internal fun resolveMode(request: VideoGenerationRequest): String {
        if (request.resolution?.lowercase() == "4k") return "4k"
        return if (request.serviceTier.lowercase() == "pro") "pro" else "std"
    }

    /**
     * 实际是否产出视频内人声：请求要 + model 有 generate_audio 能力 + pro 档（官方仅 v2-6 pro ✅）。
     * 无能力的 model 恒 false——不被错配有声价。
     */
    internal fun effectiveAudio(request: VideoGenerationRequest): Boolean =
        request.generateAudio && caps.generateAudio && resolveMode(request) == "pro"

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
        val respBody = resp.body?.string() ?: error("kling submit 响应体为空")
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("kling submit 响应非 JSON：$respBody") }
        // 顶层 code 硬错误（鉴权失败/参数非法）优先暴露
        responseError(root)?.let { error(it) }
        val taskId = root.getAsJsonObject("data")?.get("task_id")?.asString
            ?: error("kling submit 响应缺 data.task_id：$respBody")
        return taskId
    }

    private suspend fun pollUntilDone(
        client: OkHttpClient,
        subpath: String,
        taskId: String,
        durationSeconds: Int,
        onProgress: (VideoProgress) -> Unit,
        onExpired: (String) -> Unit
    ): String {
        val pollUrl = cfg.baseUrl.trimEnd('/') + POLL_BASE + subpath + "/" + taskId
        val maxWait = maxOf(900L, durationSeconds * 60L).seconds
        val interval = 10.seconds

        val result = VideoBackendHttp.pollWithRetry(
            pollFn = { doPoll(client, pollUrl) },
            isDone = { it.status == "succeed" },
            isFailed = { it.status == "failed" },
            pollInterval = interval,
            maxWait = maxWait,
            retryIf = VideoBackendHttp::shouldRetryPoll,
            label = "kling poll subpath=$subpath taskId=$taskId",
            onProgress = onProgress
        )
        return result.videoUrl ?: error("kling poll succeed 但无 video url：${result.raw}")
    }

    private fun doPoll(client: OkHttpClient, url: String): KlingPollResult {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .get()
            .build()
        val respBody = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("kling poll HTTP ${resp.code} ${resp.message}")
            resp.body?.string() ?: error("kling poll 响应体为空")
        }
        val root = runCatching { JsonParser.parseString(respBody).asJsonObject }
            .getOrElse { error("kling poll 响应非 JSON：$respBody") }
        responseError(root)?.let { error(it) }
        val status = root.getAsJsonObject("data")?.get("task_status")?.asString ?: "unknown"
        val videoUrl = root.getAsJsonObject("data")?.getAsJsonObject("task_result")
            ?.getAsJsonArray("videos")?.firstOrNull()?.asJsonObject?.get("url")?.asString
        return KlingPollResult(status, videoUrl, respBody)
    }

    /** 顶层 code != 0 → 错误描述；0/缺失 → null。bearer/中转可能把 code 序列化成字符串，归一化 int 比较。 */
    private fun responseError(root: JsonObject): String? {
        val codeEl = root.get("code") ?: return null
        val code = runCatching { codeEl.asBigDecimal.toIntExact() }.getOrElse {
            runCatching { codeEl.asString.toInt() }.getOrNull()
        } ?: return "Kling API code=${codeEl}: ${root.get("message")}"
        return if (code != 0) "Kling API code=$code: ${root.get("message")}" else null
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

    private data class KlingPollResult(val status: String, val videoUrl: String?, val raw: String)

    /** 单个可灵视频模型的能力位（官方一手核实）。 */
    internal data class KlingModelCaps(
        val textToVideo: Boolean,
        val imageToVideo: Boolean,
        val lastFrame: Boolean,
        val referenceImages: Boolean,
        val maxReferenceImages: Int,
        val generateAudio: Boolean,
        val audioParam: Boolean
    )

    companion object {
        private const val DEFAULT_MODEL = "kling-v2-5-turbo"
        private const val SUBMIT_BASE = "/v1/videos/"
        private const val POLL_BASE = "/v1/videos/"
        private const val TEXT2VIDEO = "text2video"
        private const val IMAGE2VIDEO = "image2video"
        private const val MULTI_IMAGE2VIDEO = "multi-image2video"
        private val RESUMABLE_SUBPATHS = setOf(TEXT2VIDEO, IMAGE2VIDEO, MULTI_IMAGE2VIDEO)

        /** 多图主体（R2V）参考图上限保守值；待 app.klingai.com 控制台核对。 */
        private const val R2V_MAX_REFERENCE_IMAGES = 4

        /** turbo / 未登记 model 兜底：文/图生视频、首尾帧，无音频/参考。 */
        private val _DEFAULT_CAPS = KlingModelCaps(
            textToVideo = true, imageToVideo = true, lastFrame = true,
            referenceImages = false, maxReferenceImages = 0,
            generateAudio = false, audioParam = false
        )

        /**
         * 各视频模型能力查表（5 档，官方一手核实）：
         * - kling-v2-5-turbo：文/图生视频含首尾帧，无音频/参考（默认 model）
         * - kling-v3 / kling-v3-omni：旗舰，首尾帧 + 4K；v3-omni 多图主体 R2V
         * - kling-v2-6：pro 档支持视频内人声（enable_audio）
         * - kling-video-o1：图生 + 多图主体 R2V（不支持 t2v）
         */
        private val _KLING_VIDEO_CAPS: Map<String, KlingModelCaps> = mapOf(
            "kling-v2-5-turbo" to _DEFAULT_CAPS,
            "kling-v3" to KlingModelCaps(
                textToVideo = true, imageToVideo = true, lastFrame = true,
                referenceImages = false, maxReferenceImages = 0,
                generateAudio = false, audioParam = true
            ),
            "kling-v3-omni" to KlingModelCaps(
                textToVideo = true, imageToVideo = true, lastFrame = true,
                referenceImages = true, maxReferenceImages = R2V_MAX_REFERENCE_IMAGES,
                generateAudio = false, audioParam = true
            ),
            "kling-v2-6" to KlingModelCaps(
                textToVideo = true, imageToVideo = true, lastFrame = true,
                referenceImages = false, maxReferenceImages = 0,
                generateAudio = true, audioParam = true
            ),
            "kling-video-o1" to KlingModelCaps(
                textToVideo = false, imageToVideo = true, lastFrame = true,
                referenceImages = true, maxReferenceImages = R2V_MAX_REFERENCE_IMAGES,
                generateAudio = false, audioParam = false
            )
        )

        /**
         * 按 model 取能力位：剥厂商前缀（`vendor/kling-v3-omni` / `provider:kling-v3-omni`）
         * 后取最后一段 + lower 归一化，再做【精确】命中。未登记 model 回落保守默认——
         * 绝不按子串猜未知 model 的能力上限（误报参考图能力会触发 provider 400 或计费漂移）。
         */
        internal fun lookupCaps(model: String): KlingModelCaps {
            val key = model.replace(":", "/").substringAfterLast("/").trim().lowercase()
            return _KLING_VIDEO_CAPS[key] ?: _DEFAULT_CAPS
        }

        /**
         * 参考图级能力（移植 ArcReel kling.py `video_capabilities_for_model`）：
         * first_frame 恒真（各档均支持 i2v 首帧）；last_frame / reference_images / 上限按 model 查表。
         */
        fun videoCapabilitiesForModel(model: String): VideoCapabilities {
            val c = lookupCaps(model)
            return VideoCapabilities(
                firstFrame = true,
                lastFrame = c.lastFrame,
                referenceImages = c.referenceImages,
                maxReferenceImages = c.maxReferenceImages,
                referenceImagesWithStartFrame = false
            )
        }

        /** 把「子路径:task_id:有声标志」编进持久化 job_id（resume 据此复原查询端点 + 有声决策）。 */
        internal fun encodeJobId(subpath: String, taskId: String, generateAudio: Boolean): String =
            "$subpath:$taskId:${if (generateAudio) 1 else 0}"

        /**
         * 从持久化 job_id 复原 (子路径, task_id, 有声标志)。
         * 新格式 `subpath:task_id:audio`（3 段）；旧格式 `subpath:task_id`（2 段，audio=null）；
         * 无已知前缀回落 text2video、整串作 task_id。
         */
        internal fun decodeJobId(jobId: String): Triple<String, String, Boolean?> {
            val parts = jobId.split(":")
            if (parts.size == 3 && parts[0] in RESUMABLE_SUBPATHS && parts[2] in listOf("0", "1")) {
                return Triple(parts[0], parts[1], parts[2] == "1")
            }
            val idx = jobId.indexOf(":")
            if (idx > 0 && jobId.substring(0, idx) in RESUMABLE_SUBPATHS) {
                return Triple(jobId.substring(0, idx), jobId.substring(idx + 1), null)
            }
            return Triple(TEXT2VIDEO, jobId, null)
        }

        init {
            VideoBackendRegistry.register(AiVideoProviderConfig.TYPE_KLING) { cfg -> KlingVideoBackend(cfg) }
        }
    }
}
