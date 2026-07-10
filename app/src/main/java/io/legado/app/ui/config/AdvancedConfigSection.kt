package io.legado.app.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle

/**
 * 高级配置折叠区：默认收起，点击 header 展开。
 * 收纳 submit/poll URL、JSONPath、timeout 等技术字段，避免吓退普通用户。
 * 展开后所有字段 placeholder 标注"留空用默认"。
 */
@Composable
internal fun AdvancedConfigSection(
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    val style = rememberAppDialogStyle()
    Column(modifier = Modifier.fillMaxWidth()) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!expanded) },
            color = style.fieldSurface,
            contentColor = style.primaryText,
            cornerRadius = style.actionRadius,
            insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.provider_advanced),
                    color = style.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(if (expanded) R.string.provider_advanced_collapse else R.string.provider_advanced_expand),
                    color = style.accent,
                    fontSize = 13.sp
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                content()
            }
        }
    }
}
