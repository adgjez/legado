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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
            .drawBehind {
                val shadowHeight = 10.dp.toPx()
                drawRoundRect(
                    color = Color.Black.copy(alpha = if (spec.night) 0.18f else 0.11f),
                    topLeft = Offset(0f, size.height - shadowHeight),
                    size = Size(size.width, shadowHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        x = radius.toPx(),
                        y = radius.toPx()
                    )
                )
            }
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
        "frosted" -> ColorUtils.blendColors(spec.baseColor, neutral, if (baseIsLight) 0.24f else 0.14f)
        else -> ColorUtils.blendColors(spec.baseColor, neutral, if (baseIsLight) 0.30f else 0.16f)
    }
    val selectedBoost = if (spec.selected) 0.04f else 0f
    val accentBoost = if (spec.selected) 0.14f else 0f
    val tintedSurface = ColorUtils.blendColors(surface, spec.accentColor, accentBoost)
    val alpha = when (spec.mode) {
        "standard", "eink" -> listOf(1f, 1f, 1f)
        "solid" -> {
            val solid = (0.24f + level * 0.40f + selectedBoost).coerceIn(0.18f, 0.72f)
            listOf(solid, solid, solid)
        }
        "frosted" -> listOf(
            (0.22f + level * 0.24f + selectedBoost).coerceAtMost(0.56f),
            (0.17f + level * 0.21f + selectedBoost).coerceAtMost(0.48f),
            (0.13f + level * 0.18f + selectedBoost).coerceAtMost(0.42f)
        )
        else -> listOf(
            (0.18f + level * 0.22f + selectedBoost).coerceAtMost(0.50f),
            (0.14f + level * 0.19f + selectedBoost).coerceAtMost(0.42f),
            (0.10f + level * 0.16f + selectedBoost).coerceAtMost(0.36f)
        )
    }
    return alpha.map { Color(ColorUtils.adjustAlpha(tintedSurface, it)) }
}
