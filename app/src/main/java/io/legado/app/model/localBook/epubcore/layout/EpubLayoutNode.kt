package io.legado.app.model.localBook.epubcore.layout

import io.legado.app.model.localBook.epubcore.model.InlineNode
import io.legado.app.model.localBook.epubcore.model.SourceRange
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle

sealed interface EpubLayoutNode {
    val style: EpubComputedStyle
    val source: SourceRange?
}

data class EpubBlockNode(
    override val style: EpubComputedStyle,
    override val source: SourceRange?,
    val tagName: String = "",
    val children: List<EpubLayoutNode>
) : EpubLayoutNode

data class EpubInlineNode(
    override val style: EpubComputedStyle,
    override val source: SourceRange?,
    val inline: List<InlineNode>
) : EpubLayoutNode

data class EpubImageNode(
    val href: String,
    val alt: String?,
    val width: Int?,
    val height: Int?,
    override val style: EpubComputedStyle,
    override val source: SourceRange?
) : EpubLayoutNode

data class EpubTableNode(
    override val style: EpubComputedStyle,
    override val source: SourceRange?,
    val children: List<EpubLayoutNode>
) : EpubLayoutNode

data class EpubFlexNode(
    override val style: EpubComputedStyle,
    override val source: SourceRange?,
    val children: List<EpubLayoutNode>
) : EpubLayoutNode
