package io.legado.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.legado.app.utils.ColorUtils

@Immutable
data class MainBottomBarGlassSpec(
    val mode: String,
    val baseColor: Int,
    val accentColor: Int,
    val borderColor: Int?,
    val cornerRadiusPx: Float,
    val oval: Boolean,
    val selected: Boolean,
    val level: Float,
    val night: Boolean
)

fun ComposeView.setMainBottomBarGlassContent(spec: MainBottomBarGlassSpec) {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    setContent {
        MainBottomBarGlassSurface(spec = spec)
    }
}

@Composable
private fun MainBottomBarGlassSurface(spec: MainBottomBarGlassSpec) {
    val radius = with(LocalDensity.current) { spec.cornerRadiusPx.toDp() }
    val shape = if (spec.oval) CircleShape else RoundedCornerShape(radius)
    val colors = bottomBarGlassColors(spec)
    val borderColor = spec.borderColor?.let { Color(it) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors), shape)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, shape)
                } else {
                    Modifier
                }
            )
    )
}

private fun bottomBarGlassColors(spec: MainBottomBarGlassSpec): List<Color> {
    val level = spec.level.coerceIn(0f, 1f)
    val baseIsLight = ColorUtils.isColorLight(spec.baseColor)
    val neutral = if (baseIsLight) 0xffffffff.toInt() else 0xff000000.toInt()
    val surface = when (spec.mode) {
        "standard", "eink" -> spec.baseColor
        "solid" -> ColorUtils.blendColors(spec.baseColor, neutral, if (baseIsLight) 0.08f else 0.12f)
        "frosted" -> ColorUtils.blendColors(spec.baseColor, neutral, if (baseIsLight) 0.46f else 0.24f)
        else -> ColorUtils.blendColors(spec.baseColor, neutral, if (baseIsLight) 0.58f else 0.28f)
    }
    val selectedBoost = if (spec.selected) 0.08f else 0f
    val accentBoost = if (spec.selected) 0.36f else 0.04f
    val tintedSurface = ColorUtils.blendColors(surface, spec.accentColor, accentBoost)
    val alpha = when (spec.mode) {
        "standard", "eink" -> listOf(1f, 1f, 1f)
        "solid" -> {
            val solid = (0.42f + level * 0.42f + selectedBoost).coerceIn(0.34f, 0.92f)
            listOf(solid, solid, solid)
        }
        "frosted" -> listOf(
            (0.48f + level * 0.30f + selectedBoost).coerceAtMost(0.90f),
            (0.40f + level * 0.26f + selectedBoost).coerceAtMost(0.82f),
            (0.34f + level * 0.22f + selectedBoost).coerceAtMost(0.76f)
        )
        else -> listOf(
            (0.42f + level * 0.34f + selectedBoost).coerceAtMost(0.86f),
            (0.34f + level * 0.30f + selectedBoost).coerceAtMost(0.78f),
            (0.28f + level * 0.26f + selectedBoost).coerceAtMost(0.70f)
        )
    }
    return alpha.map { Color(ColorUtils.adjustAlpha(tintedSurface, it)) }
}
