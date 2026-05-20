package io.legado.app.model.localBook.epubcore.layout

import android.graphics.RectF

class EpubImageLayouter(
    private val context: EpubLayoutContext
) {

    fun layout(node: EpubImageNode, constraint: EpubConstraintBox): EpubImageLayoutBox {
        val maxWidth = constraint.widthPx
            .takeIf { it.isFinite() && it > 0f }
            ?: context.contentWidthPx
        val maxHeight = constraint.heightPx
            .takeIf { it.isFinite() && it > 0f }
            ?: context.contentHeightPx
        val meta = context.imageResolver?.probe(node.href)
        val measured = context.measuredDom?.node(node.source?.start?.nodePath)
        val intrinsicWidth = node.width?.takeIf { it > 0 }?.toFloat()
            ?: measured?.naturalWidthPx?.takeIf { it > 0f }
            ?: measured?.widthPx?.takeIf { it > 0f }
            ?: meta?.width?.takeIf { it > 0 }?.toFloat()
            ?: maxWidth
        val intrinsicHeight = node.height?.takeIf { it > 0 }?.toFloat()
            ?: measured?.naturalHeightPx?.takeIf { it > 0f }
            ?: measured?.heightPx?.takeIf { it > 0f }
            ?: meta?.height?.takeIf { it > 0 }?.toFloat()
            ?: (maxWidth * 0.56f).coerceAtLeast(context.config.textPaint.textSize * 2f)

        val cssWidth = node.style.width.resolve(maxWidth)
        val cssHeight = node.style.height.resolve(maxHeight)
        val widthFromCssOrIntrinsic = cssWidth ?: intrinsicWidth
        val targetWidth = widthFromCssOrIntrinsic
            .coerceInStyleBounds(node.style.minWidth, node.style.maxWidth, maxWidth)
            .coerceAtMost(maxWidth)
            .coerceAtLeast(1f)
        val scale = (targetWidth / intrinsicWidth)
            .takeIf { it.isFinite() && it > 0f }
            ?: 1f
        val targetHeight = (cssHeight ?: intrinsicHeight * scale)
            .coerceInStyleBounds(node.style.minHeight, node.style.maxHeight, maxHeight)
            .coerceAtMost(maxHeight)
            .coerceAtLeast(context.config.textPaint.textSize)

        return EpubImageLayoutBox(
            href = node.href,
            alt = node.alt,
            frame = RectF(0f, 0f, targetWidth, targetHeight),
            source = node.source,
            style = node.style
        )
    }
}
