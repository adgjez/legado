package io.legado.app.ui.book.info.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.utils.ColorUtils

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
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(style.colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BookInfoHeaderSkeleton(state, style)
            BookInfoSectionSkeleton(
                title = stringResource(R.string.book_info_component_meta),
                body = listOf(
                    state.groupText,
                    state.originName,
                    state.tocText
                ).filter { it.isNotBlank() }.joinToString("\n").ifBlank { state.latestChapterTitle },
                style = style
            )
            BookInfoSectionSkeleton(
                title = stringResource(R.string.book_info_tab_intro),
                body = state.intro.ifBlank { stringResource(R.string.intro_show_null) },
                style = style
            )
            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}

@Composable
private fun BookInfoHeaderSkeleton(
    state: BookInfoUiState,
    style: BookInfoComposeStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.panelRadius))
            .background(style.colors.surface)
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .width(92.dp)
                .height(126.dp)
                .clip(RoundedCornerShape(style.metrics.panelRadius))
                .background(style.colors.surfaceVariant)
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
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = state.author.ifBlank { stringResource(R.string.author) },
                color = style.colors.secondaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = state.latestChapterTitle,
                color = style.colors.secondaryText,
                fontSize = 12.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BookInfoSectionSkeleton(
    title: String,
    body: String,
    style: BookInfoComposeStyle
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.panelRadius))
            .background(style.colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = style.colors.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = body,
            color = style.colors.secondaryText,
            fontSize = 13.5.sp,
            lineHeight = 20.sp
        )
    }
}
