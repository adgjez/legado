package io.legado.app.ui.book.read

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.lib.theme.composeActionShape
import io.legado.app.lib.theme.composePanelShape
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.ui.book.read.page.entities.TextParagraph
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.showDialogFragment
import kotlin.math.roundToInt

class ReadAloudPlayerPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun finish()
        fun onReadAloudPlayerVisibilityChanged(visible: Boolean)
    }

    enum class DisplayMode {
        Immersive,
        Text
    }

    data class ParagraphUi(
        val index: Int,
        val text: String,
        val current: Boolean,
        val key: String = index.toString(),
        val sequence: Int = index
    )

    data class FocusTextUi(
        val key: String = "",
        val sequence: Int = 0,
        val text: String = ""
    )

    data class ChapterPreviewUi(
        val index: Int,
        val title: String,
        val indexText: String,
        val current: Boolean,
        val volume: Boolean,
        val key: String
    )

    data class PlayerUiState(
        val bookName: String = "",
        val author: String = "",
        val coverUrl: String? = null,
        val sourceOrigin: String? = null,
        val chapterTitle: String = "",
        val chapterIndexText: String = "",
        val playing: Boolean = false,
        val serviceRunning: Boolean = false,
        val timerMinute: Int = 0,
        val progress: Float = 0f,
        val progressText: String = "0%",
        val paragraphText: String = "",
        val paragraphIndex: Int = 0,
        val paragraphCount: Int = 0,
        val chapterIndex: Int = 0,
        val chapterCount: Int = 0,
        val chapterPreview: List<ChapterPreviewUi> = emptyList(),
        val nearbyParagraphs: List<ParagraphUi> = emptyList(),
        val chapterKey: String = "",
        val paragraphKey: String = "",
        val paragraphSequence: Int = 0,
        val focusText: FocusTextUi = FocusTextUi(),
        val speechRate: Int = AppConfig.ttsSpeechRate.coerceIn(0, 45),
        val followSystemSpeechRate: Boolean = AppConfig.ttsFlowSys,
        val mode: DisplayMode = DisplayMode.Immersive,
        val foregroundActive: Boolean = true
    )

    private val composeView = ComposeView(context)
    private var callBack: CallBack? = null
    private var dismissedForCurrentRun = false
    private var foregroundActive = true
    private var lastChapterStart = 0

    private var uiState by mutableStateOf(PlayerUiState())

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        composeView.setContent {
            ReadAloudPlayerContent(
                state = uiState,
                onClose = ::closeByUser,
                onPlayPause = { callBack?.onClickReadAloud() },
                onModeChange = ::setMode,
                onOpenChapterList = {
                    callBack?.openChapterList()
                },
                onPreviousChapter = { ReadBook.moveToPrevChapter(upContent = true, toLast = false) },
                onNextChapter = { ReadBook.moveToNextChapter(true) },
                onChapterSelect = { ReadBook.openChapter(it, upContent = true) },
                onOpenSettings = ::openReadAloudSetting,
                onTimerChange = ::setTimer,
                onSpeechRateChange = ::setSpeechRate,
                onFollowSystemSpeechRateChange = ::setFollowSystemSpeechRate
            )
        }
    }

    fun attach(lifecycleOwner: LifecycleOwner, callBack: CallBack) {
        this.callBack = callBack
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun open(force: Boolean = true) {
        if (force) {
            dismissedForCurrentRun = false
        }
        showPanel()
    }

    fun onAloudState(status: Int) {
        when (status) {
            io.legado.app.constant.Status.PLAY -> {
                refresh()
                if (!dismissedForCurrentRun) {
                    showPanel()
                }
            }

            io.legado.app.constant.Status.PAUSE -> refresh()
            io.legado.app.constant.Status.STOP -> {
                dismissedForCurrentRun = false
                hidePanel()
            }
        }
    }

    fun onTtsProgress(chapterStart: Int) {
        lastChapterStart = chapterStart.coerceAtLeast(0)
        refresh()
    }

    fun onTimerChanged(minute: Int) {
        uiState = uiState.copy(timerMinute = minute)
    }

    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
        uiState = uiState.copy(foregroundActive = active && visibility == VISIBLE)
    }

    fun close() {
        closeByUser()
    }

    fun refresh() {
        uiState = buildState(uiState.mode)
    }

    private fun showPanel() {
        post {
            val wasVisible = visibility == VISIBLE
            visibility = VISIBLE
            bringToFront()
            ViewCompat.requestApplyInsets(this)
            ViewCompat.requestApplyInsets(composeView)
            uiState = buildState(uiState.mode).copy(foregroundActive = foregroundActive)
            if (!wasVisible) {
                callBack?.onReadAloudPlayerVisibilityChanged(true)
            }
        }
    }

    private fun hidePanel() {
        val wasVisible = visibility == VISIBLE
        visibility = GONE
        uiState = buildState(uiState.mode).copy(foregroundActive = false)
        if (wasVisible) {
            callBack?.onReadAloudPlayerVisibilityChanged(false)
        }
    }

    private fun closeByUser() {
        dismissedForCurrentRun = BaseReadAloudService.isRun
        hidePanel()
    }

    private fun closeFromAction() {
        dismissedForCurrentRun = BaseReadAloudService.isRun
        hidePanel()
    }

    private fun stopReadAloud() {
        ReadAloud.stop(context)
        dismissedForCurrentRun = false
        hidePanel()
    }

    private fun openReadAloudSetting() {
        (context as? AppCompatActivity)?.showDialogFragment(ReadAloudConfigDialog())
    }

    private fun setTimer(minute: Int) {
        AppConfig.ttsTimer = minute
        ReadAloud.setTimer(context, minute)
        uiState = uiState.copy(timerMinute = minute)
    }

    private fun setSpeechRate(value: Int) {
        val rate = value.coerceIn(0, 45)
        AppConfig.ttsSpeechRate = rate
        ReadAloud.upTtsSpeechRate(context)
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(context)
            ReadAloud.resume(context)
        }
        uiState = buildState(uiState.mode)
    }

    private fun setFollowSystemSpeechRate(value: Boolean) {
        AppConfig.ttsFlowSys = value
        ReadAloud.upTtsSpeechRate(context)
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(context)
            ReadAloud.resume(context)
        }
        uiState = buildState(uiState.mode)
    }

    private fun setMode(mode: DisplayMode) {
        uiState = buildState(mode)
    }

    private fun buildState(mode: DisplayMode): PlayerUiState {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter
        val bookName = book?.name?.ifBlank { context.getString(R.string.book_name) }.orEmpty()
        val author = book?.author.orEmpty()
        val chapterTitle = chapter?.chapter?.title?.ifBlank { "当前章节" }.orEmpty()
        val chapterIndexText = chapter?.chapter?.let {
            "${it.index + 1}/${chapter.chaptersSize.coerceAtLeast(it.index + 1)}"
        }.orEmpty()
        val chapterSequence = chapter?.chapter?.index ?: ReadBook.durChapterIndex
        val chapterKey = "${book?.bookUrl.orEmpty()}:$chapterSequence"
        val paragraphs = chapter?.getParagraphs(false).orEmpty()
        val totalLength = chapter?.lastPage
            ?.let { it.chapterPosition + it.charSize }
            ?.coerceAtLeast(1)
            ?: 1
        val chapterStart = when {
            lastChapterStart > 0 -> lastChapterStart
            else -> ReadBook.durChapterPos
        }.coerceIn(0, totalLength)
        val paragraphIndex = findParagraphIndex(paragraphs, chapterStart)
        val paragraph = paragraphs.getOrNull(paragraphIndex)
        val paragraphText = paragraph?.text?.cleanReadAloudText().orEmpty()
        val paragraphSequence = chapterSequence * 100_000 + paragraphIndex.coerceAtLeast(0)
        val paragraphKey = "$chapterKey:${paragraph?.chapterPosition ?: paragraphIndex}"
        val sentence = paragraph?.text
            ?.focusSentenceAt(chapterStart - paragraph.chapterPosition)
            ?: (0 to paragraphText)
        val focusText = FocusTextUi(
            key = "$paragraphKey:${sentence.first}:${sentence.second.hashCode()}",
            sequence = paragraphSequence * 1_000 + sentence.first.coerceAtLeast(0),
            text = sentence.second.ifBlank { paragraphText.ifBlank { "暂无当前段落" } }
        )
        val nearby = paragraphs.nearbyParagraphs(paragraphIndex, chapterKey, chapterSequence)
        val progress = (chapterStart.toFloat() / totalLength.toFloat()).coerceIn(0f, 1f)
        val timerMinute = BaseReadAloudService.timeMinute
        return PlayerUiState(
            bookName = bookName,
            author = author,
            coverUrl = book?.getDisplayCover(),
            sourceOrigin = book?.origin,
            chapterTitle = chapterTitle,
            chapterIndexText = chapterIndexText,
            playing = BaseReadAloudService.isPlay(),
            serviceRunning = BaseReadAloudService.isRun,
            timerMinute = timerMinute,
            progress = progress,
            progressText = "${(progress * 100).roundToInt()}%",
            paragraphText = paragraphText,
            paragraphIndex = if (paragraphIndex >= 0) paragraphIndex + 1 else 0,
            paragraphCount = paragraphs.size,
            chapterIndex = chapterSequence.coerceAtLeast(0),
            chapterCount = ReadBook.chapterSize.coerceAtLeast(chapterSequence + 1),
            chapterPreview = buildChapterPreview(book?.bookUrl, chapterSequence, ReadBook.chapterSize),
            nearbyParagraphs = nearby,
            chapterKey = chapterKey,
            paragraphKey = paragraphKey,
            paragraphSequence = paragraphSequence,
            focusText = focusText,
            speechRate = AppConfig.ttsSpeechRate.coerceIn(0, 45),
            followSystemSpeechRate = AppConfig.ttsFlowSys,
            mode = mode,
            foregroundActive = foregroundActive && visibility == VISIBLE
        )
    }

    private fun findParagraphIndex(paragraphs: List<TextParagraph>, chapterStart: Int): Int {
        if (paragraphs.isEmpty()) return -1
        val exact = paragraphs.indexOfFirst { chapterStart in it.chapterIndices }
        if (exact >= 0) return exact
        return paragraphs.indexOfLast { it.chapterPosition <= chapterStart }
            .coerceIn(0, paragraphs.lastIndex)
    }

    private fun List<TextParagraph>.nearbyParagraphs(
        currentIndex: Int,
        chapterKey: String,
        chapterSequence: Int
    ): List<ParagraphUi> {
        if (isEmpty() || currentIndex !in indices) return emptyList()
        val start = (currentIndex - 4).coerceAtLeast(0)
        val end = (currentIndex + 4).coerceAtMost(lastIndex)
        return (start..end).map { index ->
            val paragraph = this[index]
            ParagraphUi(
                index = index + 1,
                text = paragraph.text.cleanReadAloudText(),
                current = index == currentIndex,
                key = "$chapterKey:${paragraph.chapterPosition}:${paragraph.realNum}",
                sequence = chapterSequence * 100_000 + index
            )
        }
    }

    private fun buildChapterPreview(
        bookUrl: String?,
        currentIndex: Int,
        chapterCount: Int
    ): List<ChapterPreviewUi> {
        if (bookUrl.isNullOrBlank() || chapterCount <= 0) return emptyList()
        val start = (currentIndex - 5).coerceAtLeast(0)
        val end = (currentIndex + 6).coerceAtMost(chapterCount - 1)
        return runCatching {
            appDb.bookChapterDao.getChapterList(bookUrl, start, end).map { chapter ->
                ChapterPreviewUi(
                    index = chapter.index,
                    title = chapter.title.ifBlank { "未命名章节" },
                    indexText = if (chapter.isVolume) {
                        "卷"
                    } else {
                        "${chapter.index + 1}/$chapterCount"
                    },
                    current = chapter.index == currentIndex,
                    volume = chapter.isVolume,
                    key = "$bookUrl:${chapter.index}:${chapter.title}"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun String.focusSentenceAt(offset: Int): Pair<Int, String> {
        if (isBlank()) return 0 to ""
        val cursor = offset.coerceIn(0, lastIndex.coerceAtLeast(0))
        fun isBoundary(char: Char): Boolean {
            return char == '。' || char == '！' || char == '？' ||
                    char == '!' || char == '?' || char == ';' || char == '；' ||
                    char == '\n'
        }
        fun isClosingQuote(char: Char): Boolean {
            return char == '”' || char == '’' || char == '」' || char == '』' ||
                    char == ')' || char == '）' || char == ']' || char == '】'
        }
        var start = cursor
        while (start > 0 && !isBoundary(this[start - 1])) {
            start--
        }
        while (start < length && this[start].isWhitespace()) {
            start++
        }
        var end = cursor
        while (end < length && !isBoundary(this[end])) {
            end++
        }
        if (end < length) {
            end++
        }
        while (end < length && isClosingQuote(this[end])) {
            end++
        }
        val safeStart = start.coerceIn(0, end.coerceAtLeast(0))
        return safeStart to substring(safeStart, end.coerceIn(safeStart, length)).cleanReadAloudText()
    }

    private fun String.cleanReadAloudText(): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "暂无当前段落" }
    }
}

@Composable
private fun ReadAloudPlayerContent(
    state: ReadAloudPlayerPanel.PlayerUiState,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit,
    onOpenChapterList: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelect: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onTimerChange: (Int) -> Unit,
    onSpeechRateChange: (Int) -> Unit,
    onFollowSystemSpeechRateChange: (Boolean) -> Unit
) {
    val palette = ReaderSheetStyle.resolve(LocalContext.current)
    val colors = rememberPlayerColors(palette)
    var activeSheet by remember(state.mode) { mutableStateOf(PlayerSheet.None) }
    var sheetVisible by remember(state.mode) { mutableStateOf(false) }
    val animateTextChanges = !AppConfig.isEInkMode && state.foregroundActive
    val animatePanelChanges = !AppConfig.isEInkMode && state.foregroundActive
    val sheetEnter = if (animatePanelChanges) {
        fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(240, easing = FastOutSlowInEasing)) { height -> height / 6 } +
                expandVertically(
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top
                )
    } else {
        fadeIn(tween(1)) + expandVertically(tween(1), expandFrom = Alignment.Top)
    }
    val sheetExit = if (animatePanelChanges) {
        shrinkVertically(
            animationSpec = tween(240, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        ) +
                slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { height -> height / 8 } +
                fadeOut(tween(170, easing = FastOutSlowInEasing))
    } else {
        shrinkVertically(tween(1), shrinkTowards = Alignment.Top) + fadeOut(tween(1))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        CoverAtmosphereBackdrop(state, colors)
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val short = maxHeight < 660.dp
            val veryShort = maxHeight < 560.dp
            val sidePadding = when {
                landscape -> 34.dp
                maxWidth < 360.dp -> 18.dp
                else -> 24.dp
            }
            val topPadding = if (veryShort) 10.dp else 18.dp
            val bottomPadding = if (veryShort) 16.dp else 26.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(start = sidePadding, end = sidePadding, top = topPadding, bottom = bottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MinimalHeader(
                    mode = state.mode,
                    colors = colors,
                    onClose = onClose,
                    onOpenSettings = onOpenSettings,
                    onModeChange = {
                        sheetVisible = false
                        onModeChange(it)
                    }
                )
                Spacer(modifier = Modifier.height(if (veryShort) 4.dp else 10.dp))
                if (landscape) {
                    LandscapePlayerBody(
                        state = state,
                        colors = colors,
                        short = short,
                        animateTextChanges = animateTextChanges,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    PortraitPlayerBody(
                        state = state,
                        colors = colors,
                        short = short,
                        veryShort = veryShort,
                        animateTextChanges = animateTextChanges,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(if (veryShort) 8.dp else 14.dp))
                MinimalProgress(state, colors)
                Spacer(modifier = Modifier.height(if (veryShort) 8.dp else 12.dp))
                PlayerControlDock(
                    state = state,
                    colors = colors,
                    activeSheet = if (sheetVisible) activeSheet else PlayerSheet.None,
                    onSheetChange = { sheet ->
                        if (sheetVisible && activeSheet == sheet) {
                            sheetVisible = false
                        } else {
                            activeSheet = sheet
                            sheetVisible = true
                        }
                    },
                    onPlayPause = onPlayPause,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onOpenChapterList = onOpenChapterList
                )
                AnimatedVisibility(
                    visible = sheetVisible && activeSheet != PlayerSheet.None,
                    enter = sheetEnter,
                    exit = sheetExit
                ) {
                    PlayerSheetPanel(
                        sheet = activeSheet,
                        state = state,
                        colors = colors,
                        onOpenChapterList = onOpenChapterList,
                        onPreviousChapter = onPreviousChapter,
                        onNextChapter = onNextChapter,
                        onChapterSelect = onChapterSelect,
                        onTimerChange = onTimerChange,
                        onSpeechRateChange = onSpeechRateChange,
                        onFollowSystemSpeechRateChange = onFollowSystemSpeechRateChange,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

private enum class PlayerSheet {
    None,
    Chapter,
    Timer,
    Speed
}

private data class LyricsTarget(
    val key: String,
    val sequence: Int,
    val paragraphs: List<ReadAloudPlayerPanel.ParagraphUi>
)

private data class PlayerColors(
    val background: Color,
    val panel: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val subtleText: Color,
    val accent: Color,
    val accentText: Color,
    val fluidA: Color,
    val fluidB: Color,
    val fluidC: Color
)

@Composable
private fun rememberPlayerColors(palette: ReaderSheetStyle.Palette): PlayerColors {
    val accent = Color(palette.accentColor)
    return PlayerColors(
        background = Color(ColorUtils.blendColors(palette.surface, android.graphics.Color.BLACK, 0.72f)),
        panel = Color.White.copy(alpha = 0.12f),
        primaryText = Color.White.copy(alpha = 0.94f),
        secondaryText = Color.White.copy(alpha = 0.68f),
        subtleText = Color.White.copy(alpha = 0.42f),
        accent = accent,
        accentText = if (ColorUtils.isColorLight(palette.accentColor)) Color.Black else Color.White,
        fluidA = accent,
        fluidB = Color(ColorUtils.blendColors(palette.accentColor, 0xFF9E5A2A.toInt(), 0.38f)),
        fluidC = Color(ColorUtils.blendColors(palette.primaryColor, 0xFF24505A.toInt(), 0.42f))
    )
}

@Composable
private fun CoverAtmosphereBackdrop(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        CoverBackdropImage(
            state = state,
            alpha = 0.30f,
            modifier = Modifier.fillMaxSize()
        )
        FluidBackdropLayer(state, colors)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.32f),
                            colors.background.copy(alpha = 0.54f),
                            Color.Black.copy(alpha = 0.66f)
                        )
                    )
                )
        )
    }
}

@Composable
private fun CoverBackdropImage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = {
            AppCompatImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = false
                this.alpha = alpha
            }
        },
        update = {
            it.alpha = alpha
            val loadKey = listOf(
                state.coverUrl.orEmpty(),
                state.sourceOrigin.orEmpty(),
                AppConfig.useDefaultCover.toString()
            ).joinToString("|")
            if (it.tag != loadKey) {
                it.tag = loadKey
                BookCover.loadBlur(
                    context = it.context,
                    path = state.coverUrl,
                    loadOnlyWifi = false,
                    sourceOrigin = state.sourceOrigin
                ).into(it)
            }
        }
    )
}

@Composable
private fun FluidBackdropLayer(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors
) {
    val visualActive = state.foregroundActive && state.playing
    val shift = if (visualActive) {
        val transition = rememberInfiniteTransition(label = "readAloudFluid")
        val value by transition.animateFloat(
            initialValue = -0.10f,
            targetValue = 0.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 9200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "fluidShift"
        )
        value
    } else {
        0f
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    colors.fluidC.copy(alpha = 0.28f),
                    Color.Transparent,
                    colors.fluidB.copy(alpha = 0.22f)
                ),
                start = Offset(size.width * (0.05f + shift), 0f),
                end = Offset(size.width, size.height)
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.fluidA.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(size.width * (0.20f + shift), size.height * 0.06f),
                radius = size.maxDimension * 0.70f
            ),
            radius = size.maxDimension * 0.70f,
            center = Offset(size.width * (0.20f + shift), size.height * 0.06f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.fluidB.copy(alpha = 0.26f), Color.Transparent),
                center = Offset(size.width * (0.78f - shift), size.height * 0.50f),
                radius = size.maxDimension * 0.58f
            ),
            radius = size.maxDimension * 0.58f,
            center = Offset(size.width * (0.78f - shift), size.height * 0.50f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.fluidC.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * (0.34f + shift * 0.5f), size.height * 0.98f),
                radius = size.maxDimension * 0.62f
            ),
            radius = size.maxDimension * 0.62f,
            center = Offset(size.width * (0.34f + shift * 0.5f), size.height * 0.98f)
        )
    }
}

@Composable
private fun MinimalHeader(
    mode: ReadAloudPlayerPanel.DisplayMode,
    colors: PlayerColors,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderIconButton(
            icon = R.drawable.ic_expand_more,
            contentDescription = "收起",
            colors = colors,
            onClick = onClose
        )
        Spacer(modifier = Modifier.weight(1f))
        ModeSwitch(mode, colors, onModeChange)
        Spacer(modifier = Modifier.weight(1f))
        HeaderIconButton(
            icon = R.drawable.ic_settings,
            contentDescription = "设置",
            colors = colors,
            onClick = onOpenSettings
        )
    }
}

@Composable
private fun HeaderIconButton(
    icon: Int,
    contentDescription: String,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(42.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = colors.primaryText,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun ModeSwitch(
    mode: ReadAloudPlayerPanel.DisplayMode,
    colors: PlayerColors,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    Row(
        modifier = Modifier
            .width(124.dp)
            .height(32.dp)
            .clip(actionShape)
            .background(Color.White.copy(alpha = 0.13f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        ModeChip(
            text = "沉浸",
            selected = mode == ReadAloudPlayerPanel.DisplayMode.Immersive,
            colors = colors,
            shape = actionShape,
            modifier = Modifier.weight(1f)
        ) {
            onModeChange(ReadAloudPlayerPanel.DisplayMode.Immersive)
        }
        ModeChip(
            text = "原文",
            selected = mode == ReadAloudPlayerPanel.DisplayMode.Text,
            colors = colors,
            shape = actionShape,
            modifier = Modifier.weight(1f)
        ) {
            onModeChange(ReadAloudPlayerPanel.DisplayMode.Text)
        }
    }
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    colors: PlayerColors,
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) colors.accentText else colors.secondaryText,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun PortraitPlayerBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    short: Boolean,
    veryShort: Boolean,
    animateTextChanges: Boolean,
    modifier: Modifier = Modifier
) {
    val immersive = state.mode == ReadAloudPlayerPanel.DisplayMode.Immersive
    if (immersive) {
        ImmersivePlayerStage(
            state = state,
            colors = colors,
            short = short,
            veryShort = veryShort,
            animateTextChanges = animateTextChanges,
            modifier = modifier
        )
    } else {
        LyricsPlayerStage(
            state = state,
            colors = colors,
            compact = short,
            maxParagraphs = if (veryShort) 5 else 7,
            currentMaxLines = if (veryShort) 4 else 6,
            animateTextChanges = animateTextChanges,
            modifier = modifier
        )
    }
}

@Composable
private fun LandscapePlayerBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    short: Boolean,
    animateTextChanges: Boolean,
    modifier: Modifier = Modifier
) {
    val immersive = state.mode == ReadAloudPlayerPanel.DisplayMode.Immersive
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (immersive) {
            CoverArt(
                state = state,
                colors = colors,
                width = if (short) 136.dp else 176.dp
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            BookIdentity(
                state = state,
                colors = colors,
                centered = false,
                compact = short
            )
            Spacer(modifier = Modifier.height(if (short) 12.dp else 18.dp))
            if (immersive) {
                FocusSentenceBody(
                    state = state,
                    colors = colors,
                    compact = short,
                    animateTextChanges = animateTextChanges,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LyricParagraphBody(
                    state = state,
                    colors = colors,
                    compact = short,
                    maxParagraphs = if (short) 5 else 7,
                    currentMaxLines = if (short) 4 else 6,
                    textAlign = TextAlign.Start,
                    animateTextChanges = animateTextChanges,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun ImmersivePlayerStage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    short: Boolean,
    veryShort: Boolean,
    animateTextChanges: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CoverArt(
            state = state,
            colors = colors,
            width = when {
                veryShort -> 128.dp
                short -> 164.dp
                else -> 210.dp
            }
        )
        Spacer(modifier = Modifier.height(if (veryShort) 14.dp else 22.dp))
        BookIdentity(
            state = state,
            colors = colors,
            centered = true,
            compact = veryShort
        )
        Spacer(modifier = Modifier.height(if (veryShort) 12.dp else 22.dp))
        FocusSentenceBody(
            state = state,
            colors = colors,
            compact = short,
            animateTextChanges = animateTextChanges,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (veryShort) 84.dp else 118.dp)
        )
    }
}

@Composable
private fun LyricsPlayerStage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    maxParagraphs: Int,
    currentMaxLines: Int,
    animateTextChanges: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BookIdentity(
            state = state,
            colors = colors,
            centered = true,
            compact = compact
        )
        Spacer(modifier = Modifier.height(if (compact) 12.dp else 18.dp))
        LyricParagraphBody(
            state = state,
            colors = colors,
            compact = compact,
            maxParagraphs = maxParagraphs,
            currentMaxLines = currentMaxLines,
            animateTextChanges = animateTextChanges,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

@Composable
private fun CoverArt(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    width: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(0.75f)
            .shadow(
                elevation = 22.dp,
                shape = RoundedCornerShape(15.dp),
                clip = false
            )
            .clip(RoundedCornerShape(13.dp))
            .background(colors.panel)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                CoverImageView(it).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = {
                it.load(
                    path = state.coverUrl,
                    name = state.bookName,
                    author = state.author,
                    loadOnlyWifi = false,
                    sourceOrigin = state.sourceOrigin,
                    preferThumb = false
                )
            }
        )
    }
}

@Composable
private fun BookIdentity(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    centered: Boolean,
    compact: Boolean
) {
    Column(
        modifier = Modifier.widthIn(max = 560.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(
            text = state.bookName.ifBlank { "当前书籍" },
            color = colors.primaryText,
            fontSize = if (compact) 15.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = state.chapterTitle.ifBlank { "当前章节" },
            color = colors.secondaryText,
            fontSize = if (compact) 11.sp else 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun FocusSentenceBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    animateTextChanges: Boolean,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center
) {
    val focus = state.focusText.takeIf { it.text.isNotBlank() }
        ?: ReadAloudPlayerPanel.FocusTextUi(
            key = state.paragraphKey,
            sequence = state.paragraphSequence,
            text = state.paragraphText.ifBlank { "暂无当前段落" }
        )
    AnimatedContent(
        targetState = focus,
        transitionSpec = {
            val direction = if (targetState.sequence >= initialState.sequence) 1 else -1
            if (animateTextChanges) {
                ((slideInVertically(tween(320)) { height -> height * direction / 3 } +
                        fadeIn(tween(220)) +
                        scaleIn(tween(320), initialScale = 0.98f)) togetherWith
                        (slideOutVertically(tween(240)) { height -> -height * direction / 4 } +
                                fadeOut(tween(160)) +
                                scaleOut(tween(240), targetScale = 1.02f)))
                    .using(SizeTransform(clip = false))
            } else {
                (fadeIn(tween(1)) togetherWith fadeOut(tween(1)))
                    .using(SizeTransform(clip = false))
            }
        },
        modifier = modifier,
        label = "readAloudFocusText"
    ) { target ->
        Text(
            text = target.text,
            color = colors.primaryText,
            fontSize = if (compact) 24.sp else 28.sp,
            lineHeight = if (compact) 33.sp else 38.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (compact) 3 else 4,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LyricParagraphBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    maxParagraphs: Int,
    currentMaxLines: Int,
    animateTextChanges: Boolean,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center
) {
    val paragraphs = state.nearbyParagraphs.ifEmpty {
        listOf(
            ReadAloudPlayerPanel.ParagraphUi(
                index = state.paragraphIndex.coerceAtLeast(1),
                text = state.paragraphText.ifBlank { "暂无当前段落" },
                current = true,
                key = state.paragraphKey.ifBlank { state.paragraphIndex.toString() },
                sequence = state.paragraphSequence
            )
        )
    }
    val currentPosition = paragraphs.indexOfFirst { it.current }.let { if (it >= 0) it else 0 }
    val half = maxParagraphs / 2
    val start = (currentPosition - half).coerceAtLeast(0)
    val end = (start + maxParagraphs - 1).coerceAtMost(paragraphs.lastIndex)
    val visible = paragraphs.subList(start, end + 1)
    val target = remember(state.paragraphKey, visible) {
        LyricsTarget(
            key = state.paragraphKey,
            sequence = state.paragraphSequence,
            paragraphs = visible
        )
    }
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            val direction = if (targetState.sequence >= initialState.sequence) 1 else -1
            if (animateTextChanges) {
                ((slideInVertically(tween(300)) { height -> height * direction / 5 } +
                        fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(240)) { height -> -height * direction / 6 } +
                                fadeOut(tween(160))))
                    .using(SizeTransform(clip = false))
            } else {
                (fadeIn(tween(1)) togetherWith fadeOut(tween(1)))
                    .using(SizeTransform(clip = false))
            }
        },
        modifier = modifier.fillMaxHeight(),
        label = "readAloudLyrics"
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp),
                horizontalAlignment = when (textAlign) {
                    TextAlign.Start -> Alignment.Start
                    TextAlign.End -> Alignment.End
                    else -> Alignment.CenterHorizontally
                },
                verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
            ) {
                it.paragraphs.forEach { paragraph ->
                    LyricParagraphLine(
                        paragraph = paragraph,
                        colors = colors,
                        compact = compact,
                        currentMaxLines = currentMaxLines,
                        textAlign = textAlign,
                        animate = animateTextChanges
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricParagraphLine(
    paragraph: ReadAloudPlayerPanel.ParagraphUi,
    colors: PlayerColors,
    compact: Boolean,
    currentMaxLines: Int,
    textAlign: TextAlign,
    animate: Boolean
) {
    val emphasis by animateFloatAsState(
        targetValue = if (paragraph.current) 1f else 0f,
        animationSpec = tween(if (animate) 220 else 1),
        label = "readAloudLyricEmphasis"
    )
    val fontSize = when {
        compact -> 14f + emphasis * 6f
        else -> 15f + emphasis * 8f
    }
    val lineHeight = when {
        compact -> 21f + emphasis * 7f
        else -> 23f + emphasis * 9f
    }
    Text(
        text = paragraph.text,
        color = colors.primaryText.copy(alpha = 0.36f + emphasis * 0.58f),
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
        fontWeight = if (paragraph.current) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = if (paragraph.current) currentMaxLines else 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun MinimalProgress(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors
) {
    val actionShape = LocalContext.current.composeActionShape()
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(actionShape),
            color = colors.primaryText,
            trackColor = Color.White.copy(alpha = 0.16f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.chapterIndexText.ifBlank { "章节" },
                color = colors.subtleText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = state.progressText,
                color = colors.subtleText,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PlayerControlDock(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    activeSheet: PlayerSheet,
    onSheetChange: (PlayerSheet) -> Unit,
    onPlayPause: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenChapterList: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 300.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundTransportButton(
                icon = R.drawable.ic_skip_previous,
                contentDescription = "上一章",
                colors = colors,
                size = 50.dp,
                iconSize = 25.dp,
                onClick = onPreviousChapter
            )
            Surface(
                modifier = Modifier
                    .size(72.dp)
                    .clickable(onClick = onPlayPause),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.92f),
                shadowElevation = 16.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp),
                        contentDescription = if (state.playing) context.getString(R.string.pause) else context.getString(R.string.audio_play),
                        tint = Color.Black.copy(alpha = 0.88f),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            RoundTransportButton(
                icon = R.drawable.ic_skip_next,
                contentDescription = "下一章",
                colors = colors,
                size = 50.dp,
                iconSize = 25.dp,
                onClick = onNextChapter
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FeaturePill(
                icon = R.drawable.ic_toc,
                text = context.getString(R.string.chapter_list),
                selected = activeSheet == PlayerSheet.Chapter,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Chapter)
            }
            FeaturePill(
                icon = R.drawable.ic_time_add_24dp,
                text = if (state.timerMinute > 0) context.getString(R.string.timer_m, state.timerMinute)
                else context.getString(R.string.set_timer),
                selected = activeSheet == PlayerSheet.Timer,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Timer)
            }
            FeaturePill(
                icon = R.drawable.ic_speed_control,
                text = formatSpeechRate(state.speechRate),
                selected = activeSheet == PlayerSheet.Speed,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Speed)
            }
        }
    }
}

@Composable
private fun RoundTransportButton(
    icon: Int,
    contentDescription: String,
    colors: PlayerColors,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(size)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = colors.primaryText,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun FeaturePill(
    icon: Int,
    text: String,
    selected: Boolean,
    colors: PlayerColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        shape = actionShape,
        color = if (selected) colors.accent.copy(alpha = 0.86f) else Color.White.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (selected) 0.28f else 0.12f)),
        shadowElevation = if (selected) 8.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = text,
                tint = if (selected) colors.accentText else colors.primaryText,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                color = if (selected) colors.accentText else colors.primaryText,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlayerSheetPanel(
    sheet: PlayerSheet,
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpenChapterList: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelect: (Int) -> Unit,
    onTimerChange: (Int) -> Unit,
    onSpeechRateChange: (Int) -> Unit,
    onFollowSystemSpeechRateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val panelShape = LocalContext.current.composePanelShape()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = panelShape,
        color = Color.Black.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        shadowElevation = 12.dp
    ) {
        when (sheet) {
            PlayerSheet.Chapter -> ChapterSheet(
                state = state,
                colors = colors,
                onOpenChapterList = onOpenChapterList,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onChapterSelect = onChapterSelect
            )
            PlayerSheet.Timer -> TimerSheet(state, colors, onTimerChange)
            PlayerSheet.Speed -> SpeedSheet(state, colors, onSpeechRateChange, onFollowSystemSpeechRateChange)
            PlayerSheet.None -> Unit
        }
    }
}

@Composable
private fun ChapterSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpenChapterList: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelect: (Int) -> Unit
) {
    val context = LocalContext.current
    val actionShape = context.composeActionShape()
    val chapterCount = state.chapterCount.coerceAtLeast(1)
    val chapters = state.chapterPreview
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = context.getString(R.string.chapter_list),
                    color = colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = state.chapterTitle.ifBlank { state.chapterIndexText },
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                text = "${state.chapterIndex.coerceIn(0, chapterCount - 1) + 1}/$chapterCount",
                color = colors.primaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp, max = 286.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(
                items = chapters,
                key = { it.key }
            ) { chapter ->
                ChapterPreviewRow(
                    chapter = chapter,
                    colors = colors,
                    shape = actionShape,
                    onClick = { onChapterSelect(chapter.index) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SheetActionButton(
                text = context.getString(R.string.previous_chapter),
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onPreviousChapter
            )
            SheetActionButton(
                text = context.getString(R.string.chapter_list),
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onOpenChapterList
            )
            SheetActionButton(
                text = context.getString(R.string.next_chapter),
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onNextChapter
            )
        }
    }
}

@Composable
private fun ChapterPreviewRow(
    chapter: ReadAloudPlayerPanel.ChapterPreviewUi,
    colors: PlayerColors,
    shape: Shape,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(enabled = !chapter.volume, onClick = onClick),
        shape = shape,
        color = when {
            chapter.current -> colors.accent.copy(alpha = 0.86f)
            chapter.volume -> Color.White.copy(alpha = 0.08f)
            else -> Color.White.copy(alpha = 0.12f)
        },
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (chapter.current) 0.28f else 0.10f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = chapter.indexText,
                color = if (chapter.current) colors.accentText else colors.subtleText,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(56.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = chapter.title,
                color = if (chapter.current) colors.accentText else colors.primaryText,
                fontSize = if (chapter.volume) 12.sp else 13.sp,
                fontWeight = if (chapter.current || chapter.volume) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SheetActionButton(
    text: String,
    colors: PlayerColors,
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        shape = shape,
        color = Color.White.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = colors.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TimerSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onTimerChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val actionShape = context.composeActionShape()
    val times = listOf(0, 5, 10, 15, 30, 60, 90, 180)
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = context.getString(R.string.set_timer),
            color = colors.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        times.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { minute ->
                    val selected = state.timerMinute == minute || (state.timerMinute <= 0 && minute == 0)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clickable { onTimerChange(minute) },
                        shape = actionShape,
                        color = if (selected) colors.accent.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.12f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = context.getString(R.string.timer_m, minute),
                                color = if (selected) colors.accentText else colors.primaryText,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onSpeechRateChange: (Int) -> Unit,
    onFollowSystemSpeechRateChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var pendingRate by remember(state.speechRate) { mutableStateOf(state.speechRate.toFloat()) }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = context.getString(R.string.read_aloud_speed),
                color = colors.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatSpeechRate(pendingRate.roundToInt()),
                color = colors.primaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = pendingRate,
            onValueChange = { pendingRate = it },
            onValueChangeFinished = { onSpeechRateChange(pendingRate.roundToInt()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.followSystemSpeechRate,
            valueRange = 0f..45f,
            steps = 44
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                enabled = !state.followSystemSpeechRate,
                onClick = {
                    val next = (pendingRate.roundToInt() - 1).coerceIn(0, 45)
                    pendingRate = next.toFloat()
                    onSpeechRateChange(next)
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_reduce),
                    contentDescription = "reduce",
                    tint = colors.primaryText
                )
            }
            IconButton(
                enabled = !state.followSystemSpeechRate,
                onClick = {
                    val next = (pendingRate.roundToInt() + 1).coerceIn(0, 45)
                    pendingRate = next.toFloat()
                    onSpeechRateChange(next)
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = "add",
                    tint = colors.primaryText
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = context.getString(R.string.flow_sys),
                color = colors.secondaryText,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = state.followSystemSpeechRate,
                onCheckedChange = onFollowSystemSpeechRateChange
            )
        }
    }
}

private fun formatSpeechRate(value: Int): String {
    return ((value + 5) / 10f).toString()
}
