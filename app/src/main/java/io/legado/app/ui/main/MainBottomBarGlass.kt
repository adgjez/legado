package io.legado.app.ui.main

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
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
    val shape = if (spec.oval) RoundedCornerShape(percent = 50) else RoundedCornerShape(radius)
    val colors = bottomBarGlassColors(spec)
    val borderColor = spec.borderColor?.let { Color(it) }
    val surfaceBrush = Brush.verticalGradient(colors)
    val backdrop = rememberCanvasBackdrop {
        drawBottomBarBackdropSeed(spec)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawBottomBarBackdropSeed(spec)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(bottomBarSurfaceModifier(spec, backdrop, shape, surfaceBrush))
                .then(
                    if (borderColor != null) {
                        Modifier.border(1.dp, borderColor, shape)
                    } else {
                        Modifier
                    }
                )
        )
    }
}

@Composable
private fun bottomBarSurfaceModifier(
    spec: MainBottomBarGlassSpec,
    backdrop: com.kyant.backdrop.Backdrop,
    shape: Shape,
    surfaceBrush: Brush
): Modifier {
    if (!spec.usesBackdropEffect) {
        return Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {},
            onDrawSurface = {
                drawRect(surfaceBrush)
            }
        )
    }
    val density = LocalDensity.current
    val level = spec.level.coerceIn(0f, 1f)
    val blurRadius = with(density) {
        (if (spec.mode == "frosted") 18f + level * 18f else 8f + level * 12f).dp.toPx()
    }
    val lensHeight = with(density) {
        (if (spec.mode == "frosted") 8f + level * 8f else 12f + level * 14f).dp.toPx()
    }
    val lensAmount = if (spec.mode == "frosted") {
        (0.08f + level * 0.12f).coerceAtMost(0.24f)
    } else {
        (0.18f + level * 0.24f).coerceAtMost(0.46f)
    }
    return Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vibrancy()
                blur(blurRadius)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lens(
                        refractionHeight = lensHeight,
                        refractionAmount = lensAmount,
                        depthEffect = spec.mode == "glass",
                        chromaticAberration = spec.mode == "glass" && !spec.night
                    )
                }
            }
        },
        onDrawSurface = {
            drawRect(surfaceBrush)
            val lightAlpha = if (spec.night) 0.10f else 0.22f
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = lightAlpha),
                        Color.Transparent
                    )
                )
            )
        }
    )
}

private val MainBottomBarGlassSpec.usesBackdropEffect: Boolean
    get() = mode == "glass" || mode == "frosted"

private fun DrawScope.drawBottomBarBackdropSeed(spec: MainBottomBarGlassSpec) {
    val base = Color(spec.baseColor)
    val accent = Color(spec.accentColor)
    val light = ColorUtils.isColorLight(spec.baseColor)
    val softColor = if (light) Color.White else Color(0xff121820)
    val shadeColor = if (light) Color(0xffdae8f5) else Color.Black
    drawRect(base)
    drawRect(
        Brush.linearGradient(
            colors = listOf(
                softColor.copy(alpha = if (light) 0.58f else 0.30f),
                base.copy(alpha = 0.78f),
                shadeColor.copy(alpha = if (light) 0.30f else 0.44f)
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
    )
    drawCircle(
        color = accent.copy(alpha = if (spec.selected) 0.20f else 0.12f),
        radius = size.maxDimension * if (spec.oval) 0.62f else 0.48f,
        center = Offset(size.width * 0.82f, size.height * 0.18f)
    )
    drawCircle(
        color = Color.White.copy(alpha = if (light) 0.22f else 0.08f),
        radius = size.maxDimension * 0.42f,
        center = Offset(size.width * 0.12f, size.height * 0.08f)
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
            (0.26f + level * 0.24f + selectedBoost).coerceAtMost(0.60f),
            (0.20f + level * 0.21f + selectedBoost).coerceAtMost(0.52f),
            (0.15f + level * 0.18f + selectedBoost).coerceAtMost(0.44f)
        )
        else -> listOf(
            (0.22f + level * 0.24f + selectedBoost).coerceAtMost(0.54f),
            (0.17f + level * 0.21f + selectedBoost).coerceAtMost(0.46f),
            (0.12f + level * 0.18f + selectedBoost).coerceAtMost(0.38f)
        )
    }
    return alpha.map { Color(ColorUtils.adjustAlpha(tintedSurface, it)) }
}
