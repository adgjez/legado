package io.legado.app.ui.book.cache

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.utils.externalCache
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.compress.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
        lateinit var job: Job
        job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            loadingLiveData.postValue(true)
            try {
                val items = getBooks(mode)
                    .asSequence()
                    .mapNotNull { book -> buildCacheBookItem(book, mode) }
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
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (loadJob === job) {
                    loadingLiveData.postValue(false)
                }
            }
        }
        loadJob = job
        job.start()
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
        return getChapterItems(book, key, false)
    }

    suspend fun getChapterItems(
        book: Book,
        key: String? = null,
        cachedOnly: Boolean = false
    ): List<CacheChapterItem> {
        return withContext(Dispatchers.IO) {
            val cacheNames = getCacheFileNames(book)
            if (cachedOnly && cacheNames.none { it.endsWith(".nb") }) {
                return@withContext emptyList()
            }
            val chapters = if (key.isNullOrBlank()) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            } else {
                appDb.bookChapterDao.search(book.bookUrl, key)
            }
            chapters
                .asSequence()
                .filterNot { it.isVolume }
                .mapNotNull { chapter ->
                    val cached = isChapterCached(book, chapter, cacheNames, validateImageContent = false)
                    if (cachedOnly && !cached) {
                        return@mapNotNull null
                    }
                    CacheChapterItem(chapter = chapter, cached = cached)
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

    private fun buildCacheBookItem(book: Book, mode: CacheManageMode): CacheBookItem? {
        val rawCachedCount = getFastCachedCount(book)
        if (rawCachedCount <= 0) return null
        val totalChapterCount = book.totalChapterNum.takeIf { it > 0 } ?: rawCachedCount
        val cachedCount = rawCachedCount.coerceAtMost(totalChapterCount)
        return CacheBookItem(
            book = book,
            mode = mode,
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount
        )
    }

    private fun getFastCachedCount(book: Book): Int {
        return getCacheFileNames(book).count { it.endsWith(".nb") }
    }

    private fun getCacheFileNames(book: Book): Set<String> {
        val cacheDir = BookHelp.getCacheDir(book)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return emptySet()
        return cacheDir.list()?.toSet().orEmpty()
    }

    private fun isChapterCached(
        book: Book,
        chapter: BookChapter,
        cacheNames: Set<String> = getCacheFileNames(book),
        validateImageContent: Boolean = true
    ): Boolean {
        if (book.isLocal) return false
        val hasContent = BookHelp.getChapterCacheFileNames(book, chapter).any(cacheNames::contains)
        return if (validateImageContent && book.isImage && hasContent) {
            BookHelp.hasImageContent(book, chapter)
        } else {
            hasContent
        }
    }

    private fun getBooks(mode: CacheManageMode): List<Book> {
        return when (mode) {
            CacheManageMode.BOOK -> appDb.bookDao.getByTypeOnLine(BookType.text)
            CacheManageMode.AUDIO -> appDb.bookDao.getByTypeOnLine(BookType.audio)
            CacheManageMode.MANGA -> appDb.bookDao.getByTypeOnLine(BookType.image)
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
