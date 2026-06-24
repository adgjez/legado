package io.legado.app.model.localBook.epubcore.web

data class EpubChapterDocument(
    val chapterIndex: Int,
    val chapterHref: String,
    val title: String?,
    val html: String,
    val baseUrl: String
)
