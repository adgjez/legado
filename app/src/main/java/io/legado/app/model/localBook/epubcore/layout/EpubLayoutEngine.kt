package io.legado.app.model.localBook.epubcore.layout

class EpubLayoutEngine(
    private val context: EpubLayoutContext
) {
    private val inlineLayouter = EpubInlineLayouter(context)
    private val imageLayouter = EpubImageLayouter(context)
    private val blockLayouter = EpubBlockLayouter(this)

    fun layout(node: EpubLayoutNode, constraint: EpubConstraintBox): EpubLayoutBox? {
        return when (node) {
            is EpubBlockNode -> blockLayouter.layout(node, constraint)
            is EpubInlineNode -> inlineLayouter.layout(node, constraint)
            is EpubImageNode -> imageLayouter.layout(node, constraint)
            is EpubTableNode -> blockLayouter.layoutFlowContainer(node.style, node.source, node.children, constraint)
                ?.let { box -> EpubTableLayoutBox(box.frame, box.source, box.style, box.children) }
            is EpubFlexNode -> blockLayouter.layoutFlowContainer(node.style, node.source, node.children, constraint)
                ?.let { box -> EpubFlexLayoutBox(box.frame, box.source, box.style, box.children) }
        }
    }

    internal fun layoutChildren(
        children: List<EpubLayoutNode>,
        constraint: EpubConstraintBox
    ): List<EpubLayoutBox> {
        return children.mapNotNull { layout(it, constraint) }
    }
}
