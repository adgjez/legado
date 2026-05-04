package io.legado.app.ui.book.cache

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.utils.externalCache
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.compress.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

class CacheManageViewModel(application: Application) : BaseViewModel(application) {

    val itemsLiveData = MutableLiveData<List<CacheBookItem>>()
    val summaryLiveData = MutableLiveData<CacheSummary>()
    val loadingLiveData = MutableLiveData<Boolean>()

    private var loadJob: Job? = null
    var mode: CacheManageMode = CacheManageMode.BOOK
        private set

    fun load(mode: CacheManageMode = this.mode) {
        this.mode = mode
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            loadingLiveData.postValue(true)
            val items = appDb.bookDao.all
                .asSequence()
                .filter { it.matchMode(mode) }
                .map { book -> buildCacheBookItem(book, mode) }
                .filter { it.cachedCount > 0 }
                .sortedWith(compareByDescending<CacheBookItem> { it.cachedCount }.thenBy { it.book.name })
                .toList()
            ensureActive()
            itemsLiveData.postValue(items)
            summaryLiveData.postValue(
                CacheSummary(
                    bookCount = items.size,
                    cachedChapterCount = items.sumOf { it.cachedCount },
                    mode = mode
                )
            )
            loadingLiveData.postValue(false)
        }
    }

    fun deleteBookCache(book: Book, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            BookHelp.clearCache(book)
            withContext(Dispatchers.Main) {
                onDone()
            }
            load(mode)
        }
    }

    suspend fun getChapterItems(book: Book, key: String? = null): List<CacheChapterItem> {
        return withContext(Dispatchers.IO) {
            val cacheNames = BookHelp.getChapterFiles(book)
            appDb.bookChapterDao.getChapterList(book.bookUrl)
                .asSequence()
                .filterNot { it.isVolume }
                .filter { key.isNullOrBlank() || it.title.contains(key, ignoreCase = true) }
                .map { chapter ->
                    CacheChapterItem(
                        chapter = chapter,
                        cached = isChapterCached(book, chapter, cacheNames)
                    )
                }
                .toList()
        }
    }

    suspend fun deleteChapterCache(book: Book, chapter: BookChapter) {
        withContext(Dispatchers.IO) {
            BookHelp.delChapterCache(book, chapter)
        }
    }

    suspend fun createCachePackage(book: Book): File {
        return withContext(Dispatchers.IO) {
            val cacheDir = BookHelp.getCacheDir(book)
            if (!cacheDir.exists() || cacheDir.listFiles().isNullOrEmpty()) {
                throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
            }
            val outDir = File(appCtx.externalCache, "cache_package").apply {
                if (!exists()) mkdirs()
            }
            val fileName = "${book.name}_${book.author}_${System.currentTimeMillis()}"
                .normalizeFileName()
                .ifBlank { "cache_${System.currentTimeMillis()}" }
            val zipFile = File(outDir, "$fileName.zip").apply {
                if (exists()) delete()
            }
            if (!ZipUtils.zipFile(cacheDir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
                throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
            }
            zipFile
        }
    }

    private fun buildCacheBookItem(book: Book, mode: CacheManageMode): CacheBookItem {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .filterNot { it.isVolume }
        val cacheNames = BookHelp.getChapterFiles(book)
        val cachedCount = chapters.count { isChapterCached(book, it, cacheNames) }
        return CacheBookItem(
            book = book,
            mode = mode,
            cachedCount = cachedCount,
            totalChapterCount = chapters.size.takeIf { it > 0 } ?: book.totalChapterNum
        )
    }

    private fun isChapterCached(
        book: Book,
        chapter: BookChapter,
        cacheNames: Set<String> = BookHelp.getChapterFiles(book)
    ): Boolean {
        if (book.isLocal) return false
        val hasContent = BookHelp.getChapterCacheFileNames(book, chapter).any(cacheNames::contains)
        return if (book.isImage && hasContent) {
            BookHelp.hasImageContent(book, chapter)
        } else {
            hasContent
        }
    }

    private fun Book.matchMode(mode: CacheManageMode): Boolean {
        if (isLocal) return false
        return when (mode) {
            CacheManageMode.BOOK -> !isAudio && !isImage
            CacheManageMode.AUDIO -> isAudio
            CacheManageMode.MANGA -> isImage
        }
    }
}

enum class CacheManageMode(@StringRes val titleRes: Int) {
    BOOK(R.string.cache_manage_books),
    AUDIO(R.string.cache_manage_audio),
    MANGA(R.string.cache_manage_manga)
}

data class CacheBookItem(
    val book: Book,
    val mode: CacheManageMode,
    val cachedCount: Int,
    val totalChapterCount: Int
)

data class CacheChapterItem(
    val chapter: BookChapter,
    val cached: Boolean
)

data class CacheSummary(
    val bookCount: Int,
    val cachedChapterCount: Int,
    val mode: CacheManageMode
)
