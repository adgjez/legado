package io.legado.app.ui.widget.compose

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.utils.ColorUtils
import kotlin.math.roundToInt

enum class MainTopBarAction {
    SEARCH,
    FILTER,
    STAR,
    REFRESH,
    LOGIN,
    MORE,
    FILTER_TOGGLE
}

enum class MainTopBarAnchor {
    TITLE,
    SEARCH_ENTRY,
    SEARCH,
    FILTER,
    STAR,
    REFRESH,
    LOGIN,
    MORE,
    FILTER_TOGGLE
}

@Immutable
data class MainTopBarAnchorBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

@Immutable
data class MainTopBarActionState(
    val action: MainTopBarAction,
    @DrawableRes val iconRes: Int,
    val contentDescription: String,
    val visible: Boolean,
    val enabled: Boolean,
    val alpha: Float,
    val rotationDegrees: Float = 0f
)

@Immutable
data class MainTopBarTagItem(
    val text: String,
    val alpha: Float
)

@Immutable
data class MainTopBarTagBarState(
    val items: List<MainTopBarTagItem>,
    val selectedIndex: Int,
    val visible: Boolean,
    val selectedBackgroundVisible: Boolean,
    val displayMode: RoundedTagBarView.DisplayMode
)

@Immutable
data class MainTopBarUiState(
    val mode: MainTopBarView.Mode,
    val config: TopBarConfig.Config,
    val statusBarInsetTopPx: Int,
    val title: String,
    val titleTextSizeSp: Float,
    val titleMaxWidthPx: Int?,
    val titleEnabled: Boolean,
    val titleAlpha: Float,
    val searchHint: String,
    val searchEntryRequested: Boolean,
    val searchEntryEnabled: Boolean,
    val searchEntryAlpha: Float,
    val filtersExpanded: Boolean,
    val primaryBar: MainTopBarTagBarState,
    val selectsBar: MainTopBarTagBarState,
    val tagsBar: MainTopBarTagBarState,
    val actions: List<MainTopBarActionState>
)

@Composable
fun ComposeMainTopBar(
    state: MainTopBarUiState,
    onTitleClick: () -> Unit,
    onTitleLongClick: () -> Boolean,
    onSearchEntryClick: () -> Unit,
    onActionClick: (MainTopBarAction) -> Unit,
    onAnchorBoundsChanged: (MainTopBarAnchor, MainTopBarAnchorBounds) -> Unit,
    onTagClick: (TopBarTagSlot, Int) -> Unit,
    onTagLongClick: (TopBarTagSlot, Int) -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isRegular = state.config.style == TopBarConfig.STYLE_REGULAR
    val horizontalPadding = dimensionResource(R.dimen.bookshelf_tag_bar_margin_horizontal)
    val titleTopPadding = if (isRegular) 0.dp else dimensionResource(R.dimen.bookshelf_title_row_margin_top)
    val verticalInset = with(density) { state.statusBarInsetTopPx.toDp() }
    val regularVerticalPadding = if (isRegular) 5.dp else 0.dp
    val textColor = Color(context.primaryTextColor)
    val titleFont = remember(context) { FontFamily(context.titleTypeface()) }
    val bodyFont = remember(context) { FontFamily(context.uiTypeface()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = verticalInset + regularVerticalPadding,
                bottom = regularVerticalPadding
            )
    ) {
        MainTopTitleRow(
            state = state,
            isRegular = isRegular,
            textColor = textColor,
            titleFont = titleFont,
            bodyFont = bodyFont,
            titleTopPadding = titleTopPadding,
            onTitleClick = onTitleClick,
            onTitleLongClick = onTitleLongClick,
            onSearchEntryClick = onSearchEntryClick,
            onActionClick = onActionClick,
            onAnchorBoundsChanged = onAnchorBoundsChanged
        )
        if (isRegular) {
            val filterToggle = state.action(MainTopBarAction.FILTER_TOGGLE)
            if (state.primaryBar.visible || filterToggle.visible) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.bookshelf_tag_bar_margin_top)))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dimensionResource(R.dimen.bookshelf_tag_bar_height)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.primaryBar.visible) {
                        ComposeTopBarTagBar(
                            state = state.primaryBar,
                            config = state.config,
                            slot = TopBarTagSlot.PRIMARY,
                            modifier = Modifier.weight(1f),
                            onTagClick = onTagClick,
                            onTagLongClick = onTagLongClick
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TopBarActionButton(
                        state = filterToggle,
                        isRegular = true,
                        textColor = textColor,
                        marginStart = 6.dp,
                        onActionClick = onActionClick,
                        onAnchorBoundsChanged = onAnchorBoundsChanged
                    )
                }
            }
            FilterBarVisibility(visible = state.selectsBar.visible) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.bookshelf_tag_bar_margin_top)))
                ComposeTopBarTagBar(
                    state = state.selectsBar,
                    config = state.config,
                    slot = TopBarTagSlot.SELECTS,
                    onTagClick = onTagClick,
                    onTagLongClick = onTagLongClick
                )
            }
            FilterBarVisibility(visible = state.tagsBar.visible) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.bookshelf_tag_bar_margin_top)))
                ComposeTopBarTagBar(
                    state = state.tagsBar,
                    config = state.config,
                    slot = TopBarTagSlot.TAGS,
                    onTagClick = onTagClick,
                    onTagLongClick = onTagLongClick
                )
            }
        } else {
            if (state.selectsBar.visible) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.bookshelf_tag_bar_margin_top)))
                ComposeTopBarTagBar(
                    state = state.selectsBar,
                    config = state.config,
                    slot = TopBarTagSlot.SELECTS,
                    onTagClick = onTagClick,
                    onTagLongClick = onTagLongClick
                )
            }
            if (state.tagsBar.visible) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.bookshelf_tag_bar_margin_top)))
                ComposeTopBarTagBar(
                    state = state.tagsBar,
                    config = state.config,
                    slot = TopBarTagSlot.TAGS,
                    onTagClick = onTagClick,
                    onTagLongClick = onTagLongClick
                )
            }
        }
    }
}

enum class TopBarTagSlot {
    PRIMARY,
    SELECTS,
    TAGS
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainTopTitleRow(
    state: MainTopBarUiState,
    isRegular: Boolean,
    textColor: Color,
    titleFont: FontFamily,
    bodyFont: FontFamily,
    titleTopPadding: Dp,
    onTitleClick: () -> Unit,
    onTitleLongClick: () -> Boolean,
    onSearchEntryClick: () -> Unit,
    onActionClick: (MainTopBarAction) -> Unit,
    onAnchorBoundsChanged: (MainTopBarAnchor, MainTopBarAnchorBounds) -> Unit
) {
    val density = LocalDensity.current
    val controlHeight = if (isRegular) {
        dimensionResource(R.dimen.top_bar_regular_action_size)
    } else {
        dimensionResource(R.dimen.bookshelf_title_select_height)
    }
    val titleMaxWidth = state.titleMaxWidthPx?.let { with(density) { it.toDp() } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = titleTopPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isRegular && state.searchEntryRequested) {
            SearchEntry(
                text = state.searchHint,
                enabled = state.searchEntryEnabled,
                alpha = state.searchEntryAlpha,
                textColor = textColor,
                bodyFont = bodyFont,
                height = controlHeight,
                modifier = Modifier.weight(1f),
                onClick = onSearchEntryClick,
                onAnchorBoundsChanged = onAnchorBoundsChanged
            )
        } else {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier
                        .height(controlHeight)
                        .then(if (titleMaxWidth != null) Modifier.widthIn(max = titleMaxWidth) else Modifier)
                        .topBarAnchor(MainTopBarAnchor.TITLE, onAnchorBoundsChanged)
                        .clip(RoundedCornerShape(dimensionResource(R.dimen.ui_action_radius)))
                        .combinedClickable(
                            enabled = state.titleEnabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onLongClick = { onTitleLongClick() },
                            onClick = onTitleClick
                        )
                        .alpha(state.titleAlpha)
                        .padding(
                            start = if (isRegular) 12.dp else 0.dp,
                            end = if (isRegular) 8.dp else 0.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.title,
                        color = textColor,
                        fontSize = state.titleTextSizeSp.sp,
                        fontFamily = titleFont,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(textColor),
                        modifier = Modifier.size(dimensionResource(R.dimen.bookshelf_title_arrow_size))
                    )
                }
            }
        }
        val marginStart = if (isRegular) 6.dp else 8.dp
        state.actions
            .filter { it.action != MainTopBarAction.FILTER_TOGGLE }
            .forEach { action ->
                TopBarActionButton(
                    state = action,
                    isRegular = isRegular,
                    textColor = textColor,
                    marginStart = marginStart,
                    onActionClick = onActionClick,
                    onAnchorBoundsChanged = onAnchorBoundsChanged
                )
            }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchEntry(
    text: String,
    enabled: Boolean,
    alpha: Float,
    textColor: Color,
    bodyFont: FontFamily,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onAnchorBoundsChanged: (MainTopBarAnchor, MainTopBarAnchorBounds) -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .height(height)
            .topBarAnchor(MainTopBarAnchor.SEARCH_ENTRY, onAnchorBoundsChanged)
            .clip(RoundedCornerShape(dimensionResource(R.dimen.ui_action_radius)))
            .background(Color(context.backgroundColor).copy(alpha = 0.42f))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .alpha(alpha)
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            colorFilter = ColorFilter.tint(textColor),
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = textColor.copy(alpha = 0.78f),
            fontSize = 14.sp,
            fontFamily = bodyFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TopBarActionButton(
    state: MainTopBarActionState,
    isRegular: Boolean,
    textColor: Color,
    marginStart: Dp,
    onActionClick: (MainTopBarAction) -> Unit,
    onAnchorBoundsChanged: (MainTopBarAnchor, MainTopBarAnchorBounds) -> Unit
) {
    if (!state.visible) return
    val size = if (isRegular) {
        dimensionResource(R.dimen.top_bar_regular_action_size)
    } else {
        dimensionResource(R.dimen.bookshelf_action_button_size)
    }
    val rotationDegrees = if (state.action == MainTopBarAction.FILTER_TOGGLE) {
        animateFloatAsState(
            targetValue = state.rotationDegrees,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "mainTopBarActionRotation"
        ).value
    } else {
        state.rotationDegrees
    }
    Box(
        modifier = Modifier
            .padding(start = marginStart)
            .size(size)
            .topBarAnchor(state.action.anchor(), onAnchorBoundsChanged)
            .clip(RoundedCornerShape(dimensionResource(R.dimen.ui_action_radius)))
            .clickable(
                enabled = state.enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onActionClick(state.action) }
            )
            .alpha(state.alpha),
        contentAlignment = Alignment.Center
    ) {
        val iconModifier = Modifier
            .size(if (isRegular) 20.dp else 19.dp)
            .then(
                if (rotationDegrees != 0f || state.action == MainTopBarAction.FILTER_TOGGLE) {
                    Modifier.graphicsLayer {
                        rotationZ = rotationDegrees
                    }
                } else {
                    Modifier
                }
            )
        LegacyTopBarIcon(
            iconRes = state.iconRes,
            contentDescription = state.contentDescription,
            tint = textColor,
            modifier = iconModifier
        )
    }
}

@Composable
private fun LegacyTopBarIcon(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            LegacyTopBarIconView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { imageView ->
            val tintArgb = tint.toArgb()
            if (imageView.appliedIconRes != iconRes) {
                imageView.appliedIconRes = iconRes
                imageView.setImageResource(iconRes)
            }
            if (imageView.appliedTintArgb != tintArgb) {
                imageView.appliedTintArgb = tintArgb
                imageView.imageTintList = ColorStateList.valueOf(tintArgb)
            }
            if (imageView.appliedContentDescription != contentDescription) {
                imageView.appliedContentDescription = contentDescription
                imageView.contentDescription = contentDescription
            }
        }
    )
}

private class LegacyTopBarIconView(context: android.content.Context) : AppCompatImageView(context) {
    var appliedIconRes: Int = 0
    var appliedTintArgb: Int = Int.MIN_VALUE
    var appliedContentDescription: String? = null
}

@Composable
private fun FilterBarVisibility(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(90, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(90, easing = FastOutSlowInEasing))
    ) {
        content()
    }
}

private fun Modifier.topBarAnchor(
    anchor: MainTopBarAnchor,
    onAnchorBoundsChanged: (MainTopBarAnchor, MainTopBarAnchorBounds) -> Unit
): Modifier {
    return onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        val left = position.x.roundToInt()
        val top = position.y.roundToInt()
        onAnchorBoundsChanged(
            anchor,
            MainTopBarAnchorBounds(
                left = left,
                top = top,
                right = left + coordinates.size.width,
                bottom = top + coordinates.size.height
            )
        )
    }
}

private fun MainTopBarAction.anchor(): MainTopBarAnchor {
    return when (this) {
        MainTopBarAction.SEARCH -> MainTopBarAnchor.SEARCH
        MainTopBarAction.FILTER -> MainTopBarAnchor.FILTER
        MainTopBarAction.STAR -> MainTopBarAnchor.STAR
        MainTopBarAction.REFRESH -> MainTopBarAnchor.REFRESH
        MainTopBarAction.LOGIN -> MainTopBarAnchor.LOGIN
        MainTopBarAction.MORE -> MainTopBarAnchor.MORE
        MainTopBarAction.FILTER_TOGGLE -> MainTopBarAnchor.FILTER_TOGGLE
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposeTopBarTagBar(
    state: MainTopBarTagBarState,
    config: TopBarConfig.Config,
    slot: TopBarTagSlot,
    modifier: Modifier = Modifier,
    onTagClick: (TopBarTagSlot, Int) -> Unit,
    onTagLongClick: (TopBarTagSlot, Int) -> Boolean
) {
    if (!state.visible) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val isTextMode = state.displayMode == RoundedTagBarView.DisplayMode.TEXT
    val tagBarColor = config.tagBarColor
        ?: if (config.style == TopBarConfig.STYLE_REGULAR) {
            android.graphics.Color.WHITE
        } else {
            ContextCompat.getColor(context, R.color.background_menu)
        }
    val selectedColor = config.tagSelectedColor
        ?: ContextCompat.getColor(context, R.color.background_card)
    val selectedBackgroundColor = TopBarConfig.withOpacity(selectedColor, config.tagSelectedAlpha)
    val selectedTextColor = readableTagTextColor(context.accentColor, selectedBackgroundColor)
    val normalTextColor = context.primaryTextColor
    val bodyFont = remember(context) { FontFamily(context.uiTypeface()) }
    val panelRadius = with(density) { io.legado.app.lib.theme.UiCorner.panelRadius(context).toDp() }
    val actionRadius = with(density) { io.legado.app.lib.theme.UiCorner.actionRadius(context).toDp() }
    val verticalPadding = if (isTextMode) 0.dp else dimensionResource(R.dimen.bookshelf_tag_bar_padding_vertical)
    val horizontalPadding = if (isTextMode) 0.dp else dimensionResource(R.dimen.bookshelf_tag_bar_padding_horizontal)
    val itemHorizontalPadding = if (isTextMode) 8.dp else dimensionResource(R.dimen.bookshelf_tag_item_padding_horizontal)
    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedIndex, state.items.size) {
        if (state.selectedIndex in state.items.indices) {
            listState.scrollToItem(state.selectedIndex)
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.bookshelf_tag_bar_height))
            .clip(RoundedCornerShape(panelRadius))
            .then(
                if (isTextMode) {
                    Modifier
                } else {
                    Modifier.background(Color(TopBarConfig.withOpacity(tagBarColor, config.tagBarAlpha)))
                }
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            itemsIndexed(state.items) { index, item ->
                val selected = index == state.selectedIndex
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(actionRadius))
                        .then(
                            if (selected && state.selectedBackgroundVisible && !isTextMode) {
                                Modifier.background(Color(selectedBackgroundColor))
                            } else {
                                Modifier
                            }
                        )
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onLongClick = { onTagLongClick(slot, index) },
                            onClick = { onTagClick(slot, index) }
                        )
                        .alpha(item.alpha)
                        .padding(horizontal = itemHorizontalPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.text,
                        color = Color(if (selected) selectedTextColor else normalTextColor),
                        fontSize = 14.sp,
                        fontFamily = bodyFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun MainTopBarUiState.action(action: MainTopBarAction): MainTopBarActionState {
    return actions.firstOrNull { it.action == action }
        ?: MainTopBarActionState(action, R.drawable.ic_more_vert, "", false, true, 1f)
}

private fun readableTagTextColor(preferredColor: Int, backgroundColor: Int): Int {
    if (android.graphics.Color.alpha(backgroundColor) < 40) return preferredColor
    val preferredIsLight = ColorUtils.isColorLight(preferredColor)
    val backgroundIsLight = ColorUtils.isColorLight(backgroundColor)
    return if (preferredIsLight != backgroundIsLight) {
        preferredColor
    } else if (backgroundIsLight) {
        android.graphics.Color.BLACK
    } else {
        android.graphics.Color.WHITE
    }
}
