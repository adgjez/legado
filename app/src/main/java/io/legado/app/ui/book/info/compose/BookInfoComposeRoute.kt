package io.legado.app.ui.book.info.compose

import android.content.Context
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.utils.ColorUtils
import androidx.compose.ui.viewinterop.AndroidView

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
    Box(modifier = modifier.fillMaxSize().background(style.colors.background)) {
        BookInfoCoverBackdrop(
            coverPath = state.coverPath,
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 22.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BookInfoHero(state, actions, style)
            BookInfoMetaPanel(state, actions, style)
            BookInfoIntroPanel(
                intro = state.intro.ifBlank { stringResource(R.string.intro_show_null) },
                style = style
            )
        }
        BookInfoBottomActions(
            state = state,
            actions = actions,
            style = style,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BookInfoCoverBackdrop(
    coverPath: String?,
    style: BookInfoComposeStyle,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(style.colors.surfaceVariant)) {
        BookInfoImage(
            path = coverPath,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(style.colors.scrim)
        )
    }
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
        Text(
            text = intro,
            color = style.colors.secondaryText,
            fontSize = 13.5.sp,
            lineHeight = 20.sp
        )
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
