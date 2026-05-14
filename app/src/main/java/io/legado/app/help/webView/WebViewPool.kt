package io.legado.app.help.webView

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.rss.read.VisibleWebView
import io.legado.app.utils.setDarkeningAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.random.Random

object WebViewPool {
    const val BLANK_HTML = "about:blank"
    const val DATA_HTML = "data:text/html;charset=utf-8;base64,"
    const val GROUP_DEFAULT = "default"
    const val GROUP_COMMENT_BROWSER = "commentBrowser"

    private val idlePools = mutableMapOf<String, ArrayDeque<PooledWebView>>()
    private val inUsePool = mutableMapOf<String, PooledWebView>()

    private var needInitialize = true
    private val CACHED_WEB_VIEW_MAX_NUM = max(AppConfig.threadCount / 10, 5)
    private const val COMMENT_WEB_VIEW_MAX_NUM = 2
    private const val IDLE_TIME_OUT: Long = 5 * 60 * 1000
    private const val IDLE_TIME_OUT_LAST: Long = 30 * 60 * 1000
    private val cleanupScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    private var cleanupJob: Job? = null

    @Synchronized
    fun acquire(context: Context, group: String = GROUP_DEFAULT): PooledWebView {
        val normalizedGroup = group.ifBlank { GROUP_DEFAULT }
        val idlePool = idlePool(normalizedGroup)
        val pooledWebView = if (idlePool.isNotEmpty()) {
            idlePool.removeLast()
        } else {
            if (needInitialize) {
                needInitialize = false
                startCleanupTimer()
            }
            createNewWebView(normalizedGroup)
        }
        pooledWebView.upContext(context).apply {
            realWebView.settings.setDarkeningAllowed(AppConfig.isNightTheme)
            if (inUsePool.isEmpty()) {
                realWebView.resumeTimers()
            }
            isInUse = true
        }
        inUsePool[pooledWebView.id] = pooledWebView
        pooledWebView.realWebView.setBackgroundColor(Color.TRANSPARENT)
        return pooledWebView
    }

    @Synchronized
    fun release(pooledWebView: PooledWebView) {
        if (inUsePool.remove(pooledWebView.id) == null) {
            pooledWebView.realWebView.destroy()
            return
        }
        pooledWebView.realWebView.run {
            (parent as? ViewGroup)?.removeView(this)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            stopLoading()
            clearFocus()
            setOnLongClickListener(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener(null)
            }
            setDownloadListener(null)
            outlineProvider = null
            clipToOutline = false
            webChromeClient = null
            removeJavascriptInterface(WebJsExtensions.nameBasic)
            removeJavascriptInterface(WebJsExtensions.nameJava)
            removeJavascriptInterface(WebJsExtensions.nameSource)
            removeJavascriptInterface(WebJsExtensions.nameCache)
            clearFormData()
            clearMatches()
            clearDisappearingChildren()
            clearAnimation()
            pooledWebView.upContext(appCtx)
            val idlePool = idlePool(pooledWebView.group)
            if (idlePool.size >= idleLimit(pooledWebView.group)) {
                pooledWebView.realWebView.destroy()
                return
            }
            webViewClient = object : WebViewClient() {
                @SuppressLint("SetJavaScriptEnabled")
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != BLANK_HTML) return
                    view?.let { webView ->
                        webView.settings.apply {
                            javaScriptEnabled = false
                            javaScriptEnabled = true
                            blockNetworkImage = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                            useWideViewPort = false
                            loadWithOverviewMode = false
                            textZoom = 100
                        }
                        if (inUsePool.isEmpty()) {
                            webView.pauseTimers()
                        }
                        webView.onPause()
                    }
                    pooledWebView.isInUse = false
                    pooledWebView.lastUseTime = System.currentTimeMillis()
                    idlePool.addLast(pooledWebView)
                    startCleanupTimer()
                }
            }
            loadUrl(BLANK_HTML)
        }
    }

    private fun createNewWebView(group: String): PooledWebView {
        val webView = VisibleWebView(MutableContextWrapper(appCtx))
        preInitWebView(webView)
        return PooledWebView(webView, generateId(), group)
    }

    private fun idlePool(group: String): ArrayDeque<PooledWebView> {
        return idlePools.getOrPut(group.ifBlank { GROUP_DEFAULT }) { ArrayDeque() }
    }

    private fun idleLimit(group: String): Int {
        return if (group == GROUP_COMMENT_BROWSER) COMMENT_WEB_VIEW_MAX_NUM else CACHED_WEB_VIEW_MAX_NUM
    }

    private fun generateId(): String {
        return "web_${System.currentTimeMillis()}_${Random.nextLong()}"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun preInitWebView(webView: WebView) {
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.settings.apply {
            javaScriptEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun startCleanupTimer() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = cleanupScope.launch {
            while (true) {
                delay(30_000)
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<PooledWebView>()
                var shouldCancel = false
                synchronized(this@WebViewPool) {
                    idlePools.values.forEach { idlePool ->
                        idlePool.forEachIndexed { index, pooled ->
                            val timeout = if (index == 0) IDLE_TIME_OUT_LAST else IDLE_TIME_OUT
                            if (now - pooled.lastUseTime > timeout) {
                                toRemove.add(pooled)
                            }
                        }
                    }
                    toRemove.forEach { pooled ->
                        idlePools[pooled.group]?.remove(pooled)
                        try {
                            pooled.realWebView.destroy()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    idlePools.entries.removeAll { it.value.isEmpty() }
                    if (idlePools.isEmpty()) {
                        shouldCancel = true
                    }
                }
                if (shouldCancel) {
                    needInitialize = true
                    this@launch.cancel()
                }
            }
        }
    }
}
