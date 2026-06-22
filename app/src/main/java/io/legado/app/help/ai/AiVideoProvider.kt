package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.json.JSONObject

/** 视频生成参数 */
data class VideoGenerationParams(
    val prompt: String,
    val negativePrompt: String = "",
    val firstFrame: String? = null,
    val lastFrame: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Int = 0,
    val seed: Long = -1L,
    val aspectRatio: String = "",
    val extra: JSONObject = JSONObject()
)

/** 轮询返回的视频任务状态 */
enum class VideoStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED;

    companion object {
        fun from(raw: String?): VideoStatus = when (raw?.lowercase()) {
            "pending", "queued", "waiting" -> PENDING
            "running", "processing", "in_progress", "inprogress" -> RUNNING
            "success", "succeeded", "completed", "done" -> SUCCESS
            "failed", "error" -> FAILED
            "cancelled", "canceled" -> CANCELLED
            else -> PENDING
        }
    }
}

/** 轮询结果 */
data class VideoPollResult(
    val status: VideoStatus,
    val progress: Int = 0,
    val videoUrl: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val failReason: String? = null,
    val raw: JSONObject? = null
)

/**
 * 视频生成 Provider 统一接口。
 * 实现需负责：
 *  - submit: 提交生成请求，返回 Provider 内部任务 id
 *  - poll: 轮询任务状态，解析出 [VideoPollResult]
 *  - cancel: 取消任务（可选实现）
 */
interface AiVideoProvider {
    val config: AiVideoProviderConfig

    suspend fun submit(prompt: String, params: VideoGenerationParams): String

    suspend fun poll(externalTaskId: String): VideoPollResult

    suspend fun cancel(externalTaskId: String): Boolean = false
}
