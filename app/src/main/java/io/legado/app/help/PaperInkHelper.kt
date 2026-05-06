package io.legado.app.help

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig

object PaperInkHelper {

    private var paperShader: BitmapShader? = null

    val strength: Int
        get() = ReadBookConfig.paperInkStrength

    fun drawBackground(canvas: Canvas, width: Int, height: Int, paperPaint: Paint, tintPaint: Paint) {
        val strength = strength
        if (strength <= 0 || width <= 0 || height <= 0) return
        val ratio = strength / 100f
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        val alpha = if (AppConfig.isNightTheme) {
            (4 + 10 * ratio).toInt()
        } else {
            (6 + 18 * ratio).toInt()
        }
        tintPaint.color = Color.argb(alpha, 245, 245, 238)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
        paperPaint.shader = shader()
        paperPaint.alpha = ((if (AppConfig.isNightTheme) 12 else 18) + 46 * ratio).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)
        paperPaint.shader = null
        canvas.restore()
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        x: Float,
        y: Float,
        paint: Paint,
        enableBlend: Boolean = true
    ) {
        val strength = strength
        if (strength <= 0 || !enableBlend) {
            canvas.drawText(text, start, end, x, y, paint)
            return
        }
        val seed = seed(text, start, end, x, y)
        drawTextBlock(canvas, paint, seed) {
            canvas.drawText(text, start, end, x, y, paint)
        }
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        enableBlend: Boolean = true
    ) {
        drawText(canvas, text, 0, text.length, x, y, paint, enableBlend)
    }

    fun drawTextBlock(
        canvas: Canvas,
        paint: Paint,
        seed: Int,
        draw: () -> Unit
    ) {
        val strength = strength
        if (strength <= 0) {
            draw()
            return
        }
        val ratio = strength / 100f
        val oldColor = paint.color
        val oldAlpha = paint.alpha
        val oldShader = paint.shader
        val oldMaskFilter = paint.maskFilter

        paint.color = blendInkColor(oldColor, ratio)
        paint.alpha = (oldAlpha * (1f - if (AppConfig.isNightTheme) 0.14f * ratio else 0.22f * ratio))
            .toInt()
            .coerceIn(0, oldAlpha)
        val shadowDx = randomSigned(seed, 13) * (0.12f + 0.45f * ratio)
        val shadowDy = randomSigned(seed, 29) * (0.12f + 0.45f * ratio)
        paint.setShadowLayer(0.35f + 0.95f * ratio, shadowDx, shadowDy, shadowColor(oldColor, ratio))
        draw()

        val shader = shader()
        shader.setLocalMatrix(Matrix().apply {
            setTranslate(randomOffset(seed, 47), randomOffset(seed, 89))
        })
        paint.clearShadowLayer()
        paint.shader = shader
        paint.alpha = (oldAlpha * (0.36f + 0.56f * ratio)).toInt().coerceIn(0, oldAlpha)
        draw()

        val jitter = 0.18f + 0.55f * ratio
        canvas.save()
        canvas.translate(randomSigned(seed, 131) * jitter, randomSigned(seed, 197) * jitter)
        shader.setLocalMatrix(Matrix().apply {
            setTranslate(randomOffset(seed, 211), randomOffset(seed, 263))
        })
        paint.alpha = (oldAlpha * (0.10f + 0.26f * ratio)).toInt().coerceIn(0, oldAlpha)
        draw()
        canvas.restore()

        paint.shader = oldShader
        paint.maskFilter = oldMaskFilter
        paint.alpha = oldAlpha
        paint.color = oldColor
        paint.clearShadowLayer()
    }

    private fun blendInkColor(color: Int, strength: Float): Int {
        val bg = ReadBookConfig.bgMeanColor
        val ratio = (if (AppConfig.isNightTheme) 0.22f else 0.34f) * strength
        val inv = 1f - ratio
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * inv + Color.red(bg) * ratio).toInt().coerceIn(0, 255),
            (Color.green(color) * inv + Color.green(bg) * ratio).toInt().coerceIn(0, 255),
            (Color.blue(color) * inv + Color.blue(bg) * ratio).toInt().coerceIn(0, 255)
        )
    }

    private fun shadowColor(color: Int, strength: Float): Int {
        val alpha = ((if (AppConfig.isNightTheme) 22 else 28) + 44 * strength).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun shader(): BitmapShader {
        return paperShader ?: createShader().also { paperShader = it }
    }

    private fun createShader(): BitmapShader {
        val size = 79
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val seed = x * 1103515245 + y * 12345 + x * y * 31
                val fiber = if ((x * 5 + y * 3 + (seed ushr 28)) % 13 == 0) 42 else 0
                val alpha = 20 + (seed ushr 24 and 0x2F) + fiber
                val color = if ((seed and 3) == 0) {
                    Color.argb(alpha, 255, 255, 255)
                } else {
                    Color.argb(alpha, 0, 0, 0)
                }
                bitmap.setPixel(x, y, color)
            }
        }
        return BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun seed(text: String, start: Int, end: Int, x: Float, y: Float): Int {
        var result = 17
        result = 31 * result + text.hashCode()
        result = 31 * result + start
        result = 31 * result + end
        result = 31 * result + x.toInt()
        result = 31 * result + y.toInt()
        return result
    }

    private fun randomOffset(seed: Int, salt: Int): Float {
        return (mix(seed, salt, 1103515245L, 12345L) % 79L).toFloat()
    }

    private fun randomSigned(seed: Int, salt: Int): Float {
        val value = (mix(seed, salt, 1664525L, 1013904223L) % 2001L) / 1000f - 1f
        return value.coerceIn(-1f, 1f)
    }

    private fun mix(seed: Int, salt: Int, a: Long, b: Long): Long {
        return (seed.toLong() * a + salt.toLong() * b) and Long.MAX_VALUE
    }

}
