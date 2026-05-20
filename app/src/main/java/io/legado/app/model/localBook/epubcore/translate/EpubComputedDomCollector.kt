package io.legado.app.model.localBook.epubcore.translate

import io.legado.app.model.localBook.epubcore.model.InlineNode
import io.legado.app.model.localBook.epubcore.model.ReaderBlock
import io.legado.app.model.localBook.epubcore.model.ReaderModel
import io.legado.app.model.localBook.epubcore.model.SourceRange
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle

data class EpubComputedDomDocument(
    val chapterIndex: Int,
    val chapterHref: String,
    val title: String?,
    val nodes: List<EpubComputedDomNode>
)

data class EpubComputedDomNode(
    val nodePath: String,
    val nodeOrder: Int,
    val tagName: String,
    val text: String,
    val source: EpubSourceRange,
    val computedStyle: EpubComputedStyle,
    val children: List<EpubComputedDomNode> = emptyList(),
    val attributes: Map<String, String> = emptyMap()
)

class EpubComputedDomCollector {

    fun collect(model: ReaderModel): EpubComputedDomDocument {
        val nodes = model.blocks.mapIndexedNotNull { index, block ->
            collectBlock(
                chapterIndex = model.chapterIndex,
                block = block,
                nodeOrder = index
            )
        }
        return EpubComputedDomDocument(
            chapterIndex = model.chapterIndex,
            chapterHref = model.chapterHref,
            title = model.title,
            nodes = nodes
        )
    }

    private fun collectBlock(
        chapterIndex: Int,
        block: ReaderBlock,
        nodeOrder: Int
    ): EpubComputedDomNode? {
        return when (block) {
            is ReaderBlock.Paragraph -> {
                val children = block.inline.mapIndexed { inlineIndex, inline ->
                    collectInline(
                        chapterIndex = chapterIndex,
                        inline = inline,
                        nodeOrder = nodeOrder * NodeOrderStride + inlineIndex
                    )
                }
                EpubComputedDomNode(
                    nodePath = block.source.start.nodePath,
                    nodeOrder = nodeOrder,
                    tagName = "p",
                    text = children.joinToString(separator = "") { it.text },
                    source = block.source.toEpubSourceRange(chapterIndex),
                    computedStyle = block.computedStyle,
                    children = children
                )
            }
            is ReaderBlock.Heading -> {
                val children = block.inline.mapIndexed { inlineIndex, inline ->
                    collectInline(
                        chapterIndex = chapterIndex,
                        inline = inline,
                        nodeOrder = nodeOrder * NodeOrderStride + inlineIndex
                    )
                }
                EpubComputedDomNode(
                    nodePath = block.source.start.nodePath,
                    nodeOrder = nodeOrder,
                    tagName = "h${block.level.coerceIn(1, 6)}",
                    text = children.joinToString(separator = "") { it.text },
                    source = block.source.toEpubSourceRange(chapterIndex),
                    computedStyle = block.computedStyle,
                    children = children
                )
            }
            is ReaderBlock.Image -> EpubComputedDomNode(
                nodePath = block.source.start.nodePath,
                nodeOrder = nodeOrder,
                tagName = "img",
                text = block.alt.orEmpty(),
                source = block.source.toEpubSourceRange(chapterIndex),
                computedStyle = block.computedStyle,
                attributes = buildMap {
                    put("src", block.href)
                    block.alt?.let { put("alt", it) }
                    block.width?.let { put("width", it.toString()) }
                    block.height?.let { put("height", it.toString()) }
                }
            )
        }
    }

    private fun collectInline(
        chapterIndex: Int,
        inline: InlineNode,
        nodeOrder: Int
    ): EpubComputedDomNode {
        return when (inline) {
            is InlineNode.Text -> EpubComputedDomNode(
                nodePath = inline.source.start.nodePath,
                nodeOrder = nodeOrder,
                tagName = "#text",
                text = inline.value,
                source = inline.source.toEpubSourceRange(chapterIndex),
                computedStyle = inline.computedStyle
            )
            is InlineNode.FootnoteRef -> EpubComputedDomNode(
                nodePath = inline.source.start.nodePath,
                nodeOrder = nodeOrder,
                tagName = "a",
                text = inline.label,
                source = inline.source.toEpubSourceRange(chapterIndex),
                computedStyle = inline.computedStyle,
                attributes = mapOf("epub:type" to "noteref", "href" to inline.noteId)
            )
        }
    }

    private fun SourceRange.toEpubSourceRange(chapterIndex: Int): EpubSourceRange {
        return EpubSourceRange(
            start = start.toEpubSourceAnchor(chapterIndex),
            end = end.toEpubSourceAnchor(chapterIndex)
        )
    }

    private fun io.legado.app.model.localBook.epubcore.model.SourceAnchor.toEpubSourceAnchor(
        chapterIndex: Int
    ): EpubSourceAnchor {
        return EpubSourceAnchor(
            chapterIndex = chapterIndex,
            chapterHref = chapterHref,
            nodePath = nodePath,
            nodeOrder = blockIndex,
            textOffset = textOffset
        )
    }

    private companion object {
        private const val NodeOrderStride = 1_000
    }
}
