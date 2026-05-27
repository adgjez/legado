package io.legado.app.ui.main.ai.compose

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.help.config.AppConfig
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
    val pageBackground: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val cardSurface: Color,
    val composerSurface: Color,
    val composerStroke: Color,
    val assistantBubble: Color,
    val assistantBubbleStroke: Color,
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
    val pageBackground = ContextCompat.getColor(
        context,
        if (AppConfig.isNightTheme) R.color.md_grey_900 else R.color.white
    )
    val background = if (context.backgroundColor == android.graphics.Color.TRANSPARENT) {
        pageBackground
    } else {
        context.backgroundColor
    }
    val accent = context.accentColor
    val baseIsLight = ColorUtils.isColorLight(pageBackground)
    val cardSurface = if (baseIsLight) {
        ColorUtils.blendColors(pageBackground, ContextCompat.getColor(context, R.color.background_card), 0.72f)
    } else {
        ColorUtils.blendColors(pageBackground, ContextCompat.getColor(context, R.color.white), 0.12f)
    }
    val composerSurface = ColorUtils.adjustAlpha(
        ColorUtils.blendColors(pageBackground, accent, if (baseIsLight) 0.08f else 0.18f),
        if (baseIsLight) 0.96f else 0.88f
    )
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
            pageBackground = Color(pageBackground),
            primaryText = Color(context.primaryTextColor),
            secondaryText = Color(context.secondaryTextColor),
            cardSurface = Color(UiCorner.surfaceColor(cardSurface)),
            composerSurface = Color(UiCorner.surfaceColor(composerSurface)),
            composerStroke = Color(ColorUtils.adjustAlpha(accent, if (baseIsLight) 0.18f else 0.24f)),
            assistantBubble = Color(0xfff8f8f8),
            assistantBubbleStroke = Color(0xffe2e2e2),
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
