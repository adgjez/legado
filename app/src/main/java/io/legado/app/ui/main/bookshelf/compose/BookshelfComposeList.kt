package io.legado.app.ui.main.bookshelf.compose

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface

object BookshelfListItemStyle {
    const val Classic = 0
    const val RoundedCard = 1
}

@Immutable
data class BookshelfListPalette(
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val rowColor: Int,
    val rowPressedColor: Int,
    val borderColor: Int?,
    val panelRadiusPx: Float,
    val panelRadius: Dp,
    val actionRadius: Dp,
    val titleFontFamily: FontFamily,
    val bodyFontFamily: FontFamily
)

@Immutable
data class BookshelfListRenderConfig(
    val palette: BookshelfListPalette,
    val panelImage: Drawable?,
    val classicMinHeight: Dp = 112.dp,
    val classicCompactMinHeight: Dp = 82.dp,
    val roundedMinHeight: Dp = 154.dp,
    val roundedCompactMinHeight: Dp = 112.dp,
    val classicCoverWidth: Dp = 78.dp,
    val classicCompactCoverWidth: Dp = 58.dp,
    val cardCoverWidth: Dp = 94.dp,
    val cardCompactCoverWidth: Dp = 68.dp
)

@Composable
fun rememberBookshelfListPalette(): BookshelfListPalette {
    val context = LocalContext.current
    val rowBaseColor = ContextCompat.getColor(context, R.color.background_card)
    val rowColor = UiCorner.surfaceColor(rowBaseColor)
    val rowPressedColor = UiCorner.surfaceColor(rowBaseColor, pressed = true)
    val primaryText = Color(ContextCompat.getColor(context, R.color.primaryText))
    val secondaryText = Color(ContextCompat.getColor(context, R.color.tv_text_summary))
    val accent = Color(context.accentColor)
    val border = UiCorner.panelBorderColor(context)
    val panelRadiusPx = UiCorner.panelRadius(context)
    val panelRadius = context.composePanelRadius()
    val actionRadius = context.composeActionRadius()
    val titleFontFamily = FontFamily(context.titleTypeface())
    val bodyFontFamily = FontFamily(context.uiTypeface())
    return remember(
        rowColor,
        rowPressedColor,
        primaryText,
        secondaryText,
        accent,
        border,
        panelRadiusPx,
        panelRadius,
        actionRadius,
        titleFontFamily,
        bodyFontFamily
    ) {
        BookshelfListPalette(
            primaryText = primaryText,
            secondaryText = secondaryText,
            accent = accent,
            rowColor = rowColor,
            rowPressedColor = rowPressedColor,
            borderColor = border,
            panelRadiusPx = panelRadiusPx,
            panelRadius = panelRadius,
            actionRadius = actionRadius,
            titleFontFamily = titleFontFamily,
            bodyFontFamily = bodyFontFamily
        )
    }
}

@Composable
fun rememberBookshelfListRenderConfig(): BookshelfListRenderConfig {
    val context = LocalContext.current
    val palette = rememberBookshelfListPalette()
    val panelImage = remember(context, palette.panelRadiusPx) {
        UiCorner.panelImageDrawable(context, palette.panelRadiusPx)
    }
    return remember(palette, panelImage) {
        BookshelfListRenderConfig(
            palette = palette,
            panelImage = panelImage
        )
    }
}

@Composable
fun BookshelfListItem(
    item: BookshelfItemUi,
    listLayout: Int,
    cardStyle: Int,
    renderConfig: BookshelfListRenderConfig,
    modifier: Modifier = Modifier,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    onClick: (BookshelfItemUi) -> Unit,
    onLongClick: (BookshelfItemUi) -> Unit
) {
    val compact = listLayout == 1
    when (cardStyle) {
        BookshelfListItemStyle.RoundedCard -> BookshelfRoundedCardListItem(
            item = item,
            compact = compact,
            renderConfig = renderConfig,
            modifier = modifier,
            fragment = fragment,
            lifecycle = lifecycle,
            onClick = onClick,
            onLongClick = onLongClick
        )

        else -> BookshelfClassicListItem(
            item = item,
            compact = compact,
            renderConfig = renderConfig,
            modifier = modifier,
            fragment = fragment,
            lifecycle = lifecycle,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookshelfClassicListItem(
    item: BookshelfItemUi,
    compact: Boolean,
    renderConfig: BookshelfListRenderConfig,
    modifier: Modifier,
    fragment: Fragment?,
    lifecycle: Lifecycle?,
    onClick: (BookshelfItemUi) -> Unit,
    onLongClick: (BookshelfItemUi) -> Unit
) {
    val palette = renderConfig.palette
    BookListCardSurface(
        rounded = false,
        compact = compact,
        renderConfig = renderConfig,
        modifier = modifier
            .fillMaxWidth(),
        onClick = { onClick(item) },
        onLongClick = { onLongClick(item) }
    ) { metrics ->
        BookshelfCoverBlock(
            item = item,
            width = metrics.coverWidth,
            cornerRadius = metrics.cornerRadius,
            palette = palette,
            fragment = fragment,
            lifecycle = lifecycle
        )
        Spacer(modifier = Modifier.width(12.dp))
        BookshelfListTextContent(
            item = item,
            compact = compact,
            palette = palette,
            modifier = Modifier.weight(1f),
            showIntro = false,
            showTags = false,
            introMaxLines = 1
        )
        BookshelfListStatus(item = item, palette = palette)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookshelfRoundedCardListItem(
    item: BookshelfItemUi,
    compact: Boolean,
    renderConfig: BookshelfListRenderConfig,
    modifier: Modifier,
    fragment: Fragment?,
    lifecycle: Lifecycle?,
    onClick: (BookshelfItemUi) -> Unit,
    onLongClick: (BookshelfItemUi) -> Unit
) {
    val palette = renderConfig.palette
    BookListCardSurface(
        rounded = true,
        compact = compact,
        renderConfig = renderConfig,
        modifier = modifier
            .fillMaxWidth(),
        onClick = { onClick(item) },
        onLongClick = { onLongClick(item) }
    ) { metrics ->
        BookshelfCoverBlock(
            item = item,
            width = metrics.coverWidth,
            cornerRadius = metrics.cornerRadius,
            palette = palette,
            fragment = fragment,
            lifecycle = lifecycle
        )
        Spacer(modifier = Modifier.width(12.dp))
        BookshelfListTextContent(
            item = item,
            compact = compact,
            palette = palette,
            modifier = Modifier.weight(1f),
            showIntro = true,
            showTags = true,
            introMaxLines = if (compact) 1 else 2
        )
        BookshelfListStatus(item = item, palette = palette)
    }
}

@Composable
private fun BookshelfCoverBlock(
    item: BookshelfItemUi,
    width: Dp,
    cornerRadius: Dp,
    palette: BookshelfListPalette,
    fragment: Fragment?,
    lifecycle: Lifecycle?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.width(width)) {
        BookshelfListCover(
            item = item,
            width = width,
            cornerRadius = cornerRadius,
            fragment = fragment,
            lifecycle = lifecycle
        )
        BookshelfCoverBadge(
            item = item,
            palette = palette,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
        )
    }
}

@Composable
private fun BookshelfCoverBadge(
    item: BookshelfItemUi,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier
) {
    if (item !is BookshelfBookItemUi) return
    if (item.isUpdating) {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        }
        return
    }
    if (!AppConfig.showUnread || item.unreadCount <= 0) return
    val badgeColor = if (item.hasNewChapter) {
        palette.accent
    } else {
        Color.Black.copy(alpha = 0.58f)
    }
    Text(
        text = item.unreadCount.coerceAtMost(999).toString(),
        modifier = modifier
            .clip(CircleShape)
            .background(badgeColor)
            .widthIn(min = 22.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

@Composable
private fun BookshelfListCover(
    item: BookshelfItemUi,
    width: Dp,
    cornerRadius: Dp,
    fragment: Fragment?,
    lifecycle: Lifecycle?,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false
) {
    BookshelfComposeCover(
        item = item,
        modifier = modifier
            .then(if (fillWidth) Modifier else Modifier.width(width).aspectRatio(0.72f))
            .clip(RoundedCornerShape(cornerRadius)),
        fragment = fragment,
        lifecycle = lifecycle,
        fillBounds = true
    )
}

@Composable
private fun BookshelfListTextContent(
    item: BookshelfItemUi,
    compact: Boolean,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier,
    showIntro: Boolean,
    showTags: Boolean,
    introMaxLines: Int
) {
    Column(modifier = modifier) {
        Text(
            text = item.displayName,
            color = palette.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.titleFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(if (compact) 4.dp else 6.dp))
        when (item) {
            is BookshelfBookItemUi -> BookshelfBookMeta(
                item = item,
                compact = compact,
                palette = palette,
                showIntro = showIntro,
                showTags = showTags,
                introMaxLines = introMaxLines
            )

            is BookshelfFolderItemUi -> Text(
                text = stringResource(R.string.bookshelf),
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BookshelfBookMeta(
    item: BookshelfBookItemUi,
    compact: Boolean,
    palette: BookshelfListPalette,
    showIntro: Boolean,
    showTags: Boolean,
    introMaxLines: Int
) {
    val book = item.book
    if (compact) {
        Text(
            text = listOf(book.author, book.durChapterTitle)
                .filter { !it.isNullOrBlank() }
                .joinToString(" • "),
            color = palette.secondaryText,
            fontSize = 13.sp,
            fontFamily = palette.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        BookshelfMetaLine(
            iconRes = R.drawable.ic_author,
            text = book.author,
            palette = palette
        )
        BookshelfMetaLine(
            iconRes = R.drawable.ic_history,
            text = book.durChapterTitle,
            palette = palette
        )
    }
    BookshelfMetaLine(
        iconRes = R.drawable.ic_book_last,
        text = book.latestChapterTitle,
        palette = palette
    )
    if (showIntro) {
        item.intro?.let { intro ->
            Text(
                text = intro,
                color = palette.secondaryText,
                fontSize = 12.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = introMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    if (showTags && item.tags.isNotEmpty()) {
        BookshelfTagChips(
            tags = item.tags,
            palette = palette,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun BookshelfMetaLine(
    iconRes: Int,
    text: String?,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier
) {
    if (text.isNullOrBlank()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = palette.secondaryText,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            color = palette.secondaryText,
            fontSize = 13.sp,
            fontFamily = palette.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BookshelfTagChips(
    tags: List<String>,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tags.take(3).forEach { tag ->
            Text(
                text = tag,
                color = palette.accent,
                fontSize = 11.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(palette.actionRadius))
                    .background(palette.accent.copy(alpha = 0.12f))
                    .widthIn(max = 84.dp)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun BookshelfListStatus(
    item: BookshelfItemUi,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier
) {
    if (item !is BookshelfBookItemUi) return
    Column(
        modifier = modifier
            .padding(start = 8.dp)
            .widthIn(min = 28.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Center
    ) {
        item.lastUpdateText?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = palette.secondaryText,
                fontSize = 11.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private val BookshelfItemUi.displayName: String
    get() = when (this) {
        is BookshelfBookItemUi -> book.name
        is BookshelfFolderItemUi -> group.groupName
    }
