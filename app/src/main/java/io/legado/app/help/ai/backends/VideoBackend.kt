package io.legado.app.help.ai.backends

import java.io.File

/**
 * 视频 backend 抽象（移植 ArcReel lib/video_backends/base.py:451-475）。
 *
 * 非 sealed：各 backend 实现在 `video/` 子包，sealed 跨包不允许；
 * 动态分发经 [VideoBackendRegistry]，无需 exhaustive when。
 *
 * 生命周期模型 1a 忠实：[generate] 自管 submit+poll+download 全生命周期。
 * cancellation 通过 suspend 函数在 suspension point（[kotlinx.coroutines.delay]/HTTP await）
 * 自然响应 `CoroutineScope.cancel`。进度通过 [onProgress] 回调（对应 ArcReel
 * `poll_with_retry.on_progress`）。429 Retry-After、[AmbiguousSubmitError]、重试谓词在
 * [VideoBackendHttp] 内；413 续档在 MediaGenerator 咽喉层（P1）。
 */
interface VideoBackend {
    val typeId: String                          // "ark"/"agnes"/...
    val model: String
    val capabilities: Set<VideoCapability>      // 端点级
    val videoCapabilities: VideoCapabilities    // 参考图级

    suspend fun generate(
        request: VideoGenerationRequest,
        onProgress: (VideoProgress) -> Unit = {}
    ): VideoGenerationResult

    /** 恢复已提交但未完成的任务（jobId 由 [JobIdStore] 持久化）。 */
    suspend fun resumeVideo(jobId: String, request: VideoGenerationRequest): VideoGenerationResult
}

/** 端点级能力（移植 base.py 双能力模型）。 */
enum class VideoCapability {
    TEXT_TO_VIDEO, IMAGE_TO_VIDEO, GENERATE_AUDIO,
    NEGATIVE_PROMPT, VIDEO_EXTEND, SEED_CONTROL, FLEX_TIER
}

/**
 * 参考图级能力（移植 base.py:378-405）。
 *
 * 每个 backend 通过伴生纯函数 `videoCapabilitiesForModel(model)` 计算 instance 值——
 * 统一 ArcReel 的不对称（6 静态方法 + 5 property）为单一真相源。
 */
data class VideoCapabilities(
    val firstFrame: Boolean = true,
    val lastFrame: Boolean = false,
    val referenceImages: Boolean = false,
    val maxReferenceImages: Int = 0,
    val referenceImagesWithStartFrame: Boolean = false
)

/** 视频生成请求（移植 base.py:408-448）。参考图为本地 [File]，由压缩管线（P1）处理。 */
data class VideoGenerationRequest(
    val prompt: String,
    val outputPath: File,
    val aspectRatio: String = "9:16",
    val durationSeconds: Int = 5,
    val resolution: String? = null,
    val startImage: File? = null,
    val endImage: File? = null,
    val referenceImages: List<File>? = null,
    val generateAudio: Boolean = true,
    val projectName: String? = null,
    val taskId: String? = null,
    val serviceTier: String = "default",
    val seed: Long? = null
)

data class VideoGenerationResult(
    val videoPath: File,
    val provider: String,
    val model: String,
    val durationSeconds: Int,
    val videoUri: String? = null,
    val seed: Long? = null,
    val usageTokens: Long? = null,
    val taskId: String? = null,
    val generateAudio: Boolean? = null
)

data class VideoProgress(val status: String, val message: String? = null)
