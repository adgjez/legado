package io.legado.app.model.localBook.epubcore.layout

import android.graphics.RectF
import io.legado.app.model.localBook.epubcore.image.EpubImageResolver
import io.legado.app.model.localBook.epubcore.model.ReaderModel
import io.legado.app.model.localBook.epubcore.model.SourceRange
import io.legado.app.model.localBook.epubcore.style.EpubBreak

class EpubCorePaginator(
    imageResolver: EpubImageResolver? = null
) {

    private val boxBuilder = EpubLayoutBoxBuilder(imageResolver)

    fun paginate(model: ReaderModel, config: EpubCoreLayoutConfig): List<EpubCorePage> {
        return paginate(
            chapterIndex = model.chapterIndex,
            chapterHref = model.chapterHref,
            boxes = boxBuilder.build(model, config),
            config = config
        )
    }

    private fun paginate(
        chapterIndex: Int,
        chapterHref: String,
        boxes: List<EpubLayoutBox>,
        config: EpubCoreLayoutConfig
    ): List<EpubCorePage> {
        val pages = ArrayList<PageBuilder>()
        var current = PageBuilder()

        fun commit() {
            if (current.isNotEmpty()) {
                pages.add(current)
                current = PageBuilder()
            }
        }

        for (box in boxes) {
            if (box.style.pageBreakBefore == EpubBreak.Always) {
                commit()
            }
            addFragmentToPages(box.toPageFragment(config), config, pages, { current }, { current = it }, ::commit)
            if (box.style.pageBreakAfter == EpubBreak.Always) {
                commit()
            }
        }
        commit()

        return pages.mapIndexed { index, page ->
            EpubCorePage(
                chapterIndex = chapterIndex,
                chapterHref = chapterHref,
                pageIndex = index,
                totalPagesInChapter = pages.size,
                text = page.text.trimEnd(),
                fragments = page.fragments,
                start = page.start,
                end = page.end
            )
        }
    }

    private fun addFragmentToPages(
        fragment: EpubPageFragment,
        config: EpubCoreLayoutConfig,
        pages: MutableList<PageBuilder>,
        currentProvider: () -> PageBuilder,
        currentUpdater: (PageBuilder) -> Unit,
        commit: () -> Unit
    ) {
        when (fragment) {
            is EpubTextFragment -> addTextToPages(fragment, config, pages, currentProvider, currentUpdater, commit)
            is EpubMeasuredTextFragment -> addUnsplittableToPages(fragment, config, currentProvider, commit)
            is EpubImageFragment -> addImageToPages(fragment, config, currentProvider, commit)
            is EpubContainerFragment -> addParentToPages(
                fragment = fragment,
                children = fragment.children,
                config = config,
                pages = pages,
                currentProvider = currentProvider,
                currentUpdater = currentUpdater,
                commit = commit
            ) { frame, children ->
                fragment.copy(frame = frame, children = children)
            }
            is EpubTableFragment -> addParentToPages(
                fragment = fragment,
                children = fragment.children,
                config = config,
                pages = pages,
                currentProvider = currentProvider,
                currentUpdater = currentUpdater,
                commit = commit
            ) { frame, children ->
                fragment.copy(frame = frame, children = children)
            }
            is EpubFlexFragment -> addParentToPages(
                fragment = fragment,
                children = fragment.children,
                config = config,
                pages = pages,
                currentProvider = currentProvider,
                currentUpdater = currentUpdater,
                commit = commit
            ) { frame, children ->
                fragment.copy(frame = frame, children = children)
            }
            is EpubMeasuredBoxFragment -> addUnsplittableToPages(fragment.fitToPage(config), config, currentProvider, commit)
            is EpubWebFragment -> addUnsplittableToPages(fragment.fitToPage(config), config, currentProvider, commit)
        }
    }

    private fun addTextToPages(
        sourceFragment: EpubTextFragment,
        config: EpubCoreLayoutConfig,
        pages: MutableList<PageBuilder>,
        currentProvider: () -> PageBuilder,
        currentUpdater: (PageBuilder) -> Unit,
        commit: () -> Unit
    ) {
        val layout = sourceFragment.staticLayout
        if (layout == null || layout.lineCount == 0) {
            addUnsplittableToPages(sourceFragment, config, currentProvider, commit)
            return
        }

        var lineStart = sourceFragment.startLine.coerceIn(0, layout.lineCount)
        val lineLimit = sourceFragment.endLineExclusive.coerceIn(lineStart, layout.lineCount)
        while (lineStart < lineLimit) {
            var current = currentProvider()
            var availableHeight = current.remainingHeight(config)
            if (current.isNotEmpty() && availableHeight <= 0f) {
                commit()
                current = currentProvider()
                availableHeight = current.remainingHeight(config)
            }

            val remainingLines = lineLimit - lineStart
            if (remainingLines > 1 && current.isNotEmpty()) {
                val nextLineHeight = (layout.getLineBottom(lineStart) - layout.getLineTop(lineStart)).coerceAtLeast(1)
                if (availableHeight <= nextLineHeight + config.paragraphSpacingPx) {
                    commit()
                    continue
                }
            }

            var lineEnd = lineStart
            while (lineEnd < lineLimit &&
                layout.getLineBottom(lineEnd) - layout.getLineTop(lineStart) <= (availableHeight - config.paragraphSpacingPx).coerceAtLeast(1f)
            ) {
                lineEnd++
            }
            if (lineEnd == lineStart) {
                if (current.isNotEmpty()) {
                    commit()
                    continue
                }
                lineEnd++
            }

            val textStart = layout.getLineStart(lineStart).coerceAtMost(sourceFragment.text.length)
            val textEnd = layout.getLineEnd(lineEnd - 1).coerceAtMost(sourceFragment.text.length)
            val splitText = sourceFragment.text.subSequence(textStart, textEnd).trimEnd()
            val height = (layout.getLineBottom(lineEnd - 1) - layout.getLineTop(lineStart)).coerceAtLeast(1)
            val lineBoxes = (lineStart until lineEnd).map { line ->
                val top = layout.getLineTop(line).toFloat() - layout.getLineTop(lineStart).toFloat()
                val bottom = layout.getLineBottom(line).toFloat() - layout.getLineTop(lineStart).toFloat()
                EpubTextLineBox(
                    lineIndex = line,
                    textStart = layout.getLineStart(line).coerceIn(0, sourceFragment.text.length),
                    textEnd = layout.getLineEnd(line).coerceIn(0, sourceFragment.text.length),
                    rect = RectF(
                        layout.getLineLeft(line),
                        top,
                        layout.getLineRight(line).coerceAtLeast(layout.width.toFloat()),
                        bottom
                    ),
                    baselinePx = layout.getLineBaseline(line).toFloat() - layout.getLineTop(lineStart).toFloat()
                )
            }
            val fragment = sourceFragment.copy(
                text = splitText,
                anchors = sourceFragment.anchors.filter { anchor ->
                    anchor.endOffset > textStart && anchor.startOffset < textEnd
                },
                lineBoxes = lineBoxes,
                frame = RectF(0f, 0f, config.contentWidthPx.toFloat(), height.toFloat()),
                startLine = lineStart,
                endLineExclusive = lineEnd,
                lineTopOffsetPx = layout.getLineTop(lineStart).toFloat()
            )
            current.appendText(fragment, height, config, addTrailingSpacing = lineEnd == lineLimit)
            currentUpdater(current)
            lineStart = lineEnd

            if (lineStart < lineLimit && current.remainingHeight(config) <= 0f) {
                commit()
            }
        }
        if (pages.isEmpty() && !currentProvider().isNotEmpty() && lineLimit == 0) {
            val page = PageBuilder()
            page.appendText(sourceFragment, layout.height, config)
            pages.add(page)
        }
    }

    private fun addImageToPages(
        fragment: EpubImageFragment,
        config: EpubCoreLayoutConfig,
        currentProvider: () -> PageBuilder,
        commit: () -> Unit
    ) {
        val fitted = fragment.fitImageToPage(config)
        addUnsplittableToPages(fitted, config, currentProvider, commit)
    }

    private fun addUnsplittableToPages(
        fragment: EpubPageFragment,
        config: EpubCoreLayoutConfig,
        currentProvider: () -> PageBuilder,
        commit: () -> Unit
    ) {
        val current = currentProvider()
        val height = fragment.pageHeight(config)
        if (current.isNotEmpty() && current.usedHeight + height > config.contentHeightPx) {
            commit()
        }
        currentProvider().appendFragment(fragment, config)
    }

    private fun addParentToPages(
        fragment: EpubPageFragment,
        children: List<EpubPageFragment>,
        config: EpubCoreLayoutConfig,
        pages: MutableList<PageBuilder>,
        currentProvider: () -> PageBuilder,
        currentUpdater: (PageBuilder) -> Unit,
        commit: () -> Unit,
        copyWith: (RectF, List<EpubPageFragment>) -> EpubPageFragment
    ) {
        if (children.isEmpty()) {
            addUnsplittableToPages(fragment.fitToPage(config), config, currentProvider, commit)
            return
        }

        val childPages = ArrayList<PageBuilder>()
        var childCurrent = PageBuilder()
        fun commitChild() {
            if (childCurrent.isNotEmpty()) {
                childPages.add(childCurrent)
                childCurrent = PageBuilder()
            }
        }
        children.forEach { child ->
            addFragmentToPages(child.normalizeForNestedPage(config), config, childPages, { childCurrent }, { childCurrent = it }, ::commitChild)
        }
        commitChild()

        if (childPages.isEmpty()) {
            addUnsplittableToPages(fragment.fitToPage(config), config, currentProvider, commit)
            return
        }

        childPages.forEach { childPage ->
            val height = childPage.usedHeight.coerceAtLeast(config.textPaint.textSize)
            val wrapper = copyWith(
                RectF(0f, 0f, fragment.frame.width().coerceAtLeast(config.contentWidthPx.toFloat()), height),
                childPage.fragments
            )
            addUnsplittableToPages(wrapper, config, currentProvider, commit)
        }
    }

    private fun EpubLayoutBox.toPageFragment(config: EpubCoreLayoutConfig): EpubPageFragment {
        return when (this) {
            is EpubTextLayoutBox -> EpubTextFragment(
                text = text,
                staticLayout = staticLayout,
                anchors = anchors,
                frame = frame,
                source = source,
                alignment = style.toLayoutAlignment(config.alignment),
                lineSpacingMultiplier = config.lineSpacingMultiplier,
                lineSpacingExtraPx = style.lineHeightPx?.let { lineHeight ->
                    (lineHeight - (style.fontSizePx ?: config.textPaint.textSize)).coerceAtLeast(0f)
                } ?: config.lineSpacingExtraPx
            )
            is EpubImageLayoutBox -> EpubImageFragment(
                href = href,
                alt = alt,
                frame = frame,
                source = source,
                opacity = style.opacity,
                borderRadius = style.borderRadius
            )
            is EpubContainerLayoutBox -> EpubContainerFragment(
                frame = measuredContainerFrame(config),
                source = source,
                children = children.map { it.toPageFragment(config) },
                backgroundColor = style.backgroundColor,
                background = style.background,
                borderColor = style.border.color,
                borderWidthPx = style.border.widthPx,
                borderRadius = style.borderRadius,
                opacity = style.opacity
            )
            is EpubTableLayoutBox -> EpubTableFragment(frame, source, children.map { it.toPageFragment(config) })
            is EpubFlexLayoutBox -> EpubFlexFragment(frame, source, children.map { it.toPageFragment(config) })
        }
    }

    private fun EpubContainerLayoutBox.measuredContainerFrame(config: EpubCoreLayoutConfig): RectF {
        val childFragments = children.map { it.toPageFragment(config) }
        val right = childFragments.maxOfOrNull { it.frame.right } ?: frame.right
        val bottom = childFragments.maxOfOrNull { it.frame.bottom } ?: frame.bottom
        return RectF(
            frame.left,
            frame.top,
            frame.right.coerceAtLeast(right + style.padding.rightPx + style.border.widthPx),
            frame.bottom.coerceAtLeast(bottom + style.padding.bottomPx + style.border.widthPx)
        )
    }

    private class PageBuilder {
        private val builder = StringBuilder()
        private val mutableFragments = ArrayList<EpubPageFragment>()
        private var cursorY = 0f
        var start: SourceRange? = null
            private set
        var end: SourceRange? = null
            private set

        val text: String
            get() = builder.toString()

        val fragments: List<EpubPageFragment>
            get() = mutableFragments.toList()

        val usedHeight: Float
            get() = cursorY

        fun isNotEmpty(): Boolean = mutableFragments.isNotEmpty()

        fun remainingHeight(config: EpubCoreLayoutConfig): Float {
            return (config.contentHeightPx - cursorY).coerceAtLeast(0f)
        }

        fun appendText(
            fragment: EpubTextFragment,
            measuredHeightPx: Int,
            config: EpubCoreLayoutConfig,
            addTrailingSpacing: Boolean = true
        ) {
            if (start == null) start = fragment.firstSource()
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(fragment.text.trim())
            val y = cursorY
            val height = measuredHeightPx.coerceAtLeast(1).toFloat()
            mutableFragments.add(
                fragment.copy(
                    frame = RectF(
                        0f,
                        y,
                        config.contentWidthPx.toFloat(),
                        y + height
                    )
                )
            )
            val spacing = if (addTrailingSpacing) {
                config.paragraphSpacingPx.coerceAtMost((config.contentHeightPx * 0.08f).toInt().coerceAtLeast(8))
            } else {
                0
            }
            cursorY += height + spacing
            end = fragment.lastSource()
        }

        fun appendImage(fragment: EpubImageFragment, config: EpubCoreLayoutConfig) {
            if (start == null) start = fragment.firstSource()
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(fragment.alt ?: fragment.href.substringAfterLast('/'))
            val y = cursorY
            val width = fragment.frame.width().coerceIn(1f, config.contentWidthPx.toFloat())
            val height = fragment.frame.height().coerceIn(1f, config.contentHeightPx.toFloat())
            mutableFragments.add(
                fragment.copy(
                    frame = RectF(
                        0f,
                        y,
                        width,
                        y + height
                    )
                )
            )
            cursorY += height + config.paragraphSpacingPx.coerceAtMost((config.contentHeightPx * 0.08f).toInt().coerceAtLeast(8))
            end = fragment.lastSource()
        }

        fun appendFragment(fragment: EpubPageFragment, config: EpubCoreLayoutConfig) {
            if (fragment is EpubTextFragment) {
                appendText(fragment, fragment.frame.height().toInt().coerceAtLeast(1), config)
                return
            }
            if (fragment is EpubImageFragment) {
                appendImage(fragment, config)
                return
            }
            if (start == null) start = fragment.firstSource()
            val y = cursorY
            val height = fragment.frame.height().coerceAtLeast(config.textPaint.textSize)
            mutableFragments.add(
                fragment.offsetToPageY(y, height, config)
            )
            builder.appendPlainText(fragment)
            cursorY += height + config.paragraphSpacingPx.coerceAtMost((config.contentHeightPx * 0.08f).toInt().coerceAtLeast(8))
            end = fragment.lastSource()
        }

        private fun EpubPageFragment.offsetToPageY(y: Float, height: Float, config: EpubCoreLayoutConfig): EpubPageFragment {
            return when (this) {
                is EpubContainerFragment -> copy(
                    frame = RectF(0f, y, frame.width().coerceAtLeast(config.contentWidthPx.toFloat()), y + height)
                )
                is EpubTableFragment -> copy(frame = RectF(0f, y, frame.width(), y + height))
                is EpubFlexFragment -> copy(frame = RectF(0f, y, frame.width(), y + height))
                is EpubWebFragment -> copy(frame = RectF(0f, y, frame.width(), y + height))
                is EpubTextFragment -> copy(frame = RectF(0f, y, frame.width(), y + height))
                is EpubMeasuredTextFragment -> copy(frame = RectF(0f, y, frame.width(), y + height))
                is EpubImageFragment -> copy(frame = RectF(0f, y, frame.width(), y + height))
                is EpubMeasuredBoxFragment -> copy(frame = RectF(0f, y, frame.width(), y + height))
            }
        }

        private fun StringBuilder.appendPlainText(fragment: EpubPageFragment) {
            when (fragment) {
                is EpubTextFragment -> {
                    if (isNotEmpty()) append("\n\n")
                    append(fragment.text.trim())
                }
                is EpubImageFragment -> {
                    if (isNotEmpty()) append("\n\n")
                    append(fragment.alt ?: fragment.href.substringAfterLast('/'))
                }
                is EpubMeasuredTextFragment -> {
                    if (isNotEmpty()) append("\n\n")
                    append(fragment.text.trim())
                }
                is EpubMeasuredBoxFragment -> Unit
                is EpubContainerFragment -> fragment.children.forEach { appendPlainText(it) }
                is EpubTableFragment -> fragment.children.forEach { appendPlainText(it) }
                is EpubFlexFragment -> fragment.children.forEach { appendPlainText(it) }
                is EpubWebFragment -> fragment.fallbackText?.let {
                    if (isNotEmpty()) append("\n\n")
                    append(it)
                }
            }
        }
    }
}

private fun EpubPageFragment.offsetWithinContainerPage(
    x: Float,
    y: Float,
    config: EpubCoreLayoutConfig
): EpubPageFragment {
    val width = frame.width().coerceAtLeast(1f)
    val height = frame.height().coerceAtLeast(config.textPaint.textSize)
    return when (this) {
        is EpubTextFragment -> copy(frame = RectF(x, y, x + width, y + height))
        is EpubMeasuredTextFragment -> copy(frame = RectF(x, y, x + width, y + height))
        is EpubImageFragment -> copy(frame = RectF(x, y, x + width, y + height))
        is EpubMeasuredBoxFragment -> copy(frame = RectF(x, y, x + width, y + height))
        is EpubContainerFragment -> copy(frame = RectF(x, y, x + width, y + height))
        is EpubTableFragment -> copy(frame = RectF(x, y, x + width, y + height))
        is EpubFlexFragment -> copy(frame = RectF(x, y, x + width, y + height))
        is EpubWebFragment -> copy(frame = RectF(x, y, x + width, y + height))
    }
}

private fun EpubPageFragment.pageHeight(config: EpubCoreLayoutConfig): Float {
    return frame.height().coerceAtLeast(config.textPaint.textSize)
}

private fun EpubPageFragment.fitToPage(config: EpubCoreLayoutConfig): EpubPageFragment {
    val width = frame.width().coerceIn(1f, config.contentWidthPx.toFloat())
    val height = frame.height().coerceIn(1f, config.contentHeightPx.toFloat())
    val fitted = RectF(0f, 0f, width, height)
    return when (this) {
        is EpubTextFragment -> copy(frame = fitted)
        is EpubMeasuredTextFragment -> copy(frame = fitted)
        is EpubImageFragment -> copy(frame = fitted)
        is EpubMeasuredBoxFragment -> copy(frame = fitted)
        is EpubContainerFragment -> copy(frame = fitted)
        is EpubTableFragment -> copy(frame = fitted)
        is EpubFlexFragment -> copy(frame = fitted)
        is EpubWebFragment -> copy(frame = fitted)
    }
}

private fun EpubImageFragment.fitImageToPage(config: EpubCoreLayoutConfig): EpubImageFragment {
    val width = frame.width().coerceAtLeast(1f)
    val height = frame.height().coerceAtLeast(1f)
    val scale = minOf(
        1f,
        config.contentWidthPx / width,
        config.contentHeightPx / height
    )
    return copy(frame = RectF(0f, 0f, width * scale, height * scale))
}

private fun EpubPageFragment.normalizeForNestedPage(config: EpubCoreLayoutConfig): EpubPageFragment {
    return offsetWithinContainerPage(0f, 0f, config)
}

private fun EpubPageFragment.firstSource(): SourceRange? {
    return when (this) {
        is EpubContainerFragment -> children.firstNotNullOfOrNull { it.firstSource() } ?: source
        is EpubTableFragment -> children.firstNotNullOfOrNull { it.firstSource() } ?: source
        is EpubFlexFragment -> children.firstNotNullOfOrNull { it.firstSource() } ?: source
        else -> source
    }
}

private fun EpubPageFragment.lastSource(): SourceRange? {
    return when (this) {
        is EpubContainerFragment -> children.asReversed().firstNotNullOfOrNull { it.lastSource() } ?: source
        is EpubTableFragment -> children.asReversed().firstNotNullOfOrNull { it.lastSource() } ?: source
        is EpubFlexFragment -> children.asReversed().firstNotNullOfOrNull { it.lastSource() } ?: source
        else -> source
    }
}
