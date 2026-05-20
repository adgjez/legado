package io.legado.app.model.localBook.epubcore.chapter

import io.legado.app.model.localBook.epubcore.model.SourceRange
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle

data class StyledNode(
    val tagName: String,
    val nodePath: String,
    val style: EpubComputedStyle,
    val source: SourceRange?,
    val text: String? = null,
    val imageHref: String? = null,
    val imageAlt: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val children: List<StyledNode> = emptyList()
)
