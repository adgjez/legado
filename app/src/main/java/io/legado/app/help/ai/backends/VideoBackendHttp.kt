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
 * - [downloadVideo]：下载视频到本地文件，带重试（[DOWNLOAD_MAX_ATTEMPTS] 次，退避 [DOWNLOAD_BACKOFF_MS]）。
 * - [shouldRetrySubmit]/[shouldRetryPoll]/[shouldRetryDownload]：重试谓词。
 *
 * 重试常量（移植 ArcReel retry.py:43-48）：
 * - [DEFAULT_MAX_ATTEMPTS]=3 / [DEFAULT_BACKOFF_MS]=(2,4,8)s
 * - [DOWNLOAD_MAX_ATTEMPTS]=5 / [DOWNLOAD_BACKOFF_MS]=(5,10,20,40)s
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

    /** 默认重试次数（移植 ArcReel DEFAULT_MAX_ATTEMPTS=3）。 */
    const val DEFAULT_MAX_ATTEMPTS = 3

    /** 默认退避序列（秒，移植 ArcReel DEFAULT_BACKOFF_SECONDS=(2,4,8)）。 */
    val DEFAULT_BACKOFF_MS: List<Long> = listOf(2_000L, 4_000L, 8_000L)

    /** 下载重试次数（移植 ArcReel DOWNLOAD_MAX_ATTEMPTS=5）。 */
    const val DOWNLOAD_MAX_ATTEMPTS = 5

    /** 下载退避序列（毫秒，移植 ArcReel DOWNLOAD_BACKOFF_SECONDS=(5,10,20,40)）。 */
    val DOWNLOAD_BACKOFF_MS: List<Long> = listOf(5_000L, 10_000L, 20_000L, 40_000L)

    private const val POLL_BACKOFF_MS = 2_000L
    private const val POLL_BACKOFF_CAP_MS = 30_000L

    /**
     * 非幂等 POST 提交。
     *
     * @param postFn 实际发请求并返回 [Response]（调用方负责构造请求；响应体由本函数或调用方关闭）。
     * @param provider 用于错误消息标识（如 "agnes"/"ark"）。
     *
     * - 429：读 Retry-After（秒）等待后重试，上限 [MAX_429_RETRIES] 次；耗尽抛 [VideoRateLimitedException]。
     * - 传输歧义（[postFn] 抛 [IOException]，但**非**连接建立失败）→ [AmbiguousSubmitError]，不重试
     *   （请求可能已送达，避免重复扣费）。
     * - 连接建立失败（[isNotSentTransport]）→ 原样抛 [IOException]，交 [shouldRetrySubmit] 重试
     *   （请求确定未送达，重试无重复扣费风险）。
     * - 其余状态码原样返回，由调用方处理（成功或 4xx/5xx 业务错误）。
     */
    suspend fun submitPost(postFn: suspend () -> Response, provider: String): Response {
        var four29 = 0
        while (true) {
            val resp = try {
                postFn()
            } catch (e: IOException) {
                // 区分「连接建立失败」（确定未送达，可重试）与「已送达歧义」（终态不重试）
                if (isNotSentTransport(e)) throw e
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

    /**
     * 判定 IOException 是否为「请求确定未送达」（对应 ArcReel _NOT_SENT_TRANSPORT_ERRORS）。
     *
     * 这类异常可安全重试（无重复扣费风险），不应包成 [AmbiguousSubmitError]：
     * - [java.net.ConnectException]：TCP 连接建立失败
     * - [java.net.UnknownHostException]：DNS 解析失败
     * - [java.net.SocketTimeoutException]：连接阶段超时（message 含 "connect"）
     *
     * 其余 IOException（写/读阶段失败、SSL 握手后断开等）视为歧义态，包 AmbiguousSubmitError。
     */
    private fun isNotSentTransport(e: IOException): Boolean {
        return when (e) {
            is java.net.ConnectException,
            is java.net.UnknownHostException -> true
            is java.net.SocketTimeoutException ->
                // connect 阶段超时判定未送达；read 阶段超时判歧义
                e.message?.lowercase()?.contains("connect") == true
            else -> false
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
     *   **null 时默认重试瞬态异常**（[defaultShouldRetry]，对齐 ArcReel `retry_if is None → _should_retry`）。
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
        val predicate = retryIf ?: ::defaultShouldRetry
        return try {
            withTimeout(maxWait) {
                while (true) {
                    val result = try {
                        pollFn()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (predicate(e)) {
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

    /**
     * 下载视频 URL 到 [outputPath]，带重试（[DOWNLOAD_MAX_ATTEMPTS] 次，退避 [DOWNLOAD_BACKOFF_MS]）。
     *
     * 移植 ArcReel `download_video` + 各 backend 的 `_download_with_retry`。
     * 下载瞬态失败（5xx/网络）按 [shouldRetryDownload] 重试；404 终态不重试。
     */
    suspend fun downloadVideo(url: String, outputPath: File, timeout: Duration = 120.seconds) {
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .callTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .build()
            var lastError: Throwable? = null
            for (attempt in 0 until DOWNLOAD_MAX_ATTEMPTS) {
                try {
                    val request = Request.Builder().url(url).get().build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) error("下载视频失败：HTTP ${resp.code} ${resp.message}")
                        val body = resp.body ?: error("下载视频失败：响应体为空")
                        body.byteStream().use { input ->
                            outputPath.parentFile?.mkdirs()
                            outputPath.outputStream().use { input.copyTo(it) }
                        }
                    }
                    return@withContext
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    if (!shouldRetryDownload(e) || attempt == DOWNLOAD_MAX_ATTEMPTS - 1) {
                        throw e
                    }
                    val backoff = DOWNLOAD_BACKOFF_MS.getOrNull(attempt) ?: DOWNLOAD_BACKOFF_MS.last()
                    delay(backoff)
                }
            }
            throw lastError ?: error("下载视频失败：未知原因 $url")
        }
    }

    /** submit 重试谓词：4xx（非 429/408/425）终态不重试；5xx/网络瞬态重试。 */
    fun shouldRetrySubmit(e: Throwable): Boolean = when (e) {
        is AmbiguousSubmitError -> false
        is VideoRateLimitedException -> false
        is IOException -> true
        else -> isRetryableHttpStatus(e)
    }

    /** poll 重试谓词：404 视为瞬态（任务未就绪）可重试；408/425/429/5xx/网络重试。 */
    fun shouldRetryPoll(e: Throwable): Boolean = when (e) {
        is IOException -> true
        else -> httpCodeOf(e)?.let { it == 404 || isRetryableStatus(it) } ?: false
    }

    /** download 重试谓词：404 终态不重试；408/425/429/5xx/网络重试。 */
    fun shouldRetryDownload(e: Throwable): Boolean = when (e) {
        is IOException -> true
        else -> httpCodeOf(e)?.let { it != 404 && isRetryableStatus(it) } ?: false
    }

    /**
     * 默认瞬态重试谓词（[pollWithRetry] 的 retryIf=null 时用，对齐 ArcReel `_should_retry`）。
     * 网络 IOException + 可重试 HTTP 状态 + 常见瞬态关键词。
     */
    private fun defaultShouldRetry(e: Throwable): Boolean = when (e) {
        is IOException -> true
        else -> isRetryableHttpStatus(e) || hasRetryableKeyword(e)
    }

    /** 可重试 HTTP 状态码（移植 ArcReel retry.py:RETRYABLE_STATUS_PATTERNS）：408/425/429/5xx。 */
    private fun isRetryableStatus(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code >= 500

    private fun isRetryableHttpStatus(e: Throwable): Boolean =
        httpCodeOf(e)?.let { isRetryableStatus(it) } ?: false

    private fun hasRetryableKeyword(e: Throwable): Boolean {
        val msg = e.message.orEmpty().lowercase()
        return msg.contains("timeout") || msg.contains("timed out") ||
            msg.contains("service unavailable") || msg.contains("bad gateway") ||
            msg.contains("gateway timeout") || msg.contains("internal server error")
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

/**
 * Resume 路径任务过期（对齐 ArcReel ResumeExpiredError）。
 *
 * resume（断点续传）时收到 404/task_not_found/expired → 抛此异常，worker 区分「过期」与「真错误」。
 * generate（新建）路径不抛此异常。
 */
class ResumeExpiredError(message: String) : IllegalStateException(message)
