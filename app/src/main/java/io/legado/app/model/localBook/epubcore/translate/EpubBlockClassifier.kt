package io.legado.app.model.localBook.epubcore.translate

import io.legado.app.model.localBook.epubcore.style.EpubDisplay
import io.legado.app.model.localBook.epubcore.style.EpubPosition

class EpubBlockClassifier {

    fun classify(document: EpubComputedDomDocument): EpubTranslationResult {
        val blocks = document.nodes.map { node -> classifyNode(node) }
            .sortedBy { it.nodeOrder }
        val fallbackReasons = blocks
            .mapNotNull { it.fallbackReason }
            .groupingBy { it }
            .eachCount()
        val translated = EpubNativeDocument(
            protocolVersion = ProtocolVersion,
            chapterIndex = document.chapterIndex,
            chapterHref = document.chapterHref,
            title = document.title,
            blocks = blocks,
            diagnostics = EpubTranslationDiagnostics(
                nativeBlockCount = blocks.count { it.fallbackReason == null },
                fallbackBlockCount = blocks.count { it.fallbackReason != null },
                fallbackReasons = fallbackReasons
            )
        )
        return EpubTranslationResult(document = translated)
    }

    private fun classifyNode(node: EpubComputedDomNode): EpubNativeBlock {
        unsupportedReason(node)?.let { reason ->
            return EpubNativeFallbackBlock(
                id = node.id(),
                source = node.source,
                style = node.nativeStyle(),
                nodeOrder = node.nodeOrder,
                fallbackReason = reason,
                html = "",
                text = node.allText()
            )
        }
        if (node.tagName.equals("img", ignoreCase = true)) {
            return EpubNativeImageBlock(
                id = node.id(),
                source = node.source,
                style = node.nativeStyle(),
                nodeOrder = node.nodeOrder,
                href = node.attributes["src"].orEmpty(),
                alt = node.attributes["alt"]
            )
        }
        return EpubNativeTextBlock(
            id = node.id(),
            source = node.source,
            style = node.nativeStyle(),
            nodeOrder = node.nodeOrder,
            tagName = node.tagName,
            inline = node.toInline(),
            text = node.allText(),
            blockKind = node.textBlockKind()
        )
    }

    private fun unsupportedReason(node: EpubComputedDomNode): EpubFallbackReason? {
        val style = node.computedStyle
        if (style.display == EpubDisplay.None) return EpubFallbackReason.UnsupportedDisplay
        if (style.display in UnsupportedDisplays) return displayReason(style.display)
        if (style.position != EpubPosition.Static && style.position != EpubPosition.Relative) {
            return EpubFallbackReason.UnsupportedPosition
        }
        val writingMode = node.attributes["writing-mode"].orEmpty()
        if (writingMode.isNotBlank() && !writingMode.equals("horizontal-tb", ignoreCase = true)) {
            return EpubFallbackReason.UnsupportedWritingMode
        }
        if (node.tagName.equals("ruby", ignoreCase = true) ||
            node.children.any { it.tagName.equals("ruby", ignoreCase = true) }
        ) {
            return EpubFallbackReason.UnsupportedRuby
        }
        if (node.attributes["float"]?.equals("none", ignoreCase = true) == false) {
            return EpubFallbackReason.UnsupportedFloat
        }
        if (node.attributes["transform"].orEmpty().isNotBlank()) {
            return EpubFallbackReason.UnsupportedTransform
        }
        return null
    }

    private fun displayReason(display: EpubDisplay): EpubFallbackReason {
        return when (display) {
            EpubDisplay.Table,
            EpubDisplay.TableRow,
            EpubDisplay.TableCell -> EpubFallbackReason.UnsupportedTable
            EpubDisplay.Flex -> EpubFallbackReason.UnsupportedDisplay
            else -> EpubFallbackReason.UnsupportedDisplay
        }
    }

    private fun EpubComputedDomNode.toInline(): List<EpubNativeInline> {
        if (children.isEmpty()) {
            return listOf(
                EpubNativeInline.Text(
                    value = text,
                    source = source,
                    style = nativeStyle()
                )
            )
        }
        return children.flatMap { child ->
            when {
                child.tagName.equals("img", ignoreCase = true) -> listOf(
                    EpubNativeInline.Image(
                        href = child.attributes["src"].orEmpty(),
                        alt = child.attributes["alt"],
                        source = child.source,
                        style = child.nativeStyle()
                    )
                )
                child.children.isNotEmpty() -> child.toInline()
                child.text.isNotEmpty() -> listOf(
                    EpubNativeInline.Text(
                        value = child.text,
                        source = child.source,
                        style = child.nativeStyle()
                    )
                )
                else -> emptyList()
            }
        }
    }

    private fun EpubComputedDomNode.nativeStyle(): EpubNativeStyle {
        return EpubNativeStyle(
            computed = computedStyle,
            display = computedStyle.display.name,
            position = computedStyle.position.name,
            writingMode = attributes["writing-mode"],
            direction = attributes["direction"]
        )
    }

    private fun EpubComputedDomNode.textBlockKind(): EpubNativeTextBlockKind {
        return when {
            tagName.matches(HeadingRegex) -> EpubNativeTextBlockKind.Heading
            tagName.equals("blockquote", ignoreCase = true) -> EpubNativeTextBlockKind.Quote
            tagName.equals("li", ignoreCase = true) -> EpubNativeTextBlockKind.ListItem
            tagName.equals("pre", ignoreCase = true) -> EpubNativeTextBlockKind.Preformatted
            tagName.equals("figcaption", ignoreCase = true) -> EpubNativeTextBlockKind.Caption
            else -> EpubNativeTextBlockKind.Paragraph
        }
    }

    private fun EpubComputedDomNode.allText(): String {
        if (children.isEmpty()) return text
        return children.joinToString(separator = "") { it.allText() }
    }

    private fun EpubComputedDomNode.id(): String {
        return "${source.chapterIndex}:${nodeOrder}:${nodePath}"
    }

    private companion object {
        private const val ProtocolVersion = 1
        private val HeadingRegex = Regex("h[1-6]", RegexOption.IGNORE_CASE)
        private val UnsupportedDisplays = setOf(
            EpubDisplay.Flex,
            EpubDisplay.Table,
            EpubDisplay.TableRow,
            EpubDisplay.TableCell
        )
    }
}
