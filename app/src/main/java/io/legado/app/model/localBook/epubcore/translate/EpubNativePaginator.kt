package io.legado.app.model.localBook.epubcore.translate

import android.graphics.RectF
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.layout.EpubImageFragment
import io.legado.app.model.localBook.epubcore.layout.EpubPageFragment
import io.legado.app.model.localBook.epubcore.layout.EpubTextAnchor
import io.legado.app.model.localBook.epubcore.layout.EpubTextFragment
import io.legado.app.model.localBook.epubcore.layout.EpubWebFragment
import io.legado.app.model.localBook.epubcore.model.SourceAnchor as ReaderSourceAnchor
import io.legado.app.model.localBook.epubcore.model.SourceRange as ReaderSourceRange

class EpubNativePaginator(
    private val config: EpubCoreLayoutConfig
) {

    fun paginate(document: EpubNativeLaidDocument): List<EpubCorePage> {
        val pages = ArrayList<MutablePage>()
        var current = MutablePage(
            chapterIndex = document.source.chapterIndex,
            chapterHref = document.source.chapterHref,
            pageIndex = 0,
            heightLimit = config.contentHeightPx.toFloat()
        )
        fun flushPage() {
            if (current.fragments.isEmpty()) return
            pages += current
            current = MutablePage(
                chapterIndex = document.source.chapterIndex,
                chapterHref = document.source.chapterHref,
                pageIndex = pages.size,
                heightLimit = config.contentHeightPx.toFloat()
            )
        }
        document.blocks.forEach { block ->
            when (block) {
                is EpubNativeLaidTextBlock -> {
                    var line = 0
                    while (line < block.layout.lineCount) {
                        val availableHeight = current.availableHeight()
                        if (availableHeight <= 0f) {
                            flushPage()
                            continue
                        }
                        val endLine = findEndLine(block, line, availableHeight)
                        if (endLine <= line) {
                            flushPage()
                            continue
                        }
                        current.add(block.toFragment(line, endLine, current.cursorY))
                        line = endLine
                        if (line < block.layout.lineCount) flushPage()
                    }
                }
                is EpubNativeLaidImageBlock -> {
                    if (block.heightPx > current.availableHeight() && current.fragments.isNotEmpty()) {
                        flushPage()
                    }
                    current.add(block.toFragment(current.cursorY))
                }
                is EpubNativeLaidFallbackBlock -> {
                    if (block.heightPx > current.availableHeight() && current.fragments.isNotEmpty()) {
                        flushPage()
                    }
                    current.add(block.toFragment(current.cursorY))
                }
            }
        }
        flushPage()
        return pages.map { page ->
            page.toCorePage(totalPages = pages.size)
        }.ifEmpty {
            listOf(
                EpubCorePage(
                    chapterIndex = document.source.chapterIndex,
                    chapterHref = document.source.chapterHref,
                    pageIndex = 0,
                    totalPagesInChapter = 1,
                    start = null,
                    end = null
                )
            )
        }
    }

    private fun findEndLine(
        block: EpubNativeLaidTextBlock,
        startLine: Int,
        availableHeight: Float
    ): Int {
        var endLine = startLine
        val startTop = block.layout.getLineTop(startLine)
        while (endLine < block.layout.lineCount) {
            val bottom = block.layout.getLineBottom(endLine)
            if (bottom - startTop > availableHeight && endLine > startLine) break
            if (bottom - startTop > availableHeight) break
            endLine++
        }
        return endLine
    }

    private fun EpubNativeLaidTextBlock.toFragment(
        startLine: Int,
        endLine: Int,
        y: Float
    ): EpubTextFragment {
        val lineTop = layout.getLineTop(startLine).toFloat()
        val lineBottom = layout.getLineBottom(endLine - 1).toFloat()
        val height = (lineBottom - lineTop).coerceAtLeast(1f)
        return EpubTextFragment(
            text = text,
            staticLayout = layout,
            anchors = anchors.map { it.toLayoutAnchor() },
            frame = RectF(
                0f,
                y,
                contentWidthPx.toFloat(),
                y + height
            ),
            source = block.source.toReaderSourceRange(),
            alignment = block.style.textAlign.toLayoutAlignment(config.alignment),
            lineSpacingMultiplier = config.lineSpacingMultiplier,
            lineSpacingExtraPx = config.lineSpacingExtraPx,
            startLine = startLine,
            endLineExclusive = endLine,
            lineTopOffsetPx = lineTop
        )
    }

    private fun EpubNativeLaidImageBlock.toFragment(y: Float): EpubImageFragment {
        return EpubImageFragment(
            href = block.href,
            alt = block.alt,
            frame = RectF(
                0f,
                y,
                widthPx,
                y + heightPx
            ),
            source = block.source.toReaderSourceRange(),
            opacity = block.style.opacity,
            borderRadius = block.style.borderRadius
        )
    }

    private fun EpubNativeLaidFallbackBlock.toFragment(y: Float): EpubWebFragment {
        return EpubWebFragment(
            frame = RectF(
                0f,
                y,
                config.contentWidthPx.toFloat(),
                y + heightPx
            ),
            source = block.source.toReaderSourceRange(),
            fallbackText = block.text.ifBlank { block.fallbackReason.name }
        )
    }

    private fun EpubNativeTextAnchor.toLayoutAnchor(): EpubTextAnchor {
        return EpubTextAnchor(
            source = source.toReaderSourceRange(),
            startOffset = startOffset,
            endOffset = endOffset
        )
    }

    private data class MutablePage(
        val chapterIndex: Int,
        val chapterHref: String,
        val pageIndex: Int,
        val heightLimit: Float,
        val fragments: ArrayList<EpubPageFragment> = ArrayList(),
        var cursorY: Float = 0f
    ) {
        fun availableHeight(): Float {
            return (heightLimit - cursorY).coerceAtLeast(0f)
        }

        fun add(fragment: EpubPageFragment) {
            fragments += fragment
            cursorY = fragment.frame.bottom + ParagraphGapPx
        }

        fun toCorePage(totalPages: Int): EpubCorePage {
            return EpubCorePage(
                chapterIndex = chapterIndex,
                chapterHref = chapterHref,
                pageIndex = pageIndex,
                totalPagesInChapter = totalPages,
                text = fragments.joinToString(separator = "\n") {
                    when (it) {
                        is EpubTextFragment -> it.text
                        is EpubWebFragment -> it.fallbackText?.toString().orEmpty()
                        else -> ""
                    }
                },
                fragments = fragments,
                start = fragments.firstOrNull()?.source,
                end = fragments.lastOrNull()?.source
            )
        }

        companion object {
            const val ParagraphGapPx = 0f
        }
    }
}

private fun EpubSourceRange.toReaderSourceRange(): ReaderSourceRange {
    return ReaderSourceRange(
        start = start.toReaderSourceAnchor(),
        end = end.toReaderSourceAnchor()
    )
}

private fun EpubSourceAnchor.toReaderSourceAnchor(): ReaderSourceAnchor {
    return ReaderSourceAnchor(
        chapterHref = chapterHref,
        nodePath = nodePath,
        blockIndex = nodeOrder,
        textOffset = textOffset
    )
}

private fun io.legado.app.model.localBook.epubcore.style.EpubTextAlign.toLayoutAlignment(
    default: android.text.Layout.Alignment
): android.text.Layout.Alignment {
    return when (this) {
        io.legado.app.model.localBook.epubcore.style.EpubTextAlign.Center -> android.text.Layout.Alignment.ALIGN_CENTER
        io.legado.app.model.localBook.epubcore.style.EpubTextAlign.End -> android.text.Layout.Alignment.ALIGN_OPPOSITE
        io.legado.app.model.localBook.epubcore.style.EpubTextAlign.Start,
        io.legado.app.model.localBook.epubcore.style.EpubTextAlign.Justify -> default
    }
}
