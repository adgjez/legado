package io.legado.app.ui.main.ai.compose

import android.net.Uri
import android.text.Spannable
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiChatCompanionConfig
import io.legado.app.ui.main.ai.AiChatSession
import io.legado.app.ui.main.ai.AiChatSpeechPlayer
import io.legado.app.ui.main.ai.AiMarkdownRender
import io.legado.app.ui.main.ai.AiChatViewModel
import io.legado.app.ui.book.character.compose.CharacterAvatar
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.parseToUri
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

@Stable
data class AiChatScreenActions(
    val onSend: (String) -> Boolean,
    val onStop: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onNewChat: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onSelectModel: () -> Unit,
    val onOpenImageGallery: (() -> Unit)? = null,
    val onOpenWindowAbilities: (() -> Unit)? = null,
    val onOpenWorldBooks: (() -> Unit)? = null,
    val onToggleAutoSpeak: (() -> Unit)? = null,
    val onSpeakMessage: ((String, AiChatCompanionConfig, String) -> Unit)? = null,
    val onAddCompanion: (() -> Unit)? = null,
    val onSelectCompanion: ((String) -> Unit)? = null,
    val onSelectSession: ((String) -> Unit)? = null,
    val onSelectCompanionSession: ((String, String) -> Unit)? = null,
    val onNewCompanionChat: ((String) -> Unit)? = null,
    val onDeleteSession: ((AiChatSession) -> Unit)? = null,
    val onCompanionLongPress: ((AiChatCompanionConfig) -> Unit)? = null
)

@Composable
fun AiChatRoute(
    viewModel: AiChatViewModel,
    lifecycleOwner: LifecycleOwner,
    compactHeader: Boolean,
    refreshToken: Int,
    actions: AiChatScreenActions
) {
    var messages by remember { mutableStateOf(viewModel.messagesLiveData.value.orEmpty()) }
    var requesting by remember { mutableStateOf(viewModel.isRequesting) }
    DisposableEffect(viewModel, lifecycleOwner) {
        val messageObserver = Observer<List<AiChatMessage>> { messages = it.orEmpty() }
        val requestingObserver = Observer<Boolean> { requesting = it == true }
        viewModel.messagesLiveData.observe(lifecycleOwner, messageObserver)
        viewModel.requestingLiveData.observe(lifecycleOwner, requestingObserver)
        onDispose {
            viewModel.messagesLiveData.removeObserver(messageObserver)
            viewModel.requestingLiveData.removeObserver(requestingObserver)
        }
    }
    val modelLabel = remember(refreshToken, messages.size, requesting) {
        AppConfig.aiCurrentModelConfig?.modelId ?: ""
    }
    val companions = remember(refreshToken, messages.size, requesting) {
        viewModel.companions()
    }
    val currentCompanion = remember(refreshToken, messages.size, requesting) {
        viewModel.currentCompanion()
    }
    val sessionsByCompanion = remember(refreshToken, messages.size, requesting, companions) {
        companions.associate { companion ->
            companion.id to viewModel.historySessions(companion.id)
        }
    }
    val currentSessionId = remember(refreshToken, messages.size, requesting, currentCompanion.id) {
        viewModel.activeSessionId()
    }
    val userAvatar = remember(refreshToken) {
        ReadRecordWidgetStore.loadGoalConfig().avatar
    }
    val autoSpeakEnabled = remember(refreshToken) { AppConfig.aiChatAutoSpeakEnabled }
    val thinkingToolbarEnabled = remember(refreshToken) { AppConfig.aiThinkingToolbarEnabled }
    val enterToSend = remember(refreshToken) { AppConfig.aiEnterToSend }
    AiChatScreen(
        messages = messages,
        requesting = requesting,
        modelLabel = modelLabel,
        companions = companions,
        currentCompanion = currentCompanion,
        sessionsByCompanion = sessionsByCompanion,
        currentSessionId = currentSessionId,
        userAvatar = userAvatar,
        autoSpeakEnabled = autoSpeakEnabled,
        thinkingToolbarEnabled = thinkingToolbarEnabled,
        enterToSend = enterToSend,
        compactHeader = compactHeader,
        actions = actions
    )
}

@Composable
fun AiChatScreen(
    messages: List<AiChatMessage>,
    requesting: Boolean,
    modelLabel: String,
    companions: List<AiChatCompanionConfig>,
    currentCompanion: AiChatCompanionConfig,
    sessionsByCompanion: Map<String, List<AiChatSession>>,
    currentSessionId: String,
    userAvatar: String?,
    autoSpeakEnabled: Boolean,
    thinkingToolbarEnabled: Boolean,
    enterToSend: Boolean,
    compactHeader: Boolean,
    actions: AiChatScreenActions
) {
    val context = LocalContext.current
    val style = aiComposeStyle(context)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    var toolPreviewPayload by remember { mutableStateOf<AiToolDisplayPayload?>(null) }
    var processExpandSignal by remember { mutableStateOf(0) }
    var companionDrawerOpen by rememberSaveable { mutableStateOf(false) }
    var expandedCompanionId by rememberSaveable { mutableStateOf(currentCompanion.id) }
    var lastAutoSpokenMessageId by rememberSaveable { mutableStateOf("") }
    var stickToBottom by rememberSaveable { mutableStateOf(true) }
    var positionedConversationKey by rememberSaveable { mutableStateOf("") }
    val speechState by AiChatSpeechPlayer.playbackState.collectAsState()
    val uiItems = remember(context, messages, thinkingToolbarEnabled) {
        buildAiChatUiItems(
            context = context,
            messages = messages,
            showProcessChain = thinkingToolbarEnabled
        )
    }
    val displayItems = remember(uiItems) {
        uiItems.asReversed()
    }
    val autoScrollSignal = remember(messages) {
        messages.takeLast(8).joinToString("|") { message ->
            "${message.id}:${message.updatedAt}:${message.content.length}:${message.pending}:${message.collapsed}"
        }
    }
    val bottomThresholdPx = remember(density) { with(density) { 32.dp.toPx().toInt() } }
    val isAtBottom by remember {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount <= 1 ||
                (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset <= bottomThresholdPx)
        }
    }
    val conversationRootId = remember(uiItems) { uiItems.firstOrNull()?.id.orEmpty() }
    LaunchedEffect(uiItems.isEmpty()) {
        if (uiItems.isEmpty()) {
            positionedConversationKey = ""
            stickToBottom = true
        }
    }
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (isAtBottom) {
            stickToBottom = true
        } else if (listState.isScrollInProgress) {
            stickToBottom = false
        }
    }
    LaunchedEffect(messages.lastOrNull()?.id, messages.lastOrNull()?.role) {
        if (messages.lastOrNull()?.role == AiChatMessage.Role.USER) {
            stickToBottom = true
            if (displayItems.isNotEmpty()) {
                listState.scrollToItem(0)
            }
        }
    }
    LaunchedEffect(conversationRootId) {
        if (displayItems.isNotEmpty() && positionedConversationKey != conversationRootId) {
            listState.scrollToItem(0)
            stickToBottom = true
            positionedConversationKey = conversationRootId
        }
    }
    LaunchedEffect(requesting, autoScrollSignal, processExpandSignal, stickToBottom) {
        if (displayItems.isNotEmpty() && stickToBottom && !listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(
        autoSpeakEnabled,
        messages.lastOrNull()?.id,
        messages.lastOrNull()?.pending,
        messages.lastOrNull()?.updatedAt
    ) {
        val last = messages.lastOrNull()
        if (autoSpeakEnabled &&
            last != null &&
            last.role == AiChatMessage.Role.ASSISTANT &&
            !last.pending &&
            (last.kind ?: AiChatMessage.Kind.TEXT) == AiChatMessage.Kind.TEXT &&
            last.id != lastAutoSpokenMessageId &&
            System.currentTimeMillis() - last.updatedAt <= 60_000
        ) {
            lastAutoSpokenMessageId = last.id
            actions.onSpeakMessage?.invoke(last.content, currentCompanion, last.id)
        }
    }
    LaunchedEffect(currentCompanion.id) {
        if (expandedCompanionId.isBlank()) {
            expandedCompanionId = currentCompanion.id
        }
    }
    val drawerOpenDistancePx = remember(density) { with(density) { 72.dp.toPx() } }
    var drawerDragDistance by remember { mutableStateOf(0f) }
    var drawerDragging by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(style.colors.pageBackground)
    ) {
        val drawerWidthPx = remember(maxWidth, density) {
            min(
                with(density) { maxWidth.toPx() * 0.88f },
                with(density) { 340.dp.toPx() }
            ).coerceAtLeast(1f)
        }
        val drawerProgress = when {
            companionDrawerOpen -> (1f + drawerDragDistance / drawerWidthPx).coerceIn(0f, 1f)
            else -> (drawerDragDistance / drawerWidthPx).coerceIn(0f, 1f)
        }
        val animatedDrawerProgress by animateFloatAsState(
            targetValue = if (companionDrawerOpen) {
                1f
            } else {
                0f
            },
            animationSpec = tween(
                durationMillis = if (drawerDragging) 1 else 240,
                easing = FastOutSlowInEasing
            ),
            label = "aiCompanionDrawerProgress"
        )
        val visibleDrawerProgress = if (drawerDragging) drawerProgress else animatedDrawerProgress
        val drawerVisible = drawerDragging || visibleDrawerProgress > 0.002f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(companionDrawerOpen, drawerWidthPx) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        drawerDragDistance = 0f
                        drawerDragging = true
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        val next = if (companionDrawerOpen) {
                            (drawerDragDistance + dragAmount).coerceIn(-drawerWidthPx, 0f)
                        } else {
                            (drawerDragDistance + dragAmount).coerceIn(0f, drawerWidthPx)
                        }
                        if (next != drawerDragDistance) {
                            change.consume()
                            drawerDragDistance = next
                        }
                    },
                    onDragEnd = {
                        val endProgress = if (companionDrawerOpen) {
                            (1f + drawerDragDistance / drawerWidthPx).coerceIn(0f, 1f)
                        } else {
                            (drawerDragDistance / drawerWidthPx).coerceIn(0f, 1f)
                        }
                        if (companionDrawerOpen) {
                            if (-drawerDragDistance >= drawerOpenDistancePx ||
                                endProgress < 0.72f
                            ) {
                                companionDrawerOpen = false
                            }
                        } else if (drawerDragDistance >= drawerOpenDistancePx ||
                            endProgress >= 0.25f
                        ) {
                            companionDrawerOpen = true
                        }
                        drawerDragDistance = 0f
                        drawerDragging = false
                    },
                    onDragCancel = {
                        drawerDragDistance = 0f
                        drawerDragging = false
                    }
                )
            }
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            AiChatTopBar(
                modelLabel = modelLabel,
                currentCompanion = currentCompanion,
                requesting = requesting,
                compactHeader = compactHeader,
                autoSpeakEnabled = autoSpeakEnabled,
                style = style,
                onOpenCompanionDrawer = { companionDrawerOpen = true },
                actions = actions
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                if (uiItems.isEmpty()) {
                    AiEmptyState(style = style)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 14.dp,
                            top = 10.dp,
                            end = 14.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = displayItems,
                            key = { it.id },
                            contentType = { item ->
                                when (item) {
                                    is AiChatUiItem.User -> "user"
                                    is AiChatUiItem.Assistant -> "assistant"
                                }
                            }
                        ) { item ->
                            AiMessageRow(
                                item = item,
                                currentCompanion = currentCompanion,
                                userAvatar = userAvatar,
                                style = style,
                                speechState = speechState,
                                onSpeak = actions.onSpeakMessage,
                                onToolPreview = { toolPreviewPayload = it },
                                onProcessExpanded = {
                                    processExpandSignal += 1
                                    coroutineScope.launch {
                                        if (displayItems.isNotEmpty() && stickToBottom) {
                                            listState.scrollToItem(0)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        AiComposer(
            requesting = requesting,
            enterToSend = enterToSend,
            style = style,
            actions = actions,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        )
        if (uiItems.size > 1) {
            AiJumpButtons(
                style = style,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(end = 16.dp, bottom = 92.dp),
                onPrevious = {
                    val current = listState.firstVisibleItemIndex
                    val target = (current + 1).coerceAtMost(displayItems.lastIndex)
                    coroutineScope.launch { listState.animateScrollToItem(target) }
                },
                onNext = {
                    val current = listState.firstVisibleItemIndex
                    val target = (current - 1).coerceAtLeast(0)
                    coroutineScope.launch { listState.animateScrollToItem(target) }
                }
            )
        }
        toolPreviewPayload?.let { payload ->
            AiToolPreviewDialog(
                payload = payload,
                style = style,
                onDismiss = { toolPreviewPayload = null }
            )
        }
        if (drawerVisible) {
            AiCompanionDrawer(
                companions = companions,
                currentCompanionId = currentCompanion.id,
                expandedCompanionId = expandedCompanionId,
                sessionsByCompanion = sessionsByCompanion,
                currentSessionId = currentSessionId,
                style = style,
                actions = actions,
                animatedProgress = visibleDrawerProgress,
                drawerWidthPx = drawerWidthPx,
                onToggleCompanion = { companionId ->
                    expandedCompanionId = if (expandedCompanionId == companionId) {
                        ""
                    } else {
                        companionId
                    }
                },
                onDismiss = { companionDrawerOpen = false }
            )
        }
    }
    }
}

@Composable
private fun AiChatTopBar(
    modelLabel: String,
    currentCompanion: AiChatCompanionConfig,
    requesting: Boolean,
    compactHeader: Boolean,
    autoSpeakEnabled: Boolean,
    style: AiComposeStyle,
    onOpenCompanionDrawer: () -> Unit,
    actions: AiChatScreenActions
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (compactHeader) 22.dp else 16.dp,
                top = if (compactHeader) 8.dp else 10.dp,
                end = if (compactHeader) 14.dp else 12.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenCompanionDrawer) {
            Icon(
                painter = painterResource(R.drawable.ic_menu),
                contentDescription = null,
                tint = style.colors.primaryText,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = currentCompanion.name.ifBlank { stringResource(R.string.ai) },
                color = style.colors.primaryText,
                fontSize = if (compactHeader) 24.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (modelLabel.isNotBlank()) {
            Surface(
                onClick = actions.onSelectModel,
                enabled = !requesting,
                shape = RoundedCornerShape(style.metrics.chipRadius),
                color = style.colors.accent.copy(alpha = if (requesting) 0.06f else 0.10f)
            ) {
                Text(
                    text = modelLabel,
                    color = if (requesting) style.colors.secondaryText else style.colors.accent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = null,
                    tint = style.colors.primaryText,
                    modifier = Modifier.size(21.dp)
                )
            }
            if (menuExpanded) {
                AiModernTopMenu(
                    style = style,
                    actions = buildList {
                        add(AiTopMenuAction(stringResource(R.string.ai_new_chat)) { actions.onNewChat() })
                        add(AiTopMenuAction(stringResource(R.string.ai_chat_history)) { actions.onOpenHistory() })
                        actions.onOpenWindowAbilities?.let { openAbilities ->
                            add(AiTopMenuAction("窗口能力", openAbilities))
                        }
                        actions.onOpenWorldBooks?.let { openWorldBooks ->
                            add(AiTopMenuAction("浏览世界书", openWorldBooks))
                        }
                        actions.onToggleAutoSpeak?.let { toggleAutoSpeak ->
                            add(AiTopMenuAction("自动播放语音：${if (autoSpeakEnabled) "开" else "关"}", toggleAutoSpeak))
                        }
                        add(AiTopMenuAction(stringResource(R.string.ai_setting)) { actions.onOpenSettings() })
                        actions.onOpenImageGallery?.let { openGallery ->
                            add(AiTopMenuAction(stringResource(R.string.ai_image_gallery), openGallery))
                        }
                    },
                    onDismiss = { menuExpanded = false }
                )
            }
        }
    }
}

@Immutable
private data class AiTopMenuAction(
    val title: String,
    val invoke: () -> Unit
)

@Composable
private fun AiModernTopMenu(
    style: AiComposeStyle,
    actions: List<AiTopMenuAction>,
    onDismiss: () -> Unit
) {
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, 44),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            shape = RoundedCornerShape(style.metrics.cardRadius),
            color = style.colors.assistantBubble,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke),
            modifier = Modifier.widthIn(min = 132.dp)
        ) {
            Column(modifier = Modifier.padding(6.dp)) {
                actions.forEach { action ->
                    Box(
                        modifier = Modifier
                            .widthIn(min = 132.dp)
                            .clip(RoundedCornerShape(style.metrics.chipRadius))
                            .clickable {
                                onDismiss()
                                action.invoke()
                            }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = action.title,
                            color = style.colors.primaryText,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiCompanionDrawer(
    companions: List<AiChatCompanionConfig>,
    currentCompanionId: String,
    expandedCompanionId: String,
    sessionsByCompanion: Map<String, List<AiChatSession>>,
    currentSessionId: String,
    style: AiComposeStyle,
    actions: AiChatScreenActions,
    animatedProgress: Float,
    drawerWidthPx: Float,
    onToggleCompanion: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val clampedProgress = animatedProgress.coerceIn(0f, 1f)
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.22f * clampedProgress))
                .then(
                    if (clampedProgress >= 0.98f) {
                        Modifier.clickable { onDismiss() }
                    } else {
                        Modifier
                    }
                )
        )
        Surface(
            shape = RoundedCornerShape(
                topStart = 0.dp,
                bottomStart = 0.dp,
                topEnd = style.metrics.cardRadius,
                bottomEnd = style.metrics.cardRadius
            ),
            color = style.colors.pageBackground,
            shadowElevation = 14.dp,
            border = androidx.compose.foundation.BorderStroke(style.metrics.strokeWidth, style.colors.stroke),
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.88f)
                .widthIn(max = 340.dp)
                .align(Alignment.CenterStart)
                .offset {
                    IntOffset(
                        x = (-drawerWidthPx * (1f - clampedProgress)).roundToInt(),
                        y = 0
                    )
                }
                .graphicsLayer {
                    alpha = clampedProgress.coerceAtLeast(0.001f)
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI 酒馆",
                            color = style.colors.primaryText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "角色与会话",
                            color = style.colors.secondaryText,
                            fontSize = 12.sp
                        )
                    }
                    actions.onAddCompanion?.let { add ->
                        IconButton(onClick = add) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                tint = style.colors.accent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_x),
                            contentDescription = null,
                            tint = style.colors.secondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        DrawerSectionTitle("助手", style)
                    }
                    items(companions, key = { it.id }) { companion ->
                        val expanded = companion.id == expandedCompanionId
                        val companionSessions = sessionsByCompanion[companion.id].orEmpty()
                        Column(
                            modifier = Modifier.animateContentSize(
                                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                            ),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AiCompanionDrawerItem(
                                companion = companion,
                                selected = companion.id == currentCompanionId,
                                expanded = expanded,
                                style = style,
                                onSelect = {
                                    onToggleCompanion(companion.id)
                                },
                                onLongPress = actions.onCompanionLongPress?.let { action ->
                                    {
                                        onDismiss()
                                        action(companion)
                                    }
                                }
                            )
                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically(
                                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                                ) + fadeIn(animationSpec = tween(durationMillis = 140)),
                                exit = shrinkVertically(
                                    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(durationMillis = 120))
                            ) {
                                AiCompanionSessionPanel(
                                    sessions = companionSessions,
                                    currentSessionId = currentSessionId,
                                    style = style,
                                    onNewChat = {
                                        actions.onNewCompanionChat?.invoke(companion.id)
                                            ?: actions.onNewChat()
                                        onDismiss()
                                    },
                                    onSelect = { session ->
                                        actions.onSelectCompanionSession?.invoke(companion.id, session.id)
                                            ?: run {
                                                if (companion.id != currentCompanionId) {
                                                    actions.onSelectCompanion?.invoke(companion.id)
                                                }
                                                actions.onSelectSession?.invoke(session.id)
                                            }
                                        onDismiss()
                                    },
                                    onLongPress = actions.onDeleteSession
                                )
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AiCompanionSessionPanel(
    sessions: List<AiChatSession>,
    currentSessionId: String,
    style: AiComposeStyle,
    onNewChat: () -> Unit,
    onSelect: (AiChatSession) -> Unit,
    onLongPress: ((AiChatSession) -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(style.metrics.cardRadius),
        color = style.colors.assistantBubble.copy(alpha = 0.72f),
        border = androidx.compose.foundation.BorderStroke(style.metrics.strokeWidth, style.colors.stroke),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "会话",
                    color = style.colors.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    onClick = onNewChat,
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    color = style.colors.accent.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = "新建",
                        color = style.colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            if (sessions.isEmpty()) {
                Text(
                    text = "还没有历史会话",
                    color = style.colors.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 5.dp)
                )
            } else {
                sessions.take(8).forEach { session ->
                    AiSessionDrawerItem(
                        session = session,
                        selected = session.id == currentSessionId,
                        style = style,
                        onSelect = { onSelect(session) },
                        onLongPress = onLongPress?.let { action -> { action(session) } }
                    )
                }
                if (sessions.size > 8) {
                    Text(
                        text = "还有 ${sessions.size - 8} 个会话",
                        color = style.colors.secondaryText,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionTitle(
    text: String,
    style: AiComposeStyle,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = style.colors.secondaryText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 2.dp, vertical = 4.dp)
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AiCompanionDrawerItem(
    companion: AiChatCompanionConfig,
    selected: Boolean,
    expanded: Boolean,
    style: AiComposeStyle,
    onSelect: () -> Unit,
    onLongPress: (() -> Unit)?
) {
    val isDefault = companion.id == AiChatCompanionConfig.DEFAULT_COMPANION_ID
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.cardRadius))
            .background(if (selected) style.colors.accent.copy(alpha = 0.11f) else style.colors.cardSurface)
            .border(
                style.metrics.strokeWidth,
                if (selected) style.colors.accent.copy(alpha = 0.10f) else style.colors.stroke,
                RoundedCornerShape(style.metrics.cardRadius)
            )
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongPress
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) style.colors.accent.copy(alpha = 0.72f) else Color.Transparent)
        )
        Spacer(modifier = Modifier.width(9.dp))
        AiCompanionAvatar(companion, style, 42)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(
                text = companion.name,
                color = style.colors.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    isDefault -> "默认系统提示词"
                    companion.bookKey.isNotBlank() -> "角色 · ${displayBookKeyLabel(companion.bookKey)}"
                    else -> "角色"
                },
                color = style.colors.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            painter = painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
            contentDescription = null,
            tint = style.colors.secondaryText.copy(alpha = 0.72f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AiSessionDrawerItem(
    session: AiChatSession,
    selected: Boolean,
    style: AiComposeStyle,
    onSelect: () -> Unit,
    onLongPress: (() -> Unit)?
) {
    val timeFormat = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.cardRadius))
            .background(if (selected) style.colors.accent.copy(alpha = 0.10f) else style.colors.cardSurface)
            .border(
                style.metrics.strokeWidth,
                if (selected) style.colors.accent.copy(alpha = 0.30f) else style.colors.stroke,
                RoundedCornerShape(style.metrics.cardRadius)
            )
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) style.colors.accent else Color.Transparent)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(
                text = session.title.ifBlank { "未命名会话" },
                color = style.colors.primaryText,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${timeFormat.format(java.util.Date(session.updatedAt))} · ${session.messages.size} 条消息",
                color = style.colors.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun AiCompanionAvatar(
    companion: AiChatCompanionConfig,
    style: AiComposeStyle,
    sizeDp: Int
) {
    if (companion.avatar.isNotBlank()) {
        CharacterAvatar(
            path = companion.avatar,
            contentDescription = companion.name,
            sizeDp = sizeDp
        )
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(style.colors.toolSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    if (companion.id == AiChatCompanionConfig.DEFAULT_COMPANION_ID) {
                        R.drawable.ic_bottom_ai_e
                    } else {
                        R.drawable.ic_bottom_person_e
                    }
                ),
                contentDescription = null,
                tint = style.colors.accent,
                modifier = Modifier.size((sizeDp * 0.55f).dp)
            )
        }
    }
}

private fun displayBookKeyLabel(bookKey: String): String {
    val value = bookKey.trim()
    if (!value.startsWith("work:")) return value
    val body = value.removePrefix("work:")
    val parts = body.split('/', limit = 2)
    return when {
        parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() -> "${parts[1]} · ${parts[0]}"
        body.isNotBlank() -> body
        else -> value
    }
}

@Composable
private fun AiEmptyState(style: AiComposeStyle) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(style.metrics.cardRadius),
            color = style.colors.cardSurface,
            border = androidx.compose.foundation.BorderStroke(style.metrics.strokeWidth, style.colors.stroke)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bottom_ai_e),
                    contentDescription = stringResource(R.string.ai),
                    tint = style.colors.secondaryText,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.ai_chat_empty),
                    color = style.colors.secondaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun AiMessageRow(
    item: AiChatUiItem,
    currentCompanion: AiChatCompanionConfig,
    userAvatar: String?,
    style: AiComposeStyle,
    speechState: AiChatSpeechPlayer.PlaybackState,
    onSpeak: ((String, AiChatCompanionConfig, String) -> Unit)?,
    onToolPreview: (AiToolDisplayPayload) -> Unit,
    onProcessExpanded: () -> Unit
) {
    when (item) {
        is AiChatUiItem.User -> AiUserMessageRow(item, userAvatar, style)
        is AiChatUiItem.Assistant -> AiAssistantMessageRow(
            message = item,
            companion = currentCompanion,
            style = style,
            speechState = speechState,
            onSpeak = onSpeak,
            onToolPreview = onToolPreview,
            onProcessExpanded = onProcessExpanded
        )
    }
}

@Composable
private fun AiUserMessageRow(
    message: AiChatUiItem.User,
    userAvatar: String?,
    style: AiComposeStyle
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * 0.76f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 8.dp
                ),
                color = style.colors.userBubble,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    style.colors.userBubbleStroke.copy(alpha = 0.42f)
                ),
                shadowElevation = 1.dp,
                modifier = Modifier.widthIn(max = bubbleMaxWidth)
            ) {
                AiPlainSelectableText(
                    content = message.content,
                    color = style.colors.userText,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            AiUserAvatar(
                avatar = userAvatar,
                style = style,
                sizeDp = 34
            )
        }
    }
}

@Composable
private fun AiAssistantMessageRow(
    message: AiChatUiItem.Assistant,
    companion: AiChatCompanionConfig,
    style: AiComposeStyle,
    speechState: AiChatSpeechPlayer.PlaybackState,
    onSpeak: ((String, AiChatCompanionConfig, String) -> Unit)?,
    onToolPreview: (AiToolDisplayPayload) -> Unit,
    onProcessExpanded: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * 0.78f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            AiCompanionAvatar(companion, style, 36)
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                modifier = Modifier.widthIn(max = bubbleMaxWidth),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                message.parts.forEach { part ->
                    key(part.id) {
                        when (part) {
                            is AiMessagePartUi.Text -> AiAssistantTextPart(
                                part = part,
                                companion = companion,
                                style = style,
                                speechState = speechState,
                                onSpeak = onSpeak
                            )
                            is AiMessagePartUi.ProcessChain -> AiProcessPart(part, style, onToolPreview, onProcessExpanded)
                            is AiMessagePartUi.SearchBooks -> AiSearchBookInlinePart(part, style, onToolPreview)
                            is AiMessagePartUi.Images -> AiImageInlinePart(part, style, onToolPreview)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiAssistantTextPart(
    part: AiMessagePartUi.Text,
    companion: AiChatCompanionConfig,
    style: AiComposeStyle,
    speechState: AiChatSpeechPlayer.PlaybackState,
    onSpeak: ((String, AiChatCompanionConfig, String) -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(style.metrics.cardRadius),
        color = style.colors.assistantBubble,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(
            style.metrics.strokeWidth,
            style.colors.assistantBubbleStroke.copy(alpha = 0.62f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (part.pending) {
                Text(
                    text = part.content,
                    color = style.colors.primaryText,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            } else {
                AiMarkdownText(
                    content = part.content,
                    style = style
                )
            }
            if (!part.pending && onSpeak != null && part.content.isNotBlank()) {
                val isSpeaking = speechState.key == part.id && speechState.active
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(style.colors.accent.copy(alpha = if (isSpeaking) 0.20f else 0.10f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onSpeak(part.content, companion, part.id)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isSpeaking) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp
                            ),
                            contentDescription = null,
                            tint = style.colors.accent,
                            modifier = Modifier.size(if (isSpeaking) 15.dp else 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiUserAvatar(
    avatar: String?,
    style: AiComposeStyle,
    sizeDp: Int
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(style.colors.toolSurface),
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        },
        update = { imageView ->
            ImageLoader.load(context, avatar)
                .placeholder(R.drawable.ic_read_record_default_avatar)
                .error(R.drawable.ic_read_record_default_avatar)
                .centerCrop()
                .into(imageView)
        }
    )
}

@Composable
private fun AiFallbackAvatar(
    iconRes: Int,
    style: AiComposeStyle,
    sizeDp: Int,
    accent: Boolean
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(if (accent) style.colors.accent.copy(alpha = 0.12f) else style.colors.toolSurface),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (accent) style.colors.accent else style.colors.secondaryText,
            modifier = Modifier.size((sizeDp * 0.56f).dp)
        )
    }
}

@Composable
private fun AiProcessPart(
    part: AiMessagePartUi.ProcessChain,
    style: AiComposeStyle,
    onToolPreview: (AiToolDisplayPayload) -> Unit,
    onProcessExpanded: () -> Unit
) {
    AiProcessTimelineCard(
        steps = part.steps,
        style = style,
        onToolClick = { step ->
            step.payload?.let(onToolPreview)
        },
        onExpandedChange = onProcessExpanded
    )
}

@Composable
private fun AiSearchBookInlinePart(
    part: AiMessagePartUi.SearchBooks,
    style: AiComposeStyle,
    onToolPreview: (AiToolDisplayPayload) -> Unit
) {
    AiInfoPill(
        text = "书籍结果 ${part.books.size} 条",
        style = style,
        onClick = {
            onToolPreview(
                AiToolDisplayPayload(
                    type = AiToolPreviewType.BookResults,
                    title = "书籍结果",
                    summary = "共 ${part.books.size} 条",
                    raw = "",
                    books = part.books
                )
            )
        }
    )
}

@Composable
private fun AiImageInlinePart(
    part: AiMessagePartUi.Images,
    style: AiComposeStyle,
    onToolPreview: (AiToolDisplayPayload) -> Unit
) {
    AiInfoPill(
        text = "图片结果 ${part.images.size} 张",
        style = style,
        onClick = {
            onToolPreview(
                AiToolDisplayPayload(
                    type = AiToolPreviewType.ImageResult,
                    title = "图片结果",
                    summary = "共 ${part.images.size} 张",
                    raw = "",
                    images = part.images
                )
            )
        }
    )
}

@Composable
private fun AiInfoPill(
    text: String,
    style: AiComposeStyle,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(style.metrics.chipRadius),
        color = style.colors.accent.copy(alpha = 0.10f)
    ) {
        Text(
            text = text,
            color = style.colors.accent,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun AiMarkdownText(
    content: String,
    style: AiComposeStyle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val markwon = remember(context) {
        AiMarkdownRender.createMarkwon(context)
    }
    val normalizedContent = remember(content) { normalizeAiMarkdownContent(content) }
    val markdown = remember(markwon, normalizedContent) { markwon.toMarkdown(normalizedContent) }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthBucket = maxWidth.value.toInt()
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = {
                TextView(it).apply {
                    includeFontPadding = true
                    setLineSpacing(2f, 1.05f)
                    AiMarkdownRender.setNativeSelectionWithLinkTap(this)
                }
            },
            update = { textView ->
                val textColor = style.colors.primaryText.toArgb()
                val linkColor = style.colors.accent.toArgb()
                textView.setTextColor(textColor)
                textView.textSize = 15f
                textView.setLinkTextColor(linkColor)
                val renderKey = buildString {
                    append(AiMarkdownRender.renderKey("compose", normalizedContent, false, textView, context))
                    append(':')
                    append(widthBucket)
                    append(':')
                    append(textColor)
                    append(':')
                    append(linkColor)
                }
                if (textView.tag != renderKey) {
                    markwon.setParsedMarkdown(textView, markdown)
                    installSearchBookLinks(textView, linkColor)
                    textView.tag = renderKey
                }
            }
        )
    }
}

@Composable
private fun AiPlainSelectableText(
    content: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = {
            TextView(it).apply {
                includeFontPadding = true
                setLineSpacing(2f, 1.05f)
                AiMarkdownRender.setNativeSelectionWithLinkTap(this)
            }
        },
        update = { textView ->
            val textColor = color.toArgb()
            textView.setTextColor(textColor)
            textView.textSize = 15f
            val renderKey = "plain:${content.hashCode()}:$textColor"
            if (textView.tag != renderKey) {
                textView.text = content
                textView.tag = renderKey
            }
        }
    )
}

@Composable
private fun AiMarkdownRichText(
    text: String,
    style: AiComposeStyle,
    modifier: Modifier = Modifier,
    color: Color = style.colors.primaryText,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 21.sp,
    fontWeight: FontWeight? = null
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val annotated = remember(text, color, style.colors.accent) {
        buildAiInlineMarkdown(text, color, style.colors.accent)
    }
    if (annotated.getStringAnnotations(AI_LINK_TAG, 0, annotated.length).isEmpty()) {
        Text(
            text = annotated,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            modifier = modifier
        )
    } else {
        ClickableText(
            text = annotated,
            style = TextStyle(
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = fontWeight
            ),
            modifier = modifier,
            onClick = { offset ->
                annotated.getStringAnnotations(AI_LINK_TAG, offset, offset)
                    .firstOrNull()
                    ?.item
                    ?.let { url ->
                        if (url.startsWith(searchBookScheme)) {
                            openSearchBookLink(context, url)
                        } else {
                            runCatching { uriHandler.openUri(url) }
                        }
                    }
            }
        )
    }
}

@Composable
private fun AiMarkdownInlineContent(
    text: String,
    style: AiComposeStyle,
    modifier: Modifier = Modifier,
    color: Color = style.colors.primaryText
) {
    val pieces = remember(text) { parseMarkdownInlinePieces(text) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        pieces.forEach { piece ->
            when (piece) {
                is AiMarkdownInlinePiece.Text -> {
                    if (piece.text.isNotBlank()) {
                        AiMarkdownRichText(
                            text = piece.text.trim(),
                            style = style,
                            color = color
                        )
                    }
                }
                is AiMarkdownInlinePiece.Image -> AiMarkdownImage(
                    image = piece,
                    style = style,
                    compact = false
                )
            }
        }
    }
}

@Composable
private fun AiMarkdownTable(table: AiMarkdownBlock.Table, style: AiComposeStyle) {
    Surface(
        shape = RoundedCornerShape(style.metrics.chipRadius),
        color = style.colors.processSurface,
        border = androidx.compose.foundation.BorderStroke(style.metrics.strokeWidth, style.colors.stroke)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(1.dp)
        ) {
            val columnCount = table.headers.size.coerceAtLeast(1)
            val cellWidth = when (columnCount) {
                1 -> maxWidth
                2 -> maxWidth / 2
                else -> 132.dp
            }
            val rows = table.rows.map { normalizeTableRow(it, columnCount) }
            val totalWidth = cellWidth * columnCount
            val rawHeight = 48.dp + 58.dp * rows.size
            val scale = if (totalWidth > maxWidth) {
                min(1f, maxWidth.value / totalWidth.value)
            } else {
                1f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rawHeight * scale)
            ) {
                Column(
                    modifier = Modifier
                        .width(totalWidth)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                ) {
                    AiMarkdownTableRow(
                        cells = normalizeTableRow(table.headers, columnCount),
                        style = style,
                        header = true,
                        cellWidth = cellWidth,
                        minHeight = 48.dp
                    )
                    rows.forEach { row ->
                        AiMarkdownTableRow(
                            cells = row,
                            style = style,
                            header = false,
                            cellWidth = cellWidth,
                            minHeight = 58.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiMarkdownTableRow(
    cells: List<String>,
    style: AiComposeStyle,
    header: Boolean,
    cellWidth: androidx.compose.ui.unit.Dp,
    minHeight: androidx.compose.ui.unit.Dp
) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min)
    ) {
        cells.forEach { cell ->
            Box(
                modifier = Modifier
                    .width(cellWidth)
                    .fillMaxHeight()
                    .heightIn(min = minHeight)
                    .background(if (header) style.colors.accent.copy(alpha = 0.10f) else Color.Transparent)
                    .border(style.metrics.strokeWidth, style.colors.stroke)
                    .padding(horizontal = 9.dp, vertical = 8.dp)
            ) {
                val pieces = remember(cell) { parseMarkdownInlinePieces(cell) }
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pieces.forEach { piece ->
                        when (piece) {
                            is AiMarkdownInlinePiece.Text -> {
                                if (piece.text.isNotBlank()) {
                                    AiMarkdownRichText(
                                        text = piece.text.trim(),
                                        style = style,
                                        color = if (header) style.colors.accent else style.colors.primaryText,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = if (header) FontWeight.SemiBold else null
                                    )
                                }
                            }
                            is AiMarkdownInlinePiece.Image -> AiMarkdownImage(
                                image = piece,
                                style = style,
                                compact = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiMarkdownImage(
    image: AiMarkdownInlinePiece.Image,
    style: AiComposeStyle,
    compact: Boolean
) {
    val context = LocalContext.current
    val imageUrl = remember(image.url) { normalizeAiMarkdownImageUrl(image.url) }
    val size = if (compact) 52.dp else 180.dp
    Surface(
        modifier = Modifier
            .width(size)
            .height(size)
            .clip(RoundedCornerShape(style.metrics.chipRadius))
            .clickable {
                (context as? androidx.appcompat.app.AppCompatActivity)
                    ?.showDialogFragment(PhotoDialog(imageUrl))
            },
        shape = RoundedCornerShape(style.metrics.chipRadius),
        color = style.colors.background.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(style.metrics.strokeWidth, style.colors.stroke)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                ImageView(it).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = image.alt
                }
            },
            update = {
                ImageLoader.load(context, imageUrl)
                    .error(R.drawable.image_loading_error)
                    .into(it)
            }
        )
    }
}

private sealed class AiMarkdownBlock {
    data class Paragraph(val text: String) : AiMarkdownBlock()
    data class Heading(val level: Int, val text: String) : AiMarkdownBlock()
    data class Bullet(val text: String) : AiMarkdownBlock()
    data class Numbered(val number: Int, val text: String) : AiMarkdownBlock()
    data class Quote(val text: String) : AiMarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : AiMarkdownBlock()
    data class Code(val text: String) : AiMarkdownBlock()
    data object Divider : AiMarkdownBlock()
}

private sealed class AiMarkdownInlinePiece {
    data class Text(val text: String) : AiMarkdownInlinePiece()
    data class Image(val alt: String, val url: String) : AiMarkdownInlinePiece()
}

private fun parseAiMarkdownBlocks(content: String): List<AiMarkdownBlock> {
    val blocks = mutableListOf<AiMarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += AiMarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trimEnd()
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            flushParagraph()
            index++
            continue
        }
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val codeLines = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                codeLines += lines[index]
                index++
            }
            if (index < lines.size) index++
            blocks += AiMarkdownBlock.Code(codeLines.joinToString("\n"))
            continue
        }
        val headingMatch = headingRegex.matchEntire(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            blocks += AiMarkdownBlock.Heading(
                level = headingMatch.groupValues[1].length.coerceIn(1, 6),
                text = headingMatch.groupValues[2].trim()
            )
            index++
            continue
        }
        if (dividerRegex.matches(trimmed)) {
            flushParagraph()
            blocks += AiMarkdownBlock.Divider
            index++
            continue
        }
        val quoteMatch = quoteRegex.matchEntire(trimmed)
        if (quoteMatch != null) {
            flushParagraph()
            blocks += AiMarkdownBlock.Quote(quoteMatch.groupValues[1].trim())
            index++
            continue
        }
        val bulletMatch = bulletRegex.matchEntire(trimmed)
        if (bulletMatch != null) {
            flushParagraph()
            blocks += AiMarkdownBlock.Bullet(bulletMatch.groupValues[1].trim())
            index++
            continue
        }
        val numberedMatch = numberedRegex.matchEntire(trimmed)
        if (numberedMatch != null) {
            flushParagraph()
            blocks += AiMarkdownBlock.Numbered(
                number = numberedMatch.groupValues[1].toIntOrNull() ?: 1,
                text = numberedMatch.groupValues[2].trim()
            )
            index++
            continue
        }
        if (looksLikeMarkdownTable(lines, index)) {
            flushParagraph()
            val tableLines = mutableListOf(line)
            index++
            while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                tableLines += lines[index].trimEnd()
                index++
            }
            parseMarkdownTable(tableLines)?.let { table ->
                blocks += table
            } ?: run {
                blocks += AiMarkdownBlock.Paragraph(tableLines.joinToString("\n"))
            }
            continue
        }
        paragraph += line
        index++
    }
    flushParagraph()
    return blocks.ifEmpty { listOf(AiMarkdownBlock.Paragraph(" ")) }
}

private fun parseMarkdownTable(lines: List<String>): AiMarkdownBlock.Table? {
    if (lines.size < 2 || !markdownTableSeparatorRegex.matches(lines[1].trim())) return null
    val headers = parseMarkdownTableRow(lines[0])
    if (headers.isEmpty()) return null
    val rows = lines.drop(2)
        .map { parseMarkdownTableRow(it) }
        .filter { row -> row.any { it.isNotBlank() } }
    return AiMarkdownBlock.Table(headers, rows)
}

private fun parseMarkdownTableRow(line: String): List<String> {
    return line.trim()
        .trim('|')
        .split('|')
        .map { it.trim() }
}

private fun normalizeTableRow(row: List<String>, size: Int): List<String> {
    return when {
        row.size == size -> row
        row.size > size -> row.take(size)
        else -> row + List(size - row.size) { "" }
    }
}

private fun parseMarkdownInlinePieces(text: String): List<AiMarkdownInlinePiece> {
    val pieces = mutableListOf<AiMarkdownInlinePiece>()
    var index = 0
    markdownImageRegex.findAll(text).forEach { match ->
        if (match.range.first > index) {
            pieces += AiMarkdownInlinePiece.Text(text.substring(index, match.range.first))
        }
        val alt = match.groupValues[1].trim()
        val url = match.groupValues[2].trim()
        if (url.isNotBlank()) {
            pieces += AiMarkdownInlinePiece.Image(alt = alt, url = url)
        }
        index = match.range.last + 1
    }
    if (index < text.length) {
        pieces += AiMarkdownInlinePiece.Text(text.substring(index))
    }
    return pieces.ifEmpty { listOf(AiMarkdownInlinePiece.Text(text)) }
}

private fun normalizeAiMarkdownContent(content: String): String {
    val markdownNormalized = markdownImageRegex.replace(content) { match ->
        val alt = match.groupValues[1]
        val url = normalizeAiMarkdownImageUrl(match.groupValues[2])
        "![${alt}](${url})"
    }
    return htmlImageSrcRegex.replace(markdownNormalized) { match ->
        val quote = match.groupValues[1]
        val url = normalizeAiMarkdownImageUrl(match.groupValues[2])
        """src=$quote$url$quote"""
    }
}

private fun normalizeAiMarkdownImageUrl(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return value
    AiImageGalleryManager.resolveImageFile(value)?.let { return it.toURI().toString() }
    if (value.startsWith("file://", true)) return value
    if (value.startsWith("/", true)) return value.parseToUri().toString()
    return value
}

private fun buildAiInlineMarkdown(text: String, color: Color, accent: Color): AnnotatedString {
    return buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val match = inlineMarkdownRegex.find(text, index)
            if (match == null) {
                append(text.substring(index))
                break
            }
            if (match.range.first > index) {
                append(text.substring(index, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("`") -> withStyle(
                    SpanStyle(
                        color = color,
                        background = accent.copy(alpha = 0.10f),
                        fontFamily = FontFamily.Monospace
                    )
                ) {
                    append(token.trim('`'))
                }
                token.startsWith("[") -> {
                    val label = match.groupValues[2]
                    val url = match.groupValues[3]
                    pushStringAnnotation(AI_LINK_TAG, url)
                    withStyle(
                        SpanStyle(
                            color = accent,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(label)
                    }
                    pop()
                }
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(token.removeSurrounding("**"))
                }
                else -> append(token)
            }
            index = match.range.last + 1
        }
    }
}

private fun installSearchBookLinks(textView: TextView, accentColor: Int) {
    val spannable = textView.text as? Spannable ?: return
    val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
    spans.forEach { span ->
        val url = span.url
        if (!url.startsWith(searchBookScheme)) return@forEach
        val start = spannable.getSpanStart(span)
        val end = spannable.getSpanEnd(span)
        val flags = spannable.getSpanFlags(span)
        spannable.removeSpan(span)
        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openSearchBookLink(widget.context, url)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = accentColor
                    ds.isUnderlineText = false
                }
            },
            start,
            end,
            flags
        )
    }
}

private fun openSearchBookLink(context: android.content.Context, url: String) {
    val uri = Uri.parse(url)
    val book = SearchBook(
        name = uri.getQueryParameter("name").orEmpty(),
        author = uri.getQueryParameter("author").orEmpty(),
        bookUrl = uri.getQueryParameter("bookUrl").orEmpty(),
        origin = uri.getQueryParameter("origin").orEmpty(),
        originName = uri.getQueryParameter("originName").orEmpty()
    )
    if (book.bookUrl.isBlank() || book.origin.isBlank()) return
    SearchBookOpenHelper.open(
        context,
        book,
        uri.getQueryParameter("target") == "video"
    )
}

private fun looksLikeMarkdownTable(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    return lines[index].contains('|') && markdownTableSeparatorRegex.matches(lines[index + 1].trim())
}

private const val AI_LINK_TAG = "ai_link"
private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val dividerRegex = Regex("^([-*_]\\s*){3,}$")
private val quoteRegex = Regex("^>\\s?(.+)$")
private val bulletRegex = Regex("^[-*+]\\s+(.+)$")
private val numberedRegex = Regex("^(\\d+)\\.\\s+(.+)$")
private val markdownTableSeparatorRegex = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$")
private val markdownImageRegex = Regex("!\\[([^\\]]*)]\\(([^)]+)\\)")
private val htmlImageSrcRegex = Regex("""src\s*=\s*(['"])([^'"]+)\1""", RegexOption.IGNORE_CASE)
private val inlineMarkdownRegex = Regex("`([^`]+)`|\\[([^\\]]+)]\\(([^)]+)\\)|\\*\\*([^*]+)\\*\\*")

@Composable
private fun AiJumpButtons(
    style: AiComposeStyle,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AiJumpButton(style = style, rotation = 180f, onClick = onPrevious)
        AiJumpButton(style = style, rotation = 0f, onClick = onNext)
    }
}

@Composable
private fun AiJumpButton(
    style: AiComposeStyle,
    rotation: Float,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = style.colors.cardSurface,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(style.metrics.strokeWidth, style.colors.stroke),
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = style.colors.primaryText,
                modifier = Modifier
                    .size(22.dp)
                    .then(Modifier.graphicsLayer(rotationZ = rotation))
            )
        }
    }
}

@Composable
private fun AiComposer(
    requesting: Boolean,
    enterToSend: Boolean,
    style: AiComposeStyle,
    actions: AiChatScreenActions,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf("") }
    fun submitDraft() {
        val content = text.trim()
        if (!requesting && content.isNotEmpty() && actions.onSend(content)) {
            text = ""
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = style.colors.composerSurface,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(style.metrics.strokeWidth, style.colors.composerStroke)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 132.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (text.isBlank()) {
                    Text(
                        text = stringResource(R.string.ai_chat_hint),
                        color = style.colors.secondaryText.copy(alpha = 0.72f),
                        fontSize = 15.sp
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (enterToSend) submitDraft()
                        }
                    ),
                    textStyle = TextStyle(
                        color = style.colors.primaryText,
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Surface(
                onClick = {
                    if (requesting) {
                        actions.onStop()
                    } else {
                        submitDraft()
                    }
                },
                enabled = requesting || text.isNotBlank(),
                shape = CircleShape,
                color = style.colors.accent,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(
                            if (requesting) R.drawable.ic_stop_black_24dp else R.drawable.ic_arrow_right
                        ),
                        contentDescription = stringResource(
                            if (requesting) R.string.ai_chat_stop else R.string.ai_chat_send
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

private const val searchBookScheme = "legado-search-book://"
