package io.legado.app.ui.book.info.compose

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.openUrl
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

@Immutable
data class BookInfoChapterUi(
    val index: Int,
    val title: String,
    val isVolume: Boolean = false
)

@Immutable
data class BookInfoUiState(
    val bookUrl: String = "",
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
    val onEditBookInfo: () -> Unit = {},
    val onChangeSource: () -> Unit = {},
    val onEditSource: () -> Unit = {},
    val onChangeGroup: () -> Unit = {},
    val onOpenToc: () -> Unit = {},
    val onOpenChapter: (BookInfoChapterUi) -> Unit = {},
    val onOpenAiGallery: () -> Unit = {},
    val onCustomButton: () -> Unit = {},
    val onSetSourceVariable: () -> Unit = {},
    val onSetBookVariable: () -> Unit = {},
    val onSetupWebIntro: (WebView) -> Unit = {},
    val onRefreshEnabledChanged: (Boolean) -> Unit = {}
)

@Immutable
data class BookInfoComposeColors(
    val background: Color,
    val contentBackground: Color,
    val contentTop: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val accentContainer: Color,
    val metricTop: Color,
    val metricBottom: Color,
    val metricText: Color,
    val metricSecondaryText: Color,
    val metricHighlight: Color,
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
fun bookInfoComposeStyle(context: Context, coverColor: Int? = null): BookInfoComposeStyle {
    val night = AppConfig.isNightTheme
    val coverAccent = coverColor ?: context.accentColor
    val accent = if (night) {
        coverAccent.coverTone(saturation = 0.58f, value = 0.72f)
    } else {
        coverAccent.coverTone(saturation = 0.50f, value = 0.62f)
    }
    val pageBackground = if (night) {
        coverAccent.coverTone(saturation = 0.34f, value = 0.09f)
    } else {
        coverAccent.coverTone(saturation = 0.24f, value = 0.86f)
    }
    val contentBackground = if (night) {
        coverAccent.coverTone(saturation = 0.30f, value = 0.13f)
    } else {
        coverAccent.coverTone(saturation = 0.30f, value = 0.82f)
    }
    val contentTop = if (night) {
        coverAccent.coverTone(saturation = 0.42f, value = 0.20f)
    } else {
        coverAccent.coverTone(saturation = 0.38f, value = 0.68f)
    }
    val surface = if (night) {
        coverAccent.coverTone(saturation = 0.26f, value = 0.18f)
    } else {
        coverAccent.coverTone(saturation = 0.20f, value = 0.86f)
    }
    val variant = if (night) {
        coverAccent.coverTone(saturation = 0.28f, value = 0.22f)
    } else {
        coverAccent.coverTone(saturation = 0.26f, value = 0.76f)
    }
    val accentContainer = if (night) {
        coverAccent.coverTone(saturation = 0.38f, value = 0.24f)
    } else {
        coverAccent.coverTone(saturation = 0.32f, value = 0.72f)
    }
    val metricTop = if (night) {
        coverAccent.coverTone(saturation = 0.34f, value = 0.24f)
    } else {
        coverAccent.coverTone(saturation = 0.30f, value = 0.80f)
    }
    val metricBottom = if (night) {
        coverAccent.coverTone(saturation = 0.30f, value = 0.18f)
    } else {
        coverAccent.coverTone(saturation = 0.36f, value = 0.68f)
    }
    val actionText = if (ColorUtils.isColorLight(accent)) 0xff202124.toInt() else 0xffffffff.toInt()
    val scrim = if (night) {
        coverAccent.coverTone(saturation = 0.52f, value = 0.07f)
    } else {
        coverAccent.coverTone(saturation = 0.44f, value = 0.18f)
    }
    return BookInfoComposeStyle(
        colors = BookInfoComposeColors(
            background = Color(pageBackground),
            contentBackground = Color(contentBackground),
            contentTop = Color(contentTop),
            surface = Color(surface),
            surfaceVariant = Color(variant),
            primaryText = Color(if (night) 0xfff5f6f8.toInt() else 0xff202124.toInt()),
            secondaryText = Color(if (night) 0xffaeb4bc.toInt() else 0xff68707a.toInt()),
            accent = Color(accent),
            accentContainer = Color(accentContainer),
            metricTop = Color(metricTop),
            metricBottom = Color(metricBottom),
            metricText = Color(if (night) 0xfff5f6f8.toInt() else 0xff202124.toInt()),
            metricSecondaryText = Color(if (night) 0xffb8bec8.toInt() else 0xff5f6670.toInt()),
            metricHighlight = Color(if (night) 0x3dffffff else 0x80ffffff),
            actionText = Color(actionText),
            scrim = Color(scrim)
        ),
        metrics = BookInfoComposeMetrics(
            panelRadius = context.composePanelRadius(),
            actionRadius = context.composeActionRadius()
        )
    )
}

private fun Int.coverTone(saturation: Float, value: Float): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this, hsv)
    hsv[1] = saturation.coerceIn(0f, 1f)
    hsv[2] = value.coerceIn(0f, 1f)
    return android.graphics.Color.HSVToColor(hsv)
}

@Composable
fun BookInfoComposeRoute(
    state: BookInfoUiState,
    actions: BookInfoActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var coverColor by remember(state.coverPath) { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.coverPath) {
        coverColor = loadCoverThemeColor(context, state.coverPath)
    }
    val style = remember(context, coverColor) { bookInfoComposeStyle(context, coverColor) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val pageScrollState = rememberScrollState()
    val refreshAtTop by remember {
        derivedStateOf { pageScrollState.value == 0 }
    }
    LaunchedEffect(refreshAtTop) {
        actions.onRefreshEnabledChanged(refreshAtTop)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to style.colors.contentTop,
                    0.34f to style.colors.contentBackground,
                    1f to style.colors.background
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScrollState),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(548.dp)
            ) {
                BookInfoCoverBackdrop(
                    coverPath = state.coverPath,
                    style = style,
                    scrollOffset = pageScrollState.value,
                    modifier = Modifier.fillMaxSize()
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    BookInfoPosterHero(state, actions, style)
                    BookInfoStatusStrip(state, actions, style)
                }
            }
            BookInfoContentPanel(style = style) {
                BookInfoIntroPanel(
                    intro = state.intro.ifBlank { stringResource(R.string.intro_show_null) },
                    state = state,
                    actions = actions,
                    style = style
                )
            }
            Spacer(modifier = Modifier.height(116.dp))
        }
        BookInfoBottomActions(
            state = state,
            actions = actions,
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        )
        BookInfoTopGradient(
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )
        BookInfoFloatingTopBar(
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

@Composable
private fun BookInfoStatusStrip(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    val sourceValue = state.originName.cleanBookInfoValue()
    val tocValue = if (state.chapterCount > 0) {
        "${state.chapterCount}"
    } else {
        stringResource(R.string.view_toc)
    }
    val galleryValue = if (state.aiImageCount > 0) "${state.aiImageCount}" else stringResource(R.string.ai_image_gallery_empty)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        BookInfoMetricBox(
            label = "书源",
            value = sourceValue,
            modifier = Modifier.weight(1f),
            style = style,
            onClick = actions.onChangeSource
        )
        BookInfoMetricBox(
            label = "目录",
            value = tocValue,
            suffix = if (state.chapterCount > 0) "章" else "",
            modifier = Modifier.weight(1f),
            style = style,
            onClick = actions.onOpenToc
        )
        BookInfoMetricBox(
            label = "图库",
            value = galleryValue,
            suffix = if (state.aiImageCount > 0) "" else "",
            modifier = Modifier.weight(1f),
            style = style,
            onClick = actions.onOpenAiGallery
        )
    }
}

@Composable
private fun BookInfoMetricBox(
    label: String,
    value: String,
    suffix: String = "",
    modifier: Modifier = Modifier,
    style: BookInfoComposeStyle,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(style.metrics.actionRadius)
    Box(
        modifier = modifier
            .height(74.dp)
            .shadow(2.dp, shape, clip = false)
            .clip(shape)
            .background(style.colors.metricTop.copy(alpha = 0.96f))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = value,
                    color = style.colors.metricText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (suffix.isNotBlank()) {
                    Text(
                        text = suffix,
                        color = style.colors.metricSecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = label,
                color = style.colors.metricSecondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
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
            BookInfoMoreActionItem(stringResource(R.string.book_info_edit), style) {
                onDismiss()
                actions.onEditBookInfo()
            }
            BookInfoMoreActionItem(stringResource(R.string.refresh), style) {
                onDismiss()
                actions.onRefresh()
            }
            BookInfoMoreActionItem(stringResource(R.string.update_toc), style) {
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
        Spacer(modifier = Modifier.weight(1f))
        BookInfoTopIcon(
            iconRes = R.drawable.ic_more_vert,
            contentDescription = stringResource(R.string.more),
            style = style,
            onClick = onMore
        )
    }
}

@Composable
private fun BookInfoTopGradient(
    style: BookInfoComposeStyle,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(132.dp)
            .background(
                Brush.verticalGradient(
                    0f to style.colors.scrim.copy(alpha = 0.38f),
                    0.58f to style.colors.scrim.copy(alpha = 0.10f),
                    1f to Color.Transparent
                )
            )
    )
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
            .size(40.dp)
            .clip(CircleShape)
            .background(style.colors.scrim.copy(alpha = 0.30f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun BookInfoCoverBackdrop(
    coverPath: String?,
    style: BookInfoComposeStyle,
    scrollOffset: Int,
    modifier: Modifier = Modifier
) {
    val blurRadius = (scrollOffset / 72f).coerceIn(0f, 10f).dp
    val imageDarkenAlpha = (0.15f + scrollOffset / 1800f).coerceIn(0.15f, 0.34f)
    val parallaxOffset = scrollOffset * 0.22f
    Box(modifier = modifier.background(style.colors.contentTop)) {
        BookInfoImage(
            path = coverPath,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = parallaxOffset
                    scaleX = 1.06f
                    scaleY = 1.06f
                }
                .blur(blurRadius)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(style.colors.scrim.copy(alpha = imageDarkenAlpha))
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to style.colors.scrim.copy(alpha = 0.22f),
                        0.36f to style.colors.scrim.copy(alpha = 0.04f),
                        0.70f to style.colors.contentTop.copy(alpha = 0.62f),
                        0.90f to style.colors.contentTop.copy(alpha = 0.90f),
                        1f to style.colors.contentTop
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
                .shadow(6.dp, RoundedCornerShape(style.metrics.panelRadius), clip = false)
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
            .background(style.colors.scrim.copy(alpha = 0.28f))
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
private fun BookInfoContentPanel(
    style: BookInfoComposeStyle,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to style.colors.contentTop,
                    0.24f to style.colors.contentBackground,
                    0.72f to style.colors.contentBackground,
                    1f to style.colors.background
                )
            )
            .padding(top = 10.dp)
    ) {
        content()
    }
}

@Composable
private fun BookInfoIntroPanel(
    intro: String,
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    val isWebIntro = intro.startsWith("<useweb>", ignoreCase = true)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isWebIntro) 0.dp else 22.dp,
                end = if (isWebIntro) 0.dp else 22.dp,
                bottom = 12.dp
            ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (isWebIntro) {
            BookInfoIntroContent(
                rawIntro = intro,
                state = state,
                actions = actions,
                style = style
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(style.metrics.panelRadius))
                    .background(style.colors.surface.copy(alpha = 0.28f))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BookInfoIntroContent(
                    rawIntro = intro,
                    state = state,
                    actions = actions,
                    style = style
                )
            }
        }
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
            .background(style.colors.surface.copy(alpha = 0.80f))
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BookInfoSectionHeader(
            title = stringResource(R.string.book_info_tab_toc),
            trailing = stringResource(R.string.catalog_page_indicator, state.chapterPreview.size, state.chapterCount),
            style = style,
            onTrailingClick = actions.onOpenToc
        )
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
                    .padding(vertical = 8.dp)
            )
        }
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
            .background(style.colors.surface.copy(alpha = 0.80f))
            .clickable(onClick = actions.onOpenAiGallery)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BookInfoSectionHeader(
            title = stringResource(R.string.book_info_component_ai_images),
            trailing = if (state.aiImageCount > 0) "${state.aiImageCount}" else stringResource(R.string.ai_image_gallery_empty),
            style = style,
            onTrailingClick = actions.onOpenAiGallery
        )
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
private fun BookInfoSectionHeader(
    title: String,
    style: BookInfoComposeStyle,
    trailing: String? = null,
    onTrailingClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = style.colors.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!trailing.isNullOrBlank()) {
            Text(
                text = trailing,
                color = style.colors.accent,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(style.metrics.actionRadius))
                    .clickable(onClick = onTrailingClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
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
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to style.colors.contentBackground.copy(alpha = 0.90f),
                    1f to style.colors.contentBackground
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
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
}

@Composable
private fun BookInfoActionButton(
    text: String,
    primary: Boolean,
    style: BookInfoComposeStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (primary) style.colors.accent else style.colors.accentContainer
    val textColor = if (primary) style.colors.actionText else style.colors.primaryText
    Box(
        modifier = modifier
            .height(52.dp)
            .shadow(if (primary) 7.dp else 4.dp, RoundedCornerShape(style.metrics.actionRadius), clip = false)
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
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
            textView.setTextColor(style.colors.primaryText.toArgb())
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

                    else -> {
                        textView.text = rawIntro
                    }
                }
            }
        }
    )
}

@Composable
private fun BookInfoIntroContent(
    rawIntro: String,
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    if (rawIntro.startsWith("<useweb>", ignoreCase = true)) {
        BookInfoWebIntro(
            rawIntro = rawIntro,
            bookUrl = state.bookUrl,
            actions = actions,
            style = style
        )
    } else {
        BookInfoRichIntro(rawIntro, style)
    }
}

@Composable
@SuppressLint("SetJavaScriptEnabled")
private fun BookInfoWebIntro(
    rawIntro: String,
    bookUrl: String,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var webHeight by remember(rawIntro) { mutableStateOf(360.dp) }
    var webHeightPx by remember(rawIntro) { mutableStateOf(0) }
    val updateWebHeight: (Int) -> Unit = updateHeight@{ contentHeightPx ->
        if (contentHeightPx <= 0) return@updateHeight
        val minHeightPx = with(density) { 240.dp.roundToPx() }
        val maxHeightPx = with(density) { 12000.dp.roundToPx() }
        val thresholdPx = with(density) { 10.dp.roundToPx() }
        val targetHeightPx = contentHeightPx.coerceIn(minHeightPx, maxHeightPx)
        if (webHeightPx == 0 || abs(targetHeightPx - webHeightPx) >= thresholdPx) {
            webHeightPx = targetHeightPx
            webHeight = with(density) { targetHeightPx.toDp() }
        }
    }
    val html = remember(rawIntro) { rawIntro.extractWrappedIntro(8) }
    val baseUrl = remember(bookUrl) {
        bookUrl
            .takeIf { it.startsWith("http", ignoreCase = true) }
            ?.substringBefore(",")
    }
    val textColor = style.colors.primaryText.toCssHex()
    val themeCss = remember(style) {
        bookInfoUseWebThemeCss(style)
    }
    val transparentHtml = remember(html, textColor, themeCss) {
        """
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>
                html, body {
                  background: transparent !important;
                  color: $textColor;
                  margin: 0;
                  padding: 0;
                  font-size: 14px;
                  line-height: 1.72;
                  word-break: break-word;
                }
                img, video, iframe {
                  max-width: 100%;
                  height: auto;
                }
              </style>
            </head>
            <body>$html<style id="legado-book-info-theme">$themeCss</style></body>
            </html>
        """.trimIndent()
    }
    val loadKey = remember(baseUrl, transparentHtml) { "${baseUrl.orEmpty()}\n$transparentHtml" }
    val webView = remember(context, rawIntro, bookUrl) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            settings.apply {
                javaScriptEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowContentAccess = true
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
            }
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            addJavascriptInterface(
                BookInfoHeightBridge(this, updateWebHeight),
                "legadoBookInfoHeight"
            )
        }
    }
    DisposableEffect(webView) {
        onDispose {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(webHeight),
        factory = {
            webView.apply {
                (parent as? ViewGroup)?.removeView(this)
                setTag(R.id.tag, null)
                onResume()
                webViewClient = BookInfoIntroWebViewClient(context, updateWebHeight)
                actions.onSetupWebIntro(this)
            }
        },
        update = { webView ->
            actions.onSetupWebIntro(webView)
            webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            if (webView.getTag(R.id.tag) != loadKey) {
                webView.setTag(R.id.tag, loadKey)
                webView.loadDataWithBaseURL(
                    baseUrl,
                    transparentHtml,
                    "text/html",
                    "utf-8",
                    baseUrl
                )
            }
        }
    )
}

private class BookInfoIntroWebViewClient(
    private val context: Context,
    private val onContentHeight: (Int) -> Unit
) : WebViewClient() {
    private val jsStr = getInjectionString

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val uri = request?.url ?: return true
        return when (uri.scheme) {
            "http", "https" -> false
            "legado", "yuedu" -> {
                context.startActivity(Intent(context, OnLineImportActivity::class.java).apply {
                    data = uri
                })
                true
            }

            else -> {
                context.openUrl(uri)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        view?.evaluateJavascript(jsStr, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view ?: return
        view.installDynamicHeightObserver()
        listOf(80L, 240L, 600L, 1200L, 2200L).forEach { delay ->
            view.postDelayed({ view.measureContentHeight() }, delay)
        }
    }

    private fun WebView.measureContentHeight() {
        evaluateJavascript(
            """
                (function(){
                  if (window.__legadoBookInfoMeasureHeight) {
                    return String(window.__legadoBookInfoMeasureHeight());
                  }
                  var b = document.body || {};
                  var e = document.documentElement || {};
                  return String(Math.max(
                    b.scrollHeight || 0, b.offsetHeight || 0, b.clientHeight || 0,
                    e.scrollHeight || 0, e.offsetHeight || 0, e.clientHeight || 0
                  ));
                })()
            """.trimIndent()
        ) { result ->
            val jsHeight = result
                ?.trim('"')
                ?.toFloatOrNull()
                ?.let { it * scale }
                ?.toInt()
            val nativeHeight = (contentHeight * scale).toInt()
            onContentHeight((jsHeight ?: nativeHeight).coerceAtLeast(nativeHeight))
        }
    }

    private fun WebView.installDynamicHeightObserver() {
        evaluateJavascript(
            """
                (function(){
                  window.__legadoBookInfoMeasureHeight = function(){
                    var body = document.body;
                    var doc = document.documentElement;
                    var scrollY = window.scrollY || window.pageYOffset || 0;
                    var max = Math.max(
                      body ? (body.scrollHeight || 0) : 0,
                      body ? (body.offsetHeight || 0) : 0,
                      doc ? (doc.scrollHeight || 0) : 0,
                      doc ? (doc.offsetHeight || 0) : 0,
                      doc ? (doc.clientHeight || 0) : 0
                    );
                    if (!body) return Math.ceil(max);
                    var nodes = body.querySelectorAll('*');
                    for (var i = 0; i < nodes.length; i++) {
                      var el = nodes[i];
                      var tag = (el.tagName || '').toLowerCase();
                      if (tag === 'script' || tag === 'style' || tag === 'meta' || tag === 'link') continue;
                      var style = window.getComputedStyle ? getComputedStyle(el) : null;
                      if (style && (style.display === 'none' || style.visibility === 'hidden')) continue;
                      var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
                      if (rect && (rect.width > 0 || rect.height > 0)) {
                        if (style && style.position === 'fixed') {
                          max = Math.max(max, rect.bottom, rect.top + (el.scrollHeight || rect.height || 0));
                        } else {
                          max = Math.max(max, rect.bottom + scrollY);
                        }
                      }
                      if (el.offsetTop != null) {
                        max = Math.max(max, el.offsetTop + (el.scrollHeight || el.offsetHeight || 0));
                      }
                    }
                    var expanded = body.querySelectorAll('.show,[open],.expanded,.active');
                    for (var j = 0; j < expanded.length; j++) {
                      var ex = expanded[j];
                      var exRect = ex.getBoundingClientRect ? ex.getBoundingClientRect() : null;
                      var exTop = exRect ? (exRect.top + scrollY) : (ex.offsetTop || 0);
                      max = Math.max(max, exTop + (ex.scrollHeight || ex.offsetHeight || 0));
                    }
                    return Math.ceil(max + 8);
                  };
                  if (window.__legadoBookInfoHeightObserverInstalled) return;
                  window.__legadoBookInfoHeightObserverInstalled = true;
                  var scheduled = false;
                  var attachImageLoad = function(root) {
                    try {
                      Array.prototype.forEach.call((root || document).querySelectorAll('img'), function(img) {
                        if (!img.__legadoBookInfoLoadBound) {
                          img.__legadoBookInfoLoadBound = true;
                          img.addEventListener('load', reportLater, { passive: true });
                          img.addEventListener('error', reportLater, { passive: true });
                        }
                      });
                    } catch(e) {}
                  };
                  var reportNow = function(){
                    try {
                      var h = window.__legadoBookInfoMeasureHeight();
                      legadoBookInfoHeight.reportHeight(String(h));
                    } catch(e) {}
                  };
                  var report = function(){
                    if (scheduled) return;
                    scheduled = true;
                    var run = function(){
                      scheduled = false;
                      reportNow();
                    };
                    if (window.requestAnimationFrame) requestAnimationFrame(run);
                    else setTimeout(run, 16);
                  };
                  var reportLater = function(){
                    report();
                    setTimeout(report, 70);
                    setTimeout(report, 180);
                    setTimeout(report, 420);
                  };
                  ['click','touchend','input','change','toggle','transitionend','animationend'].forEach(function(name){
                    try { document.addEventListener(name, reportLater, true); } catch(e) {}
                  });
                  if (window.ResizeObserver) {
                    try {
                      var resizeObserver = new ResizeObserver(reportLater);
                      resizeObserver.observe(document.documentElement);
                      if (document.body) resizeObserver.observe(document.body);
                    } catch(e) {}
                  }
                  if (window.MutationObserver && document.body) {
                    try {
                      new MutationObserver(function(mutations){
                        for (var i = 0; i < mutations.length; i++) {
                          for (var j = 0; j < mutations[i].addedNodes.length; j++) {
                            var node = mutations[i].addedNodes[j];
                            if (node && node.querySelectorAll) attachImageLoad(node);
                          }
                        }
                        reportLater();
                      }).observe(document.body, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        characterData: true
                      });
                    } catch(e) {}
                  }
                  attachImageLoad(document);
                  var count = 0;
                  var timer = setInterval(function(){
                    reportLater();
                    count++;
                    if (count > 12) clearInterval(timer);
                  }, 300);
                  reportLater();
                })()
            """.trimIndent(),
            null
        )
    }
}

private class BookInfoHeightBridge(
    private val webView: WebView,
    private val onContentHeight: (Int) -> Unit
) {
    private var lastHeight = 0
    private var pendingHeight = 0
    private var scheduled = false

    @JavascriptInterface
    fun reportHeight(value: String?) {
        val height = value?.toFloatOrNull()?.let { it * webView.scale }?.toInt() ?: return
        webView.post {
            pendingHeight = height
            if (scheduled) return@post
            scheduled = true
            webView.postDelayed({
                scheduled = false
                val nativeHeight = (webView.contentHeight * webView.scale).toInt()
                val targetHeight = maxOf(pendingHeight, nativeHeight)
                pendingHeight = 0
                if (targetHeight <= 0) return@postDelayed
                if (lastHeight == 0 || abs(targetHeight - lastHeight) >= 12) {
                    lastHeight = targetHeight
                    onContentHeight(targetHeight)
                }
            }, 48L)
        }
    }
}

private fun Color.toCssHex(): String {
    return "#%06X".format(0xFFFFFF and toArgb())
}

private fun Color.toCssRgba(alpha: Float = this.alpha): String {
    val color = copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()
    val red = android.graphics.Color.red(color)
    val green = android.graphics.Color.green(color)
    val blue = android.graphics.Color.blue(color)
    val finalAlpha = android.graphics.Color.alpha(color) / 255f
    return "rgba($red,$green,$blue,${String.format(Locale.US, "%.3f", finalAlpha)})"
}

private fun bookInfoUseWebThemeCss(style: BookInfoComposeStyle): String {
    val night = AppConfig.isNightTheme
    val pageBackground = "transparent"
    val cardBackground = style.colors.surface.copy(alpha = if (night) 0.92f else 0.86f).toCssRgba()
    val subtleBackground = style.colors.surfaceVariant.copy(alpha = if (night) 0.82f else 0.72f).toCssRgba()
    val primaryText = style.colors.primaryText.toCssHex()
    val secondaryText = style.colors.secondaryText.toCssHex()
    val mutedText = style.colors.secondaryText.copy(alpha = if (night) 0.78f else 0.86f).toCssRgba()
    val border = style.colors.accentContainer.copy(alpha = if (night) 0.42f else 0.48f).toCssRgba()
    val divider = style.colors.accentContainer.copy(alpha = if (night) 0.28f else 0.34f).toCssRgba()
    val accent = style.colors.accent.toCssHex()
    val accentSoft = style.colors.accent.copy(alpha = if (night) 0.18f else 0.14f).toCssRgba()
    val cardShadow = if (night) {
        "0 14px 30px rgba(0,0,0,0.22)"
    } else {
        "0 10px 24px rgba(40,48,64,0.10)"
    }
    val hoverShadow = if (night) {
        "0 18px 36px rgba(0,0,0,0.28)"
    } else {
        "0 14px 30px rgba(40,48,64,0.14)"
    }
    val scheme = if (night) "dark" else "light"
    return """
        :root, body {
          color-scheme: $scheme !important;
          --bg-body: $pageBackground !important;
          --bg-soft: $subtleBackground !important;
          --card-bg: $cardBackground !important;
          --text-primary: $primaryText !important;
          --text-secondary: $secondaryText !important;
          --text-muted: $mutedText !important;
          --border-light: $border !important;
          --border-divider: $divider !important;
          --accent: $accent !important;
          --accent-soft: $accentSoft !important;
          --shadow-sm: $cardShadow !important;
          --shadow-md: $hoverShadow !important;
        }
        html, body {
          background: transparent !important;
        }
    """.trimIndent()
}

private suspend fun loadCoverThemeColor(context: Context, coverPath: String?): Int? {
    if (coverPath.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = ImageLoader.loadBitmap(context, coverPath)
                .submit(48, 72)
                .get()
            bitmap.extractThemeColor()
        }.getOrNull()
    }
}

private fun Bitmap.extractThemeColor(): Int? {
    if (width <= 0 || height <= 0) return null
    var red = 0L
    var green = 0L
    var blue = 0L
    var weightSum = 0L
    val stepX = (width / 12).coerceAtLeast(1)
    val stepY = (height / 16).coerceAtLeast(1)
    val centerLeft = width * 0.18f
    val centerRight = width * 0.82f
    val centerTop = height * 0.12f
    val centerBottom = height * 0.88f
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = getPixel(x, y)
            val alpha = android.graphics.Color.alpha(pixel)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val brightness = (r + g + b) / 3
            val chroma = max - min
            if (alpha > 180 && brightness in 34..232 && chroma > 12) {
                val centerWeight = if (
                    x in centerLeft.toInt()..centerRight.toInt() &&
                    y in centerTop.toInt()..centerBottom.toInt()
                ) 3L else 1L
                val colorWeight = centerWeight * (1L + chroma / 36L)
                red += r * colorWeight
                green += g * colorWeight
                blue += b * colorWeight
                weightSum += colorWeight
            }
            x += stepX
        }
        y += stepY
    }
    if (weightSum <= 0L) return null
    val avg = android.graphics.Color.rgb(
        (red / weightSum).toInt().coerceIn(0, 255),
        (green / weightSum).toInt().coerceIn(0, 255),
        (blue / weightSum).toInt().coerceIn(0, 255)
    )
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(avg, hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.28f).coerceAtMost(0.72f)
    hsv[2] = hsv[2].coerceIn(0.34f, 0.76f)
    return android.graphics.Color.HSVToColor(hsv)
}

private fun String.cleanBookInfoValue(): String {
    return substringAfter("：")
        .substringAfter(":")
        .trim()
        .ifBlank { this }
}

private fun String.extractWrappedIntro(prefixLength: Int): String {
    val endIndex = lastIndexOf("<").takeIf { it > prefixLength } ?: length
    return substring(prefixLength.coerceAtMost(length), endIndex.coerceAtLeast(prefixLength).coerceAtMost(length))
}
