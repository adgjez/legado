package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSelectField
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
    val subtitle: String,
    val viewTitle: String,
    val groupStyleLabel: String,
    val layoutLabel: String,
    val showTitle: String,
    val sortTitle: String,
    val sortLabel: String,
    val bookNameTitle: String,
    val bookNameLabel: String,
    val marginTitle: String,
    val marginLabel: String,
    val cancelLabel: String,
    val applyLabel: String
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
            subtitle = "${getString(R.string.group_style)} / ${getString(R.string.view)} / ${getString(R.string.sort)}",
            viewTitle = getString(R.string.view),
            groupStyleLabel = getString(R.string.group_style),
            layoutLabel = getString(R.string.view),
            showTitle = getString(R.string.show),
            sortTitle = getString(R.string.sort),
            sortLabel = getString(R.string.sort),
            bookNameTitle = getString(R.string.book_name),
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
    ConfigSection(
        title = texts.viewTitle,
        style = style
    ) {
        BookshelfSegmentedControl(
            label = texts.groupStyleLabel,
            options = options.groupStyles,
            selectedValue = values.groupStyle,
            style = style,
            onSelected = { onValuesChange(values.copy(groupStyle = it)) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        DialogSelectField(
            label = texts.layoutLabel,
            options = options.layouts,
            selectedValue = values.layout,
            style = style,
            onSelected = { onValuesChange(values.copy(layout = it)) }
        )
    }
    ConfigSection(
        title = texts.showTitle,
        style = style
    ) {
        BookshelfSwitchRow(
            text = stringResource(R.string.show_unread),
            checked = values.showUnread,
            style = style,
            onCheckedChange = { onValuesChange(values.copy(showUnread = it)) }
        )
        BookshelfSwitchRow(
            text = stringResource(R.string.show_last_update_time),
            checked = values.showLastUpdateTime,
            style = style,
            onCheckedChange = { onValuesChange(values.copy(showLastUpdateTime = it)) }
        )
        BookshelfSwitchRow(
            text = stringResource(R.string.show_wait_up_count),
            checked = values.showWaitUpCount,
            style = style,
            onCheckedChange = { onValuesChange(values.copy(showWaitUpCount = it)) }
        )
        BookshelfSwitchRow(
            text = stringResource(R.string.show_bookshelf_fast_scroller),
            checked = values.showFastScroller,
            style = style,
            onCheckedChange = { onValuesChange(values.copy(showFastScroller = it)) }
        )
    }
    ConfigSection(
        title = texts.sortTitle,
        style = style
    ) {
        DialogSelectField(
            label = texts.sortLabel,
            options = options.sorts,
            selectedValue = values.sort,
            style = style,
            onSelected = { onValuesChange(values.copy(sort = it)) }
        )
    }
    ConfigSection(
        title = texts.bookNameTitle,
        style = style,
        visible = values.layout >= 2
    ) {
        BookshelfSegmentedControl(
            label = texts.bookNameLabel,
            options = options.bookNameModes,
            selectedValue = values.showBookname,
            style = style,
            onSelected = { onValuesChange(values.copy(showBookname = it)) }
        )
    }
    ConfigSection(
        title = texts.marginTitle,
        style = style
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
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 18.dp),
        color = style.surface,
        contentColor = style.primaryText,
        cornerRadius = style.panelRadius,
        insidePadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
        ) {
            Text(
                text = texts.title,
                color = style.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = texts.subtitle,
                color = style.secondaryText,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content()
            }
            Spacer(modifier = Modifier.height(12.dp))
            actions()
        }
    }
}

@Composable
private fun ConfigSection(
    title: String,
    style: AppDialogStyle,
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
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = title,
                color = style.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            content()
        }
    }
}

@Composable
private fun BookshelfSwitchRow(
    text: String,
    checked: Boolean,
    style: AppDialogStyle,
    onCheckedChange: (Boolean) -> Unit
) {
    val trackWidth = 46.dp
    val trackHeight = 26.dp
    val thumbSize = 20.dp
    val edge = 3.dp
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - edge else edge,
        label = "bookshelfSwitchThumb"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = style.primaryText,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .size(trackWidth, trackHeight)
                .clip(RoundedCornerShape(50))
                .background(if (checked) style.accent else style.surface)
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
                    .background(if (checked) Color.White else style.secondaryText.copy(alpha = 0.58f))
            )
        }
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = style.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value.toString(),
                color = style.secondaryText,
                fontSize = 13.sp
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                activeTrackColor = style.accent,
                inactiveTrackColor = style.surface,
                thumbColor = style.accent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = style.secondaryText.copy(alpha = 0.24f)
            )
        )
    }
}

@Composable
private fun DialogSelectField(
    label: String,
    options: List<BookshelfConfigOption>,
    selectedValue: Int,
    style: AppDialogStyle,
    onSelected: (Int) -> Unit
) {
    val palette = style.toMiuixPalette()
    val selectedIndex = options.indexOfFirst { it.value == selectedValue }
        .takeIf { it >= 0 }
        ?: 0
    val selected = options.getOrNull(selectedIndex) ?: return
    LegadoMiuixSelectField(
        label = label,
        options = options,
        selected = selected,
        optionLabel = { it.label },
        onSelected = { onSelected(it.value) },
        palette = palette,
        cornerRadius = style.actionRadius
    )
}

@Composable
private fun BookshelfSegmentedControl(
    label: String,
    options: List<BookshelfConfigOption>,
    selectedValue: Int,
    style: AppDialogStyle,
    onSelected: (Int) -> Unit
) {
    Column {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(7.dp))
        LegadoMiuixCard(
            modifier = Modifier.fillMaxWidth(),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.actionRadius,
            insidePadding = PaddingValues(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEach { option ->
                    val selected = option.value == selectedValue
                    LegadoMiuixCard(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clickable { onSelected(option.value) },
                        color = if (selected) style.accent.copy(alpha = 0.18f) else style.surface,
                        contentColor = style.primaryText,
                        cornerRadius = style.actionRadius,
                        insidePadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
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
            }
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
