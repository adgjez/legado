package io.legado.app.model.localBook.epubcore.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.RenderProcessGoneDetail
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
import org.json.JSONTokener
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.URLConnection
import kotlin.coroutines.resume

enum class EpubWebSelectionAction {
    SelectWord,
    MoveStart,
    MoveEnd,
    Clear
}

data class EpubWebSelectionRect(
    val rect: RectF
)

data class EpubWebSelectionPayload(
    val chapterIndex: Int,
    val chapterHref: String,
    val pageIndex: Int,
    val selectedText: String,
    val rects: List<EpubWebSelectionRect>
)

class EpubWebSelectionLayerSession(
    private val archive: EpubArchive
) : Closeable {

    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var closed = false
    private var token = 0L
    private var loadedKey: String? = null

    suspend fun select(
        request: EpubWebLayoutRequest,
        pageIndex: Int,
        action: EpubWebSelectionAction,
        x: Float,
        y: Float
    ): EpubWebSelectionPayload? {
        if (closed || request.html.isBlank()) return null
        if (request.viewportWidthPx <= 0 || request.viewportHeightPx <= 0) return null
        return mutex.withLock {
            withTimeoutOrNull(request.timeoutMillis) {
                selectLocked(request, pageIndex, action, x, y)
            }
        }
    }

    suspend fun clear(request: EpubWebLayoutRequest?) {
        mutex.withLock {
            val current = webView ?: return@withLock
            runOnUI {
                runCatching {
                    current.evaluateJavascript("window.getSelection && window.getSelection().removeAllRanges();", null)
                }
            }
        }
    }

    private suspend fun selectLocked(
        request: EpubWebLayoutRequest,
        pageIndex: Int,
        action: EpubWebSelectionAction,
        x: Float,
        y: Float
    ): EpubWebSelectionPayload? {
        return suspendCancellableCoroutine { continuation ->
            val requestKey = request.selectionKey()
            val currentToken = ++token
            var completed = false
            fun finish(payload: EpubWebSelectionPayload?) {
                if (completed) return
                completed = true
                handler.removeCallbacksAndMessages(currentToken)
                if (continuation.isActive) continuation.resume(payload)
            }
            continuation.invokeOnCancellation {
                if (token == currentToken) token++
                handler.removeCallbacksAndMessages(currentToken)
            }
            runOnUI {
                if (closed || !continuation.isActive) {
                    finish(null)
                    return@runOnUI
                }
                val view = ensureWebView()
                val baseUrl = buildBaseUrl(request.chapterHref)
                fun evaluateSelection() {
                    if (token != currentToken) return
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(request.viewportWidthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(request.viewportHeightPx, View.MeasureSpec.EXACTLY)
                    )
                    view.layout(0, 0, request.viewportWidthPx, request.viewportHeightPx)
                    view.evaluateJavascript(buildSelectionJs(request, pageIndex, action, x, y)) { raw ->
                        if (token != currentToken) return@evaluateJavascript
                        val payload = runCatching {
                            parsePayload(request, pageIndex, raw)
                        }.onFailure {
                            AppLog.putDebug("EPUB selection layer parse failed: ${it.localizedMessage}", it)
                        }.getOrNull()
                        finish(payload)
                    }
                }
                if (loadedKey == requestKey) {
                    evaluateSelection()
                } else {
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, requestWeb: WebResourceRequest?): WebResourceResponse? {
                            return archiveResponse(requestWeb?.url?.toString(), request)
                        }

                        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                        override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                            return archiveResponse(url, request)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (url != baseUrl || token != currentToken) return
                            loadedKey = requestKey
                            handler.postAtTime(::evaluateSelection, currentToken, android.os.SystemClock.uptimeMillis() + 80L)
                        }

                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            if (token == currentToken) finish(null)
                            webView = null
                            loadedKey = null
                            return true
                        }
                    }
                    view.loadDataWithBaseURL(baseUrl, wrapHtml(request), "text/html", "UTF-8", null)
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
            setBackgroundColor(Color.TRANSPARENT)
        }.also { webView = it }
    }

    private fun buildSelectionJs(
        request: EpubWebLayoutRequest,
        pageIndex: Int,
        action: EpubWebSelectionAction,
        x: Float,
        y: Float
    ): String {
        val actionName = action.name
        val safeX = x.coerceIn(0f, request.viewportWidthPx.toFloat())
        val safeY = y.coerceIn(0f, request.viewportHeightPx.toFloat())
        return """
            (function() {
              var PAGE_INDEX = ${pageIndex.coerceAtLeast(0)};
              var PAGE_W = ${request.viewportWidthPx.coerceAtLeast(1)};
              var X = $safeX;
              var Y = $safeY;
              var ACTION = '$actionName';
              window.scrollTo(PAGE_INDEX * PAGE_W, 0);
              function caretRange(x, y) {
                var range = null;
                if (document.caretRangeFromPoint) {
                  range = document.caretRangeFromPoint(x, y);
                } else if (document.caretPositionFromPoint) {
                  var pos = document.caretPositionFromPoint(x, y);
                  if (pos) {
                    range = document.createRange();
                    range.setStart(pos.offsetNode, pos.offset);
                    range.collapse(true);
                  }
                }
                if (!range) return null;
                if (!range.startContainer || range.startContainer.nodeType !== Node.TEXT_NODE) return null;
                return range;
              }
              function textOf(node) {
                return String(node && node.nodeValue || '');
              }
              function isCjk(ch) {
                return /[\u3400-\u9fff\uf900-\ufaff]/.test(ch || '');
              }
              function isWord(ch) {
                return /[A-Za-z0-9_\u00C0-\u024F]/.test(ch || '');
              }
              function expandRange(range) {
                var node = range.startContainer;
                var text = textOf(node);
                var offset = Math.max(0, Math.min(range.startOffset, text.length));
                if (!text) return range;
                if (offset >= text.length) offset = Math.max(0, text.length - 1);
                var ch = text.charAt(offset);
                if (!ch.trim() && offset > 0) {
                  offset--;
                  ch = text.charAt(offset);
                }
                var start = offset;
                var end = Math.min(text.length, offset + 1);
                if (isWord(ch)) {
                  while (start > 0 && isWord(text.charAt(start - 1))) start--;
                  while (end < text.length && isWord(text.charAt(end))) end++;
                } else if (isCjk(ch)) {
                  end = Math.min(text.length, start + 1);
                }
                var next = document.createRange();
                next.setStart(node, start);
                next.setEnd(node, Math.max(end, start + 1));
                return next;
              }
              function point(container, offset) {
                return { container: container, offset: offset };
              }
              function comparePoints(a, b) {
                if (a.container === b.container) return a.offset - b.offset;
                var r = document.createRange();
                r.setStart(a.container, a.offset);
                r.collapse(true);
                var other = document.createRange();
                other.setStart(b.container, b.offset);
                other.collapse(true);
                var result = r.compareBoundaryPoints(Range.START_TO_START, other);
                r.detach();
                other.detach();
                return result;
              }
              function orderedRange(a, b) {
                var r = document.createRange();
                if (comparePoints(a, b) <= 0) {
                  r.setStart(a.container, a.offset);
                  r.setEnd(b.container, b.offset);
                } else {
                  r.setStart(b.container, b.offset);
                  r.setEnd(a.container, a.offset);
                }
                return r;
              }
              var selection = window.getSelection();
              if (ACTION === 'Clear') {
                if (selection) selection.removeAllRanges();
                return JSON.stringify({ text: '', rects: [] });
              }
              var target = caretRange(X, Y);
              if (!target) return JSON.stringify({ text: '', rects: [] });
              var nextRange = null;
              if (ACTION === 'SelectWord' || !selection || selection.rangeCount === 0) {
                nextRange = expandRange(target);
              } else {
                var current = selection.getRangeAt(0);
                var targetPoint = point(target.startContainer, target.startOffset);
                if (ACTION === 'MoveStart') {
                  nextRange = orderedRange(targetPoint, point(current.endContainer, current.endOffset));
                } else {
                  nextRange = orderedRange(point(current.startContainer, current.startOffset), targetPoint);
                }
              }
              selection.removeAllRanges();
              selection.addRange(nextRange);
              var rects = Array.prototype.slice.call(nextRange.getClientRects ? nextRange.getClientRects() : [])
                .map(function(rect) {
                  return {
                    left: Math.max(0, Math.min(PAGE_W, rect.left)),
                    top: Math.max(0, rect.top),
                    right: Math.max(0, Math.min(PAGE_W, rect.right)),
                    bottom: Math.max(0, rect.bottom)
                  };
                })
                .filter(function(rect) {
                  return rect.right > rect.left && rect.bottom > rect.top;
                });
              return JSON.stringify({
                text: selection.toString(),
                rects: rects
              });
            })();
        """.trimIndent()
    }

    private fun parsePayload(
        request: EpubWebLayoutRequest,
        pageIndex: Int,
        raw: String?
    ): EpubWebSelectionPayload? {
        val json = decodeJavascriptString(raw) ?: return null
        val obj = JSONObject(json)
        val text = obj.optString("text").takeIf { it.isNotBlank() } ?: return null
        val rectsJson = obj.optJSONArray("rects") ?: JSONArray()
        val rects = ArrayList<EpubWebSelectionRect>(rectsJson.length())
        for (index in 0 until rectsJson.length()) {
            val item = rectsJson.optJSONObject(index) ?: continue
            val rect = RectF(
                item.optDouble("left").toFloat(),
                item.optDouble("top").toFloat(),
                item.optDouble("right").toFloat(),
                item.optDouble("bottom").toFloat()
            )
            if (rect.width() > 0f && rect.height() > 0f) {
                rects += EpubWebSelectionRect(rect)
            }
        }
        if (rects.isEmpty()) return null
        return EpubWebSelectionPayload(
            chapterIndex = request.chapterIndex,
            chapterHref = request.chapterHref,
            pageIndex = pageIndex,
            selectedText = text,
            rects = rects
        )
    }

    private fun decodeJavascriptString(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        return runCatching {
            JSONTokener(value).nextValue() as? String
        }.getOrNull() ?: value.trim('"')
    }

    private fun wrapHtml(request: EpubWebLayoutRequest): String {
        val css = buildReaderCss(request)
        val html = request.html
        val headClose = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
        val htmlTag = Regex("<html[\\s>]", RegexOption.IGNORE_CASE)
        return if (headClose.containsMatchIn(html)) {
            html.replace(headClose, "<style id=\"legado-reader-css\">$css</style></head>")
        } else if (htmlTag.containsMatchIn(html)) {
            html.replaceFirst(
                Regex("<html([^>]*)>", RegexOption.IGNORE_CASE),
                "<html$1><head><style id=\"legado-reader-css\">$css</style></head>"
            )
        } else {
            "<html><head><style id=\"legado-reader-css\">$css</style></head><body>$html</body></html>"
        }
    }

    private fun buildReaderCss(request: EpubWebLayoutRequest): String {
        val color = "#%06X".format(0xFFFFFF and request.textColor)
        val readerFontFace = if (!request.readerFontFamily.isNullOrBlank() && !request.readerFontUrl.isNullOrBlank()) {
            """
            @font-face {
              font-family: ${request.readerFontFamily.cssString()};
              src: url('${request.readerFontUrl.cssUrl()}');
            }
            """.trimIndent()
        } else {
            ""
        }
        return """
            $readerFontFace
            html {
              margin: 0 !important;
              padding: 0 !important;
              width: ${request.viewportWidthPx}px !important;
              height: ${request.viewportHeightPx}px !important;
              overflow: hidden !important;
              background: transparent !important;
              font-size: ${request.fontSizePx}px;
              line-height: ${request.lineHeightPx}px;
            }
            :root {
              font-size: ${request.fontSizePx}px;
              line-height: ${request.lineHeightPx}px;
            }
            body {
              margin: 0 !important;
              padding: 0 !important;
              width: ${request.viewportWidthPx}px !important;
              height: ${request.viewportHeightPx}px !important;
              overflow: visible !important;
              color: $color;
              font-size: ${request.fontSizePx}px;
              line-height: ${request.lineHeightPx}px;
              letter-spacing: ${request.letterSpacingEm}em;
              -webkit-column-width: ${request.viewportWidthPx}px;
              column-width: ${request.viewportWidthPx}px;
              -webkit-column-gap: 0;
              column-gap: 0;
              -webkit-column-fill: auto;
              column-fill: auto;
              -webkit-user-select: text;
              user-select: text;
            }
            body, body * {
              box-sizing: border-box;
              -webkit-user-select: text;
              user-select: text;
            }
            img, svg, video, canvas {
              max-width: 100%;
              height: auto;
            }
            body > article,
            body > section,
            body > main,
            body > div.book-wrapper,
            body > article.book-wrapper {
              max-width: none !important;
              width: 100% !important;
              margin-left: 0 !important;
              margin-right: 0 !important;
              padding-left: 0 !important;
              padding-right: 0 !important;
              background-color: transparent !important;
              border-left-width: 0 !important;
              border-right-width: 0 !important;
              box-shadow: none !important;
            }
        """.trimIndent()
    }

    private fun archiveResponse(url: String?, request: EpubWebLayoutRequest): WebResourceResponse? {
        val path = archivePath(url) ?: return emptyResponse()
        if (path == ReaderFontPath) return readerFontResponse(request)
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

    private fun readerFontResponse(request: EpubWebLayoutRequest): WebResourceResponse {
        val fontPath = request.readerFontPath?.takeIf { it.isNotBlank() } ?: return emptyResponse()
        return runCatching {
            val inputStream = if (fontPath.startsWith("content://", ignoreCase = true)) {
                appCtx.contentResolver.openInputStream(Uri.parse(fontPath))
            } else {
                java.io.File(fontPath.removePrefix("file://")).inputStream()
            } ?: return emptyResponse()
            WebResourceResponse(mimeType(fontPath), null, inputStream)
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

    private fun String.cssString(): String {
        return "'${replace("\\", "\\\\").replace("'", "\\'")}'"
    }

    private fun String.cssUrl(): String {
        return replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun EpubWebLayoutRequest.selectionKey(): String {
        return buildString {
            append(chapterIndex).append('|').append(chapterHref)
            append('|').append(startFragmentId.orEmpty()).append('|').append(endFragmentId.orEmpty())
            append('|').append(viewportWidthPx).append('x').append(viewportHeightPx)
            append('|').append(fontSizePx).append('|').append(lineHeightPx).append('|').append(letterSpacingEm)
            append('|').append(readerPaddingLeftPx).append(',').append(readerPaddingTopPx)
            append(',').append(readerPaddingRightPx).append(',').append(readerPaddingBottomPx)
            append('|').append(readerFontFamily.orEmpty()).append('|').append(readerFontUrl.orEmpty())
            append('|').append(readerFontPath.orEmpty())
        }
    }

    override fun close() {
        closed = true
        token++
        handler.removeCallbacksAndMessages(null)
        runOnUI {
            webView?.run {
                stopLoading()
                loadUrl(WebViewBlank)
                destroy()
            }
            webView = null
            loadedKey = null
        }
    }

    private companion object {
        private const val Host = "epub.local"
        private const val ReaderFontPath = "__legado_reader_font__"
        private const val WebViewBlank = "about:blank"
    }
}
