package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 内置 Agnes AI 图片/视频生成 API 服务
 * 自动共享 AI 聊天中已配置的 Agnes AI 提供商 API Key
 */
object AiCreationService {

    private const val BASE_URL = "https://apihub.agnes-ai.com"
    private const val IMAGE_MODEL = "agnes-image-2.1-flash"
    private const val VIDEO_MODEL = "agnes-video-v2.0"
    private const val DEFAULT_SIZE = "1024x768"
    private const val POLL_INTERVAL_MS = 5000L    // 视频轮询间隔 5 秒
    private const val MAX_POLL_ATTEMPTS = 120      // 最多轮询 10 分钟

    private val client = okHttpClient.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private var apiKey: String = ""

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        AppConfig.aiAgnesApiKey = trimmed
        apiKey = trimmed
    }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    /**
     * 加载 API Key：优先从 AI 聊天已配置的 Agnes AI 提供商中获取，
     * 若无则使用用户手动输入的 Key
     */
    fun loadApiKey() {
        // 1. 先尝试从聊天提供商列表中找到 Agnes AI 的 Key
        val providerKey = AppConfig.aiProviderList
            .firstOrNull { it.baseUrl.contains("agnes-ai", ignoreCase = true) }
            ?.apiKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (providerKey != null) {
            apiKey = providerKey
            // 同步到独立存储，方便下次快速加载
            AppConfig.aiAgnesApiKey = providerKey
            return
        }
        // 2. 回退到手动输入的 Key
        apiKey = AppConfig.aiAgnesApiKey
    }

    // ── 图片生成 ──

    data class ImageResult(val url: String? = null, val b64Json: String? = null)

    suspend fun textToImage(
        prompt: String,
        size: String = DEFAULT_SIZE
    ): ImageResult = requestImage(prompt = prompt, size = size)

    suspend fun imageToImage(
        prompt: String,
        imageUrl: String,
        size: String = DEFAULT_SIZE
    ): ImageResult = requestImage(prompt = prompt, imageUrl = imageUrl, size = size)

    private suspend fun requestImage(
        prompt: String,
        imageUrl: String? = null,
        size: String
    ): ImageResult {
        requireApiKey()
        val body = JSONObject().apply {
            put("model", IMAGE_MODEL)
            put("prompt", prompt)
            put("size", size)
            put("n", 1)
            if (imageUrl != null) {
                put("image", imageUrl)
            }
        }
        val response = client.newCallResponse {
            url("$BASE_URL/v1/images/generations")
            addHeader("Authorization", "Bearer $apiKey")
            addHeader("Content-Type", "application/json")
            postJson(body.toString())
        }
        response.use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errMsg = extractError(payload)
                throw AiCreationException("图片生成失败: ${resp.code} $errMsg", payload)
            }
            val root = JSONObject(payload)
            val data = root.optJSONArray("data")?.optJSONObject(0)
            return ImageResult(
                url = data?.optString("url")?.takeIf { it.isNotBlank() },
                b64Json = data?.optString("b64_json")?.takeIf { it.isNotBlank() }
            )
        }
    }

    // ── 视频生成 ──

    data class VideoResult(val videoUrl: String)

    suspend fun textToVideo(
        prompt: String,
        duration: Int = 5
    ): VideoResult = createAndPollVideo(prompt, duration)

    suspend fun imageToVideo(
        prompt: String,
        imageUrl: String,
        duration: Int = 5
    ): VideoResult = createAndPollVideo(prompt, duration, imageUrl)

    private suspend fun createAndPollVideo(
        prompt: String,
        duration: Int,
        imageUrl: String? = null
    ): VideoResult {
        requireApiKey()
        val taskId = createVideoTask(prompt, imageUrl, duration)
        return pollVideoResult(taskId)
    }

    private suspend fun createVideoTask(
        prompt: String,
        imageUrl: String?,
        duration: Int
    ): String {
        val body = JSONObject().apply {
            put("model", VIDEO_MODEL)
            put("prompt", prompt)
            put("duration", duration)
            if (imageUrl != null) put("image", imageUrl)
        }
        val response = client.newCallResponse {
            url("$BASE_URL/v1/videos")
            addHeader("Authorization", "Bearer $apiKey")
            addHeader("Content-Type", "application/json")
            postJson(body.toString())
        }
        response.use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errMsg = extractError(payload)
                throw AiCreationException("视频任务创建失败: ${resp.code} $errMsg", payload)
            }
            val root = JSONObject(payload)
            return root.optString("id").takeIf { it.isNotBlank() }
                ?: throw AiCreationException("视频任务创建失败: 未获取到任务ID", payload)
        }
    }

    private suspend fun pollVideoResult(taskId: String): VideoResult {
        repeat(MAX_POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val response = client.newCallResponse {
                url("$BASE_URL/v1/videos/$taskId")
                addHeader("Authorization", "Bearer $apiKey")
            }
            response.use { resp ->
                val payload = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = extractError(payload)
                    throw AiCreationException("视频查询失败: ${resp.code} $errMsg", payload)
                }
                val root = JSONObject(payload)
                when (root.optString("status")) {
                    "succeeded" -> {
                        val data = root.optJSONArray("data")
                        val url = data?.optJSONObject(0)?.optString("url")
                        if (!url.isNullOrBlank()) {
                            return VideoResult(videoUrl = url)
                        }
                        throw AiCreationException("视频生成完成但未获取到URL", payload)
                    }
                    "failed", "cancelled" -> {
                        val error = root.optJSONObject("error")
                        throw AiCreationException(
                            "视频生成失败: ${error?.optString("message") ?: "未知错误"}",
                            payload
                        )
                    }
                    else -> { /* queued / in_progress */ }
                }
            }
        }
        throw AiCreationException("视频生成超时（超过10分钟），请稍后重试", "")
    }

    private fun requireApiKey() {
        require(apiKey.isNotBlank()) { "请先设置 Agnes AI API Key" }
    }

    /** 从 API 错误响应中提取可读的错误信息 */
    private fun extractError(payload: String): String {
        return try {
            val root = JSONObject(payload)
            root.optJSONObject("error")?.let {
                it.optString("message").takeIf { m -> m.isNotBlank() }
                    ?: it.optString("code")
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}

class AiCreationException(message: String, debugInfo: String = "") :
    Exception("$message${if (debugInfo.isNotBlank()) "\n$debugInfo" else ""}")