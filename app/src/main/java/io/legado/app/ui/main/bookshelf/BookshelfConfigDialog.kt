package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

class BookshelfConfigDialog : ComposeDialogFragment() {

    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 720

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
                val groupStyleOptions = resources.getStringArray(R.array.group_style).toList()
                val layoutOptions = listOf(
                    getString(R.string.layout_list),
                    getString(R.string.layout_list_compact),
                    getString(R.string.layout_grid2),
                    getString(R.string.layout_grid3),
                    getString(R.string.layout_grid4),
                    getString(R.string.layout_grid5),
                    getString(R.string.layout_grid6)
                )
                val sortOptions = listOf(
                    getString(R.string.bookshelf_px_0),
                    getString(R.string.bookshelf_px_1),
                    getString(R.string.bookshelf_px_2),
                    getString(R.string.bookshelf_px_3),
                    getString(R.string.bookshelf_px_4),
                    getString(R.string.bookshelf_px_5)
                )
                val bookNameOptions = listOf(
                    getString(R.string.show),
                    getString(R.string.hide),
                    getString(R.string.overlay)
                )
                var values by remember { mutableStateOf(initialValues) }
                BookshelfConfigPanel(
                    title = getString(R.string.bookshelf_layout),
                    subtitle = "${getString(R.string.group_style)} / ${getString(R.string.view)} / ${getString(R.string.sort)}",
                    style = style,
                    content = {
                        ConfigSection(
                            title = "结构",
                            style = style
                        ) {
                            DialogSelectField(
                                label = getString(R.string.group_style),
                                options = groupStyleOptions,
                                selectedIndex = values.groupStyle.coerceIn(groupStyleOptions.indices),
                                style = style,
                                onSelected = { values = values.copy(groupStyle = it) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            DialogSelectField(
                                label = getString(R.string.view),
                                options = layoutOptions,
                                selectedIndex = values.layout.coerceIn(layoutOptions.indices),
                                style = style,
                                onSelected = { values = values.copy(layout = it) }
                            )
                        }
                        ConfigSection(
                            title = getString(R.string.show),
                            style = style
                        ) {
                            AppDialogSwitchRow(
                                text = getString(R.string.show_unread),
                                checked = values.showUnread,
                                onCheckedChange = { values = values.copy(showUnread = it) }
                            )
                            AppDialogSwitchRow(
                                text = getString(R.string.show_last_update_time),
                                checked = values.showLastUpdateTime,
                                onCheckedChange = { values = values.copy(showLastUpdateTime = it) }
                            )
                            AppDialogSwitchRow(
                                text = getString(R.string.show_wait_up_count),
                                checked = values.showWaitUpCount,
                                onCheckedChange = { values = values.copy(showWaitUpCount = it) }
                            )
                            AppDialogSwitchRow(
                                text = getString(R.string.show_bookshelf_fast_scroller),
                                checked = values.showFastScroller,
                                onCheckedChange = { values = values.copy(showFastScroller = it) }
                            )
                        }
                        ConfigSection(
                            title = getString(R.string.sort),
                            style = style
                        ) {
                            DialogSelectField(
                                label = getString(R.string.sort),
                                options = sortOptions,
                                selectedIndex = values.sort.coerceIn(sortOptions.indices),
                                style = style,
                                onSelected = { values = values.copy(sort = it) }
                            )
                        }
                        ConfigSection(
                            title = getString(R.string.book_name),
                            style = style,
                            visible = values.layout >= 2
                        ) {
                            DialogSelectField(
                                label = getString(R.string.book_name),
                                options = bookNameOptions,
                                selectedIndex = values.showBookname.coerceIn(bookNameOptions.indices),
                                style = style,
                                onSelected = { values = values.copy(showBookname = it) }
                            )
                        }
                        ConfigSection(
                            title = getString(R.string.margin),
                            style = style
                        ) {
                            AppDialogSliderRow(
                                title = getString(R.string.margin),
                                value = values.margin,
                                range = 0..60,
                                onValueChange = { values = values.copy(margin = it) }
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { dismissAllowingStateLoss() },
                            shape = RoundedCornerShape(style.actionRadius)
                        ) {
                            Text(getString(android.R.string.cancel), color = style.secondaryText)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                dismissAllowingStateLoss()
                                onApply?.invoke(values)
                            },
                            shape = RoundedCornerShape(style.actionRadius)
                        ) {
                            Text(getString(android.R.string.ok), color = style.accent)
                        }
                    }
                )
            }
        }
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
            .padding(horizontal = 10.dp, vertical = 12.dp),
        shape = RoundedCornerShape(style.panelRadius),
        color = style.surface,
        tonalElevation = 0.dp,
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
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
                    .heightIn(max = 620.dp)
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
private fun DialogSelectField(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    style: AppDialogStyle,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val value = options.getOrNull(selectedIndex).orEmpty()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                shape = RoundedCornerShape(style.actionRadius),
                color = style.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = value,
                        modifier = Modifier.weight(1f),
                        color = style.primaryText,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "v",
                        color = style.secondaryText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                color = if (index == selectedIndex) style.accent else style.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(index)
                        }
                    )
                }
            }
        }
    }
}
