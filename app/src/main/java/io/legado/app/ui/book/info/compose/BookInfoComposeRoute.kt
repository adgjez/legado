package io.legado.app.ui.book.info.compose

import android.content.Context
import android.widget.TextView
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.utils.ColorUtils
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin

@Immutable
data class BookInfoChapterUi(
    val index: Int,
    val title: String,
    val isVolume: Boolean = false
)

@Immutable
data class BookInfoUiState(
    val name: String = "",
    val author: String = "",
    val originName: String = "",
    val latestChapterTitle: String = "",
    val readTimeText: String = "",
    val coverPath: String? = null,
    val intro: String = "",
    val kinds: List<String> = emptyList(),
    val groupText: String = "",
    val tocText: String = "",
    val chapterCount: Int = 0,
    val chapterPreview: List<BookInfoChapterUi> = emptyList(),
    val aiImageCount: Int = 0,
    val aiImagePaths: List<String> = emptyList(),
    val inBookshelf: Boolean = false,
    val hasCustomButton: Boolean = false,
    val loading: Boolean = false
)

@Immutable
data class BookInfoActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRefreshToc: () -> Unit = {},
    val onRead: () -> Unit = {},
    val onShelf: () -> Unit = {},
    val onChangeCover: () -> Unit = {},
    val onPreviewCover: () -> Unit = {},
    val onAuthorClick: () -> Unit = {},
    val onAuthorLongClick: () -> Unit = {},
    val onNameClick: () -> Unit = {},
    val onNameLongClick: () -> Unit = {},
    val onChangeSource: () -> Unit = {},
    val onEditSource: () -> Unit = {},
    val onChangeGroup: () -> Unit = {},
    val onOpenToc: () -> Unit = {},
    val onOpenChapter: (BookInfoChapterUi) -> Unit = {},
    val onOpenAiGallery: () -> Unit = {},
    val onCustomButton: () -> Unit = {},
    val onSetSourceVariable: () -> Unit = {},
    val onSetBookVariable: () -> Unit = {}
)

@Immutable
data class BookInfoComposeColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val accentContainer: Color,
    val actionText: Color,
    val scrim: Color
)

@Immutable
data class BookInfoComposeMetrics(
    val panelRadius: Dp,
    val actionRadius: Dp
)

@Immutable
data class BookInfoComposeStyle(
    val colors: BookInfoComposeColors,
    val metrics: BookInfoComposeMetrics
)

@Stable
fun bookInfoComposeStyle(context: Context): BookInfoComposeStyle {
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    val pageBackground = if (night) {
        0xff111318.toInt()
    } else {
        0xfff4f6f8.toInt()
    }
    val surface = if (night) 0xff20232a.toInt() else 0xffffffff.toInt()
    val variant = if (night) 0xff292d35.toInt() else 0xfff0f3f6.toInt()
    val accentContainer = ColorUtils.blendColors(
        surface,
        accent,
        if (night) 0.22f else 0.14f
    )
    val actionText = if (ColorUtils.isColorLight(accent)) 0xff202124.toInt() else 0xffffffff.toInt()
    return BookInfoComposeStyle(
        colors = BookInfoComposeColors(
            background = Color(pageBackground),
            surface = Color(surface),
            surfaceVariant = Color(variant),
            primaryText = Color(if (night) 0xfff5f6f8.toInt() else 0xff202124.toInt()),
            secondaryText = Color(if (night) 0xffaeb4bc.toInt() else 0xff68707a.toInt()),
            accent = Color(accent),
            accentContainer = Color(accentContainer),
            actionText = Color(actionText),
            scrim = Color(if (night) 0x99000000.toInt() else 0x66ffffff)
        ),
        metrics = BookInfoComposeMetrics(
            panelRadius = context.composePanelRadius(),
            actionRadius = context.composeActionRadius()
        )
    )
}

@Composable
fun BookInfoComposeRoute(
    state: BookInfoUiState,
    actions: BookInfoActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val style = remember(context) { bookInfoComposeStyle(context) }
    var showMoreMenu by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxSize().background(style.colors.background)) {
        BookInfoCoverBackdrop(
            coverPath = state.coverPath,
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .height(430.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 132.dp,
                bottom = 116.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                BookInfoPosterHero(state, actions, style)
            }
            item {
                BookInfoIntroPanel(
                    intro = state.intro.ifBlank { stringResource(R.string.intro_show_null) },
                    style = style
                )
            }
            item {
                BookInfoChapterPreviewPanel(state, actions, style)
            }
            item {
                BookInfoAiImagesPanel(state, actions, style)
            }
        }
        BookInfoBottomActions(
            state = state,
            actions = actions,
            style = style,
            modifier = Modifier.fillMaxWidth()
        )
        BookInfoFloatingTopBar(
            title = state.name,
            style = style,
            onBack = actions.onBack,
            onMore = { showMoreMenu = true },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )
        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .statusBarsPadding()
                    .align(Alignment.TopEnd)
                    .padding(top = 58.dp, end = 24.dp)
                    .size(22.dp),
                color = style.colors.accent,
                strokeWidth = 2.dp
            )
        }
        if (showMoreMenu) {
            BookInfoMoreActionSheet(
                state = state,
                style = style,
                actions = actions,
                onDismiss = { showMoreMenu = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookInfoMoreActionSheet(
    state: BookInfoUiState,
    style: BookInfoComposeStyle,
    actions: BookInfoActions,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = style.metrics.panelRadius, topEnd = style.metrics.panelRadius),
        containerColor = style.colors.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.more),
                color = style.colors.primaryText,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            BookInfoMoreActionItem(stringResource(R.string.refresh), style) {
                onDismiss()
                actions.onRefresh()
            }
            BookInfoMoreActionItem("刷新目录", style) {
                onDismiss()
                actions.onRefreshToc()
            }
            BookInfoMoreActionItem(stringResource(R.string.change_book_source_action), style) {
                onDismiss()
                actions.onChangeSource()
            }
            BookInfoMoreActionItem(stringResource(R.string.edit_source), style) {
                onDismiss()
                actions.onEditSource()
            }
            BookInfoMoreActionItem(stringResource(R.string.change_group), style) {
                onDismiss()
                actions.onChangeGroup()
            }
            BookInfoMoreActionItem(stringResource(R.string.set_source_variable), style) {
                onDismiss()
                actions.onSetSourceVariable()
            }
            BookInfoMoreActionItem(stringResource(R.string.set_book_variable), style) {
                onDismiss()
                actions.onSetBookVariable()
            }
            BookInfoMoreActionItem(stringResource(R.string.ai_image_gallery), style) {
                onDismiss()
                actions.onOpenAiGallery()
            }
            if (state.hasCustomButton) {
                BookInfoMoreActionItem(stringResource(R.string.custom_button), style) {
                    onDismiss()
                    actions.onCustomButton()
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun BookInfoMoreActionItem(
    text: String,
    style: BookInfoComposeStyle,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = style.colors.primaryText,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(style.colors.surfaceVariant.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    )
}

@Composable
private fun BookInfoFloatingTopBar(
    title: String,
    style: BookInfoComposeStyle,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BookInfoTopIcon(
            iconRes = R.drawable.ic_back,
            contentDescription = stringResource(R.string.back),
            style = style,
            onClick = onBack
        )
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        BookInfoTopIcon(
            iconRes = R.drawable.ic_more_vert,
            contentDescription = stringResource(R.string.more),
            style = style,
            onClick = onMore
        )
    }
}

@Composable
private fun BookInfoTopIcon(
    iconRes: Int,
    contentDescription: String,
    style: BookInfoComposeStyle,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .shadow(8.dp, RoundedCornerShape(style.metrics.actionRadius), clip = false)
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(Color.Black.copy(alpha = 0.30f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun BookInfoCoverBackdrop(
    coverPath: String?,
    style: BookInfoComposeStyle,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black)) {
        BookInfoImage(
            path = coverPath,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.18f),
                        0.48f to Color.Transparent,
                        0.78f to style.colors.background.copy(alpha = 0.70f),
                        1f to style.colors.background
                    )
                )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookInfoPosterHero(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 232.dp)
            .padding(top = 22.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        BookInfoImage(
            path = state.coverPath,
            modifier = Modifier
                .width(126.dp)
                .aspectRatio(0.72f)
                .shadow(18.dp, RoundedCornerShape(style.metrics.panelRadius), clip = false)
                .clip(RoundedCornerShape(style.metrics.panelRadius))
                .combinedClickable(
                    onClick = actions.onChangeCover,
                    onLongClick = actions.onPreviewCover
                )
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = state.name.ifBlank { stringResource(R.string.book_name) },
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = actions.onNameClick,
                    onLongClick = actions.onNameLongClick
                )
            )
            Text(
                text = state.author.ifBlank { stringResource(R.string.author) },
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = actions.onAuthorClick,
                    onLongClick = actions.onAuthorLongClick
                )
            )
            if (state.latestChapterTitle.isNotBlank()) {
                Text(
                    text = state.latestChapterTitle,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.readTimeText.isNotBlank()) {
                Text(
                    text = state.readTimeText,
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.kinds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.kinds.take(6).forEach { kind ->
                        BookInfoPosterChip(kind, style)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookInfoPosterChip(
    text: String,
    style: BookInfoComposeStyle
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookInfoHero(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(style.metrics.panelRadius), clip = false)
            .clip(RoundedCornerShape(style.metrics.panelRadius))
            .background(style.colors.surface.copy(alpha = 0.96f))
            .padding(16.dp)
    ) {
        BookInfoImage(
            path = state.coverPath,
            modifier = Modifier
                .width(108.dp)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(style.metrics.panelRadius))
                .combinedClickable(
                    onClick = actions.onChangeCover,
                    onLongClick = actions.onPreviewCover
                )
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = state.name.ifBlank { stringResource(R.string.book_name) },
                color = style.colors.primaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = actions.onNameClick,
                    onLongClick = actions.onNameLongClick
                )
            )
            Text(
                text = state.author.ifBlank { stringResource(R.string.author) },
                color = style.colors.secondaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = actions.onAuthorClick,
                    onLongClick = actions.onAuthorLongClick
                )
            )
            Text(
                text = state.latestChapterTitle,
                color = style.colors.secondaryText,
                fontSize = 12.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (state.readTimeText.isNotBlank()) {
                Text(
                    text = state.readTimeText,
                    color = style.colors.secondaryText,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.kinds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.kinds.take(8).forEach { kind ->
                        BookInfoChip(kind, style)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookInfoMetaPanel(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.panelRadius))
            .background(style.colors.surface.copy(alpha = 0.96f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BookInfoMetaRow(
            title = state.groupText.ifBlank { stringResource(R.string.group_s, stringResource(R.string.no_group)) },
            action = stringResource(R.string.change_group),
            style = style,
            onClick = actions.onChangeGroup
        )
        BookInfoMetaRow(
            title = state.originName,
            action = stringResource(R.string.change_book_source_action),
            style = style,
            onClick = actions.onChangeSource,
            onTitleClick = actions.onEditSource
        )
        BookInfoMetaRow(
            title = state.tocText.ifBlank { stringResource(R.string.toc_s, stringResource(R.string.loading)) },
            action = stringResource(R.string.view_toc),
            style = style,
            onClick = actions.onOpenToc
        )
    }
}

@Composable
private fun BookInfoIntroPanel(
    intro: String,
    style: BookInfoComposeStyle
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.panelRadius))
            .background(style.colors.surface.copy(alpha = 0.96f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.book_info_tab_intro),
            color = style.colors.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        BookInfoRichIntro(intro, style)
    }
}

@Composable
private fun BookInfoQuickActionsPanel(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BookInfoCommandChip(stringResource(R.string.refresh), style, actions.onRefresh)
        BookInfoCommandChip(stringResource(R.string.set_source_variable), style, actions.onSetSourceVariable)
        BookInfoCommandChip(stringResource(R.string.set_book_variable), style, actions.onSetBookVariable)
        if (state.hasCustomButton) {
            BookInfoCommandChip(stringResource(R.string.custom_button), style, actions.onCustomButton)
        }
        BookInfoCommandChip(stringResource(R.string.ai_image_gallery), style, actions.onOpenAiGallery)
    }
}

@Composable
private fun BookInfoChapterPreviewPanel(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    if (state.chapterPreview.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.panelRadius))
            .background(style.colors.surface.copy(alpha = 0.96f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.book_info_tab_toc),
                color = style.colors.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.catalog_page_indicator, state.chapterPreview.size, state.chapterCount),
                color = style.colors.secondaryText,
                fontSize = 12.5.sp
            )
        }
        state.chapterPreview.forEach { chapter ->
            Text(
                text = chapter.title,
                color = style.colors.secondaryText,
                fontSize = 13.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(style.metrics.actionRadius))
                    .clickable { actions.onOpenChapter(chapter) }
                    .padding(vertical = 7.dp)
            )
        }
        Text(
            text = stringResource(R.string.view_toc),
            color = style.colors.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(style.metrics.actionRadius))
                .clickable(onClick = actions.onOpenToc)
                .padding(top = 2.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun BookInfoAiImagesPanel(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.panelRadius))
            .background(style.colors.surface.copy(alpha = 0.96f))
            .clickable(onClick = actions.onOpenAiGallery)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.book_info_component_ai_images),
                color = style.colors.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (state.aiImageCount > 0) "${state.aiImageCount}" else stringResource(R.string.ai_image_gallery_empty),
                color = style.colors.secondaryText,
                fontSize = 12.5.sp,
                maxLines = 1
            )
        }
        if (state.aiImagePaths.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.aiImagePaths.forEach { path ->
                    BookInfoImage(
                        path = path,
                        modifier = Modifier
                            .size(width = 88.dp, height = 104.dp)
                            .clip(RoundedCornerShape(style.metrics.actionRadius))
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.book_info_component_ai_images_hint),
                color = style.colors.secondaryText,
                fontSize = 13.5.sp
            )
        }
    }
}

@Composable
private fun BookInfoMetaRow(
    title: String,
    action: String,
    style: BookInfoComposeStyle,
    onClick: () -> Unit,
    onTitleClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            color = style.colors.secondaryText,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onTitleClick)
        )
        Text(
            text = action,
            color = style.colors.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

@Composable
private fun BookInfoChip(
    text: String,
    style: BookInfoComposeStyle
) {
    Text(
        text = text,
        color = style.colors.accent,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(style.colors.accentContainer)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun BookInfoCommandChip(
    text: String,
    style: BookInfoComposeStyle,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = style.colors.primaryText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .shadow(4.dp, RoundedCornerShape(style.metrics.actionRadius), clip = false)
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(style.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@Composable
private fun BookInfoBottomActions(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BookInfoActionButton(
            text = if (state.inBookshelf) {
                stringResource(R.string.remove_from_bookshelf)
            } else {
                stringResource(R.string.add_to_bookshelf)
            },
            primary = false,
            style = style,
            onClick = actions.onShelf,
            modifier = Modifier.weight(1f)
        )
        BookInfoActionButton(
            text = stringResource(R.string.reading),
            primary = true,
            style = style,
            onClick = actions.onRead,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BookInfoActionButton(
    text: String,
    primary: Boolean,
    style: BookInfoComposeStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (primary) style.colors.accent else style.colors.surface
    val textColor = if (primary) style.colors.actionText else style.colors.primaryText
    Box(
        modifier = modifier
            .height(50.dp)
            .shadow(8.dp, RoundedCornerShape(style.metrics.actionRadius), clip = false)
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BookInfoImage(
    path: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier.background(Color.Transparent),
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { imageView ->
            val target = path?.takeIf { it.isNotBlank() }
            val tag = target ?: R.drawable.image_cover_default
            if (imageView.tag != tag) {
                imageView.tag = tag
                if (target == null) {
                    ImageLoader.load(context, R.drawable.image_cover_default)
                        .into(imageView)
                } else {
                    ImageLoader.load(context, target)
                        .error(R.drawable.image_cover_default)
                        .placeholder(R.drawable.image_cover_default)
                        .into(imageView)
                }
            }
        }
    )
}

@Composable
private fun BookInfoRichIntro(
    rawIntro: String,
    style: BookInfoComposeStyle
) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(HtmlPlugin.create())
            .build()
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = {
            TextView(it).apply {
                includeFontPadding = true
                textSize = 13.5f
                setLineSpacing(4f, 1f)
                setTextIsSelectable(false)
            }
        },
        update = { textView ->
            textView.setTextColor(style.colors.secondaryText.toArgb())
            if (textView.tag != rawIntro) {
                textView.tag = rawIntro
                when {
                    rawIntro.startsWith("<md>", ignoreCase = true) -> {
                        markwon.setMarkdown(textView, rawIntro.extractWrappedIntro(4))
                    }

                    rawIntro.startsWith("<usehtml>", ignoreCase = true) -> {
                        textView.text = HtmlCompat.fromHtml(
                            rawIntro.extractWrappedIntro(9),
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        )
                    }

                    rawIntro.startsWith("<useweb>", ignoreCase = true) -> {
                        textView.text = HtmlCompat.fromHtml(
                            rawIntro.extractWrappedIntro(8),
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        )
                    }

                    else -> {
                        textView.text = rawIntro
                    }
                }
            }
        }
    )
}

private fun String.extractWrappedIntro(prefixLength: Int): String {
    val endIndex = lastIndexOf("<").takeIf { it > prefixLength } ?: length
    return substring(prefixLength.coerceAtMost(length), endIndex.coerceAtLeast(prefixLength).coerceAtMost(length))
}
