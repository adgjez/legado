package io.legado.app.model.localBook.epubcore.selector

import android.graphics.RectF
import android.text.Layout
import io.legado.app.model.localBook.epubcore.layout.EpubContainerFragment
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.layout.EpubFlexFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextFragment
import io.legado.app.model.localBook.epubcore.layout.EpubPageFragment
import io.legado.app.model.localBook.epubcore.layout.EpubTableFragment
import io.legado.app.model.localBook.epubcore.layout.EpubTextFragment
import io.legado.app.model.localBook.epubcore.layout.pageKey
import kotlin.math.max
import kotlin.math.min

object EpubPageSelectorBuilder {

    fun build(page: EpubCorePage): EpubSelectablePage {
        val fragments = mutableListOf<AbsoluteTextFragment>()
        collectTextFragments(page.paintFragments, 0f, 0f, fragments)
        val blocks = fragments
            .groupBy { it.nodePath }
            .toSortedMap(compareBy { it })
            .values
            .mapIndexedNotNull { index, group -> buildBlock(index, group) }
        return EpubSelectablePage(page.pageKey(), blocks)
    }

    private fun buildBlock(index: Int, fragments: List<AbsoluteTextFragment>): EpubSelectableBlock? {
        if (fragments.isEmpty()) return null
        val textBuilder = StringBuilder()
        val lines = mutableListOf<EpubSelectableLine>()
        val rect = RectF()
        val blockId = fragments.first().blockKey.ifBlank { "block:$index" }
        var cursor = 0
        fragments.forEach { item ->
            if (item.text.isBlank()) return@forEach
            rect.union(item.frame)
            val fragmentTextStart = cursor
            textBuilder.append(item.text)
            cursor += item.text.length
            lines += item.toLines(blockId, fragmentTextStart)
        }
        val text = textBuilder.toString()
        if (text.isBlank() || lines.isEmpty()) return null
        return EpubSelectableBlock(
            id = blockId,
            sourceAnchor = fragments.firstNotNullOfOrNull { it.sourceAnchor },
            text = text,
            rect = rect,
            lines = lines.sortedWith(compareBy<EpubSelectableLine> { it.rect.top }.thenBy { it.rect.left })
        )
    }

    private fun AbsoluteTextFragment.toLines(blockId: String, fragmentTextStart: Int): List<EpubSelectableLine> {
        val textFragment = fragment as? EpubTextFragment
        val layout = textFragment?.staticLayout
        if (textFragment != null && layout != null && textFragment.lineBoxes.isNotEmpty()) {
            return textFragment.lineBoxes.mapNotNull { lineBox ->
                val sourceStart = lineBox.textStart.coerceIn(0, layout.text.length)
                val sourceEnd = lineBox.textEnd.coerceIn(sourceStart, layout.text.length)
                val lineText = layout.text.subSequence(sourceStart, sourceEnd).toString()
                if (lineText.isBlank()) return@mapNotNull null
                val localStart = fragmentTextStart + (sourceStart - sourceOffset).coerceAtLeast(0)
                val localEnd = (localStart + lineText.length).coerceAtMost(fragmentTextStart + text.length)
                val rect = RectF(lineBox.rect)
                rect.offset(frame.left, frame.top)
                EpubSelectableLine(
                    blockId = blockId,
                    textStart = localStart,
                    textEnd = localEnd,
                    sourceStart = sourceStart,
                    sourceEnd = sourceEnd,
                    lineIndex = lineBox.lineIndex,
                    rect = rect,
                    textOriginX = frame.left,
                    baselinePx = frame.top + lineBox.baselinePx,
                    layoutLineIndex = lineBox.lineIndex
                )
            }
        }
        return listOf(
            EpubSelectableLine(
                blockId = blockId,
                textStart = fragmentTextStart,
                textEnd = fragmentTextStart + text.length,
                sourceStart = sourceOffset,
                sourceEnd = sourceOffset + text.length,
                lineIndex = 0,
                rect = RectF(frame),
                textOriginX = frame.left,
                baselinePx = frame.bottom,
                layoutLineIndex = null
            )
        )
    }

    private fun collectTextFragments(
        fragments: List<EpubPageFragment>,
        offsetX: Float,
        offsetY: Float,
        out: MutableList<AbsoluteTextFragment>
    ) {
        fragments.forEach { fragment ->
            when (fragment) {
                is EpubTextFragment -> out += AbsoluteTextFragment(
                    fragment = fragment,
                    text = fragment.staticLayout?.text?.toString() ?: fragment.text.toString(),
                    frame = fragment.frame.offsetBy(offsetX, offsetY),
                    nodePath = fragment.source?.start?.nodePath ?: "text:${out.size}",
                    blockKey = fragment.source?.start?.nodePath ?: "text:${out.size}",
                    sourceAnchor = fragment.source?.start,
                    sourceOffset = fragment.source?.startOffset ?: fragment.startLineOffset()
                )
                is EpubMeasuredTextFragment -> out += AbsoluteTextFragment(
                    fragment = fragment,
                    text = fragment.text.toString(),
                    frame = fragment.frame.offsetBy(offsetX, offsetY),
                    nodePath = fragment.source?.start?.nodePath ?: "measured:${out.size}",
                    blockKey = fragment.source?.start?.nodePath ?: "measured:${out.size}",
                    sourceAnchor = fragment.source?.start,
                    sourceOffset = fragment.source?.startOffset ?: 0
                )
                is EpubContainerFragment -> collectTextFragments(fragment.children, offsetX + fragment.frame.left, offsetY + fragment.frame.top, out)
                is EpubTableFragment -> collectTextFragments(fragment.children, offsetX + fragment.frame.left, offsetY + fragment.frame.top, out)
                is EpubFlexFragment -> collectTextFragments(fragment.children, offsetX + fragment.frame.left, offsetY + fragment.frame.top, out)
                else -> Unit
            }
        }
    }

    fun hitTest(page: EpubSelectablePage, x: Float, y: Float, strict: Boolean): EpubTextHit? {
        val candidates = page.blocks.flatMap { block -> block.lines.map { block to it } }
        val hit = candidates.firstOrNull { (_, line) -> line.rect.contains(x, y) }
            ?: if (strict) null else candidates.minByOrNull { (_, line) ->
                val cx = x.coerceIn(line.rect.left, line.rect.right)
                val cy = y.coerceIn(line.rect.top, line.rect.bottom)
                val dx = x - cx
                val dy = y - cy
                dx * dx + dy * dy
            }
        val pair = hit ?: return null
        val offset = offsetForLine(pair.first, pair.second, x)
        return EpubTextHit(pair.first, pair.second, offset)
    }

    fun selectionGeometry(
        page: EpubSelectablePage,
        startBlock: EpubSelectableBlock,
        startOffset: Int,
        endBlock: EpubSelectableBlock,
        endOffset: Int,
        offsetX: Float,
        offsetY: Float,
        leftLimit: Float,
        rightLimit: Float
    ): EpubSelectionGeometry {
        val orderedBlocks = page.blocks
        val startIndex = orderedBlocks.indexOf(startBlock)
        val endIndex = orderedBlocks.indexOf(endBlock)
        val forward = startIndex < endIndex || startIndex == endIndex && startOffset <= endOffset
        val firstIndex = if (forward) startIndex else endIndex
        val lastIndex = if (forward) endIndex else startIndex
        val firstOffset = if (forward) startOffset else endOffset
        val lastOffset = if (forward) endOffset else startOffset
        val rects = mutableListOf<RectF>()
        val text = StringBuilder()
        var anchorStart: RectF? = null
        var anchorEnd: RectF? = null
        for (blockIndex in firstIndex..lastIndex) {
            val block = orderedBlocks.getOrNull(blockIndex) ?: continue
            val from = if (blockIndex == firstIndex) firstOffset.coerceIn(0, block.text.length) else 0
            val to = if (blockIndex == lastIndex) lastOffset.coerceIn(from, block.text.length) else block.text.length
            if (to <= from) continue
            if (text.isNotEmpty()) text.append('\n')
            text.append(block.text.substring(from, to))
            block.lines.forEach { line ->
                val lineFrom = max(from, line.textStart)
                val lineTo = min(to, line.textEnd)
                if (lineTo <= lineFrom) return@forEach
                val startX = xForOffset(block, line, lineFrom).coerceIn(leftLimit, rightLimit)
                val endX = xForOffset(block, line, lineTo).coerceIn(leftLimit, rightLimit)
                val rect = RectF(
                    min(startX, endX) + offsetX,
                    line.rect.top + offsetY,
                    max(startX, endX) + offsetX,
                    line.rect.bottom + offsetY
                )
                if (rect.width() > 0f && rect.height() > 0f) {
                    rects += rect
                    if (anchorStart == null) anchorStart = rect
                    anchorEnd = rect
                }
            }
        }
        val firstRect = anchorStart ?: RectF()
        val lastRect = anchorEnd ?: firstRect
        return EpubSelectionGeometry(
            selectedText = text.toString(),
            rects = rects,
            anchorStartX = firstRect.left,
            anchorTopY = firstRect.top,
            anchorEndX = lastRect.right,
            anchorBottomY = lastRect.bottom,
            anchorStartBottomY = firstRect.bottom,
            anchorEndBottomY = lastRect.bottom
        )
    }

    private fun offsetForLine(block: EpubSelectableBlock, line: EpubSelectableLine, x: Float): Int {
        if (line.textEnd <= line.textStart) return line.textStart
        val width = line.rect.width().coerceAtLeast(1f)
        val ratio = ((x - line.rect.left) / width).coerceIn(0f, 1f)
        val length = line.textEnd - line.textStart
        return (line.textStart + (length * ratio).toInt()).coerceIn(line.textStart, line.textEnd)
    }

    private fun xForOffset(block: EpubSelectableBlock, line: EpubSelectableLine, offset: Int): Float {
        if (line.textEnd <= line.textStart) return line.rect.left
        val length = line.textEnd - line.textStart
        val ratio = (offset - line.textStart).toFloat().coerceIn(0f, length.toFloat()) / length.toFloat()
        return line.rect.left + line.rect.width() * ratio
    }

    private data class AbsoluteTextFragment(
        val fragment: EpubPageFragment,
        val text: String,
        val frame: RectF,
        val nodePath: String,
        val blockKey: String,
        val sourceAnchor: io.legado.app.model.localBook.epubcore.model.SourceAnchor?,
        val sourceOffset: Int
    )

    private fun RectF.offsetBy(x: Float, y: Float): RectF = RectF(this).apply { offset(x, y) }

    private fun EpubTextFragment.startLineOffset(): Int {
        val layout = staticLayout ?: return 0
        return if (startLine in 0 until layout.lineCount) {
            layout.getLineStart(startLine)
        } else {
            0
        }
    }
}
