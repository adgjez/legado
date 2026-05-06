package io.legado.app.ui.main.bookshelf

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.dpToPx

class BookshelfShelfDecoration(
    context: Context,
    private val spanCountProvider: () -> Int
) : RecyclerView.ItemDecoration() {

    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plankPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plankFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contactShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plankRect = RectF()
    private val contactShadowRect = RectF()
    private val topPath = Path()
    private val shelfShadowPath = Path()
    private val sideInset = 18.dpToPx().toFloat()
    private val bookToPlankGap = (-2).dpToPx().toFloat()
    private val topHeight = 12.dpToPx().toFloat()
    private val frontHeight = 16.dpToPx().toFloat()
    private val shadowHeight = 9.dpToPx().toFloat()
    private val rowTopSpacing = 14.dpToPx()
    private val bottomSpacing = 12.dpToPx()
    private val surfaceColor: Int
    private val toneColor: Int
    private val shadowColor = 0xFF000000.toInt()
    private val topStartColor: Int
    private val topEndColor: Int
    private val frontEndColor: Int
    private val frontBottomStartColor: Int
    private val frontBottomEndColor: Int

    init {
        surfaceColor = context.backgroundColor
        toneColor = if (ColorUtils.calculateLuminance(surfaceColor) > 0.5) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }
        topStartColor = ColorUtils.blendARGB(surfaceColor, toneColor, 0.05f)
        topEndColor = ColorUtils.blendARGB(surfaceColor, toneColor, 0.11f)
        frontEndColor = ColorUtils.blendARGB(surfaceColor, toneColor, 0.24f)
        frontBottomStartColor = ColorUtils.blendARGB(surfaceColor, toneColor, 0.20f)
        frontBottomEndColor = ColorUtils.blendARGB(surfaceColor, toneColor, 0.34f)
        highlightPaint.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 38)
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (!AppConfig.bookshelfShelfEffect) return
        val spanCount = spanCountProvider()
        if (spanCount < 2 || parent.childCount == 0) return

        val rows = linkedMapOf<Int, RowBounds>()
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val row = position / spanCount
            val cover = child.findViewById<View>(R.id.iv_cover)
            val coverTop = cover?.let { child.top + it.top + child.translationY }
                ?: (child.top + child.translationY)
            val coverBottom = cover?.let { child.top + it.bottom + child.translationY }
                ?: (child.bottom + child.translationY)
            rows[row] = rows[row]?.include(coverTop, coverBottom)
                ?: RowBounds(coverTop, coverBottom)
        }

        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        rows.values.forEach { bounds ->
            drawShelfCell(canvas, left, right, bounds)
        }
    }

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        if (!AppConfig.bookshelfShelfEffect || spanCountProvider() < 2) return
        val position = parent.getChildAdapterPosition(view)
        if (position >= spanCountProvider()) {
            outRect.top += rowTopSpacing
        }
        outRect.bottom += bottomSpacing
    }

    private fun drawShelfCell(canvas: Canvas, left: Float, right: Float, bounds: RowBounds) {
        val plankTop = bounds.bottom + bookToPlankGap
        val topBottom = plankTop + topHeight
        val frontBottom = topBottom + frontHeight
        val visualLeft = left
        val visualRight = right
        val topLeft = left + sideInset
        val topRight = right - sideInset

        contactShadowRect.set(topLeft, bounds.bottom - 1.dpToPx(), topRight, bounds.bottom + 6.dpToPx())
        contactShadowPaint.shader = LinearGradient(
            0f,
            contactShadowRect.top,
            0f,
            contactShadowRect.bottom,
            ColorUtils.setAlphaComponent(shadowColor, 72),
            ColorUtils.setAlphaComponent(shadowColor, 0),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(contactShadowRect, 5.dpToPx().toFloat(), 5.dpToPx().toFloat(), contactShadowPaint)
        contactShadowPaint.shader = null

        topPath.reset()
        topPath.moveTo(topLeft, plankTop)
        topPath.lineTo(topRight, plankTop)
        topPath.lineTo(visualRight, topBottom)
        topPath.lineTo(visualLeft, topBottom)
        topPath.close()
        topPaint.shader = LinearGradient(
            0f,
            plankTop,
            0f,
            topBottom,
            intArrayOf(topStartColor, topEndColor),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(topPath, topPaint)
        topPaint.shader = null

        plankRect.set(visualLeft, topBottom, visualRight, frontBottom)
        plankPaint.shader = LinearGradient(
            0f,
            topBottom,
            0f,
            frontBottom,
            topEndColor,
            frontEndColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(plankRect, plankPaint)
        plankPaint.shader = null

        plankRect.set(visualLeft, topBottom, visualRight, topBottom + 1.dpToPx())
        canvas.drawRect(plankRect, highlightPaint)
        plankRect.set(visualLeft, frontBottom - 7.dpToPx(), visualRight, frontBottom)
        plankFrontPaint.shader = LinearGradient(
            0f,
            frontBottom - 7.dpToPx(),
            0f,
            frontBottom,
            frontBottomStartColor,
            frontBottomEndColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(plankRect, plankFrontPaint)
        plankFrontPaint.shader = null

        shelfShadowPath.reset()
        shelfShadowPath.moveTo(visualLeft + 10.dpToPx(), frontBottom)
        shelfShadowPath.lineTo(visualRight - 4.dpToPx(), frontBottom)
        shelfShadowPath.lineTo(visualRight - 18.dpToPx(), frontBottom + shadowHeight)
        shelfShadowPath.lineTo(visualLeft + 2.dpToPx(), frontBottom + shadowHeight)
        shelfShadowPath.close()
        shadowPaint.shader = LinearGradient(
            0f,
            frontBottom,
            0f,
            frontBottom + shadowHeight,
            ColorUtils.setAlphaComponent(shadowColor, 42),
            ColorUtils.setAlphaComponent(shadowColor, 0),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(shelfShadowPath, shadowPaint)
        shadowPaint.shader = null
    }

    private data class RowBounds(
        val top: Float,
        val bottom: Float
    ) {
        fun include(otherTop: Float, otherBottom: Float): RowBounds {
            return RowBounds(minOf(top, otherTop), maxOf(bottom, otherBottom))
        }
    }
}
