package io.legado.app.lib.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx

object UiCorner {

    fun scale(): Float {
        return AppConfig.uiCornerScale.coerceIn(0f, 3f)
    }

    fun panelRadius(context: Context): Float {
        return context.resources.getDimension(R.dimen.ui_panel_radius) * scale()
    }

    fun actionRadius(context: Context): Float {
        return context.resources.getDimension(R.dimen.ui_action_radius) * scale()
    }

    fun scaledDp(value: Float): Float {
        return value.dpToPx() * scale()
    }

    fun searchRadius(value: Float): Float {
        return if (AppConfig.uiCornerSearchFollow) {
            scaledDp(value)
        } else {
            value.dpToPx()
        }
    }

    fun replyRadius(value: Float): Float {
        return if (AppConfig.uiCornerReplyFollow) {
            scaledDp(value)
        } else {
            value.dpToPx()
        }
    }

    fun effectMode(): String {
        return AppConfig.uiCornerEffectMode
    }

    fun effectLevel(): Float {
        return AppConfig.uiCornerEffectLevel.coerceIn(0, 100) / 100f
    }

    fun effectValueText(context: Context): String {
        return when (effectMode()) {
            "solid" -> context.getString(R.string.ui_corner_effect_level_solid, AppConfig.uiCornerEffectLevel)
            else -> context.getString(R.string.ui_corner_effect_level_glass, AppConfig.uiCornerEffectLevel)
        }
    }

    fun surfaceColor(color: Int, pressed: Boolean = false): Int {
        val level = effectLevel()
        return when (effectMode()) {
            "glass" -> {
                val base = if (ColorUtils.calculateLuminance(color) > 0.5) {
                    ColorUtils.blendARGB(color, Color.WHITE, 0.35f + level * 0.20f)
                } else {
                    ColorUtils.blendARGB(color, Color.WHITE, 0.10f + level * 0.12f)
                }
                val alpha = (0.28f + level * 0.34f + if (pressed) 0.12f else 0f).coerceIn(0f, 0.82f)
                ColorUtils.setAlphaComponent(base, (alpha * 255).toInt())
            }
            "frosted" -> {
                val base = if (ColorUtils.calculateLuminance(color) > 0.5) {
                    ColorUtils.blendARGB(color, Color.WHITE, 0.16f + level * 0.14f)
                } else {
                    ColorUtils.blendARGB(color, Color.WHITE, 0.08f + level * 0.08f)
                }
                val alpha = (0.48f + level * 0.38f + if (pressed) 0.08f else 0f).coerceIn(0f, 0.96f)
                ColorUtils.setAlphaComponent(base, (alpha * 255).toInt())
            }
            else -> {
                val alpha = (level + if (pressed) 0.08f else 0f).coerceIn(0f, 1f)
                ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())
            }
        }
    }

    fun effectStrokeColor(color: Int): Int {
        val level = effectLevel()
        val base = if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
        val alpha = when (effectMode()) {
            "glass" -> 0.20f + level * 0.22f
            "frosted" -> 0.12f + level * 0.16f
            else -> 0.10f
        }
        return ColorUtils.setAlphaComponent(base, (alpha.coerceIn(0f, 0.5f) * 255).toInt())
    }

    private fun roundedColor(color: Int, radius: Float, pressed: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(surfaceColor(color, pressed))
        }
    }

    fun rounded(color: Int, radius: Float): GradientDrawable {
        return roundedColor(color, radius, false)
    }

    fun roundedStroke(color: Int, radius: Float, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        return rounded(color, radius).apply {
            setStroke(strokeWidth, if (effectMode() == "solid") strokeColor else effectStrokeColor(color))
        }
    }

    fun actionSelector(defaultColor: Int, pressedColor: Int, radius: Float): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedColor(pressedColor, radius, true))
            addState(intArrayOf(android.R.attr.state_selected), roundedColor(pressedColor, radius, true))
            addState(intArrayOf(), rounded(defaultColor, radius))
        }
    }

    fun actionStrokeSelector(
        defaultColor: Int,
        pressedColor: Int,
        radius: Float,
        strokeWidth: Int,
        strokeColor: Int
    ): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedColor(pressedColor, radius, true).apply {
                    setStroke(strokeWidth, if (effectMode() == "solid") strokeColor else effectStrokeColor(pressedColor))
                }
            )
            addState(
                intArrayOf(android.R.attr.state_selected),
                roundedColor(pressedColor, radius, true).apply {
                    setStroke(strokeWidth, if (effectMode() == "solid") strokeColor else effectStrokeColor(pressedColor))
                }
            )
            addState(intArrayOf(), roundedStroke(defaultColor, radius, strokeWidth, strokeColor))
        }
    }
}
