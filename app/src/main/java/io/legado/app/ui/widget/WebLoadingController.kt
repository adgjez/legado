package io.legado.app.ui.widget

import android.view.View
import io.legado.app.ui.widget.anima.RotateLoading

class WebLoadingController(
    private val container: View,
    private val loading: RotateLoading
) {
    private var showing = false

    fun show() {
        container.animate().cancel()
        showing = true
        container.alpha = 1f
        if (container.visibility != View.VISIBLE) {
            container.visibility = View.VISIBLE
        }
        loading.visible()
    }

    fun hide() {
        if (!showing && container.visibility != View.VISIBLE) {
            loading.gone()
            return
        }
        showing = false
        container.animate().cancel()
        container.animate()
            .alpha(0f)
            .setDuration(HIDE_DURATION)
            .withEndAction {
                if (!showing) {
                    loading.gone()
                    container.visibility = View.GONE
                    container.alpha = 1f
                }
            }
            .start()
    }

    fun cancel() {
        showing = false
        container.animate().cancel()
        loading.gone()
        container.visibility = View.GONE
        container.alpha = 1f
    }

    companion object {
        private const val HIDE_DURATION = 120L
    }
}