package io.legado.app.model.localBook.epubcore.cache

import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.model.ReaderModel

class EpubCoreMemoryCache(
    private val maxChapters: Int = 16,
    private val maxPageSets: Int = 48
) {
    private val chapters = object : LinkedHashMap<String, ReaderModel>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReaderModel>?): Boolean {
            return size > maxChapters
        }
    }
    private val pages = object : LinkedHashMap<String, List<EpubCorePage>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EpubCorePage>>?): Boolean {
            return size > maxPageSets
        }
    }

    @Synchronized
    fun getChapter(key: String): ReaderModel? = chapters[key]

    @Synchronized
    fun putChapter(key: String, model: ReaderModel) {
        chapters[key] = model
    }

    @Synchronized
    fun getPages(key: String): List<EpubCorePage>? = pages[key]

    @Synchronized
    fun putPages(key: String, value: List<EpubCorePage>) {
        pages[key] = value
    }

    @Synchronized
    fun clear() {
        chapters.clear()
        pages.clear()
    }
}
