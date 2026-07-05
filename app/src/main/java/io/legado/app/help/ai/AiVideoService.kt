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

        val formMap = linkedMapOf<String, Any>(
            "model" to target.model.ifBlank { "veo3.1-components" },
            "prompt" to prompt,
            "seconds" to seconds.toString(),
            "size" to size,
            "watermark" to "false"
        )
        // 参考图作为重复的 input_reference 字段（multipart 同名多值）
        // postMultipart 的 Map 不支持重复 key，这里手动构建 MultipartBody
        val response = target.httpClient(target.validSubmitTimeout()).newCallResponse {
            url(requestUrl)
            buildMultipartBody(target, formMap, refs)
            addHeader("Accept", "application/json")
            target.apiKey.takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            addHeaders(AiChatService.parseCustomHeaders(target.headers))
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("视频提交失败：${resp.code} ${resp.message}\n$text")
            }
            val root = JSONObject(text.takeIf { it.isNotBlank() } ?: "{}")
            val taskId = resolveJsonPath(root, target.taskIdJsonPath)
                ?: error("视频提交响应缺少 taskId（path=${target.taskIdJsonPath}）：$text")
            taskId
        }
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
                    VideoPollResult.Failed("轮询失败：${resp.code} ${resp.message}")
                } else {
                    VideoPollResult.Polling
                }
            }
            val root = JSONObject(text.takeIf { it.isNotBlank() } ?: "{}")
            val status = resolveJsonPath(root, provider.statusJsonPath)?.lowercase().orEmpty()
            return when {
                status == provider.doneStatusValue.lowercase() -> {
                    val videoUrl = resolveJsonPath(root, provider.videoUrlJsonPath)
                        ?: return VideoPollResult.Failed("完成但缺少 video_url（path=${provider.videoUrlJsonPath}）：$text")
                    VideoPollResult.Success(videoUrl)
                }
                status == provider.failedStatusValue.lowercase() -> {
                    val err = resolveJsonPath(root, "$.error") ?: resolveJsonPath(root, "$.message") ?: "未知错误"
                    VideoPollResult.Failed("视频生成失败：$err")
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
            if (!resp.isSuccessful) error("视频下载失败：${resp.code} ${resp.message}")
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
}
