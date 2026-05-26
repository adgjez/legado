package io.legado.app.ui.main.ai.compose

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.ColorUtils

@Immutable
data class AiComposeColors(
    val accent: Color,
    val background: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val processSurface: Color,
    val toolSurface: Color,
    val stroke: Color,
    val danger: Color
)

@Immutable
data class AiComposeMetrics(
    val cardRadius: Dp,
    val chipRadius: Dp,
    val strokeWidth: Dp
)

@Immutable
data class AiComposeStyle(
    val colors: AiComposeColors,
    val metrics: AiComposeMetrics
)

@Stable
fun aiComposeStyle(context: Context): AiComposeStyle {
    val background = context.backgroundColor
    val accent = context.accentColor
    val processSurface = ColorUtils.blendColors(background, 0xff607d8b.toInt(), 0.08f)
    val toolSurface = ColorUtils.blendColors(background, accent, 0.07f)
    val stroke = if (UiCorner.effectMode() == "solid") {
        ColorUtils.adjustAlpha(accent, 0.18f)
    } else {
        UiCorner.effectStrokeColor(background)
    }
    return AiComposeStyle(
        colors = AiComposeColors(
            accent = Color(accent),
            background = Color(background),
            primaryText = Color(context.primaryTextColor),
            secondaryText = Color(context.secondaryTextColor),
            processSurface = Color(UiCorner.surfaceColor(processSurface)),
            toolSurface = Color(UiCorner.surfaceColor(toolSurface)),
            stroke = Color(stroke),
            danger = Color(0xfff44336.toInt())
        ),
        metrics = AiComposeMetrics(
            cardRadius = (UiCorner.scaledDp(16f) / context.resources.displayMetrics.density).dp,
            chipRadius = (UiCorner.scaledDp(10f) / context.resources.displayMetrics.density).dp,
            strokeWidth = 1.dp
        )
    )
}
