package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogSliderRow
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.rememberAppDialogStyle

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

class BookshelfConfigDialog : ComposeDialogFragment() {

    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 760
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

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
                var values by remember { mutableStateOf(initialValues) }
                BookshelfConfigPanel(
                    title = getString(R.string.bookshelf_layout),
                    subtitle = "${getString(R.string.group_style)} / ${getString(R.string.view)} / ${getString(R.string.sort)}",
                    style = style,
                    content = {
                        BookshelfConfigContent(
                            values = values,
                            options = options,
                            style = style,
                            onValuesChange = { values = it }
                        )
                    },
                    actions = {
                        BookshelfFooterActions(
                            style = style,
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
    style: AppDialogStyle,
    onValuesChange: (BookshelfConfigValues) -> Unit
) {
    BookshelfLayoutPreview(
        values = values,
        options = options,
        style = style
    )
    ConfigSection(
        title = "结构",
        style = style
    ) {
        BookshelfSegmentedControl(
            label = "分组样式",
            options = options.groupStyles,
            selectedValue = values.groupStyle,
            style = style,
            onSelected = { onValuesChange(values.copy(groupStyle = it)) }
        )
        Spacer(modifier = Modifier.height(14.dp))
        BookshelfLayoutPicker(
            options = options.layouts,
            selectedValue = values.layout,
            style = style,
            onSelected = { onValuesChange(values.copy(layout = it)) }
        )
    }
    ConfigSection(
        title = "显示",
        style = style
    ) {
        AppDialogSwitchRow(
            text = "显示未读",
            checked = values.showUnread,
            onCheckedChange = { onValuesChange(values.copy(showUnread = it)) }
        )
        AppDialogSwitchRow(
            text = "显示最近更新时间",
            checked = values.showLastUpdateTime,
            onCheckedChange = { onValuesChange(values.copy(showLastUpdateTime = it)) }
        )
        AppDialogSwitchRow(
            text = "显示追更数量",
            checked = values.showWaitUpCount,
            onCheckedChange = { onValuesChange(values.copy(showWaitUpCount = it)) }
        )
        AppDialogSwitchRow(
            text = "显示快速滚动条",
            checked = values.showFastScroller,
            onCheckedChange = { onValuesChange(values.copy(showFastScroller = it)) }
        )
    }
    ConfigSection(
        title = "排序",
        style = style
    ) {
        BookshelfChoiceGrid(
            label = "排序方式",
            options = options.sorts,
            selectedValue = values.sort,
            style = style,
            onSelected = { onValuesChange(values.copy(sort = it)) }
        )
    }
    ConfigSection(
        title = "书名",
        style = style,
        visible = values.layout >= 2
    ) {
        BookshelfSegmentedControl(
            label = "网格书名",
            options = options.bookNameModes,
            selectedValue = values.showBookname,
            style = style,
            onSelected = { onValuesChange(values.copy(showBookname = it)) }
        )
    }
    ConfigSection(
        title = "边距",
        style = style
    ) {
        AppDialogSliderRow(
            title = "边距",
            value = values.margin,
            range = 0..60,
            onValueChange = { onValuesChange(values.copy(margin = it)) }
        )
    }
}

@Composable
private fun BookshelfLayoutPicker(
    options: List<BookshelfConfigOption>,
    selectedValue: Int,
    style: AppDialogStyle,
    onSelected: (Int) -> Unit
) {
    val rows = listOf(
        options.take(2),
        options.drop(2).take(3),
        options.drop(5)
    ).filter { it.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "书架视图",
            color = style.secondaryText,
            fontSize = 12.sp
        )
        rows.forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowOptions.forEach { option ->
                    BookshelfLayoutCard(
                        option = option,
                        selected = option.value == selectedValue,
                        style = style,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelected(option.value) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookshelfLayoutCard(
    option: BookshelfConfigOption,
    selected: Boolean,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(92.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = if (selected) style.accent.copy(alpha = 0.16f) else style.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .width(4.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(20.dp),
                color = if (selected) style.accent else style.secondaryText.copy(alpha = 0.16f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {}
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BookshelfLayoutMiniature(option.value, style)
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

@Composable
private fun BookshelfLayoutMiniature(
    layout: Int,
    style: AppDialogStyle
) {
    val columns = if (layout >= 2) layout.coerceIn(2, 6) else 1
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (columns == 1) {
            repeat(if (layout == 1) 3 else 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .width(if (layout == 1) 12.dp else 16.dp)
                            .height(if (layout == 1) 14.dp else 18.dp),
                        shape = RoundedCornerShape(3.dp),
                        color = style.accent.copy(alpha = 0.25f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {}
                    Spacer(modifier = Modifier.width(5.dp))
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = style.primaryText.copy(alpha = 0.13f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {}
                }
            }
        } else {
            repeat(2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(columns) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(12.dp),
                            shape = RoundedCornerShape(3.dp),
                            color = style.accent.copy(alpha = 0.22f),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp
                        ) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun BookshelfLayoutPreview(
    values: BookshelfConfigValues,
    options: BookshelfConfigOptions,
    style: AppDialogStyle
) {
    val layoutLabel = options.layouts
        .getOrNull(values.layout.coerceIn(options.layouts.indices))
        ?.label
        .orEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.panelRadius),
        color = style.fieldSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "实时预览",
                        color = style.primaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = layoutLabel,
                        color = style.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "边距 ${values.margin}",
                    color = style.secondaryText,
                    fontSize = 12.sp
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(style.actionRadius),
                color = style.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding((8 + values.margin / 8).dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        values.layout <= 0 -> repeat(3) { BookshelfPreviewListRow(style, compact = false) }
                        values.layout == 1 -> repeat(4) { BookshelfPreviewListRow(style, compact = true) }
                        else -> {
                            val columns = values.layout.coerceIn(2, 6)
                            repeat(2) {
                                BookshelfPreviewGridRow(
                                    columns = columns,
                                    showTitle = values.showBookname != 1,
                                    style = style
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookshelfPreviewListRow(
    style: AppDialogStyle,
    compact: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .width(if (compact) 24.dp else 32.dp)
                .height(if (compact) 34.dp else 44.dp),
            shape = RoundedCornerShape(5.dp),
            color = style.accent.copy(alpha = 0.24f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {}
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(if (compact) 0.62f else 0.72f)
                    .height(if (compact) 8.dp else 10.dp),
                shape = RoundedCornerShape(20.dp),
                color = style.primaryText.copy(alpha = 0.16f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {}
            Surface(
                modifier = Modifier
                    .fillMaxWidth(if (compact) 0.44f else 0.54f)
                    .height(7.dp),
                shape = RoundedCornerShape(20.dp),
                color = style.secondaryText.copy(alpha = 0.14f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {}
        }
    }
}

@Composable
private fun BookshelfPreviewGridRow(
    columns: Int,
    showTitle: Boolean,
    style: AppDialogStyle
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        repeat(columns) { index ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((34 + (index % 2) * 5).dp),
                    shape = RoundedCornerShape(5.dp),
                    color = style.accent.copy(alpha = 0.18f + index.coerceAtMost(3) * 0.025f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {}
                if (showTitle) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .height(6.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = style.primaryText.copy(alpha = 0.13f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun BookshelfConfigPanel(
    title: String,
    subtitle: String,
    style: AppDialogStyle,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp)
            .padding(top = 10.dp),
        shape = RoundedCornerShape(style.panelRadius),
        color = style.surface,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 18.dp)
                .padding(top = 16.dp, bottom = 14.dp)
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp),
                shape = RoundedCornerShape(20.dp),
                color = style.secondaryText.copy(alpha = 0.22f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {}
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = style.secondaryText,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 660.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions()
            }
        }
    }
}

@Composable
private fun BookshelfFooterActions(
    style: AppDialogStyle,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .height(42.dp)
                .width(92.dp)
                .clickable(onClick = onCancel),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.fieldSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "取消",
                    color = style.secondaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Surface(
            modifier = Modifier
                .height(42.dp)
                .width(108.dp)
                .clickable(onClick = onApply),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.accent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "完成",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
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
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = title,
                color = style.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
private fun BookshelfSegmentedControl(
    label: String,
    options: List<BookshelfConfigOption>,
    selectedValue: Int,
    style: AppDialogStyle,
    onSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.forEach { option ->
                    val selected = option.value == selectedValue
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clickable { onSelected(option.value) },
                        shape = RoundedCornerShape(style.actionRadius),
                        color = if (selected) style.accent.copy(alpha = 0.18f) else style.surface,
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
            }
        }
    }
}

@Composable
private fun BookshelfChoiceGrid(
    label: String,
    options: List<BookshelfConfigOption>,
    selectedValue: Int,
    style: AppDialogStyle,
    onSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp
        )
        options.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowOptions.forEach { option ->
                    BookshelfChoiceCard(
                        option = option,
                        selected = option.value == selectedValue,
                        style = style,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelected(option.value) }
                    )
                }
                if (rowOptions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BookshelfChoiceCard(
    option: BookshelfConfigOption,
    selected: Boolean,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = if (selected) style.accent.copy(alpha = 0.15f) else style.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .width(6.dp)
                    .height(6.dp),
                shape = RoundedCornerShape(20.dp),
                color = if (selected) style.accent else style.secondaryText.copy(alpha = 0.22f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {}
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = option.label,
                modifier = Modifier.weight(1f),
                color = if (selected) style.accent else style.primaryText,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
