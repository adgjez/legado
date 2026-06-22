package io.legado.app.help.ai

import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.buffer
import okio.sink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * AI 视频 Provider 通用 HTTP 工具：
 *  - JSON 请求（POST/GET）
 *  - 文件下载（带进度）
 *  - 简单 5xx/网络错误重试
 */
object AiVideoApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * POST JSON 到指定 URL，返回响应文本。
     * 5xx 或 IO 异常会重试 [maxRetries] 次（指数退避）。
     */
    suspend fun postJson(
        url: String,
        payload: JSONObject,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 120_000L,
        maxRetries: Int = 2
    ): String = requestText("POST", url, payload.toString().toRequestBody(JSON), headers, timeoutMs, maxRetries)

    /**
     * POST 原始 body（字符串）到指定 URL，返回响应文本。
     */
    suspend fun postRaw(
        url: String,
        body: String,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 120_000L,
        maxRetries: Int = 2
    ): String = requestText("POST", url, body.toRequestBody(contentType.toMediaType()), headers, timeoutMs, maxRetries)

    /**
     * GET 请求指定 URL，返回响应文本。
     */
    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 120_000L,
        maxRetries: Int = 2
    ): String = requestText("GET", url, null, headers, timeoutMs, maxRetries)

    /**
     * 从 URL 下载文件到 [target]，并把进度回调到 [onProgress]（已下载字节数）。
     * 返回写入的总字节数。
     */
    suspend fun downloadToFile(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 600_000L,
        onProgress: ((Long) -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        val client: OkHttpClient = okHttpClient.newBuilder()
            .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} ${response.message}")
            }
            val body = response.body ?: error("Empty body")
            target.parentFile?.mkdirs()
            val sink = target.sink().buffer()
            val source = body.source()
            var total = 0L
            val buffer = Buffer()
            try {
                while (!source.exhausted()) {
                    val read = source.read(buffer, 8 * 1024L)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    sink.flush()
                    total += read
                    onProgress?.invoke(total)
                }
            } finally {
                sink.close()
            }
            total
        }
    }

    private suspend fun requestText(
        method: String,
        url: String,
        body: okhttp3.RequestBody?,
        headers: Map<String, String>,
        timeoutMs: Long,
        maxRetries: Int
    ): String = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                val client: OkHttpClient = okHttpClient.newBuilder()
                    .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .apply {
                        headers.forEach { (k, v) -> header(k, v) }
                        when (method.uppercase()) {
                            "GET" -> get()
                            "POST" -> if (body != null) post(body) else post("".toRequestBody(JSON))
                            "PUT" -> if (body != null) put(body) else put("".toRequestBody(JSON))
                            "DELETE" -> delete(body)
                            else -> error("Unsupported method $method")
                        }
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return@withContext text
                    }
                    // 4xx 不重试
                    if (response.code in 400..499) {
                        error("HTTP ${response.code} ${response.message}: $text")
                    }
                    // 5xx 重试
                    throw IOException("HTTP ${response.code} ${response.message}: $text")
                }
            } catch (e: Throwable) {
                lastError = e
                if (attempt >= maxRetries) throw e
                val backoff = (500L * (1L shl attempt)).coerceAtMost(5_000L)
                kotlinx.coroutines.delay(backoff)
            }
        }
        throw lastError ?: IOException("Request failed")
    }
}

/**
 * 把响应文本解析成 JSON 对象（容错：空文本抛错）。
 */
fun String.toJsonObjectOrNull(): JSONObject? = runCatching {
    if (isBlank()) null else JSONObject(this)
}.getOrNull()

/**
 * 把响应文本解析成 JSON 数组。
 */
fun String.toJsonArrayOrNull(): JSONArray? = runCatching {
    if (isBlank()) null else JSONArray(this)
}.getOrNull()

internal fun Throwable.debugPrint(prefix: String) {
    io.legado.app.utils.LogUtils.d("AiVideo", prefix + ": " + javaClass.simpleName + " - " + (message ?: ""))
}
