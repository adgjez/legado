package io.legado.app.ui.main.ai.compose

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
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
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiChatViewModel
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.launch
import android.net.Uri
import android.text.Spannable
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView

@Stable
data class AiChatScreenActions(
    val onSend: (String) -> Boolean,
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
    val enterToSend = remember(refreshToken) { AppConfig.aiEnterToSend }
    AiChatScreen(
        messages = messages,
        requesting = requesting,
        modelLabel = modelLabel,
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
    enterToSend: Boolean,
    compactHeader: Boolean,
    actions: AiChatScreenActions
) {
    val context = LocalContext.current
    val style = aiComposeStyle(context)
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var toolPreviewPayload by remember { mutableStateOf<AiToolDisplayPayload?>(null) }
    val uiItems = remember(context, messages) {
        buildAiChatUiItems(context, messages)
    }
    val shouldAutoScroll by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            total <= 1 || (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 2
        }
    }
    LaunchedEffect(uiItems.size, uiItems.lastOrNull()?.id, requesting) {
        if (uiItems.isNotEmpty() && shouldAutoScroll) {
            listState.animateScrollToItem(uiItems.lastIndex)
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
                .statusBarsPadding()
        ) {
            AiChatTopBar(
                modelLabel = modelLabel,
                requesting = requesting,
                compactHeader = compactHeader,
                style = style,
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
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 14.dp,
                            top = 10.dp,
                            end = 14.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiItems, key = { it.id }) { item ->
                            AiMessageRow(
                                item = item,
                                style = style,
                                onToolPreview = { toolPreviewPayload = it }
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
                    val target = (current - 1).coerceAtLeast(0)
                    coroutineScope.launch { listState.animateScrollToItem(target) }
                },
                onNext = {
                    val current = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        ?: listState.firstVisibleItemIndex
                    val target = (current + 1).coerceAtMost(uiItems.lastIndex)
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
    }
}

@Composable
private fun AiChatTopBar(
    modelLabel: String,
    requesting: Boolean,
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
        IconButton(onClick = actions.onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.ai_setting),
                tint = style.colors.primaryText,
                modifier = Modifier.size(21.dp)
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
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
                        add(AiTopMenuAction(stringResource(R.string.ai_setting)) { actions.onOpenSettings() })
                        actions.onGenerateImage?.let { generate ->
                            add(AiTopMenuAction(stringResource(R.string.ai_image_generate), generate))
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
    style: AiComposeStyle,
    onToolPreview: (AiToolDisplayPayload) -> Unit
) {
    when (item) {
        is AiChatUiItem.User -> AiUserMessageRow(item, style)
        is AiChatUiItem.Assistant -> AiAssistantMessageRow(item, style, onToolPreview)
    }
}

@Composable
private fun AiUserMessageRow(message: AiChatUiItem.User, style: AiComposeStyle) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
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
                style.colors.userBubbleStroke
            ),
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .animateContentSize()
        ) {
            SelectionContainer {
                Text(
                    text = message.content,
                    color = style.colors.userText,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun AiAssistantMessageRow(
    message: AiChatUiItem.Assistant,
    style: AiComposeStyle,
    onToolPreview: (AiToolDisplayPayload) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.94f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            message.parts.forEach { part ->
                when (part) {
                    is AiMessagePartUi.Text -> AiAssistantTextPart(part, style)
                    is AiMessagePartUi.ProcessChain -> AiProcessPart(part, style, onToolPreview)
                    is AiMessagePartUi.SearchBooks -> AiSearchBookInlinePart(part, style, onToolPreview)
                    is AiMessagePartUi.Images -> AiImageInlinePart(part, style, onToolPreview)
                }
            }
        }
    }
}

@Composable
private fun AiAssistantTextPart(part: AiMessagePartUi.Text, style: AiComposeStyle) {
    Surface(
        shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 8.dp,
            bottomEnd = 20.dp
        ),
        color = style.colors.assistantBubble,
        border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.assistantBubbleStroke),
        modifier = Modifier.animateContentSize()
    ) {
        SelectionContainer {
            AiMarkdownText(
                content = part.content,
                style = style,
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun AiProcessPart(
    part: AiMessagePartUi.ProcessChain,
    style: AiComposeStyle,
    onToolPreview: (AiToolDisplayPayload) -> Unit
) {
    AiProcessTimelineCard(
        steps = part.steps,
        style = style,
        onToolClick = { step ->
            step.payload?.let(onToolPreview)
        }
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
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
    }
    AndroidView(
        modifier = modifier,
        factory = {
            TextView(it).apply {
                textSize = 15f
                setTextColor(android.graphics.Color.rgb(32, 32, 32))
                setLineSpacing(2f, 1f)
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
            }
        },
        update = { textView ->
            textView.setTextColor(style.colors.primaryText.toArgb())
            textView.setLinkTextColor(style.colors.accent.toArgb())
            markwon.setMarkdown(textView, content.ifBlank { " " })
            textView.setTextColor(style.colors.primaryText.toArgb())
            textView.setLinkTextColor(style.colors.accent.toArgb())
            installSearchBookLinks(textView)
            textView.movementMethod = LinkMovementMethod.getInstance()
        }
    )
}

private fun installSearchBookLinks(textView: TextView) {
    val spannable = textView.text as? Spannable ?: return
    val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
    spans.forEach { span ->
        val url = span.url
        if (!url.startsWith(searchBookScheme)) return@forEach
        val start = spannable.getSpanStart(span)
        val end = spannable.getSpanEnd(span)
        val flags = spannable.getSpanFlags(span)
        spannable.removeSpan(span)
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
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
                    widget.context,
                    book,
                    uri.getQueryParameter("target") == "video"
                )
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = false
            }
        }, start, end, flags)
    }
}

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
