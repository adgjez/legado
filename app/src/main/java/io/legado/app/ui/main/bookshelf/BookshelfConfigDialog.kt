package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSlider
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlin.math.roundToInt

data class BookshelfConfigValues(
    val groupStyle: Int,
    val showUnread: Boolean,
    val showLastUpdateTime: Boolean,
    val showWaitUpCount: Boolean,
    val showFastScroller: Boolean,
    val layout: Int,
    val sort: Int,
    val showBookname: Int,
    val margin: Int
)

private data class BookshelfConfigOption(
    val label: String,
    val value: Int
)

private data class BookshelfConfigOptions(
    val groupStyles: List<BookshelfConfigOption>,
    val layouts: List<BookshelfConfigOption>,
    val sorts: List<BookshelfConfigOption>,
    val bookNameModes: List<BookshelfConfigOption>
)

private data class BookshelfConfigTexts(
    val title: String,
    val viewTitle: String,
    val groupStyleLabel: String,
    val layoutLabel: String,
    val showTitle: String,
    val sortLabel: String,
    val bookNameLabel: String,
    val marginTitle: String,
    val marginLabel: String,
    val cancelLabel: String,
    val applyLabel: String
)

private data class BookshelfPremiumSpec(
    val panelHorizontalPadding: Dp = 16.dp,
    val panelVerticalPadding: Dp = 14.dp,
    val contentMaxHeight: Dp = 460.dp,
    val sectionPadding: Dp = 10.dp,
    val sectionGap: Dp = 8.dp,
    val tileHeight: Dp = 64.dp,
    val switchTileHeight: Dp = 46.dp,
    val choiceHeight: Dp = 38.dp,
    val gridGap: Dp = 8.dp,
    val compactGap: Dp = 6.dp
)

private data class BookshelfSelectItem(
    val key: String,
    val label: String,
    val options: List<BookshelfConfigOption>,
    val selectedValue: Int,
    val onSelected: (Int) -> Unit
)

private data class BookshelfSwitchItem(
    val label: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

class BookshelfConfigDialog : ComposeDialogFragment() {

    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 640
    override val dialogGravity: Int = Gravity.CENTER
    override val dialogWindowAnimations: Int = R.style.AnimDialogCenter

    private var initialValues = BookshelfConfigValues(
        groupStyle = 0,
        showUnread = false,
        showLastUpdateTime = false,
        showWaitUpCount = false,
        showFastScroller = false,
        layout = 0,
        sort = 0,
        showBookname = 0,
        margin = 12
    )
    private var onApply: ((BookshelfConfigValues) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val options = remember { buildBookshelfConfigOptions() }
                val texts = remember { buildBookshelfConfigTexts() }
                var values by remember { mutableStateOf(initialValues) }
                BookshelfConfigPanel(
                    texts = texts,
                    style = style,
                    content = {
                        BookshelfConfigContent(
                            values = values,
                            options = options,
                            texts = texts,
                            style = style,
                            onValuesChange = { values = it }
                        )
                    },
                    actions = {
                        BookshelfFooterActions(
                            style = style,
                            cancelLabel = texts.cancelLabel,
                            applyLabel = texts.applyLabel,
                            onCancel = { dismissAllowingStateLoss() },
                            onApply = {
                                dismissAllowingStateLoss()
                                onApply?.invoke(values)
                            }
                        )
                    }
                )
            }
        }
    }

    private fun buildBookshelfConfigOptions(): BookshelfConfigOptions {
        return BookshelfConfigOptions(
            groupStyles = resources.getStringArray(R.array.group_style)
                .mapIndexed { index, label -> BookshelfConfigOption(label, index) },
            layouts = listOf(
                getString(R.string.layout_list),
                getString(R.string.layout_list_compact),
                getString(R.string.layout_grid2),
                getString(R.string.layout_grid3),
                getString(R.string.layout_grid4),
                getString(R.string.layout_grid5),
                getString(R.string.layout_grid6)
            ).mapIndexed { index, label -> BookshelfConfigOption(label, index) },
            sorts = listOf(
                getString(R.string.bookshelf_px_0),
                getString(R.string.bookshelf_px_1),
                getString(R.string.bookshelf_px_2),
                getString(R.string.bookshelf_px_3),
                getString(R.string.bookshelf_px_4),
                getString(R.string.bookshelf_px_5)
            ).mapIndexed { index, label -> BookshelfConfigOption(label, index) },
            bookNameModes = listOf(
                getString(R.string.show),
                getString(R.string.hide),
                getString(R.string.overlay)
            ).mapIndexed { index, label -> BookshelfConfigOption(label, index) }
        )
    }

    private fun buildBookshelfConfigTexts(): BookshelfConfigTexts {
        return BookshelfConfigTexts(
            title = getString(R.string.bookshelf_layout),
            viewTitle = getString(R.string.view),
            groupStyleLabel = getString(R.string.group_style),
            layoutLabel = getString(R.string.view),
            showTitle = getString(R.string.show),
            sortLabel = getString(R.string.sort),
            bookNameLabel = getString(R.string.book_name),
            marginTitle = getString(R.string.margin),
            marginLabel = getString(R.string.margin),
            cancelLabel = getString(android.R.string.cancel),
            applyLabel = getString(android.R.string.ok)
        )
    }

    companion object {
        fun create(
            initialValues: BookshelfConfigValues,
            onApply: (BookshelfConfigValues) -> Unit
        ): BookshelfConfigDialog {
            return BookshelfConfigDialog().apply {
                this.initialValues = initialValues
                this.onApply = onApply
            }
        }
    }
}

@Composable
private fun BookshelfConfigContent(
    values: BookshelfConfigValues,
    options: BookshelfConfigOptions,
    texts: BookshelfConfigTexts,
    style: AppDialogStyle,
    onValuesChange: (BookshelfConfigValues) -> Unit
) {
    val spec = BookshelfPremiumSpec()
    val selectItems = buildList {
        add(
            BookshelfSelectItem(
                key = "group",
                label = texts.groupStyleLabel,
                options = options.groupStyles,
                selectedValue = values.groupStyle,
                onSelected = { onValuesChange(values.copy(groupStyle = it)) }
            )
        )
        add(
            BookshelfSelectItem(
                key = "layout",
                label = texts.layoutLabel,
                options = options.layouts,
                selectedValue = values.layout,
                onSelected = { onValuesChange(values.copy(layout = it)) }
            )
        )
        add(
            BookshelfSelectItem(
                key = "sort",
                label = texts.sortLabel,
                options = options.sorts,
                selectedValue = values.sort,
                onSelected = { onValuesChange(values.copy(sort = it)) }
            )
        )
        if (values.layout >= 2) {
            add(
                BookshelfSelectItem(
                    key = "bookName",
                    label = texts.bookNameLabel,
                    options = options.bookNameModes,
                    selectedValue = values.showBookname,
                    onSelected = { onValuesChange(values.copy(showBookname = it)) }
                )
            )
        }
    }
    val switchItems = listOf(
        BookshelfSwitchItem(
            label = stringResource(R.string.show_unread),
            checked = values.showUnread,
            onCheckedChange = { onValuesChange(values.copy(showUnread = it)) }
        ),
        BookshelfSwitchItem(
            label = stringResource(R.string.show_last_update_time),
            checked = values.showLastUpdateTime,
            onCheckedChange = { onValuesChange(values.copy(showLastUpdateTime = it)) }
        ),
        BookshelfSwitchItem(
            label = stringResource(R.string.show_wait_up_count),
            checked = values.showWaitUpCount,
            onCheckedChange = { onValuesChange(values.copy(showWaitUpCount = it)) }
        ),
        BookshelfSwitchItem(
            label = stringResource(R.string.show_bookshelf_fast_scroller),
            checked = values.showFastScroller,
            onCheckedChange = { onValuesChange(values.copy(showFastScroller = it)) }
        )
    )
    ConfigSection(
        title = texts.viewTitle,
        style = style,
        spec = spec
    ) {
        BookshelfOptionGrid(
            items = selectItems,
            style = style,
            spec = spec
        )
    }
    ConfigSection(
        title = texts.showTitle,
        style = style,
        spec = spec
    ) {
        BookshelfSwitchGrid(
            items = switchItems,
            style = style,
            spec = spec
        )
    }
    ConfigSection(
        title = texts.marginTitle,
        style = style,
        spec = spec
    ) {
        BookshelfSliderRow(
            title = texts.marginLabel,
            value = values.margin,
            range = 0..60,
            style = style,
            onValueChange = { onValuesChange(values.copy(margin = it)) }
        )
    }
}

@Composable
private fun BookshelfConfigPanel(
    texts: BookshelfConfigTexts,
    style: AppDialogStyle,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    val spec = BookshelfPremiumSpec()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 18.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(
                horizontal = spec.panelHorizontalPadding,
                vertical = spec.panelVerticalPadding
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Text(
                    text = texts.title,
                    color = style.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = spec.contentMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spec.sectionGap)
                ) {
                    content()
                }
                Spacer(modifier = Modifier.height(10.dp))
                actions()
            }
        }
    }
}

@Composable
private fun ConfigSection(
    title: String,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    visible: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!visible) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.fieldSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(spec.sectionPadding)) {
            Text(
                text = title,
                color = style.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = spec.compactGap),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            content()
        }
    }
}

@Composable
private fun BookshelfOptionGrid(
    items: List<BookshelfSelectItem>,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec
) {
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val expandedItem = items.firstOrNull { it.key == expandedKey }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spec.gridGap)
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spec.gridGap)
            ) {
                rowItems.forEach { item ->
                    BookshelfSelectTile(
                        item = item,
                        expanded = item.key == expandedKey,
                        style = style,
                        spec = spec,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            expandedKey = if (expandedKey == item.key) null else item.key
                        }
                    )
                }
                if (rowItems.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        expandedItem?.let { item ->
            BookshelfChoicePanel(
                item = item,
                style = style,
                spec = spec,
                onSelected = {
                    item.onSelected(it)
                    expandedKey = null
                }
            )
        }
    }
}

@Composable
private fun BookshelfSelectTile(
    item: BookshelfSelectItem,
    expanded: Boolean,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val selected = item.options.firstOrNull { it.value == item.selectedValue }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "bookshelfSelectTileArrow"
    )
    Surface(
        modifier = modifier
            .height(spec.tileHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = if (expanded) style.accent.copy(alpha = 0.10f) else style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    color = style.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = selected?.label.orEmpty(),
                    color = if (expanded) style.accent else style.primaryText,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_expand_more),
                contentDescription = null,
                tint = if (expanded) style.accent else style.secondaryText,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }
}

@Composable
private fun BookshelfChoicePanel(
    item: BookshelfSelectItem,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    onSelected: (Int) -> Unit
) {
    val columns = 2
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item.options.chunked(columns).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowOptions.forEach { option ->
                        BookshelfChoiceChip(
                            option = option,
                            selected = option.value == item.selectedValue,
                            style = style,
                            spec = spec,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelected(option.value) }
                        )
                    }
                    repeat(columns - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BookshelfChoiceChip(
    option: BookshelfConfigOption,
    selected: Boolean,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(spec.choiceHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = if (selected) style.accent.copy(alpha = 0.14f) else style.fieldSurface,
        contentColor = if (selected) style.accent else style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = option.label,
                color = if (selected) style.accent else style.primaryText,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BookshelfSwitchGrid(
    items: List<BookshelfSwitchItem>,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spec.gridGap)
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spec.gridGap)
            ) {
                rowItems.forEach { item ->
                    BookshelfSwitchTile(
                        item = item,
                        style = style,
                        spec = spec,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BookshelfSwitchTile(
    item: BookshelfSwitchItem,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(spec.switchTileHeight)
            .clickable { item.onCheckedChange(!item.checked) },
        shape = RoundedCornerShape(style.actionRadius),
        color = if (item.checked) style.accent.copy(alpha = 0.10f) else style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.label,
                modifier = Modifier.weight(1f),
                color = if (item.checked) style.primaryText else style.secondaryText,
                fontSize = 13.sp,
                fontWeight = if (item.checked) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BookshelfMiniSwitch(
                checked = item.checked,
                style = style
            )
        }
    }
}

@Composable
private fun BookshelfMiniSwitch(
    checked: Boolean,
    style: AppDialogStyle
) {
    val trackWidth = 38.dp
    val trackHeight = 22.dp
    val thumbSize = 16.dp
    val edge = 3.dp
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - edge else edge,
        label = "bookshelfMiniSwitchThumb"
    )
    Box(
        modifier = Modifier
            .size(trackWidth, trackHeight)
            .clip(RoundedCornerShape(50))
            .background(if (checked) style.accent else style.fieldSurface)
            .border(
                width = 1.dp,
                color = if (checked) Color.Transparent else style.stroke,
                shape = RoundedCornerShape(50)
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(if (checked) Color.White else style.secondaryText.copy(alpha = 0.62f))
        )
    }
}

@Composable
private fun BookshelfSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    style: AppDialogStyle,
    onValueChange: (Int) -> Unit
) {
    val palette = style.toMiuixPalette()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = style.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = style.accent.copy(alpha = 0.12f),
                    contentColor = style.accent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Text(
                        text = value.toString(),
                        color = style.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }
            LegadoMiuixSlider(
                value = value.toFloat(),
                onValueChange = {
                    onValueChange(it.roundToInt().coerceIn(range.first, range.last))
                },
                palette = palette,
                modifier = Modifier.height(28.dp),
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0)
            )
        }
    }
}

@Composable
private fun BookshelfFooterActions(
    style: AppDialogStyle,
    cancelLabel: String,
    applyLabel: String,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    val palette = style.toMiuixPalette()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegadoMiuixActionButton(
            text = cancelLabel,
            palette = palette,
            onClick = onCancel,
            modifier = Modifier.width(92.dp),
            cornerRadius = style.actionRadius
        )
        Spacer(modifier = Modifier.width(10.dp))
        LegadoMiuixActionButton(
            text = applyLabel,
            palette = palette,
            onClick = onApply,
            modifier = Modifier.width(108.dp),
            primary = true,
            cornerRadius = style.actionRadius
        )
    }
}
