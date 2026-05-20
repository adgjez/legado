package io.legado.app.model.localBook.epubcore.layout

import android.graphics.RectF
import io.legado.app.model.localBook.epubcore.model.SourceRange
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle

class EpubBlockLayouter(
    private val engine: EpubLayoutEngine
) {

    fun layout(node: EpubBlockNode, constraint: EpubConstraintBox): EpubContainerLayoutBox? {
        return layoutFlowContainer(
            style = node.style,
            source = node.source,
            children = node.children,
            constraint = constraint,
            isRoot = node.tagName.equals("body", ignoreCase = true),
            keepEmpty = node.tagName.equals("body", ignoreCase = true)
        )
    }

    fun layoutFlowContainer(
        style: EpubComputedStyle,
        source: SourceRange?,
        children: List<EpubLayoutNode>,
        constraint: EpubConstraintBox,
        isRoot: Boolean = false,
        keepEmpty: Boolean = false
    ): EpubContainerLayoutBox? {
        val borderBoxWidth = style.resolveBorderBoxWidth(constraint.widthPx)
        val childConstraint = EpubConstraintBox(
            widthPx = style.resolveContentWidth(constraint.widthPx),
            heightPx = constraint.heightPx
        )
        val childBoxes = engine.layoutChildren(children, childConstraint)
        if (childBoxes.isEmpty() && !keepEmpty) return null

        val borderWidth = style.border.widthPx.coerceAtLeast(0f)
        val contentLeft = borderWidth + style.padding.leftPx.coerceAtLeast(0f)
        val contentTop = borderWidth + style.padding.topPx.coerceAtLeast(0f)
        var cursorY = contentTop
        var previousBottomMargin = 0f
        var maxRight = contentLeft
        var hasLaidOutChild = false

        childBoxes.forEach { child ->
            val childMargin = child.style.margin
            val collapsedMargin = if (hasLaidOutChild) {
                collapseVerticalMargins(previousBottomMargin, childMargin.topPx)
            } else {
                childMargin.topPx.coerceAtLeast(0f)
            }
            val childX = contentLeft + childMargin.leftPx.coerceAtLeast(0f)
            val childY = cursorY + collapsedMargin
            child.frame.offsetTo(childX, childY)
            cursorY = childY + child.frame.height()
            previousBottomMargin = childMargin.bottomPx
            maxRight = maxOf(
                maxRight,
                child.frame.right + childMargin.rightPx.coerceAtLeast(0f)
            )
            hasLaidOutChild = true
        }

        val contentBottom = if (hasLaidOutChild) {
            cursorY + previousBottomMargin.coerceAtLeast(0f)
        } else {
            contentTop
        }
        val borderBoxContentBottom = contentBottom +
            style.padding.bottomPx.coerceAtLeast(0f) +
            borderWidth
        val measuredWidth = maxOf(
            borderBoxWidth,
            maxRight + style.padding.rightPx.coerceAtLeast(0f) + borderWidth
        ).coerceAtLeast(1f)
        val measuredHeight = style.resolveBorderBoxHeight(borderBoxContentBottom, constraint.heightPx)

        return EpubContainerLayoutBox(
            frame = RectF(0f, 0f, measuredWidth, measuredHeight),
            source = source,
            style = style,
            children = childBoxes,
            isRoot = isRoot
        )
    }
}
