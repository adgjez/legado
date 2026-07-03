package io.legado.app.ui.book.read

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.config.ShareNoteTemplateManageActivity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

class ShareNotePreviewOverlay private constructor(
    private val activity: ReadBookActivity,
    initialEntry: ShareNoteTemplateManager.Entry,
    private val payload: ShareNoteImageRenderer.Payload
) : FrameLayout(activity) {

    private val cardMargin = 22.dpToPx()
    private val topPadding = 72.dpToPx()
    private val bottomBarHeight = 132.dpToPx()
    private val bottomContentPadding = bottomBarHeight + 28.dpToPx()
    private val scrollView = ScrollView(activity)
    private val cardContainer = FrameLayout(activity)
    private val webView = WebView(activity)
    private val previewImage = ImageView(activity)
    private val statusView = TextView(activity)
    private val bottomBar = LinearLayout(activity)
    private var currentEntry = initialEntry
    private var renderedResult: ShareNoteImageRenderer.RenderResult? = null
    private var renderJob: Job? = null
    private var closed = false

    init {
        isClickable = true
        setBackgroundColor(0x8A000000.toInt())
        clipChildren = false
        clipToPadding = false
        setupScrollLayer()
        setupStatusView()
        setupBottomBar()
        post { render(currentEntry) }
    }

    private fun setupScrollLayer() {
        scrollView.clipToPadding = false
        scrollView.isFillViewport = false
        scrollView.setPadding(0, topPadding, 0, bottomContentPadding)
        scrollView.overScrollMode = View.OVER_SCROLL_NEVER
        addView(
            scrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        scrollView.addView(
            holder,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        holder.addView(
            cardContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = cardMargin
                rightMargin = cardMargin
            }
        )

        ShareNoteImageRenderer.configureWebView(webView)
        webView.isClickable = false
        webView.isLongClickable = false
        webView.setOnTouchListener { _, _ -> true }
        cardContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                1
            )
        )
        previewImage.isVisible = false
        previewImage.scaleType = ImageView.ScaleType.FIT_CENTER
        previewImage.adjustViewBounds = false
        cardContainer.addView(
            previewImage,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun setupStatusView() {
        statusView.text = activity.getString(R.string.share_note_loading_preview)
        statusView.setTextColor(Color.WHITE)
        statusView.textSize = 14f
        statusView.gravity = Gravity.CENTER
        statusView.setPadding(14.dpToPx(), 8.dpToPx(), 14.dpToPx(), 8.dpToPx())
        statusView.background = GradientDrawable().apply {
            cornerRadius = 18.dpToPx().toFloat()
            setColor(0x99000000.toInt())
        }
        addView(
            statusView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
    }

    private fun setupBottomBar() {
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.CENTER
        bottomBar.setPadding(20.dpToPx(), 14.dpToPx(), 20.dpToPx(), 10.dpToPx())
        bottomBar.background = GradientDrawable().apply {
            val radius = 28.dpToPx().toFloat()
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setColor(activity.accentColor)
        }
        val textColor = activity.primaryTextColor
        bottomBar.addView(actionButton(R.drawable.ic_image, R.string.share_note_template, textColor) {
            showTemplateSelector()
        })
        bottomBar.addView(actionButton(R.drawable.ic_download_line, R.string.action_save, textColor) {
            exportAndSave()
        })
        bottomBar.addView(actionButton(R.drawable.ic_share, R.string.share, textColor) {
            exportAndShare()
        })
        bottomBar.addView(actionButton(R.drawable.ic_close_x, R.string.cancel, textColor) {
            dismiss()
        })
        addView(
            bottomBar,
            LayoutParams(LayoutParams.MATCH_PARENT, bottomBarHeight, Gravity.BOTTOM)
        )
    }

    private fun actionButton(
        iconRes: Int,
        labelRes: Int,
        color: Int,
        onClick: () -> Unit
    ): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            addView(ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(color)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(1.dpToPx(), color and 0x99ffffff.toInt())
                }
                setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
            }, LinearLayout.LayoutParams(54.dpToPx(), 54.dpToPx()))
            addView(TextView(activity).apply {
                text = activity.getString(labelRes)
                setTextColor(color)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dpToPx()
            })
        }
    }

    private fun setBusy(message: String?, busy: Boolean) {
        statusView.text = message.orEmpty()
        statusView.isVisible = busy
        bottomBar.isEnabled = !busy
        for (i in 0 until bottomBar.childCount) {
            bottomBar.getChildAt(i).isEnabled = !busy
            bottomBar.getChildAt(i).alpha = if (busy) 0.55f else 1f
        }
    }

    private fun render(entry: ShareNoteTemplateManager.Entry) {
        if (closed) return
        currentEntry = entry
        renderedResult = null
        renderJob?.cancel()
        renderJob = activity.lifecycleScope.launch {
            setBusy(activity.getString(R.string.share_note_loading_preview), true)
            runCatching {
                val availableWidth = (width - cardMargin * 2).coerceAtLeast(240.dpToPx())
                previewImage.isVisible = false
                webView.isVisible = true
                val result = ShareNoteImageRenderer.renderMountedWebView(
                    context = activity,
                    webView = webView,
                    entry = entry,
                    payload = payload,
                    targetWidth = availableWidth
                )
                renderedResult = result
                val displayWidth = result.width.coerceAtMost(availableWidth).coerceAtLeast(1)
                val displayHeight = ceil(result.height * (displayWidth.toFloat() / result.width.toFloat()))
                    .toInt()
                    .coerceAtLeast(1)
                cardContainer.layoutParams = cardContainer.layoutParams.apply {
                    width = displayWidth
                    height = displayHeight
                }
                webView.layoutParams = webView.layoutParams.apply {
                    width = displayWidth
                    height = displayHeight
                }
                previewImage.layoutParams = previewImage.layoutParams.apply {
                    width = displayWidth
                    height = displayHeight
                }
                ImageLoader.load(activity, result.file)
                    .fitCenter()
                    .into(previewImage)
                webView.isVisible = false
                previewImage.isVisible = true
                cardContainer.requestLayout()
                scrollView.scrollTo(0, 0)
            }.onFailure {
                if (!closed && it !is CancellationException) {
                    previewImage.isVisible = false
                    webView.isVisible = false
                    AppLog.put("Share note preview render failed\n${it.localizedMessage}", it, true)
                    activity.toastOnUi(
                        activity.getString(R.string.share_note_render_failed, it.localizedMessage ?: "unknown")
                    )
                }
            }
            if (!closed) setBusy(null, false)
        }
    }

    private fun showTemplateSelector() {
        activity.lifecycleScope.launch {
            val entries = withContext(IO) { ShareNoteTemplateManager.loadEntries() }
            if (closed || entries.isEmpty()) {
                activity.toastOnUi(R.string.share_note_no_template)
                return@launch
            }
            val labels = entries.map { it.meta.name }.toMutableList()
            labels.add(activity.getString(R.string.share_note_manage_templates))
            AlertDialog.Builder(activity)
                .setTitle(R.string.share_note_template)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < entries.size) {
                        ShareNoteTemplateManager.rememberLast(entries[which])
                        render(entries[which])
                    } else {
                        activity.startActivity(Intent(activity, ShareNoteTemplateManageActivity::class.java))
                    }
                }
                .show()
        }
    }

    private fun exportAndSave() {
        renderJob?.cancel()
        renderJob = activity.lifecycleScope.launch {
            setBusy(activity.getString(R.string.share_note_exporting), true)
            runCatching {
                val file = renderedResult?.file
                    ?: throw IllegalStateException(activity.getString(R.string.share_note_render_failed, "empty"))
                ShareNoteImageRenderer.savePngToGallery(activity, file)
            }.onSuccess {
                if (!closed) activity.toastOnUi(R.string.share_note_saved)
            }.onFailure {
                if (!closed && it !is CancellationException) {
                    AppLog.put("Share note save failed\n${it.localizedMessage}", it, true)
                    activity.toastOnUi(
                        activity.getString(R.string.share_note_save_failed, it.localizedMessage ?: "unknown")
                    )
                }
            }
            if (!closed) setBusy(null, false)
        }
    }

    private fun exportAndShare() {
        renderJob?.cancel()
        renderJob = activity.lifecycleScope.launch {
            setBusy(activity.getString(R.string.share_note_exporting), true)
            runCatching {
                renderedResult?.file
                    ?: throw IllegalStateException(activity.getString(R.string.share_note_render_failed, "empty"))
            }.onSuccess { file ->
                if (!closed) {
                    runCatching {
                        activity.share(file, "image/png")
                    }.onFailure {
                        activity.toastOnUi(it.localizedMessage ?: activity.getString(R.string.can_not_share))
                    }
                }
            }.onFailure {
                if (!closed && it !is CancellationException) {
                    AppLog.put("Share note image export failed\n${it.localizedMessage}", it, true)
                    activity.toastOnUi(
                        activity.getString(R.string.share_note_render_failed, it.localizedMessage ?: "unknown")
                    )
                }
            }
            if (!closed) setBusy(null, false)
        }
    }

    fun dismiss() {
        if (closed) return
        closed = true
        renderJob?.cancel()
        runCatching {
            webView.stopLoading()
            cardContainer.removeView(webView)
            webView.destroy()
        }
        (parent as? ViewGroup)?.removeView(this)
    }

    companion object {
        fun show(
            activity: ReadBookActivity,
            parent: ViewGroup,
            entry: ShareNoteTemplateManager.Entry,
            payload: ShareNoteImageRenderer.Payload
        ): ShareNotePreviewOverlay {
            val overlay = ShareNotePreviewOverlay(activity, entry, payload)
            parent.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            return overlay
        }
    }
}
