package io.legado.app.ui.replace

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

@Composable
internal fun ReplaceRuleScreen(
    rules: List<ReplaceRule>,
    selected: Set<Long>,
    onSelectToggle: (ReplaceRule) -> Unit,
    onToggleEnabled: (ReplaceRule, Boolean) -> Unit,
    onEdit: (ReplaceRule) -> Unit,
    onShowMenu: (ReplaceRule) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(
            rules,
            key = { _, rule -> rule.id }
        ) { _, rule ->
            ReplaceRuleItemRow(
                rule = rule,
                isSelected = rule.id in selected,
                palette = palette,
                style = style,
                onSelectToggle = { onSelectToggle(rule) },
                onToggleEnabled = { onToggleEnabled(rule, it) },
                onEdit = { onEdit(rule) },
                onShowMenu = { onShowMenu(rule) }
            )
        }
    }
}

@Composable
private fun ReplaceRuleItemRow(
    rule: ReplaceRule,
    isSelected: Boolean,
    palette: LegadoMiuixPalette,
    style: AppDialogStyle,
    onSelectToggle: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onShowMenu: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelectToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = palette.accent,
                    uncheckedColor = palette.secondaryText
                )
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            ) {
                Text(
                    text = rule.getDisplayNameGroup(),
                    color = palette.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            LegadoMiuixSwitch(
                checked = rule.isEnabled,
                onCheckedChange = onToggleEnabled,
                palette = palette,
                modifier = Modifier.padding(horizontal = 4.dp)
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
