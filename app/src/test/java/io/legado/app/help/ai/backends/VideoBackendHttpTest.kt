package io.legado.app.help.ai.backends

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [VideoBackendHttp] 单测（P0 头条：429 Retry-After / AmbiguousSubmitError / shouldRetry 谓词）。
 *
 * 纯 JVM + [runTest] 虚拟时间：submit/poll 不内置 Dispatcher 切换，delay 在测试调度器下即时推进。
 * 响应用 okhttp3 真实 [Response] 构造（无需 mockwebserver）。
 */
class VideoBackendHttpTest {

    private fun resp(code: Int, retryAfter: String? = null, body: String = "{}"): Response {
        val respBody = body.toResponseBody("application/json".toMediaType())
        val builder = Response.Builder()
            .code(code)
            .message("test")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("http://test.local/").build())
            .body(respBody)
        if (retryAfter != null) builder.header("Retry-After", retryAfter)
        return builder.build()
    }

    // ========== submitPost ==========

    @Test
    fun submitPostReturnsResponseOnSuccess() = runTest {
        var calls = 0
        val r = VideoBackendHttp.submitPost({ calls++; resp(200) }, "agnes")
        assertEquals(200, r.code)
        assertEquals(1, calls)
        r.close()
    }

    @Test
    fun submitPostRetriesOn429ThenSucceeds() = runTest {
        val codes = mutableListOf(429, 429, 200)
        var i = 0
        val r = VideoBackendHttp.submitPost({ resp(codes[i++]) }, "agnes")
        assertEquals(200, r.code)
        assertEquals(3, i)
        r.close()
    }

    @Test
    fun submitPostExhausts429ThrowsVideoRateLimitedException() = runTest {
        // MAX_429_RETRIES=5：1 次初始 + 5 次重试 = 6 次 429 后抛错
        val expected = VideoBackendHttp.MAX_429_RETRIES + 1
        var calls = 0
        val ex = try {
            VideoBackendHttp.submitPost({ calls++; resp(429, retryAfter = "0") }, "agnes")
            null
        } catch (e: VideoRateLimitedException) {
            e
        }
        assertNotNull("耗尽 429 应抛 VideoRateLimitedException", ex)
        assertEquals(expected, calls)
    }

    @Test
    fun submitPostThrowsAmbiguousSubmitErrorOnIOException() = runTest {
        val ex = try {
            VideoBackendHttp.submitPost({ throw IOException("broken pipe") }, "agnes")
            null
        } catch (e: AmbiguousSubmitError) {
            e
        }
        assertNotNull("IOException 应转为 AmbiguousSubmitError", ex)
        assertTrue(ex!!.message!!.contains("agnes"))
        assertTrue(ex.message!!.contains("传输歧义"))
    }

    @Test
    fun submitPostReturnsNon429ErrorAsIs() = runTest {
        // 5xx 等非 429 错误原样返回，由调用方按 shouldRetrySubmit 决定
        val r = VideoBackendHttp.submitPost({ resp(502) }, "agnes")
        assertEquals(502, r.code)
        r.close()
    }

    // ========== shouldRetry 谓词 ==========

    @Test
    fun shouldRetrySubmitPredicates() {
        assertFalse("传输歧义终态不重试", VideoBackendHttp.shouldRetrySubmit(AmbiguousSubmitError("x")))
        assertFalse("429 限流由 submitPost 内部处理不外层重试", VideoBackendHttp.shouldRetrySubmit(VideoRateLimitedException(1L, "x")))
        assertTrue("网络瞬态重试", VideoBackendHttp.shouldRetrySubmit(IOException("net")))
        assertTrue("5xx 重试", VideoBackendHttp.shouldRetrySubmit(IllegalStateException("HTTP 502 Bad Gateway")))
        assertFalse("4xx 非 429 终态不重试", VideoBackendHttp.shouldRetrySubmit(IllegalStateException("HTTP 404 Not Found")))
        assertFalse("400 不重试", VideoBackendHttp.shouldRetrySubmit(IllegalStateException("HTTP 400 bad request")))
    }

    @Test
    fun shouldRetryPollPredicates() {
        assertTrue(VideoBackendHttp.shouldRetryPoll(IOException("net")))
        assertTrue("404 视为任务未就绪可重试", VideoBackendHttp.shouldRetryPoll(IllegalStateException("HTTP 404")))
        assertTrue(VideoBackendHttp.shouldRetryPoll(IllegalStateException("HTTP 503")))
        assertFalse(VideoBackendHttp.shouldRetryPoll(IllegalStateException("HTTP 400")))
    }

    @Test
    fun shouldRetryDownloadPredicates() {
        assertTrue(VideoBackendHttp.shouldRetryDownload(IOException("net")))
        assertFalse("404 终态不重试", VideoBackendHttp.shouldRetryDownload(IllegalStateException("HTTP 404")))
        assertTrue(VideoBackendHttp.shouldRetryDownload(IllegalStateException("HTTP 503")))
        assertFalse(VideoBackendHttp.shouldRetryDownload(IllegalStateException("HTTP 400")))
    }

    // ========== pollWithRetry ==========

    @Test
    fun pollWithRetryReturnsWhenDone() = runTest {
        val seq = mutableListOf("pending", "pending", "done")
        var i = 0
        val r = VideoBackendHttp.pollWithRetry(
            pollFn = { seq[i++] },
            isDone = { it == "done" },
            isFailed = { it == "failed" },
            pollInterval = 50.milliseconds,
            maxWait = 5.seconds
        )
        assertEquals("done", r)
        assertEquals(3, i)
    }

    @Test
    fun pollWithRetryThrowsWhenFailed() = runTest {
        val ex = try {
            VideoBackendHttp.pollWithRetry(
                pollFn = { "failed" },
                isDone = { false },
                isFailed = { true },
                pollInterval = 50.milliseconds,
                maxWait = 5.seconds
            )
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull("isFailed 应抛错", ex)
    }

    @Test
    fun pollWithRetryTimesOut() = runTest {
        val ex = try {
            VideoBackendHttp.pollWithRetry(
                pollFn = { "pending" },
                isDone = { false },
                isFailed = { false },
                pollInterval = 100.milliseconds,
                maxWait = 500.milliseconds
            )
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull("超时应抛错", ex)
        assertTrue(ex!!.message!!.contains("轮询超时"))
    }

    @Test
    fun pollWithRetryRetriesOnTransientError() = runTest {
        // retryIf 判定瞬态重试：前 2 次抛 IOException，第 3 次返回 done
        var i = 0
        val r = VideoBackendHttp.pollWithRetry(
            pollFn = {
                i++
                if (i < 3) throw IOException("transient")
                "done"
            },
            isDone = { it == "done" },
            isFailed = { false },
            pollInterval = 10.milliseconds,
            maxWait = 10.seconds,
            retryIf = { it is IOException }
        )
        assertEquals("done", r)
        assertEquals(3, i)
    }
}
