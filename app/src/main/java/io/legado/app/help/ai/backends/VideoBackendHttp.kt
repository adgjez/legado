package io.legado.app.help.ai.backends

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 共享 HTTP 工具（移植 ArcReel lib/video_backends/base.py:208-362）。
 *
 * - [submitPost]：非幂等 POST。传输歧义→[AmbiguousSubmitError]（终态不重试，避免重复扣费）；
 *   429 按 Retry-After 等待内部重试上限 [MAX_429_RETRIES] 次，耗尽抛 [VideoRateLimitedException]。
 * - [pollWithRetry]：轮询直到 done/failed，支持瞬态重试谓词与进度回调。
 * - [downloadVideo]：下载视频到本地文件。
 * - [shouldRetrySubmit]/[shouldRetryPoll]/[shouldRetryDownload]：重试谓词。
 *
 * 不内置 [Dispatchers] 切换（submit/poll 让调用方/服务层决定线程），仅 [downloadVideo] 用 IO。
 * 这样 submit/poll 在 [kotlinx.coroutines.test.runTest] 下走虚拟时间，便于单测。
 */
object VideoBackendHttp {

    /** 429 内部重试上限（不计入 submit 重试）。 */
    const val MAX_429_RETRIES = 5

    /** 429 退避基线（Retry-After 缺失时），单位毫秒。 */
    private const val RATE_LIMIT_BACKOFF_BASE_MS = 1_000L

    /** 429 退避上限，单位毫秒。 */
    private const val RATE_LIMIT_BACKOFF_CAP_MS = 30_000L

    private const val POLL_BACKOFF_MS = 2_000L
    private const val POLL_BACKOFF_CAP_MS = 30_000L

    /**
     * 非幂等 POST 提交。
     *
     * @param postFn 实际发请求并返回 [Response]（调用方负责构造请求；响应体由本函数或调用方关闭）。
     * @param provider 用于错误消息标识（如 "agnes"/"ark"）。
     *
     * - 429：读 Retry-After（秒）等待后重试，上限 [MAX_429_RETRIES] 次；耗尽抛 [VideoRateLimitedException]。
     * - 传输歧义（[postFn] 抛 [IOException]）：抛 [AmbiguousSubmitError]，不重试
     *   （请求可能已送达，避免重复扣费）。
     * - 其余状态码原样返回，由调用方处理（成功或 4xx/5xx 业务错误）。
     */
    suspend fun submitPost(postFn: suspend () -> Response, provider: String): Response {
        var four29 = 0
        while (true) {
            val resp = try {
                postFn()
            } catch (e: IOException) {
                throw AmbiguousSubmitError(
                    "非幂等 POST 传输歧义：$provider 提交可能已送达但未收到响应" +
                        "（${e.javaClass.simpleName}: ${e.message}），不重试以免重复扣费"
                )
            }
            if (resp.code != 429) return resp
            val retryAfter = resp.header("Retry-After")?.toLongOrNull()
            resp.close()
            four29++
            if (four29 > MAX_429_RETRIES) {
                throw VideoRateLimitedException(
                    retryAfterSeconds = retryAfter,
                    message = "$provider 提交被限流：HTTP 429，已内部重试 $MAX_429_RETRIES 次仍失败" +
                        (retryAfter?.let { "，Retry-After=${it}s" } ?: "")
                )
            }
            delay(rateLimitWaitMs(retryAfter, four29))
        }
    }

    private fun rateLimitWaitMs(retryAfter: Long?, attempt: Int): Long {
        if (retryAfter != null && retryAfter > 0) {
            return min(retryAfter * 1000L, RATE_LIMIT_BACKOFF_CAP_MS)
        }
        val backoff = (RATE_LIMIT_BACKOFF_BASE_MS * 2.0.pow(attempt - 1)).toLong()
        return min(backoff, RATE_LIMIT_BACKOFF_CAP_MS)
    }

    /**
     * 轮询直到 [isDone] 或 [isFailed]。
     *
     * - [isFailed] 返回 true → 抛 IllegalStateException。
     * - [retryIf]：pollFn 抛非 cancellation 异常时，由其判定是否瞬态重试（指数退避）。
     * - 超过 [maxWait] → 抛 IllegalStateException("轮询超时")。
     * - 每次未完成循环触发 [onProgress]（status="polling"）。
     *
     * 用 [withTimeout] 控制总时长，在 runTest 下走虚拟时间。
     */
    suspend fun <T> pollWithRetry(
        pollFn: suspend () -> T,
        isDone: (T) -> Boolean,
        isFailed: (T) -> Boolean,
        pollInterval: Duration,
        maxWait: Duration,
        retryIf: ((Throwable) -> Boolean)? = null,
        label: String = "",
        onProgress: (VideoProgress) -> Unit = {}
    ): T {
        return try {
            withTimeout(maxWait) {
                while (true) {
                    val result = try {
                        pollFn()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (retryIf != null && retryIf(e)) {
                            delay(POLL_BACKOFF_MS.milliseconds)
                            continue
                        }
                        throw e
                    }
                    if (isFailed(result)) error("轮询失败${label}")
                    if (isDone(result)) return@withTimeout result
                    onProgress(VideoProgress("polling", label.takeIf { it.isNotBlank() }))
                    delay(pollInterval.coerceAtLeast(500.milliseconds))
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            error("轮询超时${label}（>${maxWait.inWholeMilliseconds}ms）")
        }
    }

    /** 下载视频 URL 到 [outputPath]，失败抛 IllegalStateException。 */
    suspend fun downloadVideo(url: String, outputPath: File, timeout: Duration = 120.seconds) {
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .callTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("下载视频失败：HTTP ${resp.code} ${resp.message}")
                val body = resp.body ?: error("下载视频失败：响应体为空")
                body.byteStream().use { input ->
                    outputPath.parentFile?.mkdirs()
                    outputPath.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    /** submit 重试谓词：4xx（非 429）终态不重试；5xx/网络瞬态重试。 */
    fun shouldRetrySubmit(e: Throwable): Boolean = when (e) {
        is AmbiguousSubmitError -> false
        is VideoRateLimitedException -> false
        is IOException -> true
        else -> httpCodeOf(e)?.let { it >= 500 } ?: false
    }

    /** poll 重试谓词：404 视为瞬态（任务未就绪）可重试；5xx/网络重试。 */
    fun shouldRetryPoll(e: Throwable): Boolean = when (e) {
        is IOException -> true
        else -> httpCodeOf(e)?.let { it == 404 || it >= 500 } ?: false
    }

    /** download 重试谓词：404 终态不重试；5xx/网络重试。 */
    fun shouldRetryDownload(e: Throwable): Boolean = when (e) {
        is IOException -> true
        else -> httpCodeOf(e)?.let { it != 404 && it >= 500 } ?: false
    }

    /** 从异常 message 中提取 `HTTP 502` 形式的状态码（服务层错误消息约定）。 */
    private fun httpCodeOf(e: Throwable): Int? {
        val match = Regex("HTTP\\s+(\\d{3})").find(e.message.orEmpty())
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}

/** 非幂等 POST 传输歧义：请求可能已送达但未收到响应，终态不重试避免重复扣费。 */
class AmbiguousSubmitError(message: String) : IllegalStateException(message)

/** 429 限流异常，携带 Retry-After（秒）。[VideoBackendHttp.submitPost] 内部耗尽重试后抛出。 */
class VideoRateLimitedException(
    val retryAfterSeconds: Long?,
    message: String
) : IllegalStateException(message)
