package io.legado.app.ui.widget.dialog

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebViewPool
import splitties.init.appCtx

class CommentWebViewSession {

    companion object {
        val shared: CommentWebViewSession by lazy { CommentWebViewSession() }
        private const val RELEASE_AFTER_READING_DELAY = 60_000L
        private const val DEFAULT_PRE_ATTACH_DELAY = 64L
        private const val MIN_PRE_ATTACH_DELAY = 32L
        private const val MAX_PRE_ATTACH_DELAY = 160L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pooledWebView: PooledWebView? = null
    private var destroyed = false
    private var delayedReleaseRunnable: Runnable? = null
    private var tuningScore = 0
    private var preAttachDelay = DEFAULT_PRE_ATTACH_DELAY

    val isPrepared: Boolean
        get() = pooledWebView?.isDestroyed == false

    fun prepare(context: Context) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            prepareOnMain(context.applicationContext)
        } else {
            mainHandler.post { prepareOnMain(context.applicationContext) }
        }
    }

    fun acquire(context: Context): PooledWebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CommentWebViewSession.acquire must run on main thread"
        }
        cancelDelayedReleaseOnMain()
        destroyed = false
        val pooled = pooledWebView?.takeIf { !it.isDestroyed } ?: WebViewPool.acquire(context).also {
            pooledWebView = it
        }
        pooled.upContext(context)
        pooled.realWebView.apply {
            animate().cancel()
            visibility = View.INVISIBLE
            alpha = 0f
            translationY = 0f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        return pooled
    }

    fun detachForReuse(pooled: PooledWebView) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detachForReuseOnMain(pooled)
        } else {
            mainHandler.post { detachForReuseOnMain(pooled) }
        }
    }

    fun releaseAfterDelay(
        delayMillis: Long = RELEASE_AFTER_READING_DELAY,
        releaseToPool: Boolean = false
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseAfterDelayOnMain(delayMillis, releaseToPool)
        } else {
            mainHandler.post { releaseAfterDelayOnMain(delayMillis, releaseToPool) }
        }
    }

    fun destroy(releaseToPool: Boolean = true) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyOnMain(releaseToPool)
        } else {
            mainHandler.post { destroyOnMain(releaseToPool) }
        }
    }

    fun trimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyOnMain(releaseToPool = false)
        } else {
            mainHandler.post { destroyOnMain(releaseToPool = false) }
        }
    }

    fun preAttachDelayMillis(): Long = preAttachDelay

    fun recordAttachDuration(durationMillis: Long) {
        when {
            durationMillis >= 48L -> tune(2)
            durationMillis <= 12L -> tune(-1)
        }
    }

    fun recordFirstFrameDuration(durationMillis: Long) {
        when {
            durationMillis >= 800L -> tune(1)
            durationMillis <= 220L -> tune(-1)
        }
    }

    private fun prepareOnMain(context: Context) {
        cancelDelayedReleaseOnMain()
        destroyed = false
        if (pooledWebView?.isDestroyed == false) return
        pooledWebView = WebViewPool.acquire(context).also { pooled ->
            pooled.realWebView.apply {
                visibility = View.INVISIBLE
                alpha = 0f
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                onPause()
            }
        }
    }

    private fun detachForReuseOnMain(pooled: PooledWebView) {
        if (pooledWebView !== pooled) {
            WebViewPool.releaseForFastReuse(pooled)
            return
        }
        pooled.realWebView.apply {
            (parent as? ViewGroup)?.removeView(this)
            animate().cancel()
            clearAnimation()
            stopLoading()
            clearFocus()
            setOnLongClickListener(null)
            setOnTouchListener(null)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                setOnScrollChangeListener(null)
            }
            setDownloadListener(null)
            outlineProvider = null
            clipToOutline = false
            webChromeClient = null
            webViewClient = WebViewClient()
            removeJavascriptInterface(WebJsExtensions.nameBasic)
            removeJavascriptInterface(WebJsExtensions.nameJava)
            removeJavascriptInterface(WebJsExtensions.nameSource)
            removeJavascriptInterface(WebJsExtensions.nameCache)
            settings.apply {
                blockNetworkImage = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = false
                loadWithOverviewMode = false
                textZoom = 100
            }
            alpha = 0f
            translationY = 0f
            visibility = View.INVISIBLE
            onPause()
        }
        pooled.upContext(appCtx)
    }

    private fun releaseAfterDelayOnMain(delayMillis: Long, releaseToPool: Boolean) {
        if (pooledWebView == null) return
        cancelDelayedReleaseOnMain()
        val runnable = Runnable {
            delayedReleaseRunnable = null
            destroyOnMain(releaseToPool)
        }
        delayedReleaseRunnable = runnable
        mainHandler.postDelayed(runnable, delayMillis)
    }

    private fun cancelDelayedReleaseOnMain() {
        delayedReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        delayedReleaseRunnable = null
    }

    private fun destroyOnMain(releaseToPool: Boolean) {
        cancelDelayedReleaseOnMain()
        destroyed = true
        val pooled = pooledWebView ?: return
        pooledWebView = null
        if (releaseToPool) {
            WebViewPool.release(pooled)
        } else {
            WebViewPool.discard(pooled)
        }
    }

    private fun tune(delta: Int) {
        tuningScore = (tuningScore + delta).coerceIn(-4, 8)
        preAttachDelay = when {
            tuningScore >= 6 -> MAX_PRE_ATTACH_DELAY
            tuningScore >= 3 -> 128L
            tuningScore <= -3 -> MIN_PRE_ATTACH_DELAY
            tuningScore <= 0 -> DEFAULT_PRE_ATTACH_DELAY
            else -> 96L
        }
    }
}