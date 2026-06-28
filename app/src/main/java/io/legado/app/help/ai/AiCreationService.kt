package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 内置 Agnes AI 图片/视频生成 API 服务
 * 自动共享 AI 聊天中已配置的 Agnes AI 提供商 API Key
 *
 * 文档参考: Agnes Image 2.1 Flash / Agnes Video V2.0
 *   - 图片: POST /v1/images/generations
 *   - 视频: POST /v1/videos → GET /v1/videos/{task_id}
 */
object AiCreationService {

    private const val BASE_URL = "https://apihub.agnes-ai.com"
    private const val IMAGE_MODEL = "agnes-image-2.1-flash"
    private const val VIDEO_MODEL = "agnes-video-v2.0"
    private const val DEFAULT_SIZE = "1024x768"
    private const val DEFAULT_VIDEO_WIDTH = 1152
    private const val DEFAULT_VIDEO_HEIGHT = 768
    private const val POLL_INTERVAL_MS = 5000L
    private const val MAX_POLL_ATTEMPTS = 120

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

    /** 优先从 AI 聊天配置的 Agnes AI 提供商中获取 Key，回退到手动输入 */
    fun loadApiKey() {
        val providerKey = AppConfig.aiProviderList
            .firstOrNull { it.baseUrl.contains("agnes-ai", ignoreCase = true) }
            ?.apiKey?.trim()?.takeIf { it.isNotBlank() }
        if (providerKey != null) {
            apiKey = providerKey
            AppConfig.aiAgnesApiKey = providerKey
            return
        }
        apiKey = AppConfig.aiAgnesApiKey
    }

    // ════════════════════════════════════════════
    // 图片生成
    // ════════════════════════════════════════════

    data class ImageResult(val url: String? = null)

    suspend fun textToImage(prompt: String, size: String = DEFAULT_SIZE): ImageResult =
        requestImage(prompt = prompt, size = size)

    suspend fun imageToImage(prompt: String, imageUrl: String, size: String = DEFAULT_SIZE): ImageResult =
        requestImage(prompt = prompt, imageUrl = imageUrl, size = size)

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
                // 图生图: extra_body.image 为数组类型
                put("extra_body", JSONObject().apply {
                    put("image", JSONArray().apply { put(imageUrl) })
                    put("response_format", "url")
                })
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
                throw AiCreationException("图片生成失败: ${resp.code} ${extractError(payload)}", payload)
            }
            val root = JSONObject(payload)
            val data = root.optJSONArray("data")?.optJSONObject(0)
            return ImageResult(url = data?.optString("url")?.takeIf { it.isNotBlank() })
        }
    }

    // ════════════════════════════════════════════
    // 视频生成
    // ════════════════════════════════════════════

    data class VideoResult(val videoUrl: String)

    /**
     * @param numFrames 视频总帧数 (24fps: 3秒=81帧, 5秒=121帧, 10秒=241帧, 18秒=441帧)
     */
    suspend fun textToVideo(prompt: String, numFrames: Int = 121): VideoResult =
        createAndPollVideo(prompt, numFrames, mode = "ti2vid")

    suspend fun imageToVideo(prompt: String, imageUrl: String, numFrames: Int = 121): VideoResult =
        createAndPollVideo(prompt, numFrames, imageUrl = imageUrl, mode = "ti2vid")

    private suspend fun createAndPollVideo(
        prompt: String,
        numFrames: Int,
        imageUrl: String? = null,
        mode: String = "ti2vid"
    ): VideoResult {
        requireApiKey()
        val taskId = createVideoTask(prompt, numFrames, imageUrl, mode)
        return pollVideoResult(taskId)
    }

    private suspend fun createVideoTask(
        prompt: String,
        numFrames: Int,
        imageUrl: String?,
        mode: String
    ): String {
        val body = JSONObject().apply {
            put("model", VIDEO_MODEL)
            put("prompt", prompt)
            put("mode", mode)
            put("num_frames", numFrames)
            put("width", DEFAULT_VIDEO_WIDTH)
            put("height", DEFAULT_VIDEO_HEIGHT)
            if (imageUrl != null) {
                put("image", imageUrl)
            }
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
                throw AiCreationException("视频任务创建失败: ${resp.code} ${extractError(payload)}", payload)
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
                    throw AiCreationException("视频查询失败: ${resp.code} ${extractError(payload)}", payload)
                }
                val root = JSONObject(payload)
                when (root.optString("status")) {
                    "succeeded" -> {
                        val data = root.optJSONArray("data")
                        val url = data?.optJSONObject(0)?.optString("url")
                        if (!url.isNullOrBlank()) return VideoResult(videoUrl = url)
                        throw AiCreationException("视频生成完成但未获取到URL", payload)
                    }
                    "failed", "cancelled" -> {
                        val err = root.optJSONObject("error")
                        throw AiCreationException("视频生成失败: ${err?.optString("message") ?: "未知错误"}", payload)
                    }
                    // queued / in_progress: 继续轮询
                }
            }
        }
        throw AiCreationException("视频生成超时（超过10分钟），请稍后重试", "")
    }

    private fun requireApiKey() {
        require(apiKey.isNotBlank()) { "请先设置 Agnes AI API Key" }
    }

    private fun extractError(payload: String): String {
        return try {
            val root = JSONObject(payload)
            root.optJSONObject("error")?.let {
                it.optString("message").takeIf { m -> m.isNotBlank() }
                    ?: it.optString("code")
            } ?: payload.take(200)
        } catch (_: Exception) {
            payload.take(200)
        }
    }
}

class AiCreationException(message: String, debugInfo: String = "") :
    Exception("$message${if (debugInfo.isNotBlank()) "\n$debugInfo" else ""}")