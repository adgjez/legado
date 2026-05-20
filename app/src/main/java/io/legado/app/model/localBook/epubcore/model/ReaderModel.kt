package io.legado.app.model.localBook.epubcore.model

import io.legado.app.model.localBook.epubcore.layout.EpubLayoutNode
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle
import io.legado.app.model.localBook.epubcore.web.EpubDomMeasureResult

data class ReaderModel(
    val chapterIndex: Int,
    val chapterHref: String,
    val title: String?,
    val blocks: List<ReaderBlock>,
    val layoutRoot: EpubLayoutNode? = null,
    val footnotes: Map<String, FootnoteContent> = emptyMap(),
    val measuredDom: EpubDomMeasureResult? = null
)

sealed interface ReaderBlock {
    val source: SourceRange
    val computedStyle: EpubComputedStyle

    data class Paragraph(
        val inline: List<InlineNode>,
        override val source: SourceRange,
        val blockStyle: BlockStyle = BlockStyle(),
        override val computedStyle: EpubComputedStyle = EpubComputedStyle()
    ) : ReaderBlock

    data class Heading(
        val level: Int,
        val inline: List<InlineNode>,
        override val source: SourceRange,
        override val computedStyle: EpubComputedStyle = EpubComputedStyle()
    ) : ReaderBlock

    data class Image(
        val href: String,
        val alt: String?,
        val width: Int?,
        val height: Int?,
        override val source: SourceRange,
        override val computedStyle: EpubComputedStyle = EpubComputedStyle()
    ) : ReaderBlock
}

sealed interface InlineNode {
    val source: SourceRange
    val computedStyle: EpubComputedStyle

    data class Text(
        val value: String,
        val style: InlineStyle = InlineStyle(),
        override val source: SourceRange,
        override val computedStyle: EpubComputedStyle = EpubComputedStyle()
    ) : InlineNode

    data class FootnoteRef(
        val noteId: String,
        val label: String,
        override val source: SourceRange,
        override val computedStyle: EpubComputedStyle = EpubComputedStyle()
    ) : InlineNode
}

data class SourceAnchor(
    val chapterHref: String,
    val nodePath: String,
    val blockIndex: Int,
    val textOffset: Int
)

data class SourceRange(
    val start: SourceAnchor,
    val end: SourceAnchor
) {
    constructor(
        chapterHref: String,
        blockIndex: Int,
        startOffset: Int,
        endOffset: Int,
        nodePath: String = "body/$blockIndex"
    ) : this(
        start = SourceAnchor(chapterHref, nodePath, blockIndex, startOffset),
        end = SourceAnchor(chapterHref, nodePath, blockIndex, endOffset)
    )

    val chapterHref: String get() = start.chapterHref
    val blockIndex: Int get() = start.blockIndex
    val startOffset: Int get() = start.textOffset
    val endOffset: Int get() = end.textOffset
}

data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val linkHref: String? = null
)

data class BlockStyle(
    val textAlign: TextAlign = TextAlign.Start,
    val textIndentEm: Float = 0f
)

enum class TextAlign {
    Start,
    Center,
    End,
    Justify
}

data class FootnoteContent(
    val id: String,
    val blocks: List<ReaderBlock>
)
