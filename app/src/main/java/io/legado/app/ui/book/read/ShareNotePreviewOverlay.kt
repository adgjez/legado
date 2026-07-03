package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Color
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.book.read.config.ReaderOption
import io.legado.app.ui.book.read.config.ReaderSectionCard
import io.legado.app.ui.book.read.config.ReaderSegmentedOptions
import io.legado.app.ui.book.read.config.ReaderTextAction
import io.legado.app.ui.book.read.config.rememberReaderMenuDialogStyle
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
    private val bottomBarHeight = 270.dpToPx()
    private val bottomContentPadding = bottomBarHeight + 44.dpToPx()
    private val scrollView = ScrollView(activity)
    private val cardContainer = FrameLayout(activity)
    private val webView = WebView(activity)
    private val previewImage = ImageView(activity)
    private val statusView = TextView(activity)
    private val bottomBar = ComposeView(activity)
    private var currentEntry = initialEntry
    private var selectedDirName by mutableStateOf(initialEntry.dirName)
    private var selectedTemplateName by mutableStateOf(initialEntry.meta.name)
    private var templateEntries by mutableStateOf(listOf(initialEntry))
    private var shareStyle by mutableStateOf(ShareNoteTemplateManager.currentStyle())
    private var busyState by mutableStateOf(false)
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
        loadTemplateEntries()
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
        bottomBar.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        bottomBar.setContent {
            ShareNoteQuickPanel()
        }
        addView(
            bottomBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = 12.dpToPx()
                rightMargin = 12.dpToPx()
                bottomMargin = 12.dpToPx()
            }
        )
    }

    private fun setBusy(message: String?, busy: Boolean) {
        statusView.text = message.orEmpty()
        statusView.isVisible = busy
        busyState = busy
        bottomBar.isEnabled = !busy
        bottomBar.alpha = if (busy) 0.72f else 1f
    }

    private fun loadTemplateEntries() {
        activity.lifecycleScope.launch {
            val entries = withContext(IO) { ShareNoteTemplateManager.loadEntries() }
            if (!closed && entries.isNotEmpty()) {
                templateEntries = entries
            }
        }
    }

    @Composable
    private fun ShareNoteQuickPanel() {
        val style = rememberReaderMenuDialogStyle(activity.bottomBackground)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(style.panelRadius),
            color = style.surface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "摘录分享",
                            color = style.primaryText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = selectedTemplateName,
                            color = style.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    ReaderTextAction(
                        text = "管理",
                        style = style,
                        modifier = Modifier.width(72.dp),
                        enabled = !busyState,
                        onClick = ::openTemplateManage
                    )
                }
                ReaderSectionCard(
                    style = style,
                    title = "模板",
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)
                ) {
                    ReaderSegmentedOptions(
                        options = templateEntries.map { ReaderOption(it.dirName, it.meta.name) },
                        selectedValue = selectedDirName,
                        style = style,
                        scrollable = true,
                        pillStyle = true,
                        onSelected = { dirName ->
                            if (!busyState) templateEntries.firstOrNull { it.dirName == dirName }?.let(::selectTemplate)
                        }
                    )
                }
                ReaderSectionCard(
                    style = style,
                    title = "配色",
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)
                ) {
                    ReaderSegmentedOptions(
                        options = ShareNoteTemplateManager.stylePalettes.map { ReaderOption(it.id, it.name) },
                        selectedValue = shareStyle.paletteId,
                        style = style,
                        scrollable = true,
                        pillStyle = true,
                        onSelected = { paletteId ->
                            if (!busyState) updateShareStyle(shareStyle.copy(paletteId = paletteId))
                        }
                    )
                }
                ReaderSectionCard(
                    style = style,
                    title = "字体",
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)
                ) {
                    ReaderSegmentedOptions(
                        options = ShareNoteTemplateManager.fontFamilies.map {
                            ReaderOption(it, ShareNoteTemplateManager.fontLabel(it))
                        },
                        selectedValue = shareStyle.fontFamily,
                        style = style,
                        scrollable = true,
                        pillStyle = true,
                        onSelected = { font ->
                            if (!busyState) updateShareStyle(shareStyle.copy(fontFamily = font))
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReaderTextAction(
                        text = activity.getString(R.string.action_save),
                        style = style,
                        enabled = !busyState,
                        onClick = ::exportAndSave,
                        modifier = Modifier.weight(1f)
                    )
                    ReaderTextAction(
                        text = activity.getString(R.string.share),
                        style = style,
                        enabled = !busyState,
                        onClick = ::exportAndShare,
                        modifier = Modifier.weight(1f)
                    )
                    ReaderTextAction(
                        text = activity.getString(R.string.cancel),
                        style = style,
                        enabled = !busyState,
                        onClick = ::dismiss,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    private fun selectTemplate(entry: ShareNoteTemplateManager.Entry) {
        if (entry.dirName == selectedDirName && renderedResult != null) return
        ShareNoteTemplateManager.rememberLast(entry)
        render(entry)
    }

    private fun updateShareStyle(next: ShareNoteTemplateManager.ShareStyle) {
        if (next == shareStyle) return
        ShareNoteTemplateManager.saveStyle(next)
        shareStyle = ShareNoteTemplateManager.currentStyle()
        render(currentEntry)
    }

    private fun openTemplateManage() {
        activity.startActivity(Intent(activity, ShareNoteTemplateManageActivity::class.java))
    }

    private fun render(entry: ShareNoteTemplateManager.Entry) {
        if (closed) return
        currentEntry = entry
        selectedDirName = entry.dirName
        selectedTemplateName = entry.meta.name
        renderedResult = null
        renderJob?.cancel()
        renderJob = activity.lifecycleScope.launch {
            setBusy(activity.getString(R.string.share_note_loading_preview), true)
            runCatching {
                val renderStyle = shareStyle
                val availableWidth = (width - cardMargin * 2).coerceAtLeast(240.dpToPx())
                previewImage.isVisible = false
                webView.isVisible = true
                val result = ShareNoteImageRenderer.renderMountedWebView(
                    context = activity,
                    webView = webView,
                    entry = entry,
                    payload = payload,
                    targetWidth = availableWidth,
                    style = renderStyle
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
