package io.legado.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.compose.appSettingPanelBackground

@Immutable
data class ReadRecordOverviewUi(
    val todayValue: String,
    val todayLabel: String,
    val monthValue: String,
    val monthLabel: String,
    val totalValue: String,
    val totalLabel: String,
    val activeDaysValue: String,
    val activeDaysLabel: String
)

@Composable
fun ReadRecordOverviewCard(
    ui: ReadRecordOverviewUi,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReadRecordOverviewMetric(
                value = ui.todayValue,
                label = ui.todayLabel,
                modifier = Modifier.weight(1f)
            )
            ReadRecordOverviewMetric(
                value = ui.monthValue,
                label = ui.monthLabel,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReadRecordOverviewMetric(
                value = ui.totalValue,
                label = ui.totalLabel,
                modifier = Modifier.weight(1f)
            )
            ReadRecordOverviewMetric(
                value = ui.activeDaysValue,
                label = ui.activeDaysLabel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ReadRecordOverviewMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val surfaceColor = UiCorner.surfaceColor(ContextCompat.getColor(context, R.color.background_card))
    val borderColor = UiCorner.panelBorderColor(context)
    val radiusPx = UiCorner.panelRadius(context)
    val panelImage = androidx.compose.runtime.remember(context, radiusPx) {
        UiCorner.panelImageDrawable(context, radiusPx)
    }
    val primaryText = androidx.compose.ui.graphics.Color(
        ContextCompat.getColor(context, R.color.primaryText)
    )
    val secondaryText = androidx.compose.ui.graphics.Color(
        ContextCompat.getColor(context, R.color.secondaryText)
    )
    val titleFont = FontFamily(context.titleTypeface())
    val bodyFont = FontFamily(context.uiTypeface())
    Column(
        modifier = modifier
            .heightIn(min = 88.dp)
            .appSettingPanelBackground(
                normalColor = surfaceColor,
                panelImage = panelImage,
                borderColor = borderColor,
                radiusPx = radiusPx
            )
            .padding(16.dp)
    ) {
        Text(
            text = value,
            color = primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = titleFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            color = secondaryText,
            fontSize = 12.sp,
            fontFamily = bodyFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
