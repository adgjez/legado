package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object ShareNoteImageRenderer {

    private val renderMutex = Mutex()

    data class Payload(
        val type: String = "note",
        val generatedAt: String,
        val profile: Profile = Profile(),
        val appearance: Appearance = Appearance(),
        val book: Book,
        val note: Note
    )

    data class Profile(
        val name: String = "Reeden",
        val bio: String = "让阅读留下形状",
        val avatar: String? = null
    )

    data class Appearance(
        val hideComment: Boolean = false
    )

    data class Book(
        val title: String,
        val author: String = "",
        val description: String = "",
        val cover: String? = null,
        val type: String = "",
        val rating: Float? = null,
        val sizeText: String = "",
        val wordCountText: String = "",
        val readStatusText: String = "",
        val readProgressText: String = "",
        val lastReadTime: String = ""
    )

    data class Note(
        val createAt: String,
        val sectionName: String,
        val description: String,
        val comment: String = ""
    )

    suspend fun renderShareImage(
        context: Context,
        entry: ShareNoteTemplateManager.Entry,
        payload: Payload
    ): File = renderMutex.withLock {
        val bitmap = renderBitmap(
            context = context,
            entry = entry,
            payloadJson = GSON.toJson(payload),
            previewOnly = false
        )
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share_note").apply { mkdirs() }
            val file = File(dir, "share_note_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 96, it)
            }
            bitmap.recycle()
            file
        }
    }

    suspend fun renderPreview(
        context: Context,
        entry: ShareNoteTemplateManager.Entry,
        force: Boolean = false
    ): File? = renderMutex.withLock {
        val file = ShareNoteTemplateManager.previewFile(entry)
        if (!force && file.exists() && file.length() > 0L) return@withLock file
        runCatching {
            val bitmap = renderBitmap(
                context = context,
                entry = entry,
                payloadJson = null,
                previewOnly = true
            )
            withContext(Dispatchers.IO) {
                FileOutputStream(file).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 92, it)
                }
                bitmap.recycle()
                file
            }
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderBitmap(
        context: Context,
        entry: ShareNoteTemplateManager.Entry,
        payloadJson: String?,
        previewOnly: Boolean
    ): Bitmap = withContext(Dispatchers.Main) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val html = prepareHtml(ShareNoteTemplateManager.readTemplateHtml(entry), payloadJson)
        val meta = entry.meta
        val width = if (meta.canvas == ShareNoteTemplateManager.CANVAS_WIDE) {
            720
        } else {
            meta.width
        }.coerceIn(240, 1440)
        val initialHeight = if (meta.canvas == ShareNoteTemplateManager.CANVAS_WIDE) {
            meta.height
        } else {
            900
        }.coerceIn(240, 2400)
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }
        try {
            waitPageLoaded(webView, html, ShareNoteTemplateManager.baseUrl(entry))
            val size = evaluateCaptureSize(webView, width, initialHeight, previewOnly)
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(size.first, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size.second, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, size.first, size.second)
            @Suppress("DEPRECATION")
            webView.isDrawingCacheEnabled = false
            val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            if (meta.radius > 0) {
                roundBitmap(bitmap, meta.radius.toFloat())
            } else {
                bitmap
            }
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private fun prepareHtml(html: String, payloadJson: String?): String {
        if (payloadJson.isNullOrBlank()) return html
        val escaped = payloadJson.replace("</script", "<\\/script")
        val directCall = "window.ReedenShareTemplate.render(window.ReedenShareTemplateMock);"
        val payloadCall = "window.ReedenShareTemplate.render($escaped);"
        if (html.contains(directCall)) {
            return html.replace(directCall, payloadCall)
        }
        val script = """
            <script>
            window.ReedenShareTemplatePayload = $escaped;
            if (window.ReedenShareTemplate && typeof window.ReedenShareTemplate.render === "function") {
              window.ReedenShareTemplate.render(window.ReedenShareTemplatePayload);
            }
            </script>
        """.trimIndent()
        return if (html.contains("</body>", ignoreCase = true)) {
            html.replace(Regex("</body>", RegexOption.IGNORE_CASE), "$script</body>")
        } else {
            html + script
        }
    }

    private suspend fun waitPageLoaded(webView: WebView, html: String, baseUrl: String) {
        suspendCoroutine<Unit> { continuation ->
            var resumed = false
            fun resumeOnce(error: Throwable? = null) {
                if (resumed) return
                resumed = true
                webView.postDelayed({
                    if (error != null) continuation.resumeWithException(error)
                    else continuation.resume(Unit)
                }, 220)
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    resumeOnce()
                }
            }
            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            webView.postDelayed({ resumeOnce() }, 1600)
        }
    }

    private suspend fun evaluateCaptureSize(
        webView: WebView,
        fallbackWidth: Int,
        fallbackHeight: Int,
        previewOnly: Boolean
    ): Pair<Int, Int> {
        return suspendCoroutine { continuation ->
            var resumed = false
            fun resumeOnce(size: Pair<Int, Int>) {
                if (resumed) return
                resumed = true
                continuation.resume(size)
            }
            val script = """
                (function(){
                  var node = document.querySelector('[data-reeden-capture]') || document.body;
                  var rect = node.getBoundingClientRect();
                  return JSON.stringify({
                    width: Math.ceil(Math.max(rect.width, document.body.scrollWidth, $fallbackWidth)),
                    height: Math.ceil(Math.max(rect.height, document.body.scrollHeight, $fallbackHeight))
                  });
                })();
            """.trimIndent()
            webView.evaluateJavascript(script) { raw ->
                val text = raw?.trim('"')?.replace("\\\"", "\"").orEmpty()
                val width = Regex(""""width"\s*:\s*(\d+)""").find(text)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallbackWidth
                val height = Regex(""""height"\s*:\s*(\d+)""").find(text)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallbackHeight
                val nextHeight = if (previewOnly) {
                    minOf(height, 260)
                } else {
                    minOf(height, 12000)
                }.coerceAtLeast(180)
                resumeOnce(width.coerceIn(240, 1440) to nextHeight)
            }
            webView.postDelayed({
                resumeOnce(fallbackWidth.coerceIn(240, 1440) to fallbackHeight.coerceIn(180, 2400))
            }, 1200)
        }
    }

    private fun roundBitmap(source: Bitmap, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(path)
        canvas.drawBitmap(source, 0f, 0f, paint)
        source.recycle()
        return output
    }
}
