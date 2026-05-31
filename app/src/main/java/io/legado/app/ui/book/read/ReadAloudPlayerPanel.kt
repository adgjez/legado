package io.legado.app.ui.book.read

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
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
    }

    enum class DisplayMode {
        Immersive,
        Text
    }

    data class ParagraphUi(
        val index: Int,
        val text: String,
        val current: Boolean
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
        val nearbyParagraphs: List<ParagraphUi> = emptyList(),
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
                onModeChange = ::setMode
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
            visibility = VISIBLE
            bringToFront()
            uiState = buildState(uiState.mode).copy(foregroundActive = foregroundActive)
        }
    }

    private fun hidePanel() {
        visibility = GONE
        uiState = buildState(uiState.mode).copy(foregroundActive = false)
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
        val chapterTitle = chapter?.title?.ifBlank { "当前章节" }.orEmpty()
        val chapterIndexText = chapter?.chapter?.let {
            "${it.index + 1}/${chapter.chaptersSize.coerceAtLeast(it.index + 1)}"
        }.orEmpty()
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
        val paragraphText = paragraphs.getOrNull(paragraphIndex)
            ?.text
            ?.cleanReadAloudText()
            .orEmpty()
        val nearby = paragraphs.nearbyParagraphs(paragraphIndex)
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
            nearbyParagraphs = nearby,
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

    private fun List<TextParagraph>.nearbyParagraphs(currentIndex: Int): List<ParagraphUi> {
        if (isEmpty() || currentIndex !in indices) return emptyList()
        val start = (currentIndex - 2).coerceAtLeast(0)
        val end = (currentIndex + 2).coerceAtMost(lastIndex)
        return (start..end).map { index ->
            ParagraphUi(
                index = index + 1,
                text = this[index].text.cleanReadAloudText(),
                current = index == currentIndex
            )
        }
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
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit
) {
    val palette = ReaderSheetStyle.resolve(LocalContext.current)
    val colors = rememberPlayerColors(palette)
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
                    .padding(start = sidePadding, end = sidePadding, top = topPadding, bottom = bottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MinimalHeader(
                    mode = state.mode,
                    colors = colors,
                    onClose = onClose,
                    onModeChange = onModeChange
                )
                Spacer(modifier = Modifier.height(if (veryShort) 4.dp else 10.dp))
                if (landscape) {
                    LandscapePlayerBody(
                        state = state,
                        colors = colors,
                        short = short,
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(if (veryShort) 8.dp else 14.dp))
                MinimalProgress(state, colors)
                Spacer(modifier = Modifier.height(if (veryShort) 10.dp else 18.dp))
                MinimalTransport(state, colors, onPlayPause)
            }
        }
    }
}

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
        CoverBackdropImage(
            state = state,
            alpha = 0.20f,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.22f)
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
            CoverImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                this.alpha = alpha
            }
        },
        update = {
            it.alpha = alpha
            it.load(
                path = state.coverUrl,
                name = state.bookName,
                author = state.author,
                loadOnlyWifi = false,
                sourceOrigin = state.sourceOrigin,
                preferThumb = true
            )
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
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = "收起",
                tint = colors.primaryText,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        ModeSwitch(mode, colors, onModeChange)
    }
}

@Composable
private fun ModeSwitch(
    mode: ReadAloudPlayerPanel.DisplayMode,
    colors: PlayerColors,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit
) {
    Row(
        modifier = Modifier
            .width(124.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.13f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        ModeChip(
            text = "沉浸",
            selected = mode == ReadAloudPlayerPanel.DisplayMode.Immersive,
            colors = colors,
            modifier = Modifier.weight(1f)
        ) {
            onModeChange(ReadAloudPlayerPanel.DisplayMode.Immersive)
        }
        ModeChip(
            text = "原文",
            selected = mode == ReadAloudPlayerPanel.DisplayMode.Text,
            colors = colors,
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(999.dp))
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
    modifier: Modifier = Modifier
) {
    val immersive = state.mode == ReadAloudPlayerPanel.DisplayMode.Immersive
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (immersive) {
            CoverArt(
                state = state,
                colors = colors,
                width = when {
                    veryShort -> 124.dp
                    short -> 158.dp
                    else -> 196.dp
                }
            )
            Spacer(modifier = Modifier.height(if (veryShort) 12.dp else 18.dp))
        }
        BookIdentity(
            state = state,
            colors = colors,
            centered = true,
            compact = veryShort
        )
        Spacer(modifier = Modifier.height(if (veryShort) 10.dp else 18.dp))
        LyricParagraphBody(
            state = state,
            colors = colors,
            compact = short,
            maxParagraphs = if (veryShort) 3 else 5,
            currentMaxLines = if (immersive) 3 else 5,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LandscapePlayerBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    short: Boolean,
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
            LyricParagraphBody(
                state = state,
                colors = colors,
                compact = short,
                maxParagraphs = if (short) 3 else 5,
                currentMaxLines = if (immersive) 4 else 5,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CoverArt(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    width: Dp,
    modifier: Modifier = Modifier
) {
    val visualActive = state.foregroundActive &&
        state.playing &&
        state.mode == ReadAloudPlayerPanel.DisplayMode.Immersive
    val coverScale = if (visualActive) {
        val transition = rememberInfiniteTransition(label = "readAloudCover")
        val scale by transition.animateFloat(
            initialValue = 0.992f,
            targetValue = 1.012f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "coverScale"
        )
        scale
    } else {
        1f
    }
    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(0.75f)
            .scale(coverScale)
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
private fun LyricParagraphBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    maxParagraphs: Int,
    currentMaxLines: Int,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center
) {
    val paragraphs = state.nearbyParagraphs.ifEmpty {
        listOf(
            ReadAloudPlayerPanel.ParagraphUi(
                index = state.paragraphIndex.coerceAtLeast(1),
                text = state.paragraphText.ifBlank { "暂无当前段落" },
                current = true
            )
        )
    }
    val currentPosition = paragraphs.indexOfFirst { it.current }.let { if (it >= 0) it else 0 }
    val half = maxParagraphs / 2
    val start = (currentPosition - half).coerceAtLeast(0)
    val end = (start + maxParagraphs - 1).coerceAtMost(paragraphs.lastIndex)
    val visible = paragraphs.subList(start, end + 1)
    Column(
        modifier = modifier.widthIn(max = 620.dp),
        horizontalAlignment = when (textAlign) {
            TextAlign.Start -> Alignment.Start
            TextAlign.End -> Alignment.End
            else -> Alignment.CenterHorizontally
        },
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
    ) {
        visible.forEach { paragraph ->
            val current = paragraph.current
            Text(
                text = paragraph.text,
                color = if (current) colors.primaryText else colors.subtleText,
                fontSize = when {
                    current && compact -> 18.sp
                    current -> 21.sp
                    compact -> 13.sp
                    else -> 14.sp
                },
                lineHeight = when {
                    current && compact -> 26.sp
                    current -> 30.sp
                    compact -> 19.sp
                    else -> 21.sp
                },
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = if (current) currentMaxLines else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MinimalProgress(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp)),
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
private fun MinimalTransport(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onPlayPause: () -> Unit
) {
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
                contentDescription = if (state.playing) "暂停" else "播放",
                tint = Color.Black.copy(alpha = 0.88f),
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

private fun formatSpeechRate(value: Int): String {
    return ((value + 5) / 10f).toString()
}
