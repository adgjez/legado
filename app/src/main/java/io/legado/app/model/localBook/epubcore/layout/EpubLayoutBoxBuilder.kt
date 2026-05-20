package io.legado.app.model.localBook.epubcore.layout

import io.legado.app.model.localBook.epubcore.image.EpubImageResolver
import io.legado.app.model.localBook.epubcore.model.ReaderBlock
import io.legado.app.model.localBook.epubcore.model.ReaderModel
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle

class EpubLayoutBoxBuilder(
    private val imageResolver: EpubImageResolver? = null
) {

    fun build(model: ReaderModel, config: EpubCoreLayoutConfig): List<EpubLayoutBox> {
        val context = EpubLayoutContext(config, imageResolver, model.measuredDom)
        val engine = EpubLayoutEngine(context)
        val root = model.layoutRoot ?: EpubBlockNode(
            style = EpubComputedStyle(),
            source = null,
            tagName = "body",
            children = model.toFallbackLayoutNodes()
        )
        val box = engine.layout(root, EpubConstraintBox(context.contentWidthPx, context.contentHeightPx))
            ?: return emptyList()
        return when (box) {
            is EpubContainerLayoutBox -> if (box.isRoot) box.children.ifEmpty { listOf(box) } else listOf(box)
            else -> listOf(box)
        }
    }

    private fun ReaderModel.toFallbackLayoutNodes(): List<EpubLayoutNode> {
        return blocks.map { block ->
            when (block) {
                is ReaderBlock.Heading -> EpubInlineNode(
                    style = block.computedStyle,
                    source = block.source,
                    inline = block.inline
                )
                is ReaderBlock.Paragraph -> EpubInlineNode(
                    style = block.computedStyle,
                    source = block.source,
                    inline = block.inline
                )
                is ReaderBlock.Image -> EpubImageNode(
                    href = block.href,
                    alt = block.alt,
                    width = block.width,
                    height = block.height,
                    style = block.computedStyle,
                    source = block.source
                )
            }
        }
    }
}
