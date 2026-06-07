package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.legado.app.ui.widget.compose.AppDialogFrame
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
                AppDialogFrame(
                    title = getString(R.string.bookshelf_layout),
                    content = {
                        DialogSectionTitle(getString(R.string.group_style), style)
                        DialogChoiceChips(
                            options = groupStyleOptions,
                            selectedIndex = values.groupStyle.coerceIn(groupStyleOptions.indices),
                            style = style,
                            onSelected = { values = values.copy(groupStyle = it) }
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        DialogSectionTitle(getString(R.string.show), style)
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
                        Spacer(modifier = Modifier.height(14.dp))
                        DialogSectionTitle(getString(R.string.view), style)
                        DialogChoiceChips(
                            options = layoutOptions,
                            selectedIndex = values.layout.coerceIn(layoutOptions.indices),
                            style = style,
                            onSelected = { values = values.copy(layout = it) }
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        DialogSectionTitle(getString(R.string.sort), style)
                        DialogChoiceChips(
                            options = sortOptions,
                            selectedIndex = values.sort.coerceIn(sortOptions.indices),
                            style = style,
                            onSelected = { values = values.copy(sort = it) }
                        )
                        if (values.layout >= 2) {
                            Spacer(modifier = Modifier.height(14.dp))
                            DialogSectionTitle(getString(R.string.book_name), style)
                            DialogChoiceChips(
                                options = bookNameOptions,
                                selectedIndex = values.showBookname.coerceIn(bookNameOptions.indices),
                                style = style,
                                onSelected = { values = values.copy(showBookname = it) }
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        AppDialogSliderRow(
                            title = getString(R.string.margin),
                            value = values.margin,
                            range = 0..60,
                            onValueChange = { values = values.copy(margin = it) }
                        )
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
private fun DialogSectionTitle(
    text: String,
    style: AppDialogStyle
) {
    Text(
        text = text,
        color = style.accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogChoiceChips(
    options: List<String>,
    selectedIndex: Int,
    style: AppDialogStyle,
    onSelected: (Int) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = selectedIndex == index
            Surface(
                modifier = Modifier
                    .widthIn(min = 78.dp)
                    .clickable { onSelected(index) },
                shape = RoundedCornerShape(style.actionRadius),
                color = if (selected) {
                    style.accent.copy(alpha = 0.16f)
                } else {
                    style.fieldSurface
                },
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        color = if (selected) style.accent else style.primaryText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
