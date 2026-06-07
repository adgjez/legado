package io.legado.app.ui.widget.compose

import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.windowSize
import splitties.systemservices.windowManager

abstract class ComposeDialogFragment : DialogFragment() {

    protected open val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    protected open val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    protected open val widthFraction: Float? = null
    protected open val maxWidthDp: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val attr = window.attributes
            attr.windowAnimations = R.style.AnimDialogCenter
            window.attributes = attr
            window.setBackgroundDrawableResource(R.color.transparent)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }
        val fraction = widthFraction
        val maxDp = maxWidthDp
        if (fraction != null || maxDp != null) {
            val dm = requireContext().windowManager.windowSize
            val target = (dm.widthPixels * (fraction ?: 1f)).toInt()
            val maxWidth = maxDp?.dpToPx() ?: target
            dialog?.window?.setLayout(minOf(target, maxWidth), dialogHeight)
        } else {
            setLayout(dialogWidth, dialogHeight)
        }
    }

    override fun show(manager: androidx.fragment.app.FragmentManager, tag: String?) {
        kotlin.runCatching {
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }
    }
}
