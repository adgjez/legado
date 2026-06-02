package io.legado.app.ui.book.read

import android.content.Context
import android.graphics.RectF
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.platform.LocalLayoutDirection
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
import io.legado.app.data.entities.AiReadAloudUsageRecord
import io.legado.app.data.entities.AiReadAloudRoleCache
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.ai.AiReadAloudBgmService
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.help.ai.AiReadAloudRolePreviewSegment
import io.legado.app.help.ai.AiReadAloudRoleState
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.ReadAloudSpeechPlanItem
import io.legado.app.help.readaloud.ReadAloudSpeechPlanner
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composeActionShape
import io.legado.app.lib.theme.composePanelShape
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.ui.book.read.config.SpeechVoiceRoutePickerDialog
import io.legado.app.ui.book.read.config.routeMatchesGroup
import io.legado.app.ui.book.read.page.entities.ReadAloudCue
import io.legado.app.ui.book.read.page.entities.ReadAloudTextCleaner
import io.legado.app.ui.book.read.page.entities.TextParagraph
import io.legado.app.ui.book.read.page.entities.buildReadAloudCues
import io.legado.app.ui.book.read.page.entities.indexForChapterPosition
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
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
        Scene,
        Text
    }

    enum class PanelPhase {
        Hidden,
        Collapsed,
        Opening,
        Expanded
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

    data class SceneSegmentUi(
        val index: Int,
        val key: String,
        val text: String,
        val roleType: String,
        val characterId: Long,
        val characterName: String,
        val avatar: String,
        val emotionName: String,
        val leftSide: Boolean,
        val narrator: Boolean,
        val current: Boolean,
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
        val key: String,
        val group: SpeechVoiceEngineGroup
    )

    data class CharacterPreviewUi(
        val id: Long,
        val name: String,
        val role: String,
        val summary: String,
        val key: String
    )

    data class AudioInfoUi(
        val enabled: Boolean = false,
        val cacheKey: String = "",
        val status: String = "",
        val bgmCount: Int = 0,
        val soundEffectCount: Int = 0,
        val bgmNames: List<String> = emptyList(),
        val soundEffectNames: List<String> = emptyList(),
        val elapsedMillis: Long = 0L,
        val requestCount: Int = 0,
        val inputTokens: Int = 0,
        val cachedInputTokens: Int = 0,
        val outputTokens: Int = 0,
        val totalTokens: Int = 0,
        val error: String = "",
        val fingerprint: String = ""
    )

    data class PlayerUiState(
        val bookName: String = "",
        val author: String = "",
        val coverUrl: String? = null,
        val sourceOrigin: String? = null,
        val chapterTitle: String = "",
        val chapterIndexText: String = "",
        val playing: Boolean = false,
        val playbackPhase: String = ReadAloudPlaybackState.PHASE_STOPPED,
        val playbackBusy: Boolean = false,
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
        val speechRoute: SpeechRoute = SpeechRoute(),
        val characterPreview: List<CharacterPreviewUi> = emptyList(),
        val audioInfo: AudioInfoUi = AudioInfoUi(),
        val nearbyParagraphs: List<ParagraphUi> = emptyList(),
        val textCues: List<TextCueUi> = emptyList(),
        val sceneSegments: List<SceneSegmentUi> = emptyList(),
        val currentCueIndex: Int = 0,
        val chapterKey: String = "",
        val paragraphKey: String = "",
        val paragraphSequence: Int = 0,
        val focusText: FocusTextUi = FocusTextUi(),
        val speechRate: Int = AppConfig.ttsSpeechRate.coerceIn(0, 45),
        val followSystemSpeechRate: Boolean = AppConfig.ttsFlowSys,
        val mode: DisplayMode = DisplayMode.Immersive,
        val foregroundActive: Boolean = true,
        val expanded: Boolean = false,
        val opening: Boolean = false,
        val panelPhase: PanelPhase = PanelPhase.Hidden,
        val readMenuVisible: Boolean = false,
        val readMenuAvoidBounds: RectF? = null,
        val openToken: Int = 0,
        val roleStatusText: String = "",
        val roleStatusRunning: Boolean = false,
        val roleStatusError: Boolean = false,
        val roleStatusVisible: Boolean = false,
        val roleDetailVisible: Boolean = false,
        val roleBlockingCurrentContent: Boolean = false,
        val roleBlockingText: String = "",
        val roleState: AiReadAloudRoleState? = null
    )

    private val composeView = ComposeView(context)
    private var callBack: CallBack? = null
    private var dismissedForCurrentRun = false
    private var foregroundActive = true
    private var lastChapterStart = 0
    private var roleStatusText = ""
    private var roleStatusRunning = false
    private var roleStatusError = false
    private var roleStatusUntil = 0L
    private var roleState: AiReadAloudRoleState? = null
    private var roleDetailCollapsed = false
    private var roleDetailClosed = false
    private var roleDetailKey = ""
    private var playbackPhase = ReadAloudPlaybackState.PHASE_STOPPED
    private var playbackMessage = ""
    private var playbackActualPlaying: Boolean? = null
    private var playbackBuffering = false
    private var playbackCueIndex = -1
    private var playbackChapterIndex = -1
    private var expanded = false
    private var opening = false
    private var panelPhase = PanelPhase.Hidden
    private var readMenuVisible = false
    private var readMenuAvoidBounds: RectF? = null
    private var openToken = 0
    private val capsuleBounds = RectF()
    private var capsulePosition by mutableStateOf(CapsulePositionState())
    private var switchingTtsEngine = false
    private var pendingTtsEngineSwitch: PendingTtsEngineSwitch? = null
    private var chapterModelCache: PlayerChapterModel? = null
    private var ttsEngineOptionsCacheKey = ""
    private var ttsEngineOptionsCache: List<TtsEngineUi> = emptyList()

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
                onExpand = { openFromBottom(force = true) },
                onStop = ::stopReadAloud,
                onPlayPause = { callBack?.onClickReadAloud() },
                onModeChange = ::setMode,
                onOpenChapterList = {
                    callBack?.openChapterList()
                },
                onPreviousChapter = { moveChapterFromPanel(-1) },
                onNextChapter = { moveChapterFromPanel(1) },
                onChapterSelect = ::selectChapterFromPanel,
                onOpenSettings = ::openReadAloudSetting,
                onTimerChange = ::setTimer,
                onEngineSelect = ::selectTtsEngine,
                onProgressSeek = ::seekToParagraphProgress,
                onCueSelect = ::seekToChapterPosition,
                capsulePosition = capsulePosition,
                onCapsulePositionChange = ::updateCapsulePosition,
                onCapsuleBounds = ::updateCapsuleBounds,
                onOpenCharacters = { callBack?.openBookCharacters() },
                onHideRoleDetail = ::hideRoleDetail,
                onOpenRoleDetail = ::openRoleDetail,
                onDismissRoleStatus = ::dismissRoleStatus,
                onRetryRoleAssignment = ::retryRoleAssignment
            )
        }
    }

    fun attach(lifecycleOwner: LifecycleOwner, callBack: CallBack) {
        this.callBack = callBack
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun open(force: Boolean = true) {
        openFromBottom(force)
    }

    fun openFromBottom(force: Boolean = true) {
        if (force) {
            dismissedForCurrentRun = false
        }
        showPanel(expand = true, animateFromBottom = true)
    }

    fun onAloudState(status: Int, autoExpand: Boolean = true) {
        when (status) {
            io.legado.app.constant.Status.PLAY -> {
                switchingTtsEngine = false
                if (playbackPhase in setOf(
                        ReadAloudPlaybackState.PHASE_PAUSED,
                        ReadAloudPlaybackState.PHASE_STOPPED,
                        ReadAloudPlaybackState.PHASE_ERROR
                    )
                ) {
                    playbackPhase = ReadAloudPlaybackState.PHASE_PREPARING
                    playbackMessage = ""
                    playbackActualPlaying = null
                    playbackBuffering = true
                }
                syncPlaybackUiState()
                if (autoExpand && !dismissedForCurrentRun && (visibility != VISIBLE || !expanded)) {
                    showPanel(animateFromBottom = true)
                }
            }

            io.legado.app.constant.Status.PAUSE -> {
                switchingTtsEngine = false
                playbackPhase = ReadAloudPlaybackState.PHASE_PAUSED
                playbackMessage = ""
                playbackActualPlaying = false
                playbackBuffering = false
                syncPlaybackUiState()
            }
            io.legado.app.constant.Status.STOP -> {
                if (switchingTtsEngine) {
                    val pendingSwitch = pendingTtsEngineSwitch
                    if (pendingSwitch?.wasPlaying == true) {
                        pendingTtsEngineSwitch = null
                        ReadAloud.play(
                            context = context,
                            play = true,
                            pageIndex = pendingSwitch.pageIndex,
                            startPos = pendingSwitch.startPos
                        )
                    } else {
                        switchingTtsEngine = false
                        pendingTtsEngineSwitch = null
                    }
                    refresh()
                    return
                }
                playbackPhase = ReadAloudPlaybackState.PHASE_STOPPED
                playbackMessage = ""
                playbackActualPlaying = false
                playbackBuffering = false
                playbackCueIndex = -1
                playbackChapterIndex = -1
                dismissedForCurrentRun = false
                hidePanel()
            }
        }
    }

    fun onTtsProgress(chapterStart: Int) {
        lastChapterStart = chapterStart.coerceAtLeast(0)
        refresh()
    }

    fun onChapterContentChanged() {
        chapterModelCache = null
        if (visibility != VISIBLE && !BaseReadAloudService.isRun) return
        lastChapterStart = ReadBook.durChapterPos.coerceAtLeast(0)
        refresh()
    }

    fun onPlaybackState(state: ReadAloudPlaybackState) {
        val currentChapterIndex = ReadBook.curTextChapter?.chapter?.index ?: ReadBook.durChapterIndex
        if (state.chapterIndex >= 0 && state.chapterIndex != currentChapterIndex) return
        playbackPhase = state.phase
        playbackMessage = state.message
        playbackActualPlaying = state.playing
        playbackBuffering = state.busy
        playbackCueIndex = state.cueIndex
        playbackChapterIndex = state.chapterIndex
        syncPlaybackUiState()
    }

    fun onAiRoleState(state: AiReadAloudRoleState) {
        val currentBookUrl = ReadBook.book?.bookUrl ?: return
        val currentChapterIndex = ReadBook.curTextChapter?.chapter?.index ?: return
        if (state.bookUrl != currentBookUrl) return
        if (state.stage != AiReadAloudRoleState.STAGE_CURRENT ||
            state.chapterIndex != currentChapterIndex
        ) {
            return
        }
        val nextKey = "${state.bookUrl}:${state.chapterIndex}:${state.stage}"
        if (nextKey != roleDetailKey) {
            roleDetailKey = nextKey
            roleDetailCollapsed = true
            roleDetailClosed = false
        }
        roleState = state
        roleStatusText = buildRoleStatusText(state)
        roleStatusRunning = state.running
        roleStatusError = state.status == AiReadAloudRoleState.STATUS_FAILED
        roleStatusUntil = Long.MAX_VALUE
        refresh()
    }

    private fun hideRoleDetail() {
        roleDetailCollapsed = true
        refresh()
    }

    private fun openRoleDetail() {
        roleDetailCollapsed = false
        roleDetailClosed = false
        refresh()
    }

    private fun dismissRoleStatus() {
        if (roleStatusRunning) return
        roleStatusText = ""
        roleStatusError = false
        roleStatusUntil = 0L
        roleState = null
        roleDetailCollapsed = false
        roleDetailClosed = true
        refresh()
    }

    private fun retryRoleAssignment() {
        val bookUrl = ReadBook.book?.bookUrl ?: return
        val chapter = ReadBook.curTextChapter ?: return
        AiReadAloudRoleService.clearChapterCache(bookUrl, chapter.chapter.index)
        chapterModelCache = null
        roleDetailCollapsed = true
        roleDetailClosed = false
        roleStatusText = "当前章节重新分配角色中"
        roleStatusRunning = true
        roleStatusError = false
        roleStatusUntil = Long.MAX_VALUE
        val pageIndex = ReadBook.durPageIndex
        val startPos = (ReadBook.durChapterPos - chapter.getReadLength(pageIndex)).coerceAtLeast(0)
        ReadAloud.play(context, play = true, pageIndex = pageIndex, startPos = startPos)
        refresh()
    }

    private fun selectChapterFromPanel(index: Int) {
        val chapterCount = ReadBook.chapterSize
        if (chapterCount <= 0) return
        val targetIndex = index.coerceIn(0, chapterCount - 1)
        prepareChapterNavigation(targetIndex)
        ReadBook.openChapter(targetIndex, upContent = true) {
            onChapterContentChanged()
        }
    }

    private fun moveChapterFromPanel(delta: Int) {
        val chapterCount = ReadBook.chapterSize
        if (chapterCount <= 0) return
        val targetIndex = (ReadBook.durChapterIndex + delta).coerceIn(0, chapterCount - 1)
        if (targetIndex == ReadBook.durChapterIndex) return
        prepareChapterNavigation(targetIndex)
        val moved = if (delta < 0) {
            ReadBook.moveToPrevChapter(upContent = true, toLast = false)
        } else {
            ReadBook.moveToNextChapter(true)
        }
        if (!moved) {
            refresh()
            return
        }
        post { onChapterContentChanged() }
    }

    private fun prepareChapterNavigation(chapterIndex: Int) {
        chapterModelCache = null
        roleState = null
        roleStatusText = ""
        roleStatusRunning = false
        roleStatusError = false
        roleStatusUntil = 0L
        roleDetailCollapsed = false
        roleDetailClosed = false
        playbackCueIndex = -1
        playbackChapterIndex = chapterIndex
        lastChapterStart = 0
        val bookUrl = ReadBook.book?.bookUrl.orEmpty()
        val chapterCount = ReadBook.chapterSize.coerceAtLeast(chapterIndex + 1)
        val title = runCatching {
            ReadBook.book?.let { appDb.bookChapterDao.getChapter(it.bookUrl, chapterIndex)?.title }
        }.getOrNull().orEmpty().ifBlank { "第 ${chapterIndex + 1} 章" }
        uiState = uiState.copy(
            chapterTitle = title,
            chapterIndexText = "${chapterIndex + 1}/$chapterCount",
            chapterIndex = chapterIndex,
            chapterCount = chapterCount,
            chapterPreview = buildChapterPreview(bookUrl, chapterIndex, chapterCount),
            progress = 0f,
            progressText = "0/0",
            paragraphText = "章节加载中",
            paragraphIndex = 0,
            paragraphCount = 0,
            nearbyParagraphs = emptyList(),
            textCues = emptyList(),
            sceneSegments = emptyList(),
            currentCueIndex = 0,
            chapterKey = "$bookUrl:$chapterIndex",
            paragraphKey = "$bookUrl:$chapterIndex:loading",
            paragraphSequence = chapterIndex * 100_000,
            focusText = FocusTextUi(
                key = "$bookUrl:$chapterIndex:loading",
                sequence = chapterIndex * 100_000,
                text = "章节加载中"
            ),
            roleBlockingCurrentContent = false,
            roleBlockingText = ""
        )
    }

    private fun buildRoleStatusText(state: AiReadAloudRoleState): String {
        val prefix = if (state.stage == AiReadAloudRoleState.STAGE_NEXT) "下一章节" else "当前章节"
        val detail = when {
            state.error.isNotBlank() && state.status == AiReadAloudRoleState.STATUS_FAILED -> state.error
            state.createdCharacterCount > 0 -> "新增 ${state.createdCharacterCount} 个角色"
            state.segmentCount > 0 -> "${state.segmentCount} 个片段"
            else -> ""
        }
        val message = state.message.ifBlank {
            when (state.status) {
                AiReadAloudRoleState.STATUS_RUNNING -> "${prefix}分配角色中"
                AiReadAloudRoleState.STATUS_SUCCESS -> "${prefix}角色分配完成"
                AiReadAloudRoleState.STATUS_FALLBACK -> "已使用默认分角色"
                AiReadAloudRoleState.STATUS_FAILED -> "${prefix}角色分配失败"
                else -> "${prefix}角色已分配"
            }
        }
        return listOf(message, detail).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun onTimerChanged(minute: Int) {
        uiState = uiState.copy(timerMinute = minute)
    }

    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
        syncPlaybackUiState()
    }

    private fun syncPlaybackUiState() {
        val servicePlaying = playbackActualPlaying ?: BaseReadAloudService.isPlay()
        uiState = uiState.copy(
            playing = servicePlaying,
            playbackPhase = playbackPhase,
            playbackBusy = playbackBuffering ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_PREPARING ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_BUFFERING,
            serviceRunning = BaseReadAloudService.isRun,
            foregroundActive = foregroundActive && visibility == VISIBLE
        )
    }

    fun setReadMenuVisible(visible: Boolean) {
        readMenuVisible = visible
        if (!visible) {
            readMenuAvoidBounds = null
        }
        uiState = uiState.copy(
            readMenuVisible = visible,
            readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF)
        )
    }

    fun setReadMenuAvoidBounds(bounds: RectF?) {
        readMenuVisible = bounds != null
        readMenuAvoidBounds = bounds?.let(::RectF)
        uiState = uiState.copy(
            readMenuVisible = readMenuVisible,
            readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF)
        )
    }

    fun isExpanded(): Boolean = visibility == VISIBLE && expanded

    fun isFullPanelActive(): Boolean {
        return visibility == VISIBLE && (panelPhase == PanelPhase.Opening || panelPhase == PanelPhase.Expanded)
    }

    fun close() {
        closeByUser()
    }

    fun refresh() {
        uiState = buildState(uiState.mode)
    }

    private fun showPanel(expand: Boolean = true, animateFromBottom: Boolean = true) {
        post {
            val wasVisible = visibility == VISIBLE
            val wasExpanded = expanded
            if (expand && animateFromBottom && !AppConfig.isEInkMode) {
                expanded = false
                opening = true
                panelPhase = PanelPhase.Opening
                openToken++
                uiState = uiState.copy(
                    foregroundActive = foregroundActive,
                    expanded = false,
                    opening = true,
                    panelPhase = PanelPhase.Opening,
                    readMenuVisible = readMenuVisible,
                    readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
                    openToken = openToken
                )
                visibility = VISIBLE
                bringToFront()
                ViewCompat.requestApplyInsets(this)
                ViewCompat.requestApplyInsets(composeView)
                postOnAnimation {
                    expanded = true
                    opening = false
                    panelPhase = PanelPhase.Expanded
                    uiState = buildState(uiState.mode).copy(
                        foregroundActive = foregroundActive,
                        expanded = true,
                        opening = false,
                        panelPhase = PanelPhase.Expanded,
                        readMenuVisible = readMenuVisible,
                        readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
                        openToken = openToken
                    )
                    if (!wasVisible || !wasExpanded) {
                        callBack?.onReadAloudPlayerVisibilityChanged(true)
                    }
                }
                return@post
            }
            expanded = expand
            opening = false
            panelPhase = if (expand) PanelPhase.Expanded else PanelPhase.Collapsed
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
                opening = opening,
                panelPhase = panelPhase,
                readMenuVisible = readMenuVisible,
                readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
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
        opening = false
        panelPhase = PanelPhase.Hidden
        visibility = GONE
        capsuleBounds.setEmpty()
        uiState = buildState(uiState.mode).copy(
            foregroundActive = false,
            expanded = false,
            opening = false,
            panelPhase = PanelPhase.Hidden
        )
        if (wasVisible) {
            callBack?.onReadAloudPlayerVisibilityChanged(false)
        }
    }

    private fun closeByUser() {
        if (BaseReadAloudService.isRun) {
            dismissedForCurrentRun = true
            expanded = false
            opening = false
            panelPhase = PanelPhase.Collapsed
            uiState = buildState(uiState.mode).copy(
                foregroundActive = foregroundActive,
                expanded = false,
                opening = false,
                panelPhase = PanelPhase.Collapsed,
                readMenuVisible = readMenuVisible,
                readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
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

    private fun updateCapsulePosition(x: Float, y: Float) {
        val current = capsulePosition
        if (current.x != x || current.y != y) {
            capsulePosition = CapsulePositionState(x, y)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility == VISIBLE && !expanded && !uiState.roleStatusVisible && !uiState.roleDetailVisible) {
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
        val route = SpeechRoute.fromTtsEngineValue(value)
        val wasRunning = BaseReadAloudService.isRun
        val wasPlaying = BaseReadAloudService.isPlay()
        val pageIndex = ReadBook.durPageIndex
        val startPos = ReadBook.curTextChapter
            ?.getReadLength(pageIndex)
            ?.let { (ReadBook.durChapterPos - it).coerceAtLeast(0) }
            ?: 0
        ReadBook.book?.setTtsEngine(null)
        AppConfig.ttsEngine = value
        invalidateTtsEngineOptions()
        if (wasRunning) {
            switchingTtsEngine = true
            pendingTtsEngineSwitch = PendingTtsEngineSwitch(
                wasPlaying = wasPlaying,
                pageIndex = pageIndex,
                startPos = startPos
            )
        } else {
            switchingTtsEngine = false
            pendingTtsEngineSwitch = null
        }
        ReadAloud.upReadAloudClass()
        route.engineValue.toLongOrNull()
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
        if (uiState.mode != mode) {
            uiState = uiState.copy(mode = mode)
        }
    }

    private fun seekToParagraphProgress(progress: Float) {
        val cues = buildChapterModel()?.cues.orEmpty()
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

    private fun buildChapterModel(): PlayerChapterModel? {
        val book = ReadBook.book ?: return null
        val chapter = ReadBook.curTextChapter ?: return null
        val chapterSequence = chapter.chapter.index
        val readAloudByPage = context.getPrefBoolean(PreferKey.readAloudByPage)
        val paragraphs = chapter.getParagraphs(false)
        val baseCues = chapter.buildReadAloudCues(readAloudByPage)
        val cueTexts = baseCues.map { cue -> cue.text }
        val exactRoleCacheKey = AiReadAloudRoleService.cacheKeyFor(book, chapter, cueTexts)
        val exactRoleCache = exactRoleCacheKey?.let { appDb.aiReadAloudRoleCacheDao.get(it) }
        val roleCache = AiReadAloudRoleService.cacheForPlayback(book, chapter, cueTexts)
            ?: exactRoleCache
        val roleCacheKey = roleCache?.cacheKey ?: exactRoleCacheKey
        val roleCacheReady = roleCache?.status == AiReadAloudRoleCache.STATUS_SUCCESS &&
                roleCache.segmentsJson.isNotBlank()
        val roleCacheRunning = !roleCacheReady &&
                exactRoleCache?.status == AiReadAloudRoleCache.STATUS_RUNNING &&
                AiReadAloudRoleService.isRunningCacheActive(exactRoleCacheKey, exactRoleCache.updatedAt)
        val audioInfo = buildAudioInfo(book.bookUrl, chapterSequence)
        val coverUrl = book.getDisplayCover()
        val cueFingerprint = buildString {
            append(baseCues.size)
            append(':')
            append(baseCues.firstOrNull()?.key.orEmpty())
            append(':')
            append(baseCues.lastOrNull()?.key.orEmpty())
            append(':')
            append(baseCues.sumOf { it.text.length })
        }
        val modelKey = listOf(
            book.bookUrl,
            book.name,
            book.author,
            book.origin,
            coverUrl.orEmpty(),
            chapterSequence.toString(),
            chapter.chapter.url.orEmpty(),
            chapter.chapter.title,
            chapter.chaptersSize.toString(),
            ReadBook.chapterSize.toString(),
            readAloudByPage.toString(),
            AppConfig.aiReadAloudRoleEnabled.toString(),
            cueFingerprint,
            roleCacheKey.orEmpty(),
            roleCache?.status.orEmpty(),
            roleCache?.updatedAt?.toString().orEmpty(),
            roleCache?.characterHash.orEmpty(),
            roleCache?.voiceHash.orEmpty(),
            roleCache?.segmentsJson?.length?.toString().orEmpty(),
            roleCache?.segmentsJson?.hashCode()?.toString().orEmpty(),
            audioInfo.fingerprint
        ).joinToString("|")
        chapterModelCache?.takeIf { it.key == modelKey }?.let { return it }
        val chapterKey = "${book.bookUrl}:$chapterSequence"
        val speechPlan = ReadAloudSpeechPlanner.build(
            bookUrl = book.bookUrl,
            chapter = chapter,
            baseCues = baseCues,
            multiRoleEnabled = AppConfig.aiReadAloudRoleEnabled,
            roleCacheKey = roleCacheKey
        )
        val totalLength = chapter.lastPage
            ?.let { it.chapterPosition + it.charSize }
            ?.coerceAtLeast(1)
            ?: 1
        return PlayerChapterModel(
            key = modelKey,
            bookName = book.name.ifBlank { context.getString(R.string.book_name) },
            author = book.author,
            coverUrl = coverUrl,
            sourceOrigin = book.origin,
            chapterTitle = chapter.chapter.title.ifBlank { "当前章节" },
            chapterIndexText = "${chapterSequence + 1}/${chapter.chaptersSize.coerceAtLeast(chapterSequence + 1)}",
            chapterSequence = chapterSequence,
            chapterKey = chapterKey,
            chapterCount = ReadBook.chapterSize.coerceAtLeast(chapterSequence + 1),
            totalLength = totalLength,
            paragraphs = paragraphs,
            baseCues = baseCues,
            cues = speechPlan.cues,
            cuePositions = speechPlan.cues.map { it.chapterPosition }.toIntArray(),
            speechItems = speechPlan.items,
            textCues = speechPlan.cues.toTextCueUi(chapterKey, chapterSequence),
            sceneSegments = speechPlan.items.toSceneSegmentUi(chapterKey),
            roleCacheKey = roleCacheKey,
            roleCacheReady = roleCacheReady,
            roleCacheRunning = roleCacheRunning,
            chapterPreview = buildChapterPreview(book.bookUrl, chapterSequence, ReadBook.chapterSize),
            characterPreview = buildCharacterPreview(book.bookUrl),
            audioInfo = audioInfo
        ).also {
            chapterModelCache = it
        }
    }

    private fun buildState(mode: DisplayMode): PlayerUiState {
        val model = buildChapterModel()
        val paragraphs = model?.paragraphs.orEmpty()
        val cues = model?.cues.orEmpty()
        val chapterSequence = model?.chapterSequence ?: ReadBook.durChapterIndex
        val chapterKey = model?.chapterKey ?: "${ReadBook.book?.bookUrl.orEmpty()}:$chapterSequence"
        val totalLength = model?.totalLength ?: 1
        val playbackCue = model?.cues?.getOrNull(playbackCueIndex)
            ?.takeIf { playbackChapterIndex < 0 || playbackChapterIndex == chapterSequence }
        val chapterStart = when {
            playbackCue != null &&
                    playbackPhase != ReadAloudPlaybackState.PHASE_STOPPED &&
                    playbackPhase != ReadAloudPlaybackState.PHASE_ERROR -> playbackCue.chapterPosition
            lastChapterStart > 0 -> lastChapterStart
            else -> ReadBook.durChapterPos
        }.coerceIn(0, totalLength)
        val paragraphIndex = paragraphs.indexForChapterPosition(chapterStart)
        val cueIndex = model?.indexForChapterPosition(chapterStart)
            ?: cues.indexForChapterPosition(chapterStart)
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
        val nearby = emptyList<ParagraphUi>()
        val textCues = model?.textCues.orEmpty()
        val sceneSegments = model?.sceneSegments.orEmpty()
        val currentRoleState = roleState?.takeIf {
            it.stage == AiReadAloudRoleState.STAGE_CURRENT &&
                    it.bookUrl == ReadBook.book?.bookUrl &&
                    it.chapterIndex == chapterSequence
        }
        val currentRoleRunning = currentRoleState?.running == true || model?.roleCacheRunning == true
        val roleBlockingCurrentContent = AppConfig.aiReadAloudRoleEnabled &&
                model?.roleCacheKey != null &&
                currentRoleRunning &&
                !model.roleCacheReady
        val speechRoute = SpeechRoute.fromTtsEngineValue(ReadAloud.ttsEngine)
        val timerMinute = BaseReadAloudService.timeMinute
        val servicePlaying = playbackActualPlaying ?: BaseReadAloudService.isPlay()
        val roleEventVisible = roleStatusText.isNotBlank() &&
                !roleDetailClosed &&
                (roleStatusRunning || roleStatusUntil > System.currentTimeMillis())
        return PlayerUiState(
            bookName = model?.bookName.orEmpty(),
            author = model?.author.orEmpty(),
            coverUrl = model?.coverUrl,
            sourceOrigin = model?.sourceOrigin,
            chapterTitle = model?.chapterTitle.orEmpty(),
            chapterIndexText = model?.chapterIndexText.orEmpty(),
            playing = servicePlaying,
            playbackPhase = playbackPhase,
            playbackBusy = playbackBuffering ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_PREPARING ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_BUFFERING,
            serviceRunning = BaseReadAloudService.isRun,
            timerMinute = timerMinute,
            progress = paragraphProgress,
            progressText = paragraphProgressText,
            paragraphText = paragraphText,
            paragraphIndex = if (cueIndex >= 0) cueIndex + 1 else 0,
            paragraphCount = paragraphCount,
            chapterIndex = chapterSequence.coerceAtLeast(0),
            chapterCount = model?.chapterCount ?: ReadBook.chapterSize.coerceAtLeast(chapterSequence + 1),
            chapterPreview = model?.chapterPreview.orEmpty(),
            ttsEngines = buildTtsEngineOptions(),
            speechRoute = speechRoute,
            characterPreview = model?.characterPreview.orEmpty(),
            audioInfo = model?.audioInfo ?: AudioInfoUi(enabled = AppConfig.aiReadAloudBgmEnabled),
            nearbyParagraphs = nearby,
            textCues = textCues,
            sceneSegments = sceneSegments,
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
            opening = opening,
            panelPhase = panelPhase,
            readMenuVisible = readMenuVisible,
            readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
            openToken = openToken,
            roleStatusText = roleStatusText,
            roleStatusRunning = roleStatusRunning,
            roleStatusError = roleStatusError,
            roleStatusVisible = roleEventVisible && roleDetailCollapsed,
            roleDetailVisible = roleEventVisible && !roleDetailCollapsed,
            roleBlockingCurrentContent = roleBlockingCurrentContent,
            roleBlockingText = currentRoleState?.message
                ?.ifBlank { roleStatusText }
                ?.ifBlank { "当前章节角色分配中" }
                ?: "当前章节角色分配中",
            roleState = roleState
        )
    }

    private fun PlayerChapterModel.indexForChapterPosition(chapterPosition: Int): Int {
        if (cuePositions.isEmpty()) return -1
        var low = 0
        var high = cuePositions.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = cuePositions[mid]
            when {
                value < chapterPosition -> low = mid + 1
                value > chapterPosition -> high = mid - 1
                else -> return mid
            }
        }
        return (low - 1).coerceIn(0, cuePositions.lastIndex)
    }

    private fun List<TextParagraph>.indexForChapterPosition(chapterPosition: Int): Int {
        if (isEmpty()) return -1
        var low = 0
        var high = lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val paragraph = this[mid]
            when {
                chapterPosition < paragraph.chapterPosition -> high = mid - 1
                chapterPosition > paragraph.chapterIndices.last -> low = mid + 1
                else -> return mid
            }
        }
        return (low - 1).coerceIn(0, lastIndex)
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
        chapterKey: String,
        chapterSequence: Int
    ): List<TextCueUi> {
        return mapIndexed { index, cue ->
            TextCueUi(
                index = index + 1,
                text = cue.text.cleanReadAloudText(),
                current = false,
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
        val cacheKey = ReadAloud.ttsEngine.orEmpty()
        if (ttsEngineOptionsCacheKey == cacheKey && ttsEngineOptionsCache.isNotEmpty()) {
            return ttsEngineOptionsCache
        }
        val currentRoute = SpeechRoute.fromTtsEngineValue(ReadAloud.ttsEngine)
        return SpeechVoiceCatalogRepository
            .allGroups(context, runCatching { appDb.httpTTSDao.all }.getOrDefault(emptyList()))
            .map { group ->
            TtsEngineUi(
                    title = group.title,
                    subtitle = group.subtitle,
                    value = group.engineValue,
                    selected = routeMatchesGroup(currentRoute, group),
                    key = group.key,
                    group = group
            )
        }.also {
            ttsEngineOptionsCacheKey = cacheKey
            ttsEngineOptionsCache = it
        }
    }

    private fun invalidateTtsEngineOptions() {
        ttsEngineOptionsCacheKey = ""
        ttsEngineOptionsCache = emptyList()
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

    private fun buildAudioInfo(bookUrl: String?, chapterIndex: Int): AudioInfoUi {
        if (bookUrl.isNullOrBlank() || chapterIndex < 0) {
            return AudioInfoUi(enabled = AppConfig.aiReadAloudBgmEnabled)
        }
        val cached = runCatching {
            AiReadAloudBgmService.cachedAudioInfoForChapter(bookUrl, chapterIndex)
        }.getOrNull()
        val usage = runCatching {
            appDb.aiReadAloudUsageRecordDao
                .list(AiReadAloudUsageRecord.TYPE_AUDIO, bookUrl, limit = 80)
                .firstOrNull { it.chapterIndex == chapterIndex }
        }.getOrNull()
        val fingerprint = buildString {
            append(AppConfig.aiReadAloudBgmEnabled)
            append('|')
            append(cached?.cacheKey.orEmpty())
            append('|')
            append(cached?.status.orEmpty())
            append('|')
            append(cached?.assignmentCount ?: 0)
            append('|')
            append(cached?.soundEffectCount ?: 0)
            append('|')
            append(cached?.updatedAt ?: 0L)
            append('|')
            append(usage?.id ?: 0L)
            append('|')
            append(usage?.totalTokens ?: 0)
        }
        return AudioInfoUi(
            enabled = AppConfig.aiReadAloudBgmEnabled,
            cacheKey = cached?.cacheKey.orEmpty(),
            status = cached?.status.orEmpty(),
            bgmCount = cached?.assignmentCount ?: 0,
            soundEffectCount = cached?.soundEffectCount ?: 0,
            bgmNames = cached?.bgmTrackNames.orEmpty(),
            soundEffectNames = cached?.soundEffectTrackNames.orEmpty(),
            elapsedMillis = usage?.elapsedMillis ?: 0L,
            requestCount = usage?.requestCount ?: 0,
            inputTokens = usage?.inputTokens ?: 0,
            cachedInputTokens = usage?.cachedInputTokens ?: 0,
            outputTokens = usage?.outputTokens ?: 0,
            totalTokens = usage?.totalTokens ?: 0,
            error = usage?.error.orEmpty(),
            fingerprint = fingerprint
        )
    }

    private fun List<ReadAloudSpeechPlanItem>.toSceneSegmentUi(
        chapterKey: String
    ): List<SceneSegmentUi> {
        return map { item ->
            SceneSegmentUi(
                index = item.index,
                key = "$chapterKey:scene:${item.index}:${item.sourceCueIndex}:${item.sourceStart}:${item.sourceEnd}",
                text = item.cue.text.cleanReadAloudText(),
                roleType = item.roleType,
                characterId = item.characterId,
                characterName = item.characterName,
                avatar = item.avatar,
                emotionName = item.emotionName,
                leftSide = item.leftSide,
                narrator = item.narrator,
                current = false,
                chapterPosition = item.cue.chapterPosition
            )
        }
    }

    private fun buildSceneSegments(
        bookUrl: String?,
        chapterIndex: Int,
        cueIndex: Int,
        cue: ReadAloudCue?,
        chapterKey: String
    ): List<SceneSegmentUi> {
        val text = cue?.text?.cleanReadAloudText().orEmpty()
        if (text.isBlank()) return emptyList()
        val characters = runCatching {
            appDb.bookCharacterDao.characters(bookUrl.orEmpty())
        }.getOrDefault(emptyList())
        val byId = characters.associateBy { it.id }
        val byName = characters.associateBy { it.name }
        val segments = AiReadAloudRoleService.segmentsForCue(bookUrl, chapterIndex, cueIndex, text)
            .filter { it.start < text.length }
            .map { it.copy(start = it.start.coerceIn(0, text.length), end = it.end.coerceIn(0, text.length)) }
            .filter { it.start < it.end }
        if (segments.isEmpty()) {
            return listOf(
                SceneSegmentUi(
                    index = cueIndex,
                    key = "$chapterKey:scene:$cueIndex:narrator",
                    text = text,
                    roleType = "narrator",
                    characterId = 0L,
                    characterName = "旁白",
                    avatar = "",
                    emotionName = "",
                    leftSide = false,
                    narrator = true,
                    current = true,
                    chapterPosition = cue?.chapterPosition ?: ReadBook.durChapterPos
                )
            )
        }
        val result = mutableListOf<SceneSegmentUi>()
        var cursor = 0
        fun addNarrator(start: Int, end: Int) {
            val part = text.substring(start, end).cleanReadAloudText()
            if (part.isBlank()) return
            result += SceneSegmentUi(
                index = result.size,
                key = "$chapterKey:scene:$cueIndex:narrator:$start:$end",
                text = part,
                roleType = "narrator",
                characterId = 0L,
                characterName = "旁白",
                avatar = "",
                emotionName = "",
                leftSide = false,
                narrator = true,
                current = false,
                chapterPosition = (cue?.chapterPosition ?: 0) + start
            )
        }
        segments.forEach { segment ->
            if (segment.start > cursor) addNarrator(cursor, segment.start)
            val part = text.substring(segment.start, segment.end).cleanReadAloudText()
            val character = when {
                segment.characterId > 0L -> byId[segment.characterId]
                segment.characterName.isNotBlank() -> byName[segment.characterName]
                else -> null
            }
            val name = character?.displayName()
                ?: segment.characterName.takeIf { it.isNotBlank() }
                ?: if (segment.roleType == "thought") "心理" else "角色"
            val id = character?.id ?: segment.characterId
            result += SceneSegmentUi(
                index = result.size,
                key = "$chapterKey:scene:$cueIndex:${segment.start}:${segment.end}:${name.hashCode()}",
                text = part,
                roleType = segment.roleType,
                characterId = id,
                characterName = name,
                avatar = character?.avatar.orEmpty(),
                emotionName = segment.emotionName,
                leftSide = Math.floorMod((id.takeIf { it > 0 } ?: name.hashCode().toLong()).hashCode(), 2) == 0,
                narrator = segment.roleType == "narrator" || name == "旁白",
                current = true,
                chapterPosition = (cue?.chapterPosition ?: 0) + segment.start
            )
            cursor = cursor.coerceAtLeast(segment.end)
        }
        if (cursor < text.length) addNarrator(cursor, text.length)
        return result
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
    capsulePosition: CapsulePositionState,
    onCapsulePositionChange: (Float, Float) -> Unit,
    onCapsuleBounds: (RectF) -> Unit,
    onOpenCharacters: () -> Unit,
    onHideRoleDetail: () -> Unit,
    onOpenRoleDetail: () -> Unit,
    onDismissRoleStatus: () -> Unit,
    onRetryRoleAssignment: () -> Unit
) {
    val palette = ReaderSheetStyle.resolve(LocalContext.current)
    val colors = rememberPlayerColors(palette)
    var activeSheet by remember { mutableStateOf(PlayerSheet.None) }
    var sheetVisible by remember { mutableStateOf(false) }
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
        AnimatedVisibility(
            visible = state.panelPhase == ReadAloudPlayerPanel.PanelPhase.Expanded,
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
                RoleAssignmentStatus(state = state, colors = colors, onOpen = onOpenRoleDetail)
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
            if (sheetVisible && activeSheet != PlayerSheet.None) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(activeSheet) {
                            detectTapGestures {
                                sheetVisible = false
                            }
                        }
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
        if (state.panelPhase == ReadAloudPlayerPanel.PanelPhase.Collapsed && state.serviceRunning) {
            ReadAloudCapsule(
                state = state,
                colors = colors,
                onPlayPause = onPlayPause,
                onExpand = onExpand,
                onClose = onStop,
                capsulePosition = capsulePosition,
                onPositionChange = onCapsulePositionChange,
                onBounds = onCapsuleBounds
            )
        }
        RoleAssignmentProgressDialog(
            state = state,
            colors = colors,
            onHide = onHideRoleDetail,
            onDismiss = onDismissRoleStatus,
            onRetry = onRetryRoleAssignment
        )
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

private data class CapsulePositionState(
    val x: Float? = null,
    val y: Float? = null
)

private data class PendingTtsEngineSwitch(
    val wasPlaying: Boolean,
    val pageIndex: Int,
    val startPos: Int
)

private data class PlayerChapterModel(
    val key: String,
    val bookName: String,
    val author: String,
    val coverUrl: String?,
    val sourceOrigin: String?,
    val chapterTitle: String,
    val chapterIndexText: String,
    val chapterSequence: Int,
    val chapterKey: String,
    val chapterCount: Int,
    val totalLength: Int,
    val paragraphs: List<TextParagraph>,
    val baseCues: List<ReadAloudCue>,
    val cues: List<ReadAloudCue>,
    val cuePositions: IntArray,
    val speechItems: List<ReadAloudSpeechPlanItem>,
    val textCues: List<ReadAloudPlayerPanel.TextCueUi>,
    val sceneSegments: List<ReadAloudPlayerPanel.SceneSegmentUi>,
    val roleCacheKey: String?,
    val roleCacheReady: Boolean,
    val roleCacheRunning: Boolean,
    val chapterPreview: List<ReadAloudPlayerPanel.ChapterPreviewUi>,
    val characterPreview: List<ReadAloudPlayerPanel.CharacterPreviewUi>,
    val audioInfo: ReadAloudPlayerPanel.AudioInfoUi
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
    capsulePosition: CapsulePositionState,
    onPositionChange: (Float, Float) -> Unit,
    onBounds: (RectF) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val capsuleHeight = 54.dp
        val capsuleHorizontalPadding = 8.dp
        val capsuleButtonGap = 8.dp
        val coverButtonSize = 42.dp
        val playButtonSize = 38.dp
        val closeButtonSize = 34.dp
        val capsuleWidth = capsuleHorizontalPadding * 2 +
                capsuleButtonGap * 2 +
                coverButtonSize +
                playButtonSize +
                closeButtonSize
        val capsuleWidthPx = with(density) { capsuleWidth.toPx() }
        val capsuleHeightPx = with(density) { capsuleHeight.toPx() }
        val sidePx = with(density) { 18.dp.toPx() }
        val bottomGapPx = with(density) { 28.dp.toPx() }
        val avoidGapPx = with(density) { 12.dp.toPx() }
        val safeInsets = WindowInsets.safeDrawing
        val safeLeft = safeInsets.getLeft(density, layoutDirection).toFloat()
        val safeRight = safeInsets.getRight(density, layoutDirection).toFloat()
        val safeTop = safeInsets.getTop(density).toFloat()
        val safeBottom = safeInsets.getBottom(density).toFloat()
        val minX = safeLeft + sidePx
        val maxX = (widthPx - safeRight - capsuleWidthPx - sidePx).coerceAtLeast(minX)
        val minY = safeTop + sidePx
        val maxY = (heightPx - safeBottom - capsuleHeightPx - bottomGapPx).coerceAtLeast(minY)
        var baseOffsetX by remember { mutableStateOf(capsulePosition.x ?: minX) }
        var baseOffsetY by remember { mutableStateOf(capsulePosition.y ?: maxY) }
        var dragging by remember { mutableStateOf(false) }
        val coverRotation = remember { Animatable(0f) }
        val rotating = state.playing && state.foregroundActive && !AppConfig.isEInkMode
        val clampedBaseX = baseOffsetX.coerceIn(minX, maxX)
        val clampedBaseY = baseOffsetY.coerceIn(minY, maxY)
        val menuAvoidBounds = state.readMenuAvoidBounds
        val displayOffsetY = if (dragging) {
            clampedBaseY
        } else {
            menuAvoidBounds?.let { bounds ->
                val capsuleLeft = clampedBaseX
                val capsuleRight = clampedBaseX + capsuleWidthPx
                val capsuleTop = clampedBaseY
                val capsuleBottom = clampedBaseY + capsuleHeightPx
                val horizontallyOverlapped = capsuleRight > bounds.left && capsuleLeft < bounds.right
                val verticallyOverlapped = capsuleBottom + avoidGapPx > bounds.top && capsuleTop < bounds.bottom
                if (horizontallyOverlapped && verticallyOverlapped) {
                    (bounds.top - capsuleHeightPx - avoidGapPx).coerceIn(minY, maxY)
                } else {
                    clampedBaseY
                }
            } ?: clampedBaseY
        }
        val animatedX by animateFloatAsState(
            targetValue = clampedBaseX,
            animationSpec = tween(if (dragging) 1 else 260, easing = FastOutSlowInEasing),
            label = "readAloudCapsuleX"
        )
        val animatedY by animateFloatAsState(
            targetValue = displayOffsetY,
            animationSpec = tween(if (dragging) 1 else 260, easing = FastOutSlowInEasing),
            label = "readAloudCapsuleY"
        )
        val latestClampedBaseX by rememberUpdatedState(clampedBaseX)
        val latestDisplayOffsetY by rememberUpdatedState(displayOffsetY)
        LaunchedEffect(widthPx, heightPx, minX, maxX, minY, maxY) {
            baseOffsetX = if (baseOffsetX + capsuleWidthPx / 2f < widthPx / 2f) minX else maxX
            baseOffsetY = baseOffsetY.coerceIn(minY, maxY)
            onPositionChange(baseOffsetX, baseOffsetY)
        }
        LaunchedEffect(capsulePosition.x, capsulePosition.y) {
            if (!dragging) {
                capsulePosition.x?.let { baseOffsetX = it }
                capsulePosition.y?.let { baseOffsetY = it }
            }
        }
        LaunchedEffect(rotating) {
            if (rotating) {
                while (true) {
                    val start = coverRotation.value % 360f
                    coverRotation.snapTo(start)
                    coverRotation.animateTo(
                        targetValue = start + 360f,
                        animationSpec = tween(durationMillis = 16000, easing = LinearEasing)
                    )
                }
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
                .pointerInput(widthPx, heightPx, minX, maxX, minY, maxY) {
                    detectDragGestures(
                        onDragStart = {
                            baseOffsetX = latestClampedBaseX
                            baseOffsetY = latestDisplayOffsetY
                            dragging = true
                            onPositionChange(baseOffsetX, baseOffsetY)
                        },
                        onDragEnd = {
                            dragging = false
                            baseOffsetX = if (baseOffsetX + capsuleWidthPx / 2f < widthPx / 2f) {
                                minX
                            } else {
                                maxX
                            }
                            baseOffsetY = baseOffsetY.coerceIn(minY, maxY)
                            onPositionChange(baseOffsetX, baseOffsetY)
                        },
                        onDragCancel = {
                            dragging = false
                            baseOffsetX = baseOffsetX.coerceIn(minX, maxX)
                            baseOffsetY = baseOffsetY.coerceIn(minY, maxY)
                            onPositionChange(baseOffsetX, baseOffsetY)
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        baseOffsetX = (baseOffsetX + dragAmount.x).coerceIn(minX, maxX)
                        baseOffsetY = (baseOffsetY + dragAmount.y).coerceIn(minY, maxY)
                    }
                },
            shape = CircleShape,
            color = colors.panelStrong,
            border = BorderStroke(1.dp, colors.panelBorder),
            shadowElevation = 12.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = capsuleHorizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(capsuleButtonGap)
            ) {
                Box(
                    modifier = Modifier
                        .size(coverButtonSize)
                        .graphicsLayer {
                            rotationZ = coverRotation.value % 360f
                        }
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
                            val loadKey = listOf(
                                state.coverUrl.orEmpty(),
                                state.bookName,
                                state.author,
                                state.sourceOrigin.orEmpty(),
                                "thumb"
                            ).joinToString("|")
                            if (it.tag != loadKey) {
                                it.tag = loadKey
                                it.load(
                                    path = state.coverUrl,
                                    name = state.bookName,
                                    author = state.author,
                                    loadOnlyWifi = false,
                                    sourceOrigin = state.sourceOrigin,
                                    preferThumb = true
                                )
                            }
                        }
                    )
                }
                Surface(
                    onClick = onPlayPause,
                    modifier = Modifier.size(playButtonSize),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.92f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.playbackBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black.copy(alpha = 0.72f),
                                trackColor = Color.Black.copy(alpha = 0.12f)
                            )
                        } else {
                            Icon(
                                painter = painterResource(if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp),
                                contentDescription = null,
                                tint = Color.Black.copy(alpha = 0.86f),
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }
                Surface(
                    onClick = onClose,
                    modifier = Modifier.size(closeButtonSize),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
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
private fun RoleAssignmentStatus(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpen: () -> Unit
) {
    AnimatedVisibility(
        visible = state.roleStatusVisible,
        enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                expandVertically(tween(180, easing = FastOutSlowInEasing)),
        exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) +
                fadeOut(tween(140, easing = FastOutSlowInEasing))
    ) {
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.roleStatusRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = colors.accent,
                    trackColor = Color.Transparent
                )
            }
            Text(
                text = state.roleStatusText,
                color = if (state.roleStatusError) Color(0xFFFF8A9A) else colors.primaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RoleAssignmentProgressDialog(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    val roleState = state.roleState ?: return
    AnimatedVisibility(
        visible = state.roleDetailVisible,
        enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                scaleIn(tween(180, easing = FastOutSlowInEasing), initialScale = 0.96f),
        exit = scaleOut(tween(150, easing = FastOutSlowInEasing), targetScale = 0.98f) +
                fadeOut(tween(140, easing = FastOutSlowInEasing)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (state.roleStatusRunning) 0.18f else 0.10f))
                .systemBarsPadding()
                .padding(22.dp),
            contentAlignment = Alignment.Center
        ) {
            val shape = LocalContext.current.composePanelShape()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp),
                shape = shape,
                color = colors.panelStrong.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, colors.panelBorder),
                shadowElevation = 14.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(
                                text = "多角色分配明细",
                                color = colors.primaryText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = roleState.chapterTitle.ifBlank { state.chapterTitle },
                                color = colors.subtleText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        RoleDialogAction("隐藏", colors, onHide)
                        if (!state.roleStatusRunning && roleState.status == AiReadAloudRoleState.STATUS_FAILED) {
                            RoleDialogAction("重新分配", colors, onRetry)
                        }
                        if (!state.roleStatusRunning) RoleDialogAction("关闭", colors, onDismiss)
                    }
                    RoleAssignmentSummary(state, roleState, colors)
                    AudioAssignmentSummary(state.audioInfo, colors)
                    RolePreviewList(
                        state = state,
                        roleState = roleState,
                        colors = colors,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleDialogAction(
    text: String,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius().coerceAtLeast(12.dp)),
        color = colors.panel.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Text(
            text = text,
            color = colors.primaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun AudioAssignmentSummary(
    audioInfo: ReadAloudPlayerPanel.AudioInfoUi,
    colors: PlayerColors
) {
    if (!audioInfo.enabled) return
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(
            text = "智能音频",
            color = colors.subtleText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoleSummaryChip(
                if (audioInfo.cacheKey.isBlank()) "未分析" else audioStatusLabel(audioInfo.status),
                colors
            )
            RoleSummaryChip("配乐 ${audioInfo.bgmCount}", colors)
            RoleSummaryChip("音效 ${audioInfo.soundEffectCount}", colors)
            if (audioInfo.elapsedMillis > 0L) {
                RoleSummaryChip("耗时 ${formatRoleElapsed(audioInfo.elapsedMillis)}", colors)
            }
            if (audioInfo.requestCount > 0) {
                RoleSummaryChip("${audioInfo.requestCount} 次请求", colors)
            }
            if (audioInfo.totalTokens > 0) {
                RoleSummaryChip("Token ${audioInfo.totalTokens}", colors)
            }
            if (audioInfo.inputTokens > 0) {
                val uncached = (audioInfo.inputTokens - audioInfo.cachedInputTokens).coerceAtLeast(0)
                RoleSummaryChip("输入 ${audioInfo.inputTokens}", colors)
                if (uncached > 0) RoleSummaryChip("未命中 $uncached", colors)
            }
            if (audioInfo.cachedInputTokens > 0) {
                RoleSummaryChip("缓存命中 ${audioInfo.cachedInputTokens}", colors)
            }
            if (audioInfo.outputTokens > 0) {
                RoleSummaryChip("输出 ${audioInfo.outputTokens}", colors)
            }
        }
        val names = buildList {
            if (audioInfo.bgmNames.isNotEmpty()) {
                add("配乐：" + audioInfo.bgmNames.joinToString("、"))
            }
            if (audioInfo.soundEffectNames.isNotEmpty()) {
                add("音效：" + audioInfo.soundEffectNames.joinToString("、"))
            }
        }.joinToString("  ")
        if (names.isNotBlank()) {
            Text(
                text = names,
                color = colors.secondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (audioInfo.error.isNotBlank()) {
            Text(
                text = audioInfo.error,
                color = Color(0xFFFF8A9A),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun audioStatusLabel(status: String): String {
    return when (status) {
        "running" -> "分析中"
        "success" -> "已完成"
        "failed" -> "失败"
        else -> "已缓存"
    }
}

@Composable
private fun RoleAssignmentSummary(
    playerState: ReadAloudPlayerPanel.PlayerUiState,
    roleState: AiReadAloudRoleState,
    colors: PlayerColors
) {
    val preview = roleState.previewSegments
    val unmatchedCount = preview.count {
        it.roleType in setOf("character", "thought") && !it.matchedCharacter
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RoleSummaryChip(roleStatusLabel(roleState), colors)
        RoleSummaryChip(roleSourceLabel(roleState.previewSource), colors)
        RoleSummaryChip("${preview.size} 片段", colors)
        if (roleState.elapsedMillis > 0L) {
            RoleSummaryChip("耗时 ${formatRoleElapsed(roleState.elapsedMillis)}", colors)
        }
        if (roleState.requestCount > 0) {
            RoleSummaryChip("${roleState.requestCount} 次请求", colors)
        }
        if (roleState.totalTokens > 0) {
            RoleSummaryChip("Token ${roleState.totalTokens}", colors)
        } else if (roleState.requestCount > 0 && !roleState.running) {
            RoleSummaryChip("Token 未返回", colors)
        }
        if (roleState.inputTokens > 0) {
            val uncached = (roleState.inputTokens - roleState.cachedInputTokens).coerceAtLeast(0)
            RoleSummaryChip("输入 ${roleState.inputTokens}", colors)
            if (uncached > 0) {
                RoleSummaryChip("未命中 $uncached", colors)
            }
        }
        if (roleState.outputTokens > 0) {
            RoleSummaryChip("输出 ${roleState.outputTokens}", colors)
        }
        if (roleState.cachedInputTokens > 0) {
            RoleSummaryChip("缓存命中 ${roleState.cachedInputTokens}", colors)
        }
        if (roleState.createdCharacterCount > 0) {
            RoleSummaryChip("新增 ${roleState.createdCharacterCount} 角色", colors)
        }
        if (unmatchedCount > 0) {
            RoleSummaryChip("$unmatchedCount 未匹配", colors, danger = true)
        }
    }
    Text(
        text = playerState.roleStatusText,
        color = colors.secondaryText,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 10.dp)
    )
    if (roleState.error.isNotBlank()) {
        Text(
            text = roleState.error,
            color = Color(0xFFFF8A9A),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private fun formatRoleElapsed(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    return if (seconds < 60L) {
        "${seconds}s"
    } else {
        "${seconds / 60L}m${seconds % 60L}s"
    }
}

@Composable
private fun RoleSummaryChip(
    text: String,
    colors: PlayerColors,
    danger: Boolean = false
) {
    Text(
        text = text,
        color = if (danger) Color(0xFFFF8A9A) else colors.secondaryText,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 5.dp)
    )
}

@Composable
private fun RolePreviewList(
    state: ReadAloudPlayerPanel.PlayerUiState,
    roleState: AiReadAloudRoleState,
    colors: PlayerColors,
    modifier: Modifier = Modifier
) {
    val preview = roleState.previewSegments
    if (preview.isEmpty()) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(LocalContext.current.composeActionRadius().coerceAtLeast(14.dp)),
            color = colors.panel.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, colors.panelBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.roleStatusRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent
                    )
                }
                Text(
                    text = when {
                        state.roleStatusRunning -> "等待 AI 返回具体片段分配"
                        roleState.error.isNotBlank() -> "没有可展示的分配片段"
                        else -> "暂无分配明细"
                    },
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
        return
    }
    val groups = remember(preview) {
        preview.groupBy { it.paragraphIndex }
            .toSortedMap()
            .map { it.key to it.value.sortedWith(compareBy<AiReadAloudRolePreviewSegment> { segment -> segment.start }.thenBy { segment -> segment.end }) }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 430.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(groups, key = { it.first }) { (paragraphIndex, segments) ->
            RolePreviewParagraphGroup(
                paragraphIndex = paragraphIndex,
                segments = segments,
                current = paragraphIndex == state.currentCueIndex,
                colors = colors
            )
        }
    }
}

@Composable
private fun RolePreviewParagraphGroup(
    paragraphIndex: Int,
    segments: List<AiReadAloudRolePreviewSegment>,
    current: Boolean,
    colors: PlayerColors
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius().coerceAtLeast(16.dp)),
        color = if (current) colors.accent.copy(alpha = 0.12f) else colors.panel.copy(alpha = 0.52f),
        border = BorderStroke(1.dp, if (current) colors.accent.copy(alpha = 0.55f) else colors.panelBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "段落 ${paragraphIndex + 1}",
                    color = if (current) colors.accent else colors.subtleText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (current) {
                    Text("当前朗读", color = colors.accent, fontSize = 11.sp)
                }
            }
            segments.forEach { segment ->
                RolePreviewSegmentRow(segment, colors)
            }
        }
    }
}

@Composable
private fun RolePreviewSegmentRow(
    segment: AiReadAloudRolePreviewSegment,
    colors: PlayerColors
) {
    val danger = segment.roleType in setOf("character", "thought") && !segment.matchedCharacter
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius().coerceAtLeast(12.dp)),
        color = colors.panelStrong.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, if (danger) Color(0xFFFF8A9A).copy(alpha = 0.34f) else colors.panelBorder.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoleTypeChip(segment.roleType, colors, danger)
                Text(
                    text = roleSpeakerLine(segment),
                    color = if (danger) Color(0xFFFFB3BB) else colors.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(segment.confidence * 100).roundToInt()}%",
                    color = colors.subtleText,
                    fontSize = 11.sp
                )
            }
            Text(
                text = segment.text.ifBlank { "空片段" },
                color = colors.primaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp)
            )
        }
    }
}

@Composable
private fun RoleTypeChip(
    roleType: String,
    colors: PlayerColors,
    danger: Boolean
) {
    val color = when {
        danger -> Color(0xFFFF8A9A)
        roleType == "narrator" -> colors.subtleText
        roleType == "thought" -> Color(0xFFBFA7FF)
        roleType == "character" -> colors.accent
        else -> colors.secondaryText
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f))
    ) {
        Text(
            text = roleTypeLabel(roleType),
            color = color,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun roleTypeLabel(roleType: String): String {
    return when (roleType) {
        "narrator" -> "旁白"
        "character" -> "角色"
        "thought" -> "心理"
        else -> "其他"
    }
}

private fun roleStatusLabel(state: AiReadAloudRoleState): String {
    return when (state.status) {
        AiReadAloudRoleState.STATUS_RUNNING -> "分配中"
        AiReadAloudRoleState.STATUS_SUCCESS -> "已完成"
        AiReadAloudRoleState.STATUS_FALLBACK -> "Fallback"
        AiReadAloudRoleState.STATUS_FAILED -> "失败"
        AiReadAloudRoleState.STATUS_SKIPPED -> "已缓存"
        else -> "待处理"
    }
}

private fun roleSourceLabel(source: String): String {
    return when (source) {
        AiReadAloudRoleState.SOURCE_AI -> "AI 原始结果"
        AiReadAloudRoleState.SOURCE_RULE -> "本地预处理"
        AiReadAloudRoleState.SOURCE_AI_CONFIRM -> "AI 确认"
        AiReadAloudRoleState.SOURCE_RESOLVED -> "已匹配角色"
        AiReadAloudRoleState.SOURCE_CACHE -> "缓存"
        AiReadAloudRoleState.SOURCE_FALLBACK -> "默认切分"
        else -> "等待结果"
    }
}

private fun roleSpeakerLine(segment: AiReadAloudRolePreviewSegment): String {
    val roleName = when {
        segment.roleType == "narrator" -> "旁白"
        segment.characterName.isBlank() -> "未识别角色"
        segment.matchedCharacter -> segment.characterName
        else -> "${segment.characterName}（未匹配）"
    }
    val voice = segment.speakerName.ifBlank {
        if (segment.roleType == "narrator") "使用默认朗读" else "未绑定发言人"
    }
    val emotion = segment.emotionName.takeIf { it.isNotBlank() }
    return buildList {
        add(roleName)
        add(voice)
        emotion?.let(::add)
    }.joinToString(" · ")
}

@Composable
private fun HeaderIconButton(
    icon: Int,
    contentDescription: String,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
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
            .width(188.dp)
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
            text = "情景",
            selected = mode == ReadAloudPlayerPanel.DisplayMode.Scene,
            colors = colors,
            shape = actionShape,
            modifier = Modifier.weight(1f)
        ) {
            onModeChange(ReadAloudPlayerPanel.DisplayMode.Scene)
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
    if (state.roleBlockingCurrentContent) {
        RoleAssigningBody(state = state, colors = colors, modifier = modifier)
        return
    }
    when (state.mode) {
        ReadAloudPlayerPanel.DisplayMode.Immersive -> ImmersivePlayerStage(
            state = state,
            colors = colors,
            short = short,
            veryShort = veryShort,
            animateTextChanges = animateTextChanges,
            modifier = modifier
        )

        ReadAloudPlayerPanel.DisplayMode.Scene -> ScenePlayerStage(
            state = state,
            colors = colors,
            compact = short,
            onCueSelect = onCueSelect,
            modifier = modifier
        )

        ReadAloudPlayerPanel.DisplayMode.Text -> LyricsPlayerStage(
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
    if (state.roleBlockingCurrentContent) {
        RoleAssigningBody(state = state, colors = colors, modifier = modifier)
        return
    }
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
            if (state.mode == ReadAloudPlayerPanel.DisplayMode.Immersive) {
                FocusSentenceBody(
                    state = state,
                    colors = colors,
                    compact = short,
                    animateTextChanges = animateTextChanges,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (state.mode == ReadAloudPlayerPanel.DisplayMode.Scene) {
                ScenePlayerStage(
                    state = state,
                    colors = colors,
                    compact = short,
                    onCueSelect = onCueSelect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
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
private fun RoleAssigningBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
                color = colors.accent,
                trackColor = Color.Transparent
            )
            Text(
                text = state.roleBlockingText.ifBlank { "当前章节角色分配中" },
                color = colors.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "分配完成后再显示本章内容",
                color = colors.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
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
private fun ScenePlayerStage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
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
        Spacer(modifier = Modifier.height(if (compact) 10.dp else 16.dp))
        val listState = rememberLazyListState()
        val currentIndex = state.currentCueIndex.coerceIn(
            0,
            state.sceneSegments.lastIndex.coerceAtLeast(0)
        )
        var userBrowsing by remember(state.chapterKey) { mutableStateOf(false) }
        var programmaticScroll by remember(state.chapterKey) { mutableStateOf(false) }
        var lastSceneTarget by remember(state.chapterKey) { mutableStateOf<Int?>(null) }
        LaunchedEffect(state.chapterKey) {
            userBrowsing = false
            lastSceneTarget = null
        }
        LaunchedEffect(state.chapterKey, currentIndex) {
            if (state.sceneSegments.isNotEmpty() && !userBrowsing) {
                val firstTarget = lastSceneTarget == null
                val targetDistance = lastSceneTarget
                    ?.let { kotlin.math.abs(it - currentIndex) }
                    ?: 0
                programmaticScroll = true
                if (firstTarget) {
                    listState.scrollToItem(currentIndex)
                } else if (lastSceneTarget != currentIndex && !listState.isScrollInProgress) {
                    if (AppConfig.isEInkMode || !state.foregroundActive || targetDistance > 8) {
                        listState.scrollToItem(currentIndex)
                    } else {
                        listState.animateScrollToItem(currentIndex)
                    }
                }
                programmaticScroll = false
                lastSceneTarget = currentIndex
            }
        }
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress && !programmaticScroll) {
                userBrowsing = true
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .widthIn(max = 760.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 12.dp)
        ) {
            items(state.sceneSegments, key = { it.key }) { segment ->
                SceneSegmentRow(
                    segment = segment,
                    current = segment.index == currentIndex,
                    colors = colors,
                    compact = compact,
                    onClick = {
                        userBrowsing = false
                        onCueSelect(segment.chapterPosition)
                    }
                )
            }
        }
    }
}

@Composable
private fun SceneSegmentRow(
    segment: ReadAloudPlayerPanel.SceneSegmentUi,
    current: Boolean,
    colors: PlayerColors,
    compact: Boolean,
    onClick: () -> Unit
) {
    if (segment.narrator) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = segment.text,
                color = if (current) colors.secondaryText else colors.subtleText,
                fontSize = if (compact) 12.sp else 13.sp,
                lineHeight = if (compact) 18.sp else 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        return
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .align(if (segment.leftSide) Alignment.CenterStart else Alignment.CenterEnd),
            horizontalArrangement = if (segment.leftSide) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            if (segment.leftSide) {
                SceneAvatar(segment, colors, compact)
                SceneBubbleArrow(leftSide = true, colors = colors)
                SceneBubble(
                    segment = segment,
                    current = current,
                    colors = colors,
                    compact = compact,
                    onClick = onClick,
                    modifier = Modifier.weight(1f, fill = false)
                )
            } else {
                SceneBubble(
                    segment = segment,
                    current = current,
                    colors = colors,
                    compact = compact,
                    onClick = onClick,
                    modifier = Modifier.weight(1f, fill = false)
                )
                SceneBubbleArrow(leftSide = false, colors = colors)
                SceneAvatar(segment, colors, compact)
            }
        }
    }
}

@Composable
private fun SceneBubbleArrow(
    leftSide: Boolean,
    colors: PlayerColors
) {
    val arrowColor = if (leftSide) colors.panelStrong else colors.accent.copy(alpha = 0.82f)
    Canvas(
        modifier = Modifier
            .padding(top = 14.dp)
            .size(width = 8.dp, height = 14.dp)
    ) {
        val path = androidx.compose.ui.graphics.Path().apply {
            if (leftSide) {
                moveTo(size.width, 0f)
                lineTo(0f, size.height / 2f)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
            }
            close()
        }
        drawPath(path, arrowColor)
    }
}

@Composable
private fun SceneBubble(
    segment: ReadAloudPlayerPanel.SceneSegmentUi,
    current: Boolean,
    colors: PlayerColors,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleColor = if (segment.leftSide) colors.panelStrong else colors.accent.copy(alpha = 0.82f)
    val textColor = if (segment.leftSide) colors.primaryText else colors.accentText
    val actionShape = LocalContext.current.composeActionShape()
    Surface(
        onClick = onClick,
        modifier = modifier
            .widthIn(max = if (compact) 460.dp else 520.dp)
            .graphicsLayer {
                alpha = if (current) 1f else 0.88f
            },
        shape = actionShape,
        color = bubbleColor,
        border = BorderStroke(
            1.dp,
            colors.panelBorder.copy(alpha = if (current) 0.28f else 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            horizontalAlignment = if (segment.leftSide) Alignment.Start else Alignment.End
        ) {
            val emotion = segment.emotionName.takeIf { it.isNotBlank() }
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!segment.leftSide) {
                    emotion?.let {
                        Text(
                            text = it,
                            color = textColor.copy(alpha = 0.70f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                Text(
                    text = segment.characterName,
                    color = textColor.copy(alpha = 0.86f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (segment.leftSide) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.widthIn(max = if (compact) 230.dp else 280.dp)
                )
                if (segment.leftSide) {
                    emotion?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = it,
                            color = textColor.copy(alpha = 0.70f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp)
                        )
                    }
                }
            }
            Text(
                text = segment.text,
                color = textColor,
                fontSize = if (compact) 14.sp else 15.sp,
                lineHeight = if (compact) 21.sp else 23.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun SceneAvatar(
    segment: ReadAloudPlayerPanel.SceneSegmentUi,
    colors: PlayerColors,
    compact: Boolean
) {
    val context = LocalContext.current
    val size = if (compact) 34.dp else 38.dp
    AndroidView(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.panel),
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = segment.characterName
            }
        },
        update = { imageView ->
            val loadKey = segment.avatar.ifBlank { "__read_aloud_default_avatar__" }
            if (imageView.tag != loadKey) {
                imageView.tag = loadKey
                ImageLoader.load(context, segment.avatar.ifBlank { null })
                    .placeholder(R.drawable.ic_bottom_person)
                    .error(R.drawable.ic_bottom_person)
                    .into(imageView)
            }
        }
    )
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
                val loadKey = listOf(
                    state.coverUrl.orEmpty(),
                    state.bookName,
                    state.author,
                    state.sourceOrigin.orEmpty(),
                    "full"
                ).joinToString("|")
                if (it.tag != loadKey) {
                    it.tag = loadKey
                    it.load(
                        path = state.coverUrl,
                        name = state.bookName,
                        author = state.author,
                        loadOnlyWifi = false,
                        sourceOrigin = state.sourceOrigin,
                        preferThumb = false
                    )
                }
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
    var lastCenteredTarget by remember(state.chapterKey) { mutableStateOf<Int?>(null) }
    val currentIndex = state.currentCueIndex.coerceIn(0, cues.lastIndex)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 720.dp)
    ) {
        val centerPadding = ((maxHeight / 2) - if (compact) 42.dp else 54.dp)
            .coerceAtLeast(if (compact) 42.dp else 58.dp)

        suspend fun centerCue(index: Int, animated: Boolean) {
            if (cues.isEmpty()) return
            val targetIndex = index.coerceIn(0, cues.lastIndex)
            if (animated) {
                listState.animateScrollToItem(targetIndex)
            } else {
                listState.scrollToItem(targetIndex)
            }
            val layoutInfo = listState.layoutInfo
            val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex } ?: return
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            val targetTop = viewportCenter - item.size / 2
            val scrollOffset = -targetTop
            if (animated) {
                listState.animateScrollToItem(targetIndex, scrollOffset = scrollOffset)
            } else {
                listState.scrollToItem(targetIndex, scrollOffset = scrollOffset)
            }
        }

        LaunchedEffect(state.chapterKey) {
            userSeeking = false
            lastCenteredTarget = null
        }
        LaunchedEffect(state.chapterKey, currentIndex) {
            if (!userSeeking && cues.isNotEmpty()) {
                programmaticScroll = true
                val firstTarget = lastCenteredTarget == null
                centerCue(currentIndex, animated = !firstTarget)
                lastCenteredTarget = currentIndex
                programmaticScroll = false
            }
        }
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress && !programmaticScroll) {
                userSeeking = true
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = centerPadding),
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
                    current = cue.index - 1 == currentIndex,
                    colors = colors,
                    compact = compact,
                    currentMaxLines = currentMaxLines,
                    textAlign = textAlign,
                    animate = animateTextChanges,
                    onClick = {
                        userSeeking = false
                        onCueSelect(cue.chapterPosition)
                    }
                )
            }
        }
    }
}

@Composable
private fun LyricCueLine(
    cue: ReadAloudPlayerPanel.TextCueUi,
    current: Boolean,
    colors: PlayerColors,
    compact: Boolean,
    currentMaxLines: Int,
    textAlign: TextAlign,
    animate: Boolean,
    onClick: () -> Unit
) {
    val emphasis by animateFloatAsState(
        targetValue = if (current) 1f else 0f,
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
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = if (current) currentMaxLines else 2,
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
                onClick = onPlayPause,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.92f),
                shadowElevation = 16.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.playbackBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            strokeWidth = 3.dp,
                            color = Color.Black.copy(alpha = 0.72f),
                            trackColor = Color.Black.copy(alpha = 0.12f)
                        )
                    } else {
                        Icon(
                            painter = painterResource(if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp),
                            contentDescription = if (state.playing) context.getString(R.string.pause) else context.getString(R.string.audio_play),
                            tint = Color.Black.copy(alpha = 0.88f),
                            modifier = Modifier.size(34.dp)
                        )
                    }
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
        onClick = onClick,
        modifier = Modifier.size(size),
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
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = panelShape,
        color = colors.panelStrong,
        border = BorderStroke(1.dp, colors.panelBorder),
        shadowElevation = 12.dp
    ) {
        Box(
            modifier = Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            )
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
    var pickerGroupKey by remember(state.ttsEngines) { mutableStateOf<String?>(null) }
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
                    onClick = { pickerGroupKey = engine.key }
                )
            }
        }
    }
    pickerGroupKey?.let { key ->
        SpeechVoiceRoutePickerDialog(
            title = "选择发言人",
            groups = state.ttsEngines.map { it.group },
            currentRoute = state.speechRoute,
            initialGroupKey = key,
            onDismiss = { pickerGroupKey = null },
            onRouteSelected = { route ->
                pickerGroupKey = null
                onEngineSelect(route.toJson())
            }
        )
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
