package io.legado.app.model.localBook.epubcore.chapter

import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.model.BlockStyle
import io.legado.app.model.localBook.epubcore.model.FootnoteContent
import io.legado.app.model.localBook.epubcore.model.InlineNode
import io.legado.app.model.localBook.epubcore.model.InlineStyle
import io.legado.app.model.localBook.epubcore.model.ReaderBlock
import io.legado.app.model.localBook.epubcore.model.ReaderModel
import io.legado.app.model.localBook.epubcore.model.SourceRange
import io.legado.app.model.localBook.epubcore.model.TextAlign
import io.legado.app.model.localBook.epubcore.layout.EpubLayoutTreeBuilder
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle
import io.legado.app.model.localBook.epubcore.style.EpubDisplay
import io.legado.app.model.localBook.epubcore.style.EpubStyleComputer
import io.legado.app.model.localBook.epubcore.style.EpubTextAlign
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.util.IdentityHashMap

class HtmlToReaderModel(
    private val styleComputer: EpubStyleComputer
) {
    private val layoutTreeBuilder = EpubLayoutTreeBuilder()

    fun parse(chapterIndex: Int, chapterHref: String, title: String?, html: String): ReaderModel {
        val doc = Jsoup.parse(html, "", Parser.xmlParser())
        val body = doc.selectFirst("body") ?: doc
        val rules = styleComputer.collectRules(body, chapterHref)
        val matchedRules = styleComputer.matchRules(body, rules)
        val bodyStyle = styleComputer.compute(body, EpubComputedStyle(), matchedRules[body].orEmpty(), chapterHref)
        val footnotes = body.select("aside, div, section")
            .filter { it.isFootnoteContainer() }
            .mapNotNull { element ->
                val id = element.id().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to FootnoteContent(id, blockChildren(chapterHref, element, false, bodyStyle, matchedRules))
            }
            .toMap()
        return ReaderModel(
            chapterIndex = chapterIndex,
            chapterHref = chapterHref,
            title = title,
            blocks = blockChildren(chapterHref, body, true, bodyStyle, matchedRules),
            layoutRoot = styledTree(chapterHref, body, bodyStyle, matchedRules, "body", blockIndexStart = 0)
                ?.let { layoutTreeBuilder.build(it) },
            footnotes = footnotes
        )
    }

    private fun styledTree(
        chapterHref: String,
        root: Element,
        parentStyle: EpubComputedStyle,
        matchedRules: IdentityHashMap<Element, MutableList<io.legado.app.model.localBook.epubcore.style.EpubCss.Rule>>,
        nodePath: String,
        blockIndexStart: Int
    ): StyledNode? {
        val style = styleComputer.compute(root, parentStyle, matchedRules[root].orEmpty(), chapterHref)
        if (style.display == EpubDisplay.None || root.isFootnoteContainer()) return null
        val children = ArrayList<StyledNode>()
        var childIndex = 0
        var textIndex = 0
        for (node in root.childNodes()) {
            when (node) {
                is TextNode -> {
                    val text = node.text().normalizeText()
                    if (text.isNotBlank()) {
                        val path = "$nodePath/text/${textIndex++}"
                        children += StyledNode(
                            tagName = "#text",
                            nodePath = path,
                            style = style,
                            source = SourceRange(chapterHref, blockIndexStart + childIndex, 0, text.length, path),
                            text = text
                        )
                    }
                }
                is Element -> {
                    val childPath = "$nodePath/${node.normalName()}/${childIndex++}"
                    if (node.normalName() == "img") {
                        imageStyledNode(chapterHref, childPath, childIndex, node, style, matchedRules)?.let(children::add)
                    } else {
                        styledTree(chapterHref, node, style, matchedRules, childPath, blockIndexStart + childIndex)
                            ?.let(children::add)
                    }
                }
            }
        }
        if (root.normalName() == "img") {
            return imageStyledNode(chapterHref, nodePath, blockIndexStart, root, style, matchedRules)
        }
        if (children.isEmpty() && root.text().isBlank()) return null
        return StyledNode(
            tagName = root.normalName(),
            nodePath = nodePath,
            style = style,
            source = SourceRange(chapterHref, blockIndexStart, 0, root.text().length, nodePath),
            children = children
        )
    }

    private fun imageStyledNode(
        chapterHref: String,
        nodePath: String,
        blockIndex: Int,
        img: Element,
        parentStyle: EpubComputedStyle,
        matchedRules: IdentityHashMap<Element, MutableList<io.legado.app.model.localBook.epubcore.style.EpubCss.Rule>>
    ): StyledNode? {
        val style = styleComputer.compute(img, parentStyle, matchedRules[img].orEmpty(), chapterHref)
        if (style.display == EpubDisplay.None) return null
        val src = img.attr("src").ifBlank { img.attr("href") }.ifBlank { img.attr("xlink:href") }
        if (src.isBlank()) return null
        return StyledNode(
            tagName = img.normalName(),
            nodePath = nodePath,
            style = style,
            source = SourceRange(chapterHref, blockIndex, 0, 0, nodePath),
            imageHref = EpubPath.resolve(chapterHref, src),
            imageAlt = img.attr("alt").takeIf { it.isNotBlank() },
            imageWidth = img.attr("width").toIntOrNull(),
            imageHeight = img.attr("height").toIntOrNull()
        )
    }

    private fun blockChildren(
        chapterHref: String,
        root: Element,
        skipFootnotes: Boolean,
        parentStyle: EpubComputedStyle,
        matchedRules: IdentityHashMap<Element, MutableList<io.legado.app.model.localBook.epubcore.style.EpubCss.Rule>>
    ): List<ReaderBlock> {
        val result = ArrayList<ReaderBlock>()
        for (node in root.childNodes()) {
            if (node is Element) {
                if (skipFootnotes && node.isFootnoteContainer()) continue
                val style = styleComputer.compute(node, parentStyle, matchedRules[node].orEmpty(), chapterHref)
                parseBlock(chapterHref, result.size, node, style, matchedRules)?.let { result.add(it) }
                if (!node.isKnownBlock()) {
                    result.addAll(blockChildren(chapterHref, node, skipFootnotes, style, matchedRules))
                }
            }
        }
        return result
    }

    private fun parseBlock(
        chapterHref: String,
        index: Int,
        element: Element,
        style: EpubComputedStyle,
        matchedRules: IdentityHashMap<Element, MutableList<io.legado.app.model.localBook.epubcore.style.EpubCss.Rule>>
    ): ReaderBlock? {
        if (style.display == EpubDisplay.None) return null
        val tag = element.normalName()
        val textLength = element.text().length
        val source = SourceRange(chapterHref, index, 0, textLength)
        return when {
            tag.matches(Regex("h[1-6]")) -> ReaderBlock.Heading(
                level = tag.drop(1).toIntOrNull() ?: 2,
                inline = inlineChildren(chapterHref, index, element, InlineStyle(), style, matchedRules),
                source = source,
                computedStyle = style
            )
            tag in setOf("p", "div", "li", "blockquote") -> {
                val inline = inlineChildren(chapterHref, index, element, InlineStyle(), style, matchedRules)
                if (inline.isEmpty()) null else ReaderBlock.Paragraph(
                    inline = inline,
                    source = source,
                    blockStyle = style.toBlockStyle(),
                    computedStyle = style
                )
            }
            tag == "img" -> imageBlock(chapterHref, index, element, style)
            tag == "figure" -> element.selectFirst("img")?.let { imageBlock(chapterHref, index, it, style) }
            else -> null
        }
    }

    private fun inlineChildren(
        chapterHref: String,
        blockIndex: Int,
        root: Node,
        inherited: InlineStyle,
        parentStyle: EpubComputedStyle,
        matchedRules: IdentityHashMap<Element, MutableList<io.legado.app.model.localBook.epubcore.style.EpubCss.Rule>>
    ): List<InlineNode> {
        val result = ArrayList<InlineNode>()
        for (node in root.childNodes()) {
            when (node) {
                is TextNode -> {
                    val text = node.text().normalizeText()
                    if (text.isNotBlank()) {
                        result.add(
                            InlineNode.Text(
                                value = text,
                                style = inherited.merge(parentStyle),
                                source = SourceRange(chapterHref, blockIndex, 0, text.length),
                                computedStyle = parentStyle
                            )
                        )
                    }
                }
                is Element -> {
                    val tag = node.normalName()
                    val style = styleComputer.compute(node, parentStyle, matchedRules[node].orEmpty(), chapterHref)
                    if (style.display == EpubDisplay.None) continue
                    if (tag == "br") {
                        result.add(InlineNode.Text("\n", inherited.merge(style), SourceRange(chapterHref, blockIndex, 0, 1), style))
                    } else if (tag == "a" && node.isFootnoteRef()) {
                        val href = node.attr("href")
                        val id = href.substringAfter('#', "")
                        result.add(
                            InlineNode.FootnoteRef(
                                noteId = id,
                                label = node.text().ifBlank { id },
                                source = SourceRange(chapterHref, blockIndex, 0, node.text().length),
                                computedStyle = style
                            )
                        )
                    } else {
                        result.addAll(inlineChildren(chapterHref, blockIndex, node, inherited.merge(tag, node).merge(style), style, matchedRules))
                    }
                }
            }
        }
        return result
    }

    private fun imageBlock(chapterHref: String, index: Int, img: Element, style: EpubComputedStyle): ReaderBlock.Image? {
        val src = img.attr("src").ifBlank { img.attr("href") }.ifBlank { img.attr("xlink:href") }
        if (src.isBlank()) return null
        return ReaderBlock.Image(
            href = EpubPath.resolve(chapterHref, src),
            alt = img.attr("alt").takeIf { it.isNotBlank() },
            width = img.attr("width").toIntOrNull(),
            height = img.attr("height").toIntOrNull(),
            source = SourceRange(chapterHref, index, 0, 0),
            computedStyle = style
        )
    }

    private fun Element.isKnownBlock(): Boolean {
        val tag = normalName()
        return tag.matches(Regex("h[1-6]")) || tag in setOf("p", "div", "li", "blockquote", "figure", "img")
    }

    private fun Element.isFootnoteContainer(): Boolean {
        val type = attr("epub:type").ifBlank { attr("type") }
        val classes = classNames()
        return type.contains("footnote") || classes.contains("epub-footnote")
    }

    private fun Element.isFootnoteRef(): Boolean {
        val type = attr("epub:type").ifBlank { attr("type") }
        val role = attr("role")
        return type.contains("noteref") || role == "doc-noteref" || attr("href").contains("#")
    }

    private fun InlineStyle.merge(tag: String, element: Element): InlineStyle {
        return copy(
            bold = bold || tag in setOf("b", "strong"),
            italic = italic || tag in setOf("i", "em", "cite"),
            underline = underline || tag == "u",
            linkHref = if (tag == "a") element.attr("href").takeIf { it.isNotBlank() } else linkHref
        )
    }

    private fun InlineStyle.merge(style: EpubComputedStyle): InlineStyle {
        return copy(
            bold = bold || (style.fontWeight ?: 400) >= 600,
            italic = italic || style.italic,
            underline = underline || style.underline
        )
    }

    private fun EpubComputedStyle.toBlockStyle(): BlockStyle {
        return BlockStyle(
            textAlign = when (textAlign) {
                EpubTextAlign.Center -> TextAlign.Center
                EpubTextAlign.End -> TextAlign.End
                EpubTextAlign.Justify -> TextAlign.Justify
                EpubTextAlign.Start -> TextAlign.Start
            },
            textIndentEm = if ((fontSizePx ?: 0f) > 0f) textIndentPx / (fontSizePx ?: 1f) else 0f
        )
    }

    private fun String.normalizeText(): String {
        return replace(Regex("[\\t\\r\\n ]+"), " ")
    }
}
