package io.legado.app.model.localBook.epubcore.layout

import android.graphics.RectF
import android.text.StaticLayout
import io.legado.app.model.localBook.epubcore.model.SourceRange
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle

data class EpubViewport(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val paddingLeftPx: Int,
    val paddingTopPx: Int,
    val paddingRightPx: Int,
    val paddingBottomPx: Int
) {
    val contentWidthPx: Int
        get() = (pageWidthPx - paddingLeftPx - paddingRightPx).coerceAtLeast(1)

    val contentHeightPx: Int
        get() = (pageHeightPx - paddingTopPx - paddingBottomPx).coerceAtLeast(1)
}

data class EpubTextAnchor(
    val source: SourceRange,
    val startOffset: Int,
    val endOffset: Int
)

sealed interface EpubLayoutBox {
    val frame: RectF
    val source: SourceRange?
    val style: EpubComputedStyle
    val canSplit: Boolean
}

data class EpubTextLayoutBox(
    val text: CharSequence,
    val staticLayout: StaticLayout,
    val anchors: List<EpubTextAnchor>,
    override val frame: RectF,
    override val source: SourceRange?,
    override val style: EpubComputedStyle,
    override val canSplit: Boolean = true
) : EpubLayoutBox

data class EpubImageLayoutBox(
    val href: String,
    val alt: String?,
    override val frame: RectF,
    override val source: SourceRange?,
    override val style: EpubComputedStyle,
    override val canSplit: Boolean = false
) : EpubLayoutBox

data class EpubContainerLayoutBox(
    override val frame: RectF,
    override val source: SourceRange?,
    override val style: EpubComputedStyle,
    val children: List<EpubLayoutBox>,
    val isRoot: Boolean = false,
    override val canSplit: Boolean = true
) : EpubLayoutBox

data class EpubTableLayoutBox(
    override val frame: RectF,
    override val source: SourceRange?,
    override val style: EpubComputedStyle,
    val children: List<EpubLayoutBox>,
    override val canSplit: Boolean = true
) : EpubLayoutBox

data class EpubFlexLayoutBox(
    override val frame: RectF,
    override val source: SourceRange?,
    override val style: EpubComputedStyle,
    val children: List<EpubLayoutBox>,
    override val canSplit: Boolean = true
) : EpubLayoutBox
