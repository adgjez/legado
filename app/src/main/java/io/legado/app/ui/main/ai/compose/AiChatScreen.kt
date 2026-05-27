package io.legado.app.ui.main.ai.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiChatViewModel

@Stable
data class AiChatScreenActions(
    val onSend: (String) -> Unit,
    val onStop: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onNewChat: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onSelectModel: () -> Unit,
    val onGenerateImage: (() -> Unit)? = null
)

@Composable
fun AiChatRoute(
    viewModel: AiChatViewModel,
    lifecycleOwner: LifecycleOwner,
    compactHeader: Boolean,
    applyStatusPadding: Boolean,
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
    AiChatScreen(
        messages = messages,
        requesting = requesting,
        modelLabel = modelLabel,
        compactHeader = compactHeader,
        applyStatusPadding = applyStatusPadding,
        actions = actions
    )
}

@Composable
fun AiChatScreen(
    messages: List<AiChatMessage>,
    requesting: Boolean,
    modelLabel: String,
    compactHeader: Boolean,
    applyStatusPadding: Boolean,
    actions: AiChatScreenActions
) {
    val context = LocalContext.current
    val style = aiComposeStyle(context)
    val listState = rememberLazyListState()
    val visibleMessages = remember(messages) {
        messages.filterNot { (it.kind ?: AiChatMessage.Kind.TEXT) == AiChatMessage.Kind.STATUS }
    }
    val shouldAutoScroll by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            total <= 1 || (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 2
        }
    }
    LaunchedEffect(visibleMessages.size, visibleMessages.lastOrNull()?.updatedAt, requesting) {
        if (visibleMessages.isNotEmpty() && shouldAutoScroll) {
            listState.animateScrollToItem(visibleMessages.lastIndex)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.colors.pageBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (applyStatusPadding) Modifier.statusBarsPadding() else Modifier)
        ) {
            AiChatTopBar(
                modelLabel = modelLabel,
                compactHeader = compactHeader,
                style = style,
                actions = actions
            )
            Box(modifier = Modifier.weight(1f)) {
                if (visibleMessages.isEmpty()) {
                    AiEmptyState(style = style)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 14.dp,
                            top = 10.dp,
                            end = 14.dp,
                            bottom = 110.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleMessages, key = { it.id }) { message ->
                            AiLegacyMessageRow(message = message, style = style)
                        }
                    }
                }
            }
        }
        AiComposer(
            requesting = requesting,
            style = style,
            actions = actions,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun AiChatTopBar(
    modelLabel: String,
    compactHeader: Boolean,
    style: AiComposeStyle,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ai),
                color = style.colors.primaryText,
                fontSize = if (compactHeader) 24.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (!compactHeader) {
                Text(
                    text = modelLabel.ifBlank { stringResource(R.string.ai_current_model_summary_empty) },
                    color = style.colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (modelLabel.isNotBlank()) {
            Surface(
                onClick = actions.onSelectModel,
                shape = RoundedCornerShape(style.metrics.chipRadius),
                color = style.colors.accent.copy(alpha = 0.10f)
            ) {
                Text(
                    text = modelLabel,
                    color = style.colors.accent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        IconButton(onClick = actions.onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.ai_setting),
                tint = style.colors.primaryText,
                modifier = Modifier.size(21.dp)
            )
        }
        IconButton(onClick = { menuExpanded = true }) {
            Text(
                text = "...",
                color = style.colors.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_new_chat)) },
                onClick = {
                    menuExpanded = false
                    actions.onNewChat()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_chat_history)) },
                onClick = {
                    menuExpanded = false
                    actions.onOpenHistory()
                }
            )
            actions.onGenerateImage?.let { generate ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_image_generate)) },
                    onClick = {
                        menuExpanded = false
                        generate()
                    }
                )
            }
        }
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
private fun AiLegacyMessageRow(message: AiChatMessage, style: AiComposeStyle) {
    val isUser = message.role == AiChatMessage.Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 8.dp,
                bottomEnd = if (isUser) 8.dp else 20.dp
            ),
            color = if (isUser) Color(0xff95ec69.toInt()) else style.colors.assistantBubble,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isUser) Color(0xff7cd452.toInt()) else style.colors.assistantBubbleStroke
            ),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.92f)
        ) {
            SelectionContainer {
                Text(
                    text = message.content.ifBlank { if (message.pending) "..." else " " },
                    color = Color(0xff202020.toInt()),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun AiComposer(
    requesting: Boolean,
    style: AiComposeStyle,
    actions: AiChatScreenActions,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
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
                        val content = text.trim()
                        if (content.isNotEmpty()) {
                            text = ""
                            actions.onSend(content)
                        }
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
