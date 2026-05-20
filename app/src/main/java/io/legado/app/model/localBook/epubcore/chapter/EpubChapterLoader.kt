package io.legado.app.model.localBook.epubcore.chapter

import io.legado.app.data.entities.BookChapter
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.model.ReaderModel
import io.legado.app.model.localBook.epubcore.style.EpubStyleComputer

class EpubChapterLoader(
    private val archive: EpubArchive,
    baseTextSizePx: Float,
    baseTextColor: Int
) {
    private val htmlParser = HtmlToReaderModel(
        EpubStyleComputer(
            baseTextSizePx = baseTextSizePx,
            baseTextColor = baseTextColor,
            loadCss = { baseHref, href ->
                val cssHref = EpubPath.resolve(baseHref, href)
                runCatching { archive.readText(EpubPath.stripFragment(cssHref)) }.getOrDefault("")
            }
        )
    )

    fun load(chapter: BookChapter, htmlOverride: String? = null): ReaderModel {
        val href = EpubPath.stripFragment(chapter.url)
        return htmlParser.parse(chapter.index, href, chapter.title, htmlOverride ?: readHtml(href))
    }

    fun readHtml(chapterHref: String): String {
        return archive.readText(EpubPath.stripFragment(chapterHref))
    }
}
