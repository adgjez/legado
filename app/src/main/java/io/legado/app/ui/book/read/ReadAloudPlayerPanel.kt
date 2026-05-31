package io.legado.app.ui.book.read

import android.content.Context
import android.graphics.RectF
import android.speech.tts.TextToSpeech
import android.util.AttributeSet
import android.view.MotionEvent
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.BookCover
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.lib.theme.composeActionShape
import io.legado.app.lib.theme.composePanelShape
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.ui.book.read.page.entities.ReadAloudCue
import io.legado.app.ui.book.read.page.entities.ReadAloudTextCleaner
import io.legado.app.ui.book.read.page.entities.TextParagraph
import io.legado.app.ui.book.read.page.entities.buildReadAloudCues
import io.legado.app.ui.book.read.page.entities.indexForChapterPosition
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.delay
import kotlin.math.abs
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
        fun openBookCharacters()
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

    data class TextCueUi(
        val index: Int,
        val text: String,
        val current: Boolean,
        val key: String,
        val sequence: Int,
        val chapterPosition: Int
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

    data class TtsEngineUi(
        val title: String,
        val subtitle: String,
        val value: String,
        val selected: Boolean,
        val key: String
    )

    data class CharacterPreviewUi(
        val id: Long,
        val name: String,
        val role: String,
        val summary: String,
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
        val ttsEngines: List<TtsEngineUi> = emptyList(),
        val characterPreview: List<CharacterPreviewUi> = emptyList(),
        val nearbyParagraphs: List<ParagraphUi> = emptyList(),
        val textCues: List<TextCueUi> = emptyList(),
        val currentCueIndex: Int = 0,
        val chapterKey: String = "",
        val paragraphKey: String = "",
        val paragraphSequence: Int = 0,
        val focusText: FocusTextUi = FocusTextUi(),
        val speechRate: Int = AppConfig.ttsSpeechRate.coerceIn(0, 45),
        val followSystemSpeechRate: Boolean = AppConfig.ttsFlowSys,
        val mode: DisplayMode = DisplayMode.Immersive,
        val foregroundActive: Boolean = true,
        val expanded: Boolean = true,
        val readMenuVisible: Boolean = false,
        val openToken: Int = 0
    )

    private val composeView = ComposeView(context)
    private var callBack: CallBack? = null
    private var dismissedForCurrentRun = false
    private var foregroundActive = true
    private var lastChapterStart = 0
    private var cachedSystemTtsOptions: List<Pair<String, String>>? = null
    private var expanded = true
    private var readMenuVisible = false
    private var openToken = 0
    private val capsuleBounds = RectF()

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
                onExpand = { open(force = true) },
                onStop = ::stopReadAloud,
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
                onEngineSelect = ::selectTtsEngine,
                onProgressSeek = ::seekToParagraphProgress,
                onCueSelect = ::seekToChapterPosition,
                onCapsuleBounds = ::updateCapsuleBounds,
                onOpenCharacters = { callBack?.openBookCharacters() }
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
        showPanel(expand = true)
    }

    fun onAloudState(status: Int) {
        when (status) {
            io.legado.app.constant.Status.PLAY -> {
                refresh()
                if (!dismissedForCurrentRun && (visibility != VISIBLE || !expanded)) {
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

    fun setReadMenuVisible(visible: Boolean) {
        readMenuVisible = visible
        uiState = uiState.copy(readMenuVisible = visible)
    }

    fun isExpanded(): Boolean = visibility == VISIBLE && expanded

    fun close() {
        closeByUser()
    }

    fun refresh() {
        uiState = buildState(uiState.mode)
    }

    private fun showPanel(expand: Boolean = true) {
        post {
            val wasVisible = visibility == VISIBLE
            val wasExpanded = expanded
            expanded = expand
            if (expand) {
                openToken++
            }
            visibility = VISIBLE
            bringToFront()
            ViewCompat.requestApplyInsets(this)
            ViewCompat.requestApplyInsets(composeView)
            uiState = buildState(uiState.mode).copy(
                foregroundActive = foregroundActive,
                expanded = expanded,
                readMenuVisible = readMenuVisible,
                openToken = openToken
            )
            if (!wasVisible || (!wasExpanded && expanded)) {
                callBack?.onReadAloudPlayerVisibilityChanged(true)
            }
        }
    }

    private fun hidePanel() {
        val wasVisible = visibility == VISIBLE
        expanded = false
        visibility = GONE
        capsuleBounds.setEmpty()
        uiState = buildState(uiState.mode).copy(foregroundActive = false, expanded = false)
        if (wasVisible) {
            callBack?.onReadAloudPlayerVisibilityChanged(false)
        }
    }

    private fun closeByUser() {
        if (BaseReadAloudService.isRun) {
            dismissedForCurrentRun = true
            expanded = false
            uiState = buildState(uiState.mode).copy(
                foregroundActive = foregroundActive,
                expanded = false,
                readMenuVisible = readMenuVisible,
                openToken = openToken
            )
            callBack?.onReadAloudPlayerVisibilityChanged(false)
        } else {
            hidePanel()
        }
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

    private fun updateCapsuleBounds(bounds: RectF) {
        capsuleBounds.set(bounds)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility == VISIBLE && !expanded) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
                !capsuleBounds.contains(ev.x, ev.y)
            ) {
                return false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun openReadAloudSetting() {
        (context as? AppCompatActivity)?.showDialogFragment(ReadAloudConfigDialog())
    }

    private fun setTimer(minute: Int) {
        AppConfig.ttsTimer = minute
        ReadAloud.setTimer(context, minute)
        uiState = uiState.copy(timerMinute = minute)
    }

    private fun selectTtsEngine(value: String) {
        ReadBook.book?.setTtsEngine(null)
        AppConfig.ttsEngine = value
        ReadAloud.upReadAloudClass()
        value.toLongOrNull()
            ?.let { appDb.httpTTSDao.get(it) }
            ?.takeIf { !it.loginUrl.isNullOrBlank() && it.getLoginInfo().isNullOrBlank() }
            ?.let { httpTts ->
                context.startActivity<SourceLoginActivity> {
                    putExtra("type", "httpTts")
                    putExtra("key", httpTts.id.toString())
                }
            }
        uiState = buildState(uiState.mode)
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

    private fun seekToParagraphProgress(progress: Float) {
        val chapter = ReadBook.curTextChapter ?: return
        val cues = chapter.buildReadAloudCues(context.getPrefBoolean(PreferKey.readAloudByPage))
        if (cues.isEmpty()) return
        val targetIndex = if (cues.size == 1) {
            0
        } else {
            (progress.coerceIn(0f, 1f) * (cues.size - 1)).roundToInt()
        }.coerceIn(0, cues.lastIndex)
        seekToChapterPosition(cues[targetIndex].chapterPosition)
    }

    private fun seekToChapterPosition(chapterPosition: Int) {
        val chapter = ReadBook.curTextChapter ?: return
        val targetPos = chapterPosition.coerceAtLeast(0)
        val pageIndex = chapter.getPageIndexByCharIndex(targetPos)
        if (pageIndex < 0) return
        val startPos = (targetPos - chapter.getReadLength(pageIndex)).coerceAtLeast(0)
        ReadBook.durChapterPos = targetPos
        ReadAloud.play(
            context = context,
            play = BaseReadAloudService.isPlay(),
            pageIndex = pageIndex,
            startPos = startPos
        )
        lastChapterStart = targetPos
        uiState = buildState(uiState.mode)
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
        val cues = chapter?.buildReadAloudCues(context.getPrefBoolean(PreferKey.readAloudByPage)).orEmpty()
        val totalLength = chapter?.lastPage
            ?.let { it.chapterPosition + it.charSize }
            ?.coerceAtLeast(1)
            ?: 1
        val chapterStart = when {
            lastChapterStart > 0 -> lastChapterStart
            else -> ReadBook.durChapterPos
        }.coerceIn(0, totalLength)
        val paragraphIndex = findParagraphIndex(paragraphs, chapterStart)
        val cueIndex = cues.indexForChapterPosition(chapterStart)
        val cue = cues.getOrNull(cueIndex)
        val paragraph = paragraphs.getOrNull(paragraphIndex)
        val paragraphText = cue?.text ?: paragraph?.text?.cleanReadAloudText().orEmpty()
        val paragraphCount = cues.size
        val paragraphProgress = when {
            paragraphCount <= 1 || cueIndex < 0 -> 0f
            else -> (cueIndex.toFloat() / (paragraphCount - 1).toFloat()).coerceIn(0f, 1f)
        }
        val paragraphProgressText = if (paragraphCount > 0 && cueIndex >= 0) {
            "${cueIndex + 1}/$paragraphCount"
        } else {
            "0/0"
        }
        val paragraphSequence = chapterSequence * 100_000 + cueIndex.coerceAtLeast(0)
        val paragraphKey = "$chapterKey:${cue?.chapterPosition ?: paragraph?.chapterPosition ?: cueIndex}"
        val sentence = (cue?.text ?: paragraph?.text)
            ?.focusSentenceAt(chapterStart - (cue?.chapterPosition ?: paragraph?.chapterPosition ?: 0))
            ?: (0 to paragraphText)
        val focusText = FocusTextUi(
            key = "$paragraphKey:${sentence.first}:${sentence.second.hashCode()}",
            sequence = paragraphSequence * 1_000 + sentence.first.coerceAtLeast(0),
            text = sentence.second.ifBlank { paragraphText.ifBlank { "暂无当前段落" } }
        )
        val nearby = cues.nearbyCueParagraphs(cueIndex, chapterKey, chapterSequence)
        val textCues = cues.toTextCueUi(cueIndex, chapterKey, chapterSequence)
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
            progress = paragraphProgress,
            progressText = paragraphProgressText,
            paragraphText = paragraphText,
            paragraphIndex = if (cueIndex >= 0) cueIndex + 1 else 0,
            paragraphCount = paragraphCount,
            chapterIndex = chapterSequence.coerceAtLeast(0),
            chapterCount = ReadBook.chapterSize.coerceAtLeast(chapterSequence + 1),
            chapterPreview = buildChapterPreview(book?.bookUrl, chapterSequence, ReadBook.chapterSize),
            ttsEngines = buildTtsEngineOptions(),
            characterPreview = buildCharacterPreview(book?.bookUrl),
            nearbyParagraphs = nearby,
            textCues = textCues,
            currentCueIndex = cueIndex.coerceAtLeast(0),
            chapterKey = chapterKey,
            paragraphKey = paragraphKey,
            paragraphSequence = paragraphSequence,
            focusText = focusText,
            speechRate = AppConfig.ttsSpeechRate.coerceIn(0, 45),
            followSystemSpeechRate = AppConfig.ttsFlowSys,
            mode = mode,
            foregroundActive = foregroundActive && visibility == VISIBLE,
            expanded = expanded,
            readMenuVisible = readMenuVisible,
            openToken = openToken
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

    private fun List<ReadAloudCue>.nearbyCueParagraphs(
        currentIndex: Int,
        chapterKey: String,
        chapterSequence: Int
    ): List<ParagraphUi> {
        if (isEmpty() || currentIndex !in indices) return emptyList()
        val start = (currentIndex - 4).coerceAtLeast(0)
        val end = (currentIndex + 4).coerceAtMost(lastIndex)
        return (start..end).map { index ->
            val cue = this[index]
            ParagraphUi(
                index = index + 1,
                text = cue.text.cleanReadAloudText(),
                current = index == currentIndex,
                key = "$chapterKey:${cue.chapterPosition}:${cue.key}",
                sequence = chapterSequence * 100_000 + index
            )
        }
    }

    private fun List<ReadAloudCue>.toTextCueUi(
        currentIndex: Int,
        chapterKey: String,
        chapterSequence: Int
    ): List<TextCueUi> {
        return mapIndexed { index, cue ->
            TextCueUi(
                index = index + 1,
                text = cue.text.cleanReadAloudText(),
                current = index == currentIndex,
                key = "$chapterKey:${cue.chapterPosition}:${cue.key}",
                sequence = chapterSequence * 100_000 + index,
                chapterPosition = cue.chapterPosition
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

    private fun buildTtsEngineOptions(): List<TtsEngineUi> {
        val current = ReadAloud.ttsEngine
        val systemItems = systemTtsOptions().map { (title, systemValue) ->
            val value = GSON.toJson(SelectItem(title, systemValue))
            TtsEngineUi(
                title = title,
                subtitle = if (systemValue.isBlank()) "系统默认" else "系统引擎",
                value = value,
                selected = isSystemTtsSelected(current, systemValue),
                key = "system:$systemValue"
            )
        }
        val httpItems = runCatching { appDb.httpTTSDao.all }.getOrDefault(emptyList()).map { httpTts ->
            TtsEngineUi(
                title = httpTts.name.ifBlank { "HTTP TTS" },
                subtitle = "HTTP TTS",
                value = httpTts.id.toString(),
                selected = current == httpTts.id.toString(),
                key = "http:${httpTts.id}"
            )
        }
        return systemItems + httpItems
    }

    private fun systemTtsOptions(): List<Pair<String, String>> {
        cachedSystemTtsOptions?.let { return it }
        return runCatching {
            val tts = TextToSpeech(context, null)
            try {
                listOf("系统默认" to "") + tts.engines.map { it.label.toString() to it.name }
            } finally {
                tts.shutdown()
            }
        }.getOrDefault(listOf("系统默认" to ""))
            .also { cachedSystemTtsOptions = it }
    }

    private fun isSystemTtsSelected(current: String?, systemValue: String): Boolean {
        return if (current.isNullOrBlank()) {
            systemValue.isBlank()
        } else {
            current.isJsonObject() &&
                    GSON.fromJsonObject<SelectItem<String>>(current).getOrNull()?.value == systemValue
        }
    }

    private fun buildCharacterPreview(bookUrl: String?): List<CharacterPreviewUi> {
        if (bookUrl.isNullOrBlank()) return emptyList()
        return runCatching {
            appDb.bookCharacterDao.characters(bookUrl).take(8).map { character ->
                CharacterPreviewUi(
                    id = character.id,
                    name = character.displayName(),
                    role = character.roleLabel(),
                    summary = character.previewSummary(),
                    key = "character:${character.id}"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun BookCharacter.previewSummary(): String {
        return listOf(identity, skills, attributes, biography)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .take(80)
            .ifBlank { "暂无角色摘要" }
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
        return ReadAloudTextCleaner.cleanInlineText(this)
            .ifBlank { "暂无当前段落" }
    }
}

@Composable
private fun ReadAloudPlayerContent(
    state: ReadAloudPlayerPanel.PlayerUiState,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onStop: () -> Unit,
    onPlayPause: () -> Unit,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit,
    onOpenChapterList: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelect: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onTimerChange: (Int) -> Unit,
    onEngineSelect: (String) -> Unit,
    onProgressSeek: (Float) -> Unit,
    onCueSelect: (Int) -> Unit,
    onCapsuleBounds: (RectF) -> Unit,
    onOpenCharacters: () -> Unit
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
    var expandedVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.expanded, state.openToken) {
        if (state.expanded) {
            expandedVisible = false
            withFrameNanos { }
            expandedVisible = true
        } else {
            expandedVisible = false
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = expandedVisible,
            enter = slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it } +
                    fadeIn(tween(260, easing = FastOutSlowInEasing)),
            exit = slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { it } +
                    fadeOut(tween(180, easing = FastOutSlowInEasing))
        ) {
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
                        onCueSelect = onCueSelect,
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
                        onCueSelect = onCueSelect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(if (veryShort) 8.dp else 14.dp))
                MinimalProgress(state, colors, onProgressSeek)
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
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(
                        start = sidePadding,
                        end = sidePadding,
                        bottom = bottomPadding + 138.dp
                    ),
                contentAlignment = Alignment.BottomCenter
            ) {
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
                        onEngineSelect = onEngineSelect,
                        onOpenCharacters = onOpenCharacters
                    )
                }
            }
        }
    }
        }
        if (!state.expanded && state.serviceRunning) {
            ReadAloudCapsule(
                state = state,
                colors = colors,
                onPlayPause = onPlayPause,
                onExpand = onExpand,
                onClose = onStop,
                onBounds = onCapsuleBounds
            )
        }
    }
}

private enum class PlayerSheet {
    None,
    Chapter,
    Timer,
    Engine,
    Characters
}

private data class LyricsTarget(
    val key: String,
    val sequence: Int,
    val paragraphs: List<ReadAloudPlayerPanel.ParagraphUi>
)

private data class PlayerColors(
    val background: Color,
    val panel: Color,
    val panelStrong: Color,
    val panelBorder: Color,
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
    val panel = Color(ColorUtils.blendColors(palette.panel, android.graphics.Color.BLACK, 0.56f))
    val panelStrong = Color(ColorUtils.blendColors(palette.panelStrong, android.graphics.Color.BLACK, 0.66f))
    return PlayerColors(
        background = Color(ColorUtils.blendColors(palette.surface, android.graphics.Color.BLACK, 0.72f)),
        panel = panel,
        panelStrong = panelStrong,
        panelBorder = Color(ColorUtils.blendColors(palette.stroke, android.graphics.Color.WHITE, 0.24f)),
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
private fun ReadAloudCapsule(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    onBounds: (RectF) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val capsuleWidth = 188.dp
        val capsuleHeight = 60.dp
        val capsuleWidthPx = with(density) { capsuleWidth.toPx() }
        val capsuleHeightPx = with(density) { capsuleHeight.toPx() }
        val sidePx = with(density) { 18.dp.toPx() }
        val bottomPx = with(density) {
            (if (state.readMenuVisible) 210.dp else 28.dp).toPx()
        }
        val maxX = (widthPx - capsuleWidthPx - sidePx).coerceAtLeast(sidePx)
        val targetY = (heightPx - capsuleHeightPx - bottomPx).coerceAtLeast(sidePx)
        var offsetX by remember { mutableStateOf(sidePx) }
        var offsetY by remember { mutableStateOf(targetY) }
        var dragging by remember { mutableStateOf(false) }
        val animatedX by animateFloatAsState(
            targetValue = offsetX,
            animationSpec = tween(if (dragging) 1 else 260, easing = FastOutSlowInEasing),
            label = "readAloudCapsuleX"
        )
        val animatedY by animateFloatAsState(
            targetValue = offsetY,
            animationSpec = tween(if (dragging) 1 else 260, easing = FastOutSlowInEasing),
            label = "readAloudCapsuleY"
        )
        LaunchedEffect(widthPx, heightPx, state.readMenuVisible) {
            offsetX = if (offsetX + capsuleWidthPx / 2f < widthPx / 2f) sidePx else maxX
            if (state.readMenuVisible || offsetY > targetY) {
                offsetY = targetY
            }
        }
        Surface(
            modifier = Modifier
                .offset { IntOffset(animatedX.roundToInt(), animatedY.roundToInt()) }
                .width(capsuleWidth)
                .height(capsuleHeight)
                .onGloballyPositioned {
                    val bounds = it.boundsInRoot()
                    onBounds(RectF(bounds.left, bounds.top, bounds.right, bounds.bottom))
                }
                .pointerInput(widthPx, heightPx, state.readMenuVisible) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            offsetX = if (offsetX + capsuleWidthPx / 2f < widthPx / 2f) {
                                sidePx
                            } else {
                                maxX
                            }
                            offsetY = offsetY.coerceIn(sidePx, targetY)
                        },
                        onDragCancel = {
                            dragging = false
                            offsetY = offsetY.coerceIn(sidePx, targetY)
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(sidePx, maxX)
                        offsetY = (offsetY + dragAmount.y).coerceIn(sidePx, targetY)
                    }
                },
            shape = CircleShape,
            color = colors.panelStrong,
            border = BorderStroke(1.dp, colors.panelBorder),
            shadowElevation = 12.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.panel)
                        .clickable(onClick = onExpand)
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
                                preferThumb = true
                            )
                        }
                    )
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onPlayPause),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.92f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp),
                            contentDescription = null,
                            tint = Color.Black.copy(alpha = 0.86f),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "×",
                            color = Color.Transparent,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_close_x),
                            contentDescription = null,
                            tint = colors.primaryText,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
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
    onCueSelect: (Int) -> Unit,
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
                    onCueSelect = onCueSelect,
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
    onCueSelect: (Int) -> Unit,
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
                LyricCueBody(
                    state = state,
                    colors = colors,
                    compact = short,
                    maxParagraphs = if (short) 5 else 7,
                    currentMaxLines = if (short) 4 else 6,
                    textAlign = TextAlign.Start,
                    animateTextChanges = animateTextChanges,
                    onCueSelect = onCueSelect,
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
    onCueSelect: (Int) -> Unit,
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
        LyricCueBody(
            state = state,
            colors = colors,
            compact = compact,
            maxParagraphs = maxParagraphs,
            currentMaxLines = currentMaxLines,
            animateTextChanges = animateTextChanges,
            onCueSelect = onCueSelect,
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
private fun LyricCueBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    maxParagraphs: Int,
    currentMaxLines: Int,
    animateTextChanges: Boolean,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center
) {
    val cues = state.textCues.ifEmpty {
        listOf(
            ReadAloudPlayerPanel.TextCueUi(
                index = state.paragraphIndex.coerceAtLeast(1),
                text = state.paragraphText.ifBlank { "鏆傛棤褰撳墠娈佃惤" },
                current = true,
                key = state.paragraphKey.ifBlank { state.paragraphIndex.toString() },
                sequence = state.paragraphSequence,
                chapterPosition = ReadBook.durChapterPos
            )
        )
    }
    val listState = rememberLazyListState()
    var userSeeking by remember(state.chapterKey) { mutableStateOf(false) }
    var programmaticScroll by remember(state.chapterKey) { mutableStateOf(false) }
    val currentIndex = state.currentCueIndex.coerceIn(0, cues.lastIndex)
    val centerOffset = maxParagraphs / 2
    LaunchedEffect(state.chapterKey) {
        programmaticScroll = true
        listState.scrollToItem((currentIndex - centerOffset).coerceAtLeast(0))
        programmaticScroll = false
    }
    LaunchedEffect(state.chapterKey, currentIndex) {
        if (!userSeeking && cues.isNotEmpty()) {
            programmaticScroll = true
            listState.animateScrollToItem((currentIndex - centerOffset).coerceAtLeast(0))
            programmaticScroll = false
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            if (!programmaticScroll) {
                userSeeking = true
            }
        } else if (userSeeking) {
            delay(120)
            val layoutInfo = listState.layoutInfo
            val center = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            val targetIndex = layoutInfo.visibleItemsInfo
                .filter { it.index in cues.indices }
                .minByOrNull { item -> abs(item.offset + item.size / 2 - center) }
                ?.index
            val target = targetIndex?.let { cues.getOrNull(it) }
            userSeeking = false
            if (target != null && target.index - 1 != currentIndex) {
                onCueSelect(target.chapterPosition)
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 720.dp),
        contentPadding = PaddingValues(vertical = if (compact) 78.dp else 112.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
        horizontalAlignment = when (textAlign) {
            TextAlign.Start -> Alignment.Start
            TextAlign.End -> Alignment.End
            else -> Alignment.CenterHorizontally
        }
    ) {
        itemsIndexed(cues, key = { _, cue -> cue.key }) { _, cue ->
            LyricCueLine(
                cue = cue,
                colors = colors,
                compact = compact,
                currentMaxLines = currentMaxLines,
                textAlign = textAlign,
                animate = animateTextChanges,
                onClick = { onCueSelect(cue.chapterPosition) }
            )
        }
    }
}

@Composable
private fun LyricCueLine(
    cue: ReadAloudPlayerPanel.TextCueUi,
    colors: PlayerColors,
    compact: Boolean,
    currentMaxLines: Int,
    textAlign: TextAlign,
    animate: Boolean,
    onClick: () -> Unit
) {
    val emphasis by animateFloatAsState(
        targetValue = if (cue.current) 1f else 0f,
        animationSpec = tween(if (animate) 220 else 1),
        label = "readAloudCueEmphasis"
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
        text = cue.text,
        color = colors.primaryText.copy(alpha = 0.36f + emphasis * 0.58f),
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
        fontWeight = if (cue.current) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = if (cue.current) currentMaxLines else 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
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
    colors: PlayerColors,
    onProgressSeek: (Float) -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    var draggingProgress by remember(state.chapterKey) { mutableStateOf<Float?>(null) }
    val progress = draggingProgress ?: state.progress
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = { draggingProgress = it },
            onValueChangeFinished = {
                draggingProgress?.let(onProgressSeek)
                draggingProgress = null
            },
            enabled = state.paragraphCount > 1,
            modifier = Modifier
                .fillMaxWidth()
                .clip(actionShape),
            colors = SliderDefaults.colors(
                thumbColor = colors.primaryText,
                activeTrackColor = colors.primaryText,
                inactiveTrackColor = colors.panel,
                disabledThumbColor = colors.subtleText,
                disabledActiveTrackColor = colors.panel,
                disabledInactiveTrackColor = colors.panel
            )
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
                icon = R.drawable.ic_settings,
                text = "\u5f15\u64ce",
                selected = activeSheet == PlayerSheet.Engine,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Engine)
            }
            FeaturePill(
                icon = R.drawable.ic_bottom_person_e,
                selectedIcon = R.drawable.ic_bottom_person_s,
                text = "\u89d2\u8272",
                selected = activeSheet == PlayerSheet.Characters,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Characters)
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
        color = colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
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
    selectedIcon: Int = icon,
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
        color = if (selected) colors.accent else colors.panel,
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.panelBorder),
        shadowElevation = if (selected) 8.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(if (selected) selectedIcon else icon),
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
    onEngineSelect: (String) -> Unit,
    onOpenCharacters: () -> Unit,
    modifier: Modifier = Modifier
) {
    val panelShape = LocalContext.current.composePanelShape()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = panelShape,
        color = colors.panelStrong,
        border = BorderStroke(1.dp, colors.panelBorder),
        shadowElevation = 12.dp
    ) {
        AnimatedContent(
            targetState = sheet,
            transitionSpec = {
                (fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(180, easing = FastOutSlowInEasing)) { it / 10 })
                    .togetherWith(
                        fadeOut(tween(120, easing = FastOutSlowInEasing)) +
                                slideOutVertically(tween(140, easing = FastOutSlowInEasing)) { -it / 12 }
                    )
                    .using(SizeTransform(clip = false))
            },
            label = "readAloudSheetContent"
        ) { targetSheet ->
            when (targetSheet) {
                PlayerSheet.Chapter -> ChapterSheet(
                    state = state,
                    colors = colors,
                    onOpenChapterList = onOpenChapterList,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onChapterSelect = onChapterSelect
                )
                PlayerSheet.Timer -> TimerSheet(state, colors, onTimerChange)
                PlayerSheet.Engine -> EngineSheet(state, colors, onEngineSelect)
                PlayerSheet.Characters -> CharactersSheet(state, colors, onOpenCharacters)
                PlayerSheet.None -> Unit
            }
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
                .heightIn(min = 96.dp, max = 188.dp),
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
            chapter.current -> colors.accent
            chapter.volume -> colors.panel
            else -> colors.panel
        },
        border = BorderStroke(1.dp, if (chapter.current) colors.accent else colors.panelBorder)
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
        color = colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
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
                        color = if (selected) colors.accent else colors.panel
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
private fun EngineSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onEngineSelect: (String) -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "\u6717\u8bfb\u5f15\u64ce",
            color = colors.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp, max = 202.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.ttsEngines, key = { it.key }) { engine ->
                EngineRow(
                    engine = engine,
                    colors = colors,
                    shape = actionShape,
                    onClick = { onEngineSelect(engine.value) }
                )
            }
        }
    }
}

@Composable
private fun EngineRow(
    engine: ReadAloudPlayerPanel.TtsEngineUi,
    colors: PlayerColors,
    shape: Shape,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (engine.selected) colors.accent else colors.panel,
        border = BorderStroke(1.dp, if (engine.selected) colors.accent else colors.panelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = engine.title,
                    color = if (engine.selected) colors.accentText else colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = if (engine.selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = engine.subtitle,
                    color = if (engine.selected) colors.accentText.copy(alpha = 0.72f) else colors.subtleText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (engine.selected) {
                Text(
                    text = "\u5f53\u524d",
                    color = colors.accentText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun CharactersSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpenCharacters: () -> Unit
) {
    val context = LocalContext.current
    val actionShape = context.composeActionShape()
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u89d2\u8272",
                color = colors.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${state.characterPreview.size}",
                color = colors.subtleText,
                fontSize = 12.sp
            )
        }
        if (state.characterPreview.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "\u6682\u65e0\u89d2\u8272",
                    color = colors.secondaryText,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp, max = 188.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.characterPreview, key = { it.key }) { character ->
                    CharacterPreviewRow(character, colors, actionShape)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
        ) {
            SheetActionButton(
                text = "\u5b8c\u6574\u89d2\u8272\u9875",
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onOpenCharacters
            )
        }
    }
}

@Composable
private fun CharacterPreviewRow(
    character: ReadAloudPlayerPanel.CharacterPreviewUi,
    colors: PlayerColors,
    shape: Shape
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = shape,
        color = colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = character.role,
                color = colors.subtleText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(66.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.name,
                    color = colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = character.summary,
                    color = colors.subtleText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
