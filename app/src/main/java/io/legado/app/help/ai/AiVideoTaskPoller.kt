package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 视频任务的高层封装：submit → poll → downloadToLocal，三步一气呵成。
 *
 * 与 [AiVideoService] 的低层 API 区别：
 * - 合并提交+轮询+下载为一个调用，调用方只关心最终本地路径
 * - 提供统一的状态回调（onStatus），便于 UI/通知显示"提交中/排队中/生成中/下载中"
 * - 提交失败自动重试 [MAX_SUBMIT_RETRY] 次（轮询失败不重试，由总超时兜底）
 *
 * 不在这里做并发控制——并发由 [NovelVideoGenerator] 用 coroutine async 控制。
 */
object AiVideoTaskPoller {

    private const val MAX_SUBMIT_RETRY = 2

    /** 429 限流重试上限（不计入 MAX_SUBMIT_RETRY 放弃次数，防止服务商持续限流导致无限等待）。 */
    private const val MAX_RATE_LIMIT_RETRY = 5

    /** 匹配错误消息中的 HTTP 4xx 状态码（如 " 400 "、" 401 "、" 429 "），用于判断不重试的鉴权/参数错误。 */
    private val HTTP_4XX_REGEX = Regex("\\b4\\d{2}\\b")

    /**
     * 状态机：调用方可在 [onStatus] 收到这些字符串用于 UI 展示。
     *
     * 注意：这里的常量是「单段视频生成的轮询阶段」标识，与
     * [io.legado.app.data.entities.NovelVideoJobStatus.GENERATING]（任务级状态）
     * 字符串值恰好相同但语义不同，不应混用。
     */
    object Stage {
        const val SUBMITTING = "submitting"
        const val QUEUED = "queued"
        const val GENERATING = "generating"
        const val DOWNLOADING = "downloading"
    }

    /**
     * 单段视频生成结果。
     */
    sealed class Result {
        data class Success(
            val localPath: String,
            val remoteUrl: String,
            val taskId: String
        ) : Result()

        data class Failed(val message: String, val taskId: String?) : Result()
    }

    /**
     * 端到端生成一段视频（旧签名，向后兼容）。
     *
     * 内部构造 [VideoSubmitRequest] 后调 [generate] 新签名。
     * 高级参数完全由 [AiVideoProviderConfig.defaultParamsJson] 提供（按模型自适应）。
     */
    suspend fun generate(
        prompt: String,
        seconds: Int,
        size: String,
        referenceImages: List<String>,
        jobId: String,
        segId: String,
        provider: AiVideoProviderConfig? = null,
        isCancelled: () -> Boolean = { false },
        onStatus: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        generate(
            VideoSubmitRequest(
                prompt = prompt,
                seconds = seconds,
                size = size,
                referenceImages = referenceImages
            ),
            jobId = jobId, segId = segId,
            provider = provider, isCancelled = isCancelled, onStatus = onStatus
        )
    }

    /**
     * 端到端生成一段视频（新签名，接收 [VideoSubmitRequest]）。
     *
     * 调用方只需在 [request] 中提供基础字段（prompt/seconds/size/referenceImages），
     * 高级参数（mode/negative_prompt/seed/camera_fixed/generate_audio 等）由
     * [AiVideoService.submit] 内部按 Provider type 从 [AiVideoProviderConfig.defaultParamsJson]
     * 自动注入，实现「按所选模型自适应发挥其能力」。
     *
     * @param request 视频生成请求（基础字段必填，高级字段可选，留空则用 Provider 配置）
     * @param jobId 用于下载路径隔离
     * @param segId 用于下载文件命名
     * @param provider Provider；null 用当前默认
     * @param isCancelled 取消信号，每 ~400ms 检查一次
     * @param onStatus 状态回调（[Stage] 常量）
     */
    suspend fun generate(
        request: VideoSubmitRequest,
        jobId: String,
        segId: String,
        provider: AiVideoProviderConfig? = null,
        isCancelled: () -> Boolean = { false },
        onStatus: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        onStatus(Stage.SUBMITTING)
        val taskId = submitWithRetry(request, provider, isCancelled)
            ?: return@withContext Result.Failed("视频任务提交失败（重试 ${MAX_SUBMIT_RETRY + 1} 次仍失败）", null)

        onStatus(Stage.QUEUED)
        val pollResult = try {
            AiVideoService.poll(taskId, provider, isCancelled)
        } catch (e: CancellationException) {
            // 协程取消必须向上传播，不能降级为失败结果
            throw e
        } catch (e: Throwable) {
            AppLog.put("视频轮询异常 taskId=$taskId", e)
            return@withContext Result.Failed("视频轮询异常：${e.message}", taskId)
        }

        when (pollResult) {
            is AiVideoService.VideoPollResult.Success -> {
                onStatus(Stage.DOWNLOADING)
                try {
                    val localPath = AiVideoService.downloadToLocal(pollResult.videoUrl, jobId, segId)
                    Result.Success(localPath, pollResult.videoUrl, taskId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.put("视频下载失败 url=${pollResult.videoUrl}", e)
                    Result.Failed("视频下载失败：${e.message}", taskId)
                }
            }
            is AiVideoService.VideoPollResult.Failed -> {
                Result.Failed(pollResult.message, taskId)
            }
            AiVideoService.VideoPollResult.Polling -> {
                // 理论上 poll() 不会以 Polling 终态返回，保险起见处理一下
                Result.Failed("视频生成未完成（轮询结束于 Polling 态）", taskId)
            }
        }
    }

    /**
     * 提交任务，最多重试 [MAX_SUBMIT_RETRY] 次。
     * - 网络异常/5xx：立即重试
     * - 429 限流：读 Retry-After header 等待后重试，429 不计入放弃次数
     *   （限流是暂态，服务商要求降速而非放弃）
     * - 其他 4xx（400/401/403/404 等鉴权/参数错误）：直接放弃
     */
    private suspend fun submitWithRetry(
        request: VideoSubmitRequest,
        provider: AiVideoProviderConfig?,
        isCancelled: () -> Boolean
    ): String? {
        val maxAttempts = MAX_SUBMIT_RETRY + 1
        var attempt = 0
        var rateLimitedAttempts = 0
        while (attempt < maxAttempts) {
            if (isCancelled()) return null
            try {
                return AiVideoService.submit(request, provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiVideoService.VideoRateLimitedException) {
                // 429 限流：等待 Retry-After 后重试，不计入放弃次数
                // 上限 5 次限流重试，防止服务商持续限流导致无限等待
                if (rateLimitedAttempts >= MAX_RATE_LIMIT_RETRY) {
                    AppLog.put("视频提交限流重试达上限（$MAX_RATE_LIMIT_RETRY 次），放弃")
                    return null
                }
                rateLimitedAttempts++
                // Retry-After 缺失时默认等 10s；上限 60s 避免过长阻塞
                val waitMs = (e.retryAfterSeconds ?: 10L).coerceIn(1L, 60L) * 1000L
                AppLog.put("视频提交被限流（429），${waitMs}ms 后重试（限流重试 $rateLimitedAttempts/$MAX_RATE_LIMIT_RETRY）")
                delay(waitMs)
                // 限流重试不递增 attempt，仍受 maxAttempts 兜底
            } catch (e: Throwable) {
                attempt++
                val msg = e.message.orEmpty()
                // 4xx 类错误（鉴权/参数）不重试：用正则匹配 " 4xx " 形态的状态码
                // 避免旧的 contains(" 40") 误匹配 "page 40" 或漏判 43x
                if (HTTP_4XX_REGEX.containsMatchIn(msg)) {
                    AppLog.put("视频提交 $attempt/$maxAttempts 失败（4xx，不重试）", e)
                    return null
                }
                AppLog.put("视频提交 $attempt/$maxAttempts 失败：$msg", e)
            }
        }
        return null
    }
}
