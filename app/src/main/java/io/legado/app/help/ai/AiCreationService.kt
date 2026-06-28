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
 */
object AiCreationService {

    private const val BASE_URL = "https://apihub.agnes-ai.com"
    private const val IMAGE_MODEL = "agnes-image-2.1-flash"
    private const val VIDEO_MODEL = "agnes-video-v2.0"
    private const val DEFAULT_SIZE = "1024x768"
    private const val POLL_INTERVAL_MS = 3000L
    private const val MAX_POLL_ATTEMPTS = 60

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

    fun loadApiKey() {
        apiKey = AppConfig.aiAgnesApiKey
    }

    // ── 图片生成 ──

    data class ImageResult(val url: String? = null, val b64Json: String? = null)

    suspend fun textToImage(
        prompt: String,
        size: String = DEFAULT_SIZE,
        base64: Boolean = false
    ): ImageResult = requestImage(prompt = prompt, size = size, base64 = base64)

    suspend fun imageToImage(
        prompt: String,
        imageUrl: String,
        size: String = DEFAULT_SIZE,
        base64: Boolean = false
    ): ImageResult = requestImage(prompt = prompt, imageUrl = imageUrl, size = size, base64 = base64)

    private suspend fun requestImage(
        prompt: String,
        imageUrl: String? = null,
        size: String,
        base64: Boolean
    ): ImageResult {
        requireApiKey()
        val body = JSONObject().apply {
            put("model", IMAGE_MODEL)
            put("prompt", prompt)
            put("size", size)
            if (imageUrl != null) {
                put("extra_body", JSONObject().apply {
                    put("image", org.json.JSONArray().put(imageUrl))
                    put("response_format", if (base64) "b64_json" else "url")
                })
            } else if (base64) {
                put("return_base64", true)
            } else {
                put("extra_body", JSONObject().apply {
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
            if (!resp.isSuccessful) throw AiCreationException("图片生成失败: ${resp.code}", payload)
            val root = JSONObject(payload)
            val data = root.optJSONArray("data")?.optJSONObject(0)
            return ImageResult(
                url = data?.optString("url")?.takeIf { it.isNotBlank() },
                b64Json = data?.optString("b64_json")?.takeIf { it.isNotBlank() }
            )
        }
    }

    // ── 视频生成 ──

    data class VideoTask(val taskId: String, val videoId: String)
    data class VideoResult(val videoUrl: String, val size: String, val seconds: String)

    suspend fun textToVideo(
        prompt: String,
        width: Int = 1152,
        height: Int = 768,
        numFrames: Int = 121,
        frameRate: Int = 24
    ): VideoResult = createAndPollVideo(prompt, width, height, numFrames, frameRate)

    suspend fun imageToVideo(
        prompt: String,
        imageUrl: String,
        width: Int = 1152,
        height: Int = 768,
        numFrames: Int = 121,
        frameRate: Int = 24
    ): VideoResult = createAndPollVideo(prompt, width, height, numFrames, frameRate, imageUrl)

    private suspend fun createAndPollVideo(
        prompt: String,
        width: Int,
        height: Int,
        numFrames: Int,
        frameRate: Int,
        imageUrl: String? = null
    ): VideoResult {
        requireApiKey()
        val task = createVideoTask(prompt, imageUrl, width, height, numFrames, frameRate)
        return pollVideoResult(task.videoId)
    }

    private suspend fun createVideoTask(
        prompt: String,
        imageUrl: String?,
        width: Int,
        height: Int,
        numFrames: Int,
        frameRate: Int
    ): VideoTask {
        val body = JSONObject().apply {
            put("model", VIDEO_MODEL)
            put("prompt", prompt)
            put("width", width)
            put("height", height)
            put("num_frames", numFrames)
            put("frame_rate", frameRate)
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
            if (!resp.isSuccessful) throw AiCreationException("视频任务创建失败: ${resp.code}", payload)
            val root = JSONObject(payload)
            return VideoTask(
                taskId = root.optString("task_id"),
                videoId = root.optString("video_id")
            )
        }
    }

    private suspend fun pollVideoResult(videoId: String): VideoResult {
        repeat(MAX_POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val response = client.newCallResponse {
                url("$BASE_URL/agnesapi?video_id=$videoId")
                addHeader("Authorization", "Bearer $apiKey")
            }
            response.use { resp ->
                val payload = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw AiCreationException("视频查询失败: ${resp.code}", payload)
                val root = JSONObject(payload)
                when (root.optString("status")) {
                    "completed" -> {
                        val url = root.optString("remixed_from_video_id")
                        if (url.isNotBlank()) {
                            return VideoResult(
                                videoUrl = url,
                                size = root.optString("size", ""),
                                seconds = root.optString("seconds", "")
                            )
                        }
                    }
                    "failed" -> throw AiCreationException(
                        "视频生成失败",
                        root.optJSONObject("error")?.toString() ?: payload
                    )
                    else -> { /* queued / in_progress */ }
                }
            }
        }
        throw AiCreationException("视频生成超时，请稍后重试", "")
    }

    private fun requireApiKey() {
        require(apiKey.isNotBlank()) { "请先设置 Agnes AI API Key" }
    }
}

class AiCreationException(message: String, debugInfo: String = "") :
    Exception("$message${if (debugInfo.isNotBlank()) "\n$debugInfo" else ""}")