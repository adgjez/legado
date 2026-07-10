package io.legado.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle

/**
 * 供应商类型选择器：垂直卡片列表，每张卡显示类型名 + 一行说明，当前选中高亮。
 * 替代旧的"一行 clickable + 纯文字弹窗"选择方式，让预设类型一目了然。
 */
@Composable
internal fun ProviderTypeSelector(
    metas: List<ProviderTypeMeta>,
    selectedTypeId: String,
    onSelect: (String) -> Unit
) {
    val style = rememberAppDialogStyle()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        metas.forEach { meta ->
            val selected = meta.typeId == selectedTypeId
            LegadoMiuixCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(meta.typeId) },
                color = if (selected) style.accent.copy(alpha = 0.12f) else style.fieldSurface,
                contentColor = style.primaryText,
                cornerRadius = style.actionRadius,
                insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(meta.displayNameRes),
                        color = if (selected) style.accent else style.primaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = meta.description,
                        color = style.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
