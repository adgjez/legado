package io.legado.app.help.webView

import android.content.Context
import android.content.MutableContextWrapper
import io.legado.app.ui.rss.read.VisibleWebView

class PooledWebView(
    val realWebView: VisibleWebView,
    val id: String,
    val group: String = WebViewPool.GROUP_DEFAULT
) {
    var isInUse: Boolean = false
    var lastUseTime: Long = 0

    fun upContext(context: Context): PooledWebView {
        (realWebView.context as MutableContextWrapper).let {
            if (it.baseContext != context) {
                it.baseContext = context
            }
        }
        return this
    }
}