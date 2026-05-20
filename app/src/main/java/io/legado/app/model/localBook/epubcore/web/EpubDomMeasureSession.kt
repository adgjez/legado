package io.legado.app.model.localBook.epubcore.web

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.URLConnection
import kotlin.coroutines.resume

class EpubDomMeasureSession(
    private val archive: EpubArchive
) : Closeable {

    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var closed = false

    suspend fun measure(request: EpubDomMeasureRequest): EpubDomMeasureResult? {
        if (closed) return null
        if (request.html.isBlank() || request.viewportWidthPx <= 0 || request.viewportHeightPx <= 0) {
            return null
        }
        return mutex.withLock {
            withTimeoutOrNull(request.timeoutMillis) {
                measureLocked(request)
            }
        }
    }

    private suspend fun measureLocked(request: EpubDomMeasureRequest): EpubDomMeasureResult? {
        return suspendCancellableCoroutine { continuation ->
            var completed = false
            fun finish(result: EpubDomMeasureResult?) {
                if (completed) return
                completed = true
                handler.removeCallbacksAndMessages(null)
                runOnUI {
                    webView?.stopLoading()
                    webView?.loadUrl(WebViewBlank)
                }
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            continuation.invokeOnCancellation {
                runOnUI {
                    webView?.stopLoading()
                    webView?.loadUrl(WebViewBlank)
                }
            }

            runOnUI {
                if (closed || !continuation.isActive) {
                    finish(null)
                    return@runOnUI
                }
                runCatching {
                    val view = ensureWebView()
                    val baseUrl = buildBaseUrl(request.chapterHref)
                    view.webViewClient = MeasureClient(
                        request = request,
                        baseUrl = baseUrl,
                        onResult = ::finish
                    )
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(request.viewportWidthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(request.viewportHeightPx, View.MeasureSpec.EXACTLY)
                    )
                    view.layout(0, 0, request.viewportWidthPx, request.viewportHeightPx)
                    view.loadDataWithBaseURL(baseUrl, request.html, "text/html", "UTF-8", null)
                }.onFailure {
                    AppLog.putDebug("EPUB DOM measure start failed: ${it.localizedMessage}", it)
                    finish(null)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return WebView(appCtx).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                blockNetworkImage = false
                allowFileAccess = false
                allowContentAccess = false
                textZoom = 100
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            }
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }.also { webView = it }
    }

    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        runOnUI {
            webView?.run {
                stopLoading()
                loadUrl(WebViewBlank)
                destroy()
            }
            webView = null
        }
    }

    private inner class MeasureClient(
        private val request: EpubDomMeasureRequest,
        private val baseUrl: String,
        private val onResult: (EpubDomMeasureResult?) -> Unit
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return emptyResponse()
            return archiveResponse(url)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
            return archiveResponse(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url != baseUrl) return
            handler.postDelayed({
                view.evaluateJavascript(MeasureJs) { raw ->
                    runCatching { parseResult(request, raw) }
                        .onSuccess(onResult)
                        .onFailure {
                            AppLog.putDebug("EPUB DOM measure parse failed: ${it.localizedMessage}", it)
                            onResult(null)
                        }
                }
            }, 80L)
        }
    }

    private fun archiveResponse(url: String?): WebResourceResponse? {
        val path = archivePath(url) ?: return emptyResponse()
        return runCatching {
            if (!archive.exists(path)) return emptyResponse()
            val bytes = archive.readBytes(path)
            WebResourceResponse(
                mimeType(path),
                if (path.endsWith(".css", true) || path.endsWith(".html", true) || path.endsWith(".xhtml", true)) "UTF-8" else null,
                ByteArrayInputStream(bytes)
            )
        }.getOrElse {
            emptyResponse()
        }
    }

    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun archivePath(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!uri.host.equals(Host, ignoreCase = true)) return null
        val path = uri.path?.trimStart('/') ?: return null
        if (path.isBlank()) return null
        return EpubPath.normalize(Uri.decode(path))
    }

    private fun buildBaseUrl(chapterHref: String): String {
        return "https://$Host/${Uri.encode(EpubPath.stripFragment(chapterHref), "/")}"
    }

    private fun parseResult(
        request: EpubDomMeasureRequest,
        raw: String?
    ): EpubDomMeasureResult? {
        val json = decodeJavascriptString(raw) ?: return null
        val root = JSONObject(json)
        val array = root.optJSONArray("nodes") ?: JSONArray()
        val nodes = LinkedHashMap<String, EpubMeasuredNode>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val path = item.optString("path").takeIf { it.isNotBlank() } ?: continue
            nodes[path] = EpubMeasuredNode(
                nodePath = path,
                tagName = item.optString("tag").ifBlank { "" },
                display = item.optString("display").takeIf { it.isNotBlank() },
                position = item.optString("position").takeIf { it.isNotBlank() },
                leftPx = item.optFloat("left"),
                topPx = item.optFloat("top"),
                widthPx = item.optFloat("width"),
                heightPx = item.optFloat("height"),
                fontSizePx = item.optNullableFloat("fontSize"),
                lineHeightPx = item.optNullableFloat("lineHeight"),
                marginLeftPx = item.optFloat("marginLeft"),
                marginTopPx = item.optFloat("marginTop"),
                marginRightPx = item.optFloat("marginRight"),
                marginBottomPx = item.optFloat("marginBottom"),
                paddingLeftPx = item.optFloat("paddingLeft"),
                paddingTopPx = item.optFloat("paddingTop"),
                paddingRightPx = item.optFloat("paddingRight"),
                paddingBottomPx = item.optFloat("paddingBottom"),
                naturalWidthPx = item.optNullableFloat("naturalWidth"),
                naturalHeightPx = item.optNullableFloat("naturalHeight")
            )
        }
        if (nodes.isEmpty()) return null
        return EpubDomMeasureResult(
            chapterHref = request.chapterHref,
            viewportWidthPx = request.viewportWidthPx,
            viewportHeightPx = request.viewportHeightPx,
            nodes = nodes
        )
    }

    private fun decodeJavascriptString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { JSONArray("[$raw]").getString(0) }
            .getOrElse { raw.trim('"') }
            .takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optNullableFloat(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        if (value.isNaN() || value.isInfinite()) return null
        return value.toFloat()
    }

    private fun JSONObject.optFloat(key: String): Float {
        val value = optDouble(key, 0.0)
        return if (value.isNaN() || value.isInfinite()) 0f else value.toFloat()
    }

    private fun mimeType(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "xhtml", "xml" -> "application/xhtml+xml"
            "svg" -> "image/svg+xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
        }
    }

    companion object {
        private const val Host = "epub.local"
        private const val WebViewBlank = "about:blank"
        private const val MaxNodes = 6_000
        private val MeasureJs = """
            (function() {
              function cleanText(value) {
                return String(value || '').replace(/[\t\r\n ]+/g, ' ').trim();
              }
              function number(value) {
                var parsed = parseFloat(value);
                return isFinite(parsed) ? parsed : null;
              }
              function mark(element, path) {
                if (!element || element.nodeType !== 1) return;
                element.setAttribute('data-epub-node-path', path);
                var elementIndex = 0;
                var textIndex = 0;
                var children = element.childNodes || [];
                for (var i = 0; i < children.length; i++) {
                  var child = children[i];
                  if (child.nodeType === 3) {
                    if (cleanText(child.textContent).length > 0) textIndex++;
                  } else if (child.nodeType === 1) {
                    var tag = String(child.tagName || '').toLowerCase();
                    mark(child, path + '/' + tag + '/' + (elementIndex++));
                  }
                }
              }
              function collectNode(element) {
                var style = window.getComputedStyle(element);
                var rect = element.getBoundingClientRect();
                var item = {
                  path: element.getAttribute('data-epub-node-path') || '',
                  tag: String(element.tagName || '').toLowerCase(),
                  display: style.display || '',
                  position: style.position || '',
                  left: rect.left,
                  top: rect.top,
                  width: rect.width,
                  height: rect.height,
                  fontSize: number(style.fontSize),
                  lineHeight: number(style.lineHeight),
                  marginLeft: number(style.marginLeft) || 0,
                  marginTop: number(style.marginTop) || 0,
                  marginRight: number(style.marginRight) || 0,
                  marginBottom: number(style.marginBottom) || 0,
                  paddingLeft: number(style.paddingLeft) || 0,
                  paddingTop: number(style.paddingTop) || 0,
                  paddingRight: number(style.paddingRight) || 0,
                  paddingBottom: number(style.paddingBottom) || 0
                };
                if (element.tagName && element.tagName.toLowerCase() === 'img') {
                  item.naturalWidth = element.naturalWidth || null;
                  item.naturalHeight = element.naturalHeight || null;
                }
                return item;
              }
              var root = document.body || document.documentElement;
              mark(root, 'body');
              var elements = document.querySelectorAll('[data-epub-node-path]');
              var nodes = [];
              var limit = Math.min(elements.length, ${MaxNodes});
              for (var i = 0; i < limit; i++) {
                nodes.push(collectNode(elements[i]));
              }
              return JSON.stringify({
                viewportWidth: window.innerWidth,
                viewportHeight: window.innerHeight,
                nodes: nodes
              });
            })();
        """.trimIndent()
    }
}
