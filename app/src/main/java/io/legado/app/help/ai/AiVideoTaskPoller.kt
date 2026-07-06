package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

    /** 匹配错误消息中的 HTTP 4xx 状态码（如 " 400 "、" 401 "、" 429 "），用于判断不重试的鉴权/参数错误。 */
    private val HTTP_4XX_REGEX = Regex("\\b4\\d{2}\\b")

    /** 状态机：调用方可在 [onStatus] 收到这些字符串用于 UI 展示。 */
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
     * 端到端生成一段视频。
     *
     * @param prompt 视频提示词（已经过 [NovelVideoPromptBuilder.sanitizeVideoPrompt] 净化）
     * @param seconds 时长
     * @param size 分辨率
     * @param referenceImages 参考图（角色三视图 + 场景图，最多 [AiVideoProviderConfig.maxReferenceImages] 张）
     * @param jobId 用于下载路径隔离
     * @param segId 用于下载文件命名
     * @param provider Provider；null 用当前默认
     * @param isCancelled 取消信号，每 ~400ms 检查一次
     * @param onStatus 状态回调（[Stage] 常量）
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
        onStatus(Stage.SUBMITTING)
        val taskId = submitWithRetry(prompt, seconds, size, referenceImages, provider, isCancelled)
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
     * 网络异常/5xx 重试；4xx（鉴权/参数错误）直接放弃。
     */
    private suspend fun submitWithRetry(
        prompt: String,
        seconds: Int,
        size: String,
        referenceImages: List<String>,
        provider: AiVideoProviderConfig?,
        isCancelled: () -> Boolean
    ): String? {
        val maxAttempts = MAX_SUBMIT_RETRY + 1
        repeat(maxAttempts) { attempt ->
            if (isCancelled()) return null
            try {
                return AiVideoService.submit(prompt, seconds, size, referenceImages, provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = e.message.orEmpty()
                // 4xx 类错误（鉴权/参数）不重试：用正则匹配 " 4xx " 形态的状态码
                // 避免旧的 contains(" 40") 误匹配 "page 40" 或漏判 43x
                if (HTTP_4XX_REGEX.containsMatchIn(msg)) {
                    AppLog.put("视频提交 ${attempt + 1}/$maxAttempts 失败（4xx，不重试）", e)
                    return null
                }
                AppLog.put("视频提交 ${attempt + 1}/$maxAttempts 失败：$msg", e)
            }
        }
        return null
    }
}
