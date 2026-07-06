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
     * 提交视频生成任务。
     *
     * @param prompt 视频提示词（英文）
     * @param seconds 视频时长（秒）
     * @param size 分辨率，如 "1280x720"
     * @param referenceImages 参考图 URL 列表（最多 [AiVideoProviderConfig.maxReferenceImages] 张）
     * @param provider Provider 配置；null 则用当前默认
     * @return taskId
     */
    suspend fun submit(
        prompt: String,
        seconds: Int,
        size: String,
        referenceImages: List<String>,
        provider: AiVideoProviderConfig? = null
    ): String = withContext(Dispatchers.IO) {
        val target = resolveProvider(provider)
        val requestUrl = buildSubmitUrl(target)
        val maxRefs = target.maxReferenceImages.coerceAtLeast(1)
        val refs = referenceImages.filter { it.isNotBlank() }.take(maxRefs)

        val response = if (target.type == AiVideoProviderConfig.TYPE_AGNES) {
            // Agnes Video V2.0：JSON body，字段为 width/height/num_frames/frame_rate
            submitAgnesJson(target, requestUrl, prompt, seconds, size, refs)
        } else {
            // 默认（veo3.1 风格）：multipart/form-data
            submitMultipart(target, requestUrl, prompt, seconds, size, refs)
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
     * veo3.1 风格的 multipart/form-data 提交。
     * 参考图作为重复的 input_reference 字段（multipart 同名多值）。
     */
    private suspend fun submitMultipart(
        target: AiVideoProviderConfig,
        requestUrl: String,
        prompt: String,
        seconds: Int,
        size: String,
        refs: List<String>
    ): okhttp3.Response {
        val formMap = linkedMapOf<String, Any>(
            "model" to target.model.ifBlank { "veo3.1-components" },
            "prompt" to prompt,
            "seconds" to seconds.toString(),
            "size" to size,
            "watermark" to "false"
        )
        return target.httpClient(target.validSubmitTimeout()).newCallResponse {
            url(requestUrl)
            buildMultipartBody(target, formMap, refs)
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
     *   - 若 defaultParamsJson 含 mode=keyframes → extra_body.mode=keyframes（关键帧过渡）
     * - 高级参数（来自 defaultParamsJson，可选）：
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
        prompt: String,
        seconds: Int,
        size: String,
        refs: List<String>
    ): okhttp3.Response {
        val (width, height) = parseSizeStatic(size)
        val frameRate = 24
        // num_frames 需满足 8n+1 规则且 ≤ 441
        val numFrames = computeAgnesNumFramesStatic(seconds, frameRate)
        // 从 defaultParamsJson 读取可选高级参数
        val params = parseAgnesExtraParams(target.defaultParamsJson)
        val mode = params.mode

        val jsonBody = JSONObject().apply {
            put("model", target.model.ifBlank { "agnes-video-v2.0" })
            put("prompt", prompt)
            put("width", width)
            put("height", height)
            put("num_frames", numFrames)
            put("frame_rate", frameRate)
            // 可选高级字段
            params.negativePrompt?.takeIf { it.isNotBlank() }?.let { put("negative_prompt", it) }
            params.seed?.let { put("seed", it) }
            params.numInferenceSteps?.let { put("num_inference_steps", it) }

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
     * 解析 Agnes Provider 的 defaultParamsJson，提取高级可选参数。
     *
     * 支持的字段（全部可选）：
     * - mode: "ti2vid" 或 "keyframes"（关键帧模式需 ≥2 张参考图）
     * - negative_prompt: 反向提示词
     * - seed: 随机种子（Int）
     * - num_inference_steps: 推理步数（Int）
     *
     * 解析失败返回全 null 的对象，不影响默认文生视频流程。
     */
    private fun parseAgnesExtraParams(paramsJson: String): AgnesExtraParams {
        if (paramsJson.isBlank()) return AgnesExtraParams()
        return runCatching {
            val root = JSONObject(paramsJson)
            AgnesExtraParams(
                mode = root.optString("mode").ifBlank { null },
                negativePrompt = root.optString("negative_prompt").ifBlank { null },
                seed = root.opt("seed") as? Int,
                numInferenceSteps = root.opt("num_inference_steps") as? Int
            )
        }.getOrDefault(AgnesExtraParams())
    }

    /** Agnes 高级参数容器。 */
    private data class AgnesExtraParams(
        val mode: String? = null,
        val negativePrompt: String? = null,
        val seed: Int? = null,
        val numInferenceSteps: Int? = null
    )

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
