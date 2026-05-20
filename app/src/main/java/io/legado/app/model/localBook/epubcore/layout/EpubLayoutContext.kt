package io.legado.app.model.localBook.epubcore.layout

import io.legado.app.model.localBook.epubcore.image.EpubImageResolver
import io.legado.app.model.localBook.epubcore.web.EpubDomMeasureResult

data class EpubConstraintBox(
    val widthPx: Float,
    val heightPx: Float = Float.POSITIVE_INFINITY
)

data class EpubLayoutContext(
    val config: EpubCoreLayoutConfig,
    val imageResolver: EpubImageResolver? = null,
    val measuredDom: EpubDomMeasureResult? = null
) {
    val contentWidthPx: Float get() = config.contentWidthPx.toFloat()
    val contentHeightPx: Float get() = config.contentHeightPx.toFloat()
}
