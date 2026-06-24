package io.legado.app.model.localBook.epubcore.web

data class EpubDomMeasureRequest(
    val chapterHref: String,
    val html: String,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val timeoutMillis: Long = 8_000L
)

data class EpubDomMeasureResult(
    val chapterHref: String,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val nodes: Map<String, EpubMeasuredNode>
) {
    fun node(path: String?): EpubMeasuredNode? {
        if (path.isNullOrBlank()) return null
        return nodes[path]
    }
}

data class EpubMeasuredNode(
    val nodePath: String,
    val tagName: String,
    val display: String?,
    val position: String?,
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val fontSizePx: Float?,
    val lineHeightPx: Float?,
    val marginLeftPx: Float,
    val marginTopPx: Float,
    val marginRightPx: Float,
    val marginBottomPx: Float,
    val paddingLeftPx: Float,
    val paddingTopPx: Float,
    val paddingRightPx: Float,
    val paddingBottomPx: Float,
    val naturalWidthPx: Float?,
    val naturalHeightPx: Float?
)
