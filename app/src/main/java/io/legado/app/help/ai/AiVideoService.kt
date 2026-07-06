package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.JsonParser
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 文生视频 API 客户端，仿 [AiImageService] 的形态。
 *
 * 面向 veo3.1 风格的 submit/poll 双端点 API：
 * 1. [submit]：POST multipart/form-data 到 [AiVideoProviderConfig.submitUrl]，返回 taskId
 * 2. [poll]：GET [AiVideoProviderConfig.resolvePollUrl]，轮询直到 done/failed
 * 3. [downloadToLocal]：把视频 URL 下载到 `filesDir/novel_video/<jobId>/`
 *
 * Provider 解析顺序：显式传入 > [NovelVideoParams.videoProviderId] > [AppConfig.aiCurrentVideoProvider]。
 */
object AiVideoService {

    /** 视频任务轮询结果。 */
    sealed class VideoPollResult {
        data class Success(val videoUrl: String) : VideoPollResult()
        data class Failed(val message: String) : VideoPollResult()
        /** 仍在排队/进行中，需继续轮询。 */
        object Polling : VideoPollResult()
    }

    /**
     * 提交视频生成任务（旧签名，向后兼容）。
     *
     * 内部构造 [VideoSubmitRequest] 后调 [submit] 新签名。
     * 高级参数完全由 [AiVideoProviderConfig.defaultParamsJson] 提供（按模型自适应）。
     */
    suspend fun submit(
        prompt: String,
        seconds: Int,
        size: String,
        referenceImages: List<String>,
        provider: AiVideoProviderConfig? = null
    ): String = withContext(Dispatchers.IO) {
        submit(
            VideoSubmitRequest(
                prompt = prompt,
                seconds = seconds,
                size = size,
                referenceImages = referenceImages
            ),
            provider
        )
    }

    /**
     * 提交视频生成任务（新签名，接收统一抽象 [VideoSubmitRequest]）。
     *
     * 按 [AiVideoProviderConfig.type] 分发：
     * - [AiVideoProviderConfig.TYPE_OPENAI] → [submitMultipart]（veo3.1 风格 multipart）
     * - [AiVideoProviderConfig.TYPE_AGNES] → [submitAgnesJson]（Agnes V2.0 JSON body）
     * - [AiVideoProviderConfig.TYPE_DOUBAO] → [submitDoubaoJson]（豆包 Seedance 2.0 content[] 结构）
     *
     * 调用方只需在 [request] 中提供基础字段（prompt/seconds/size/referenceImages），
     * 高级字段（mode/negative_prompt/seed/camera_fixed/generate_audio 等）会通过
     * [mergeProviderParams] 从 [AiVideoProviderConfig.defaultParamsJson] 自动注入，
     * 实现「按所选模型自适应发挥其能力」。若 [request] 显式指定了某高级字段，则覆盖 Provider 默认值。
     *
     * @return taskId
     */
    suspend fun submit(
        request: VideoSubmitRequest,
        provider: AiVideoProviderConfig? = null
    ): String = withContext(Dispatchers.IO) {
        val target = resolveProvider(provider)
        val requestUrl = buildSubmitUrl(target)
        val maxRefs = target.maxReferenceImages.coerceAtLeast(1)
        val refs = request.referenceImages.filter { it.isNotBlank() }.take(maxRefs)
        // 关键：合并 Provider 的 defaultParamsJson 高级参数到 request（按模型自适应）
        val merged = mergeProviderParams(request, target).copy(referenceImages = refs)

        val response = when (target.type) {
            AiVideoProviderConfig.TYPE_AGNES ->
                submitAgnesJson(target, requestUrl, merged)
            AiVideoProviderConfig.TYPE_DOUBAO ->
                submitDoubaoJson(target, requestUrl, merged)
            else ->
                submitMultipart(target, requestUrl, merged)
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // N7：错误消息仅保留 HTTP 状态码和简短 message，不包含响应体。
                // 响应体可能含 Provider 内部错误堆栈、API 密钥片段、账户标识等敏感信息，
                // 完整暴露到 errorMessage 会经通知/UI/日志流出设备。
                error("视频提交失败：HTTP ${resp.code} ${resp.message}")
            }
            val root = JSONObject(text.takeIf { it.isNotBlank() } ?: "{}")
            val taskId = resolveJsonPath(root, target.taskIdJsonPath)
                // N7：响应解析失败不回显完整响应体，仅提示 JSON path 缺失
                ?: error("视频提交响应缺少 taskId（path=${target.taskIdJsonPath}）")
            taskId
        }
    }

    /**
     * 把 [AiVideoProviderConfig.defaultParamsJson] 中的高级参数合并进 [VideoSubmitRequest]。
     *
     * 「按模型自适应」核心：用户在 Provider 编辑页配置该 Provider 支持的高级参数，
     * 这里在提交时自动注入到 request。若 request 已显式提供某字段，则保留 request 的值（调用方覆盖）。
     *
     * 各 type 支持的字段：
     * - Agnes: mode / negative_prompt / seed / num_inference_steps
     * - 豆包: seed / camera_fixed / return_last_frame / generate_audio / watermark / draft / resolution / ratio / duration
     * - OpenAI 风格: 无高级字段（仅 duration/size 由 request 主字段携带）
     *
     * 解析失败、字段缺失、类型不匹配等一律跳过该字段，不影响默认流程。
     */
    internal fun mergeProviderParams(
        request: VideoSubmitRequest,
        target: AiVideoProviderConfig
    ): VideoSubmitRequest {
        val paramsJson = target.defaultParamsJson
        if (paramsJson.isBlank()) return request
        // 用 GSON 而非 org.json.JSONObject：Android 单元测试默认用 android.jar 的 org.json stub，
        // 其方法会抛 RuntimeException("Stub!")。GSON 在 JVM 测试环境可用，避免 stub 问题。
        val root = runCatching {
            JsonParser.parseString(paramsJson).asJsonObject
        }.getOrNull() ?: return request

        fun optInt(key: String): Int? =
            root.get(key)?.takeIf { !it.isJsonNull }?.asInt
        fun optString(key: String): String? =
            root.get(key)?.takeIf { !it.isJsonNull }?.asString?.ifBlank { null }
        fun optBool(key: String): Boolean? =
            root.get(key)?.takeIf { !it.isJsonNull }?.asBoolean

        // 通用：seed（Agnes/豆包都支持）
        val seed = request.seed ?: optInt("seed")

        return when (target.type) {
            AiVideoProviderConfig.TYPE_AGNES -> {
                val mode = request.mode ?: optString("mode")
                val neg = request.negativePrompt ?: optString("negative_prompt")
                val steps = request.numInferenceSteps ?: optInt("num_inference_steps")
                request.copy(
                    mode = mode, negativePrompt = neg, seed = seed, numInferenceSteps = steps
                )
            }
            AiVideoProviderConfig.TYPE_DOUBAO -> {
                // Boolean 字段（watermark/returnLastFrame/draft）用 OR 合并语义：
                // 因 VideoSubmitRequest 中这些字段为非空 Boolean（默认 false），
                // 无法区分「未设置」与「显式 false」，故任一为 true 则 true。
                // nullable 字段（cameraFixed/generateAudio）支持调用方覆盖 Provider 默认值。
                val cameraFixed = request.cameraFixed ?: optBool("camera_fixed")
                val returnLast = request.returnLastFrame || (optBool("return_last_frame") ?: false)
                val genAudio = request.generateAudio ?: optBool("generate_audio")
                val watermark = request.watermark || (optBool("watermark") ?: false)
                val draft = request.draft || (optBool("draft") ?: false)
                // 豆包 duration 可在 defaultParamsJson 配置，但 request.seconds 优先
                val seconds = if (request.seconds > 0) request.seconds
                else optInt("duration")?.takeIf { it > 0 } ?: request.seconds
                request.copy(
                    seed = seed,
                    cameraFixed = cameraFixed,
                    returnLastFrame = returnLast,
                    generateAudio = genAudio,
                    watermark = watermark,
                    draft = draft,
                    seconds = seconds
                )
            }
            else -> request.copy(seed = seed)
        }
    }

    /**
     * veo3.1 风格的 multipart/form-data 提交。
     * 参考图作为重复的 input_reference 字段（multipart 同名多值）。
     */
    private suspend fun submitMultipart(
        target: AiVideoProviderConfig,
        requestUrl: String,
        request: VideoSubmitRequest
    ): okhttp3.Response {
        val formMap = linkedMapOf<String, Any>(
            "model" to target.model.ifBlank { "veo3.1-components" },
            "prompt" to request.prompt,
            "seconds" to request.seconds.toString(),
            "size" to request.size,
            "watermark" to request.watermark.toString()
        )
        // OpenAI 风格也支持 seed（如 veo3.1 的 seed 参数）
        request.seed?.let { formMap["seed"] = it.toString() }
        return target.httpClient(target.validSubmitTimeout()).newCallResponse {
            url(requestUrl)
            buildMultipartBody(target, formMap, request.referenceImages)
            addHeader("Accept", "application/json")
            target.apiKey.takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            addHeaders(AiChatService.parseCustomHeaders(target.headers))
        }
    }

    /**
     * Agnes Video V2.0 的 JSON body 提交。
     *
     * 字段映射：
     * - size "1280x720" → width=1280, height=720
     * - seconds 5 → num_frames=121, frame_rate=24（满足 8n+1 规则）
     * - 参考图：
     *   - 1 张 → 顶层 image 字段（图生视频模式）
     *   - 2+ 张 → extra_body.image 数组
     *   - 若 mode=keyframes → extra_body.mode=keyframes（关键帧过渡）
     * - 高级参数（已由 [mergeProviderParams] 注入到 [VideoSubmitRequest]）：
     *   - negative_prompt：反向提示词
     *   - seed：随机种子
     *   - num_inference_steps：推理步数
     *   - mode：ti2vid / keyframes
     *
     * @see <a href="https://agnes-ai.com/zh-Hans/docs/agnes-video-v20">Agnes Video V2.0 文档</a>
     */
    private suspend fun submitAgnesJson(
        target: AiVideoProviderConfig,
        requestUrl: String,
        request: VideoSubmitRequest
    ): okhttp3.Response {
        val (width, height) = parseSizeStatic(request.size)
        val frameRate = 24
        // num_frames 需满足 8n+1 规则且 ≤ 441
        val numFrames = computeAgnesNumFramesStatic(request.seconds, frameRate)
        val refs = request.referenceImages
        val mode = request.mode

        val jsonBody = JSONObject().apply {
            put("model", target.model.ifBlank { "agnes-video-v2.0" })
            put("prompt", request.prompt)
            put("width", width)
            put("height", height)
            put("num_frames", numFrames)
            put("frame_rate", frameRate)
            // 可选高级字段（已由 mergeProviderParams 注入）
            request.negativePrompt?.takeIf { it.isNotBlank() }?.let { put("negative_prompt", it) }
            request.seed?.let { put("seed", it) }
            request.numInferenceSteps?.let { put("num_inference_steps", it) }

            // 参考图分支：单图走顶层 image，多图走 extra_body.image 数组
            when {
                refs.isEmpty() -> { /* 纯文生视频，无参考图字段 */ }
                refs.size == 1 && mode.isNullOrBlank() -> {
                    // 单图图生视频：顶层 image 字段
                    put("image", refs[0])
                }
                else -> {
                    // 多图视频 / 关键帧模式：extra_body.image 数组
                    val extraBody = JSONObject().apply {
                        put("image", org.json.JSONArray(refs))
                        // keyframes 模式需显式设置 mode
                        mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                    }
                    put("extra_body", extraBody)
                }
            }
        }
        val body = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return target.httpClient(target.validSubmitTimeout()).newCallResponse {
            url(requestUrl)
            post(body)
            addHeader("Accept", "application/json")
            target.apiKey.takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            addHeaders(AiChatService.parseCustomHeaders(target.headers))
        }
    }

    /**
     * 豆包 Seedance 2.0 的 JSON body 提交。
     *
     * 与 OpenAI/Agnes 风格差异巨大，采用 `content[]` 数组结构（OpenAI Chat 风格的 messages）：
     * ```
     * {
     *   "model": "doubao-seedance-2-0-260128",
     *   "content": [
     *     {"type": "text", "text": "<prompt>"},
     *     {"type": "image_url", "image_url": {"url": "<ref1>"}},
     *     {"type": "image_url", "image_url": {"url": "<ref2>"}}
     *   ],
     *   "duration": 5,
     *   "resolution": "720p",
     *   "ratio": "16:9",
     *   "camera_fixed": false,
     *   "return_last_frame": false,
     *   "generate_audio": false,
     *   "watermark": false,
     *   "seed": 12345
     * }
     * ```
     *
     * 字段映射：
     * - request.prompt → content[0] = {type:text, text:prompt}
     * - request.referenceImages → content[1..n] = {type:image_url, image_url:{url:...}}（最多 [AiVideoProviderConfig.maxReferenceImages] 张）
     * - request.seconds → duration（4-15 整数；超出范围由豆包侧校验）
     * - request.size → resolution + ratio（自动转换，详见 [parseDoubaoResolution]）
     * - request.cameraFixed / returnLastFrame / generateAudio / watermark / seed → 顶层字段
     *
     * 端点：`POST https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks`
     * taskIdJsonPath: `$.id`，轮询：`GET /contents/generations/tasks/{id}`
     * videoUrlJsonPath: `$.content.video_url`，statusJsonPath: `$.status`
     * done=`succeeded`，failed=`failed`
     *
     * @see <a href="https://www.volcengine.com/docs/82379/1399008">豆包 Seedance 2.0 文档</a>
     */
    private suspend fun submitDoubaoJson(
        target: AiVideoProviderConfig,
        requestUrl: String,
        request: VideoSubmitRequest
    ): okhttp3.Response {
        val (resolution, ratio) = parseDoubaoResolution(request.size)
        val contentArray = org.json.JSONArray().apply {
            // 文本提示词必填，放第一位
            put(JSONObject().apply {
                put("type", "text")
                put("text", request.prompt)
            })
            // 参考图作为 image_url 项追加
            request.referenceImages.forEach { url ->
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply { put("url", url) })
                })
            }
        }

        val jsonBody = JSONObject().apply {
            put("model", target.model.ifBlank { "doubao-seedance-2-0-260128" })
            put("content", contentArray)
            put("duration", request.seconds)
            put("resolution", resolution)
            // ratio 仅在非 adaptive 时下发；adaptive 让豆包按参考图自动决定
            if (ratio != "adaptive") put("ratio", ratio)
            // 高级可选字段（已由 mergeProviderParams 注入）
            request.cameraFixed?.let { put("camera_fixed", it) }
            if (request.returnLastFrame) put("return_last_frame", true)
            request.generateAudio?.let { put("generate_audio", it) }
            put("watermark", request.watermark)
            if (request.draft) put("draft", true)
            request.seed?.let { put("seed", it) }
        }
        val body = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return target.httpClient(target.validSubmitTimeout()).newCallResponse {
            url(requestUrl)
            post(body)
            addHeader("Accept", "application/json")
            target.apiKey.takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            addHeaders(AiChatService.parseCustomHeaders(target.headers))
        }
    }

    /**
     * 把 [size] 转换为豆包 Seedance 的 (resolution, ratio)。
     *
     * 支持两种输入：
     * 1. 直接的豆包 resolution 字符串："480p" / "720p" / "1080p" → (size, "adaptive")
     * 2. WxH 形式："1280x720" → 按宽高比映射到 ratio，按短边映射到 resolution：
     *    - 16:9 / 9:16 / 1:1 三种比例
     *    - 短边 ≥ 1080 → 1080p；≥ 720 → 720p；否则 480p
     *
     * 解析失败默认 (720p, 16:9)。
     */
    fun parseDoubaoResolution(size: String): Pair<String, String> {
        val trimmed = size.trim().lowercase()
        // 直接是 resolution 字符串
        if (trimmed in setOf("480p", "720p", "1080p")) return trimmed to "adaptive"
        val (w, h) = parseSizeStatic(trimmed)
        // 计算比例
        val ratio = when {
            w == h -> "1:1"
            w > h -> {
                val r = w.toDouble() / h
                if (kotlin.math.abs(r - 16.0 / 9) < 0.15) "16:9"
                else if (kotlin.math.abs(r - 4.0 / 3) < 0.15) "4:3"
                else "16:9" // 默认横屏
            }
            else -> {
                val r = h.toDouble() / w
                if (kotlin.math.abs(r - 16.0 / 9) < 0.15) "9:16"
                else if (kotlin.math.abs(r - 4.0 / 3) < 0.15) "3:4"
                else "9:16" // 默认竖屏
            }
        }
        // 短边决定 resolution
        val shortSide = minOf(w, h)
        val resolution = when {
            shortSide >= 1080 -> "1080p"
            shortSide >= 720 -> "720p"
            else -> "480p"
        }
        return resolution to ratio
    }

    /**
     * 轮询视频任务直到完成或失败。
     *
     * @param taskId [submit] 返回的任务 id
     * @param provider Provider 配置
     * @param isCancelled 取消信号；每 [AiVideoProviderConfig.validPollInterval] 检查一次
     */
    suspend fun poll(
        taskId: String,
        provider: AiVideoProviderConfig? = null,
        isCancelled: () -> Boolean = { false }
    ): VideoPollResult = withContext(Dispatchers.IO) {
        val target = resolveProvider(provider)
        val pollUrl = target.resolvePollUrl(taskId).ifBlank { "${buildSubmitUrl(target)}/$taskId" }
        val interval = target.validPollInterval()
        val timeout = target.validPollTimeout()
        val deadline = System.currentTimeMillis() + timeout

        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("视频轮询被取消：$taskId")
            val result = pollOnce(pollUrl, target)
            when (result) {
                is VideoPollResult.Success -> return@withContext result
                is VideoPollResult.Failed -> return@withContext result
                VideoPollResult.Polling -> delay(interval)
            }
        }
        VideoPollResult.Failed("视频生成超时（${timeout / 1000}s）：$taskId")
    }

    private suspend fun pollOnce(
        pollUrl: String,
        provider: AiVideoProviderConfig
    ): VideoPollResult {
        val response = try {
            provider.httpClient(provider.validPollInterval() * 2).newCallResponse {
                url(pollUrl)
                addHeader("Accept", "application/json")
                provider.apiKey.takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
                addHeaders(AiChatService.parseCustomHeaders(provider.headers))
            }
        } catch (e: Throwable) {
            // 网络异常视为暂态，继续轮询（受总超时约束）
            return VideoPollResult.Polling
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 4xx 视为终态失败；5xx 视为暂态，继续轮询
                return if (resp.code in 400..499) {
                    // N8：错误消息不含响应体，避免 Provider 内部错误细节流出设备
                    VideoPollResult.Failed("轮询失败：HTTP ${resp.code} ${resp.message}")
                } else {
                    VideoPollResult.Polling
                }
            }
            val root = JSONObject(text.takeIf { it.isNotBlank() } ?: "{}")
            val status = resolveJsonPath(root, provider.statusJsonPath)?.lowercase().orEmpty()
            return when {
                status == provider.doneStatusValue.lowercase() -> {
                    val videoUrl = resolveJsonPath(root, provider.videoUrlJsonPath)
                        // N8：不回显完整响应体
                        ?: return VideoPollResult.Failed("完成但缺少 video_url（path=${provider.videoUrlJsonPath}）")
                    VideoPollResult.Success(videoUrl)
                }
                status == provider.failedStatusValue.lowercase() -> {
                    val err = resolveJsonPath(root, "$.error") ?: resolveJsonPath(root, "$.message") ?: "未知错误"
                    // N9：err 是 Provider 返回的错误字段，可能含堆栈；截断到 200 字符避免长错误信息溢出
                    VideoPollResult.Failed("视频生成失败：${err.take(200)}")
                }
                else -> VideoPollResult.Polling // 排队/进行中，继续轮询
            }
        }
    }

    /**
     * 把视频 URL 下载到 `filesDir/novel_video/<jobId>/<segId>.mp4`。
     * @return 本地文件路径
     */
    suspend fun downloadToLocal(
        videoUrl: String,
        jobId: String,
        segId: String
    ): String = withContext(Dispatchers.IO) {
        val dir = File(appCtx.filesDir, "novel_video/$jobId").apply { mkdirs() }
        val file = File(dir, "$segId.mp4")
        okHttpClient.newCallResponse {
            url(videoUrl)
        }.use { resp ->
            if (!resp.isSuccessful) error("视频下载失败：HTTP ${resp.code} ${resp.message}")
            resp.body?.byteStream()?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: error("视频下载响应体为空")
        }
        file.absolutePath
    }

    fun providerByIdOrNull(providerId: String?): AiVideoProviderConfig? =
        AppConfig.findEnabledVideoProvider(providerId)

    fun currentProviderOrNull(): AiVideoProviderConfig? = AppConfig.aiCurrentVideoProvider

    // ============================================================
    // 内部工具
    // ============================================================

    private fun resolveProvider(provider: AiVideoProviderConfig?): AiVideoProviderConfig {
        return provider ?: currentProviderOrNull()
            ?: error("未配置文生视频 Provider，请先在设置中添加")
    }

    private fun buildSubmitUrl(provider: AiVideoProviderConfig): String {
        val submit = provider.submitUrl.trim()
        if (submit.isNotBlank()) {
            return if (submit.startsWith("http")) submit
            else "${provider.baseUrl.trimEnd('/')}/${submit.trimStart('/')}"
        }
        val base = provider.baseUrl.trimEnd('/')
        return when {
            base.endsWith("/v1") -> "$base/videos"
            base.endsWith("/videos") -> base
            base.isBlank() -> error("Provider baseUrl 和 submitUrl 均为空")
            else -> "$base/v1/videos"
        }
    }

    /**
     * 手动构建 multipart body（支持重复的 input_reference 字段）。
     */
    private fun okhttp3.Request.Builder.buildMultipartBody(
        provider: AiVideoProviderConfig,
        formMap: Map<String, Any>,
        referenceImages: List<String>
    ) {
        val builder = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
        formMap.forEach { (key, value) ->
            builder.addFormDataPart(key, value.toString())
        }
        referenceImages.forEach { url ->
            builder.addFormDataPart("input_reference", url)
        }
        post(builder.build())
    }

    /**
     * 简易 JSONPath 解析，支持 `$.a.b.c` 形式。
     * 不支持数组下标、过滤器等高级语法；足够覆盖默认的 `$.data.id` / `$.data.video_url` 等。
     */
    private fun resolveJsonPath(root: JSONObject, path: String): String? {
        val cleaned = path.trim().removePrefix("$").removePrefix(".")
        if (cleaned.isEmpty()) return root.toString()
        var current: Any = root
        cleaned.split(".").forEach { segment ->
            current = when (current) {
                is JSONObject -> current.opt(segment) ?: return null
                else -> return null
            }
        }
        return when (current) {
            is String -> current
            is Number -> current.toString()
            is Boolean -> current.toString()
            else -> current?.toString()
        }
    }

    private fun AiVideoProviderConfig.httpClient(timeoutMs: Long): OkHttpClient {
        return okHttpClient.newBuilder()
            .connectTimeout(timeoutMs.coerceAtLeast(10_000L), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.coerceAtLeast(10_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.coerceAtLeast(10_000L), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.coerceAtLeast(10_000L), TimeUnit.MILLISECONDS)
            .build()
    }

    /** 从 "1280x720" 解析出 (width, height)，失败用默认 1152x768。 */
    fun parseSizeStatic(size: String): Pair<Int, Int> {
        val parts = size.trim().lowercase().split("x")
        if (parts.size == 2) {
            val w = parts[0].trim().toIntOrNull()
            val h = parts[1].trim().toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) return w to h
        }
        return 1152 to 768
    }

    /**
     * 根据 seconds 计算 num_frames，需满足 8n+1 规则且 ≤ 441。
     * - 3s → 81 (8*10+1, 81/24≈3.4s)
     * - 5s → 121 (8*15+1, 121/24≈5.0s)
     * - 10s → 241 (8*30+1, 241/24≈10.0s)
     * - 18s → 441 (8*55+1, 441/24≈18.4s)
     */
    fun computeAgnesNumFramesStatic(seconds: Int, frameRate: Int): Int {
        val raw = seconds * frameRate
        // 找到 >= raw 的最小 8n+1 值
        var n = (raw - 1) / 8
        var candidate = 8 * n + 1
        while (candidate < raw) {
            n++
            candidate = 8 * n + 1
        }
        return candidate.coerceAtMost(441).coerceAtLeast(1)
    }
}
