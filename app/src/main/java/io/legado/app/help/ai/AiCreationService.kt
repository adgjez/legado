package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 内置 Agnes AI 图片/视频生成 API 服务
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
    private const val POLL_INTERVAL_MS = 8000L
    private const val MAX_POLL_ATTEMPTS = 225    // 30 分钟

    /** 进度回调: (progress 0-100, 状态描述, 已耗时秒数) */
    data class GenerationProgress(
        val percent: Int,
        val statusText: String,
        val elapsedSeconds: Long
    )

    private val client = okHttpClient.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .build()

    private var apiKey: String = ""

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        AppConfig.aiAgnesApiKey = trimmed
        apiKey = trimmed
    }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

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
                put("extra_body", JSONObject().apply {
                    put("image", JSONArray().apply { put(imageUrl) })
                    put("response_format", "url")
                })
            }
        }
        val response = try {
            client.newCallResponse {
                url("$BASE_URL/v1/images/generations")
                addHeader("Authorization", "Bearer $apiKey")
                addHeader("Content-Type", "application/json")
                postJson(body.toString())
            }
        } catch (e: SocketTimeoutException) {
            throw AiCreationException("图片生成超时: 网络连接超时，请重试", e)
        } catch (e: UnknownHostException) {
            throw AiCreationException("图片生成失败: 无法解析主机 apihub.agnes-ai.com，请检查网络", e)
        } catch (e: IOException) {
            throw AiCreationException("图片生成失败: 网络异常 (${e.javaClass.simpleName}: ${e.message})", e)
        }
        response.use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AiCreationException(
                    "图片生成失败: HTTP ${resp.code} ${resp.message} ${extractError(payload)}",
                    payload
                )
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

    private data class VideoTaskInfo(val taskId: String, val videoId: String?)

    /**
     * @param numFrames 视频总帧数 (24fps: 3秒=81帧, 5秒=121帧, 10秒=241帧, 15秒=361帧)
     * @param onProgress 进度回调，每次轮询后调用
     */
    suspend fun textToVideo(
        prompt: String,
        numFrames: Int = 121,
        width: Int = DEFAULT_VIDEO_WIDTH,
        height: Int = DEFAULT_VIDEO_HEIGHT,
        onProgress: ((GenerationProgress) -> Unit)? = null
    ): VideoResult = createAndPollVideo(prompt, numFrames, width, height, onProgress = onProgress)

    suspend fun imageToVideo(
        prompt: String,
        imageUrl: String,
        numFrames: Int = 121,
        width: Int = DEFAULT_VIDEO_WIDTH,
        height: Int = DEFAULT_VIDEO_HEIGHT,
        onProgress: ((GenerationProgress) -> Unit)? = null
    ): VideoResult = createAndPollVideo(prompt, numFrames, width, height, imageUrl = imageUrl, onProgress = onProgress)

    private suspend fun createAndPollVideo(
        prompt: String,
        numFrames: Int,
        width: Int,
        height: Int,
        imageUrl: String? = null,
        onProgress: ((GenerationProgress) -> Unit)? = null
    ): VideoResult {
        requireApiKey()
        val info = createVideoTask(prompt, numFrames, width, height, imageUrl)
        return pollVideoResult(info.taskId, info.videoId, onProgress)
    }

    private suspend fun createVideoTask(
        prompt: String,
        numFrames: Int,
        width: Int,
        height: Int,
        imageUrl: String?
    ): VideoTaskInfo {
        val body = JSONObject().apply {
            put("model", VIDEO_MODEL)
            put("prompt", prompt)
            put("num_frames", numFrames)
            put("frame_rate", 24)
            put("width", width)
            put("height", height)
            if (imageUrl != null) {
                put("image", imageUrl)
            }
        }
        val response = try {
            client.newCallResponse {
                url("$BASE_URL/v1/videos")
                addHeader("Authorization", "Bearer $apiKey")
                addHeader("Content-Type", "application/json")
                postJson(body.toString())
            }
        } catch (e: SocketTimeoutException) {
            throw AiCreationException("视频任务创建超时: 网络连接超时，请重试", e)
        } catch (e: UnknownHostException) {
            throw AiCreationException("视频任务创建失败: 无法解析主机 apihub.agnes-ai.com，请检查网络", e)
        } catch (e: IOException) {
            throw AiCreationException("视频任务创建失败: 网络异常 (${e.javaClass.simpleName}: ${e.message})", e)
        }
        response.use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AiCreationException(
                    "视频任务创建失败: HTTP ${resp.code} ${resp.message} ${extractError(payload)}",
                    payload
                )
            }
            val root = JSONObject(payload)
            val taskId = root.optString("task_id").takeIf { it.isNotBlank() }
                ?: root.optString("id").takeIf { it.isNotBlank() }
                ?: throw AiCreationException("视频任务创建失败: 未获取到任务ID", payload)
            val videoId = root.optString("video_id").takeIf { it.isNotBlank() }
            return VideoTaskInfo(taskId, videoId)
        }
    }

    private suspend fun pollVideoResult(
        taskId: String,
        videoId: String? = null,
        onProgress: ((GenerationProgress) -> Unit)? = null
    ): VideoResult {
        val startTime = System.currentTimeMillis()
        var lastStatus = "unknown"
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            delay(POLL_INTERVAL_MS)
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            val response = try {
                client.newCallResponse {
                    if (videoId != null) {
                        url("$BASE_URL/agnesapi?video_id=$videoId")
                    } else {
                        url("$BASE_URL/v1/videos/$taskId")
                    }
                    addHeader("Authorization", "Bearer $apiKey")
                }
            } catch (e: SocketTimeoutException) {
                if (attempt > 3) {
                    throw AiCreationException(
                        "视频查询超时: 连续网络超时（已轮询 ${attempt + 1} 次 / ${elapsed}秒），" +
                        "上次状态: $lastStatus，任务ID: $taskId", e
                    )
                }
                return@repeat
            } catch (e: UnknownHostException) {
                if (attempt > 3) {
                    throw AiCreationException(
                        "视频查询失败: 连续无法解析主机（已轮询 ${attempt + 1} 次 / ${elapsed}秒），" +
                        "上次状态: $lastStatus，任务ID: $taskId", e
                    )
                }
                return@repeat
            } catch (e: IOException) {
                if (attempt > 3) {
                    throw AiCreationException(
                        "视频查询失败: 网络异常 (${e.javaClass.simpleName}: ${e.message})，" +
                        "已轮询 ${attempt + 1} 次 / ${elapsed}秒，任务ID: $taskId", e
                    )
                }
                return@repeat
            }
            response.use { resp ->
                val payload = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw AiCreationException(
                        "视频查询失败: HTTP ${resp.code} ${resp.message} ${extractError(payload)}，" +
                        "已轮询 ${attempt + 1} 次 / ${elapsed}秒，任务ID: $taskId", payload
                    )
                }
                val root = JSONObject(payload)
                lastStatus = root.optString("status", lastStatus)
                // 优先用 API 返回的 progress，否则用时间估算
                val apiProgress = root.optInt("progress", -1)
                val percent = if (apiProgress in 0..100) {
                    apiProgress
                } else {
                    (attempt * 100 / MAX_POLL_ATTEMPTS).coerceIn(0, 99)
                }
                val statusText = when (lastStatus) {
                    "queued" -> "排队中"
                    "in_progress" -> "生成中"
                    "completed" -> "完成"
                    else -> lastStatus
                }
                onProgress?.invoke(GenerationProgress(percent, statusText, elapsed))

                when (lastStatus) {
                    "succeeded", "completed" -> {
                        val data = root.optJSONArray("data")
                        val url = data?.optJSONObject(0)?.optString("url")
                            ?.takeIf { it.isNotBlank() }
                            ?: root.optString("remixed_from_video_id")
                            ?: root.optString("video_url")
                            ?.takeIf { it.isNotBlank() }
                        if (!url.isNullOrBlank()) return VideoResult(videoUrl = url)
                        throw AiCreationException("视频生成完成但未获取到URL，任务ID: $taskId", payload)
                    }
                    "failed", "cancelled" -> {
                        val err = root.optJSONObject("error")
                        val code = err?.optString("code", "")
                        val hint = when {
                            code == "500" || err?.optString("message")?.contains("Internal", ignoreCase = true) == true ->
                                "（服务端故障，建议稍后重试）"
                            else -> ""
                        }
                        throw AiCreationException(
                            "视频生成失败: ${err?.optString("message") ?: "未知错误"}，" +
                            "状态: $lastStatus，任务ID: $taskId $hint", payload
                        )
                    }
                }
            }
        }
        val totalElapsed = (System.currentTimeMillis() - startTime) / 1000
        throw AiCreationException(
            "视频生成超时（已等待 ${totalElapsed}秒 / ${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000}秒），" +
            "最后状态: $lastStatus，任务ID: $taskId，请稍后重试", ""
        )
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
                    ?: it.optString("type")
            } ?: payload.take(200)
        } catch (_: Exception) {
            payload.take(200)
        }
    }
}

class AiCreationException(message: String, debugInfo: String = "") :
    Exception("$message${if (debugInfo.isNotBlank()) "\n$debugInfo" else ""}") {

    constructor(message: String, cause: Throwable) : this(
        "$message (${cause.javaClass.simpleName})",
        ""
    )
}