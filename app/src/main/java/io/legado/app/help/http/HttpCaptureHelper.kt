package io.legado.app.help.http

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.utils.get
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

object HttpCaptureHelper {

    data class Config(
        val url: String,
        val source: BaseSource? = null,
        val waitMs: Long = 5_000L,
        val timeoutMs: Long = 30_000L,
        val includeRegex: String? = null,
        val excludeRegex: String? = null,
        val maxRequests: Int = 50,
        val maxBodyChars: Int = 20_000,
        val replayResponse: Boolean = true,
        val js: String? = null,
        val coroutineContext: CoroutineContext = EmptyCoroutineContext
    )

    private data class CapturedRequest(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val isForMainFrame: Boolean,
        val hasGesture: Boolean,
        val time: Long
    )

    suspend fun capture(config: Config): JSONObject {
        require(config.url.startsWith("http://") || config.url.startsWith("https://")) {
            "Only http/https url is supported"
        }
        val include = compileRegex(config.includeRegex)
        val exclude = compileRegex(config.excludeRegex)
        val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var pooledWebView: PooledWebView? = null
        fun releaseWebView() {
            handler.removeCallbacksAndMessages(null)
            pooledWebView?.let { WebViewPool.release(it) }
            pooledWebView = null
        }
        try {
            return withTimeout(config.timeoutMs.coerceIn(10_000L, 90_000L)) {
                val loadResult = suspendCancellableCoroutine<JSONObject> { continuation ->
                    continuation.invokeOnCancellation {
                        runOnUI {
                            if (finished.compareAndSet(false, true)) {
                                releaseWebView()
                            }
                        }
                    }
                    runOnUI {
                        try {
                            pooledWebView = WebViewPool.acquire(appCtx)
                            val webView = pooledWebView?.realWebView ?: run {
                                if (!continuation.isCompleted) {
                                    continuation.resume(errorJson("WebView acquire failed"))
                                }
                                return@runOnUI
                            }
                            initWebView(webView, config)
                            webView.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): android.webkit.WebResourceResponse? {
                                    recordRequest(request, include, exclude, config.maxRequests, requests)
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    if (finished.get()) return
                                    if (!config.js.isNullOrBlank()) {
                                        view.evaluateJavascript(config.js, null)
                                    }
                                    handler.removeCallbacksAndMessages(null)
                                    handler.postDelayed({
                                        if (finished.compareAndSet(false, true) && !continuation.isCompleted) {
                                            val result = JSONObject().apply {
                                                put("ok", true)
                                                put("url", config.url)
                                                put("finalUrl", view.url.orEmpty())
                                                put("requestCount", requests.size)
                                                put("captureMode", "request_log_replay")
                                            }
                                            continuation.resume(result)
                                        }
                                        releaseWebView()
                                    }, config.waitMs.coerceIn(500L, 30_000L))
                                }
                            }
                            webView.loadUrl(config.url, config.source?.getHeaderMap(true).orEmpty())
                        } catch (error: Throwable) {
                            if (finished.compareAndSet(false, true)) {
                                releaseWebView()
                            }
                            if (!continuation.isCompleted) {
                                continuation.resume(errorJson(error.localizedMessage ?: error.javaClass.simpleName))
                            }
                        }
                    }
                }
                val array = JSONArray()
                val snapshot = requests.toList()
                snapshot.forEach { item ->
                    array.put(buildRequestJson(item, config))
                }
                loadResult.put("requests", array)
                loadResult
            }
        } finally {
            runOnUI {
                if (finished.compareAndSet(false, true)) {
                    releaseWebView()
                }
            }
        }
    }

    private fun recordRequest(
        request: WebResourceRequest,
        include: Regex?,
        exclude: Regex?,
        maxRequests: Int,
        requests: MutableList<CapturedRequest>
    ) {
        val url = request.url.toString()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (include != null && !include.containsMatchIn(url)) return
        if (exclude != null && exclude.containsMatchIn(url)) return
        synchronized(requests) {
            if (requests.any { it.url == url && it.method == request.method }) return
            if (requests.size >= maxRequests.coerceIn(1, 200)) return
            requests += CapturedRequest(
                url = url,
                method = request.method.orEmpty().ifBlank { "GET" },
                headers = request.requestHeaders.orEmpty(),
                isForMainFrame = request.isForMainFrame,
                hasGesture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    request.hasGesture()
                } else {
                    false
                },
                time = System.currentTimeMillis()
            )
        }
    }

    private suspend fun buildRequestJson(item: CapturedRequest, config: Config): JSONObject {
        val json = JSONObject().apply {
            put("url", item.url)
            put("method", item.method)
            put("headers", JSONObject(item.headers))
            put("isForMainFrame", item.isForMainFrame)
            put("hasGesture", item.hasGesture)
            put("time", item.time)
        }
        if (!config.replayResponse || item.method.uppercase() != "GET") {
            json.put("replayed", false)
            if (item.method.uppercase() != "GET") {
                json.put("replaySkipReason", "only GET requests are replayed")
            }
            return json
        }
        runCatching {
            val response = okHttpClient.newCallStrResponse {
                url(item.url)
                addHeaders(item.headers)
            }
            val body = response.body.orEmpty()
            json.put("replayed", true)
            json.put("statusCode", response.code())
            json.put("message", response.message())
            json.put("finalUrl", response.url)
            json.put("bodyLength", body.length)
            json.put("truncated", body.length > config.maxBodyChars)
            json.put("body", body.take(config.maxBodyChars.coerceIn(0, 80_000)))
        }.onFailure {
            json.put("error", it.localizedMessage ?: it.javaClass.simpleName)
        }
        return json
    }

    private fun compileRegex(pattern: String?): Regex? {
        return pattern?.takeIf { it.isNotBlank() }?.let {
            runCatching { it.toRegex() }.getOrElse { error ->
                throw IllegalArgumentException("Invalid regex: ${error.localizedMessage ?: it}")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(webView: WebView, config: Config) {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.onResume()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            blockNetworkImage = true
            loadsImagesAutomatically = false
            userAgentString = config.source?.getHeaderMap(true)?.get(AppConst.UA_NAME, true)
                ?: AppConfig.userAgent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
    }

    private fun errorJson(message: String) = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }
}
