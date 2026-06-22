package io.legado.app.help.ai

import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.service.AiVideoTaskService
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx

/**
 * AI 视频生成高层 API：负责选 Provider、提交任务、轮询。
 *
 * 实际"任务跑完"由 [AiVideoTaskService] 后台接管，
 * 这里只负责一次 submit，并发完任务立刻返回。
 */
object AiVideoService {

    /**
     * 提交一个生成任务，立刻返回行记录（status=pending）。
     * 真正的下载与状态推进由 [AiVideoTaskService] 处理。
     */
    suspend fun submitAndStore(
        prompt: String,
        provider: AiVideoProviderConfig? = null,
        negativePrompt: String = "",
        firstFrame: String? = null,
        durationSec: Int = 0,
        aspectRatio: String = "",
        metadata: AiVideoGalleryManager.VideoMetadata = AiVideoGalleryManager.VideoMetadata()
    ): AiGeneratedVideo {
        val effectiveProvider = provider
            ?: AppConfig.aiCurrentVideoProvider
            ?: error("No AI video provider configured")
        if (prompt.isBlank()) error("Prompt is empty")
        val providerImpl = AiVideoProviderFactory.create(effectiveProvider)
        val params = VideoGenerationParams(
            prompt = prompt,
            negativePrompt = negativePrompt,
            firstFrame = firstFrame,
            durationSec = durationSec,
            aspectRatio = aspectRatio
        )
        val taskId = providerImpl.submit(prompt, params)
        val row = AiVideoGalleryManager.saveSubmittedTask(
            prompt = prompt,
            negativePrompt = negativePrompt,
            provider = effectiveProvider,
            model = effectiveProvider.model,
            externalTaskId = taskId,
            durationSec = durationSec,
            aspectRatio = aspectRatio,
            firstFrame = firstFrame,
            metadata = metadata
        )
        LiveEventBus.get(EventBus.AI_VIDEO_SUBMITTED).post(row.id)
        // 触发后台服务开始执行
        AiVideoTaskService.startIfNeeded(appCtx)
        return row
    }

    /**
     * 同步等待一个任务（用于调试 / 测试入口）。
     * 不会真正阻塞主线程；只作为辅助。
     */
    suspend fun waitForCompletion(
        videoId: String,
        timeoutMs: Long = 10 * 60 * 1000L
    ): AiGeneratedVideo? = withTimeoutOrNull(timeoutMs) {
        val provider = AppConfig.findEnabledVideoProvider(
            appDb.aiGeneratedVideoDao.get(videoId)?.providerId
        ) ?: return@withTimeoutOrNull null
        val providerImpl = AiVideoProviderFactory.create(provider)
        val row = appDb.aiGeneratedVideoDao.get(videoId) ?: return@withTimeoutOrNull null
        if (row.status == AiGeneratedVideo.STATUS_SUCCESS) return@withTimeoutOrNull row
        if (row.externalTaskId.isBlank()) return@withTimeoutOrNull null
        val startedAt = System.currentTimeMillis()
        while (true) {
            if (System.currentTimeMillis() - startedAt > provider.validMaxWaitMs()) {
                AiVideoGalleryManager.updateStatus(videoId, AiGeneratedVideo.STATUS_FAILED, "timeout")
                break
            }
            val polled = try {
                providerImpl.poll(row.externalTaskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                VideoPollResult(
                    status = VideoStatus.FAILED,
                    failReason = e.message ?: e.javaClass.simpleName
                )
            }
            when (polled.status) {
                VideoStatus.SUCCESS -> {
                    val final = AiVideoGalleryManager.saveCompletedVideo(
                        videoId = videoId,
                        videoUrl = polled.videoUrl,
                        coverUrl = polled.coverUrl,
                        provider = provider,
                        durationMs = polled.durationMs,
                        width = polled.width,
                        height = polled.height,
                        sizeBytes = polled.sizeBytes
                    )
                    LiveEventBus.get(EventBus.AI_VIDEO_COMPLETED)
                        .post(Pair(videoId, final.id))
                    return@withTimeoutOrNull final
                }
                VideoStatus.FAILED -> {
                    AiVideoGalleryManager.updateStatus(
                        videoId,
                        AiGeneratedVideo.STATUS_FAILED,
                        polled.failReason ?: "unknown",
                        0
                    )
                    LiveEventBus.get(EventBus.AI_VIDEO_FAILED)
                        .post(Pair(videoId, polled.failReason ?: "unknown"))
                    return@withTimeoutOrNull null
                }
                VideoStatus.CANCELLED -> {
                    AiVideoGalleryManager.updateStatus(
                        videoId,
                        AiGeneratedVideo.STATUS_CANCELLED,
                        "cancelled",
                        polled.progress
                    )
                    return@withTimeoutOrNull null
                }
                else -> {
                    AiVideoGalleryManager.updateProgress(videoId, polled.progress)
                    LiveEventBus.get(EventBus.AI_VIDEO_PROGRESS)
                        .post(Pair(videoId, polled.progress))
                    kotlinx.coroutines.delay(provider.validPollIntervalMs())
                }
            }
        }
        null
    }
}

