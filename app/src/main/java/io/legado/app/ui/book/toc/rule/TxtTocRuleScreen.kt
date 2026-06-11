package io.legado.app.ui.book.toc.rule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

@Composable
internal fun TxtTocRuleScreen(
    rules: List<TxtTocRule>,
    selectedIds: Set<Long>,
    onToggleSelect: (TxtTocRule) -> Unit,
    onToggleEnable: (TxtTocRule) -> Unit,
    onEdit: (TxtTocRule) -> Unit,
    onMenuMore: (TxtTocRule) -> Unit,
    onSelectAll: () -> Unit,
    onRevertSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(palette.surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(
            items = rules,
            key = { _, rule -> rule.id }
        ) { _, rule ->
            TxtTocRuleItemRow(
                rule = rule,
                isSelected = selectedIds.contains(rule.id),
                style = style,
                palette = palette,
                onToggleSelect = { onToggleSelect(rule) },
                onToggleEnable = { onToggleEnable(rule) },
                onEdit = { onEdit(rule) },
                onMenuMore = { onMenuMore(rule) }
            )
        }
    }
}

@Composable
private fun TxtTocRuleItemRow(
    rule: TxtTocRule,
    isSelected: Boolean,
    style: AppDialogStyle,
    palette: LegadoMiuixPalette,
    onToggleSelect: () -> Unit,
    onToggleEnable: () -> Unit,
    onEdit: () -> Unit,
    onMenuMore: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = 16.dp,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = palette.accent,
                        uncheckedColor = palette.secondaryText,
                        checkmarkColor = palette.onAccent
                    )
                )
                Text(
                    text = rule.name,
                    modifier = Modifier.weight(1f),
                    color = palette.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
                LegadoMiuixSwitch(
                    checked = rule.enable,
                    onCheckedChange = { onToggleEnable() },
                    palette = palette
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.edit),
                        tint = palette.primaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onMenuMore),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more_menu),
                        tint = palette.primaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            val example = rule.example
            if (!example.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = example,
                    color = palette.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
