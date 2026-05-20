package io.legado.app.model.localBook.epubcore.layout

import io.legado.app.model.localBook.epubcore.chapter.StyledNode
import io.legado.app.model.localBook.epubcore.model.InlineNode
import io.legado.app.model.localBook.epubcore.model.InlineStyle
import io.legado.app.model.localBook.epubcore.model.SourceRange
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle
import io.legado.app.model.localBook.epubcore.style.EpubDisplay
import io.legado.app.model.localBook.epubcore.style.EpubPosition

class EpubLayoutTreeBuilder {

    fun build(root: StyledNode): EpubLayoutNode? {
        return buildNode(root.normalizeUnsupported())
    }

    private fun buildNode(node: StyledNode): EpubLayoutNode? {
        val style = node.style
        if (style.display == EpubDisplay.None) return null
        node.imageHref?.let { href ->
            return EpubImageNode(
                href = href,
                alt = node.imageAlt,
                width = node.imageWidth,
                height = node.imageHeight,
                style = style,
                source = node.source
            )
        }
        node.text?.takeIf { it.isNotBlank() }?.let { text ->
            return EpubInlineNode(
                style = style,
                source = node.source,
                inline = listOf(text.toInlineNode(node.source, style))
            )
        }
        val children = node.children.mapNotNull { buildNode(it.normalizeUnsupported()) }
        if (children.isEmpty()) return null
        return when (style.display) {
            EpubDisplay.Table,
            EpubDisplay.TableRow,
            EpubDisplay.TableCell -> EpubTableNode(style, node.source, children)
            EpubDisplay.Flex -> EpubFlexNode(style, node.source, children)
            EpubDisplay.Inline,
            EpubDisplay.InlineBlock,
            EpubDisplay.ListItem,
            EpubDisplay.Block -> EpubBlockNode(style, node.source, node.tagName, children)
            EpubDisplay.None -> null
        }
    }

    private fun StyledNode.normalizeUnsupported(): StyledNode {
        var next = this
        if (next.style.position == EpubPosition.Fixed) {
            next = next.copy(style = next.style.copy(display = EpubDisplay.None))
        } else if (next.style.position == EpubPosition.Absolute) {
            next = next.copy(style = next.style.copy(position = EpubPosition.Static, display = EpubDisplay.Block))
        }
        return next
    }

    private fun String.toInlineNode(source: SourceRange?, style: EpubComputedStyle): InlineNode.Text {
        return InlineNode.Text(
            value = this,
            style = InlineStyle(
                bold = (style.fontWeight ?: 400) >= 600,
                italic = style.italic,
                underline = style.underline
            ),
            source = source ?: SourceRange("", 0, 0, length),
            computedStyle = style
        )
    }
}
