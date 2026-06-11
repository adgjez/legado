package io.legado.app.ui.rss.source.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

@Composable
internal fun RssSourceScreen(
    sources: List<RssSource>,
    selectedUrls: Set<String>,
    isSelectMode: Boolean,
    onToggleSelect: (RssSource) -> Unit,
    onToggleEnabled: (RssSource, Boolean) -> Unit,
    onEdit: (RssSource) -> Unit,
    onShowMenu: (RssSource) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(
            items = sources,
            key = { _, item -> item.sourceUrl }
        ) { _, source ->
            RssSourceItemRow(
                source = source,
                palette = palette,
                isSelected = source.sourceUrl in selectedUrls,
                isSelectMode = isSelectMode,
                onToggleSelect = { onToggleSelect(source) },
                onToggleEnabled = { enabled -> onToggleEnabled(source, enabled) },
                onEdit = { onEdit(source) },
                onShowMenu = { onShowMenu(source) }
            )
        }
    }
}

@Composable
private fun RssSourceItemRow(
    source: RssSource,
    palette: LegadoMiuixPalette,
    isSelected: Boolean,
    isSelectMode: Boolean,
    onToggleSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onShowMenu: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = 14.dp,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = palette.accent,
                        uncheckedColor = palette.secondaryText
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        if (isSelectMode) onToggleSelect()
                    }
            ) {
                Text(
                    text = source.sourceName,
                    color = palette.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!source.sourceGroup.isNullOrBlank()) {
                    Text(
                        text = source.sourceGroup.orEmpty(),
                        color = palette.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            LegadoMiuixSwitch(
                checked = source.enabled,
                onCheckedChange = onToggleEnabled,
                palette = palette
            )
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit),
                    tint = palette.primaryText,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onShowMenu,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.more_menu),
                    tint = palette.primaryText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
