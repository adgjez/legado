package io.legado.app.help.book.library

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isLocal
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object LibraryCloudSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLocks = ConcurrentHashMap<String, Mutex>()
    private val pendingIndexJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun enqueueUpload(book: Book, chapter: BookChapter, content: String) {
        if (!shouldUpload(book, chapter, content)) return
        val config = LibraryContainerManager.matchForSource(book.origin) ?: return
        val lockKey = "${config.id}\u001F${book.bookUrl}\u001F${chapter.index}"
        val lock = uploadLocks.getOrPut(lockKey) { Mutex() }
        scope.launch {
            lock.withLock {
                runCatching {
                    uploadChapter(config, book, chapter, content)
                }.onFailure {
                    AppLog.put("上传书库章节失败 ${book.name} ${chapter.title}\n${it.localizedMessage}", it)
                }
            }
        }
    }

    suspend fun openSession(book: Book): LibraryCloudSession {
        val config = LibraryContainerManager.matchForSource(book.origin)
        return LibraryCloudSession.open(book, config)
    }

    suspend fun tryCloudFirst(book: Book, chapter: BookChapter): String? {
        val session = openSession(book)
        return session.downloadChapter(chapter)
    }

    suspend fun tryCloudFallback(book: Book, chapter: BookChapter): String? {
        val config = LibraryContainerManager.matchForSource(book.origin)
            ?.takeIf { it.priority == LibrarySyncPriority.SOURCE_FIRST }
            ?: return null
        val session = LibraryCloudSession.open(book, config)
        return session.downloadChapter(chapter)
    }

    private fun shouldUpload(book: Book, chapter: BookChapter, content: String): Boolean {
        if (book.isLocal) return false
        if (chapter.isVolume) return false
        if (content.isBlank()) return false
        if (content.startsWith("获取正文失败") || content.startsWith("加载正文失败")) return false
        if (!NetworkUtils.isAvailable()) return false
        return true
    }

    private suspend fun uploadChapter(
        config: LibraryContainerConfig,
        book: Book,
        chapter: BookChapter,
        content: String
    ) {
        val backend = LibraryCloudBackend(config)
        val bookKey = LibraryCloudKeys.bookKey(book)
        val chapterKey = LibraryCloudKeys.chapterKey(chapter)
        val chapterPath = LibraryCloudPaths.chapterPath(bookKey, chapterKey)
        val now = System.currentTimeMillis()
        val payload = LibraryChapterPayload(
            bookKey = bookKey,
            chapterKey = chapterKey,
            name = book.name,
            author = book.getRealAuthor(),
            title = chapter.title,
            content = content,
            updatedAt = now
        )
        backend.upload(
            chapterPath,
            LibraryCloudCrypto.encodeJson(payload, config.password),
            "application/json"
        )
        scheduleIndexUpdate(config, book, chapter, chapterPath, now)
    }

    private fun scheduleIndexUpdate(
        config: LibraryContainerConfig,
        book: Book,
        chapter: BookChapter,
        chapterPath: String,
        updatedAt: Long
    ) {
        val key = "${config.id}\u001F${book.bookUrl}"
        pendingIndexJobs.remove(key)?.cancel()
        pendingIndexJobs[key] = scope.launch {
            delay(4000)
            runCatching {
                upsertIndexes(config, book, chapter, chapterPath, updatedAt)
            }.onFailure {
                AppLog.put("更新书库索引失败 ${book.name}\n${it.localizedMessage}", it)
            }.also {
                pendingIndexJobs.remove(key)
            }
        }
    }

    private suspend fun upsertIndexes(
        config: LibraryContainerConfig,
        book: Book,
        chapter: BookChapter,
        chapterPath: String,
        updatedAt: Long
    ) {
        val backend = LibraryCloudBackend(config)
        val bookKey = LibraryCloudKeys.bookKey(book)
        val rootIndex = downloadRootIndex(backend, config).upsertBook(book, bookKey, updatedAt)
        val localChapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val oldBookIndex = downloadBookIndex(backend, config, bookKey)
        val oldMap = oldBookIndex.chapters.associateBy { it.chapterKey }
        val nextChapters = localChapters.map { item ->
            val key = LibraryCloudKeys.chapterKey(item)
            val old = oldMap[key]
            val isCurrent = item.index == chapter.index
            LibraryChapterItem(
                chapterKey = key,
                chapterIndex = item.index,
                title = item.title,
                normalizedTitle = LibraryCloudKeys.normalize(item.title),
                urlHash = LibraryCloudKeys.urlHash(item),
                identityHash = LibraryCloudKeys.identityHash(item),
                remotePath = if (isCurrent) chapterPath else old?.remotePath.orEmpty(),
                cached = isCurrent || old?.cached == true,
                updatedAt = if (isCurrent) updatedAt else old?.updatedAt ?: 0L
            )
        }
        val bookIndex = LibraryBookIndex(
            bookKey = bookKey,
            name = book.name,
            author = book.getRealAuthor(),
            normalizedName = LibraryCloudKeys.normalize(book.name),
            normalizedAuthor = LibraryCloudKeys.normalize(book.getRealAuthor()),
            chapters = nextChapters,
            updatedAt = updatedAt
        )
        backend.upload(
            LibraryCloudPaths.bookIndexPath(bookKey),
            LibraryCloudCrypto.encodeJson(bookIndex, config.password),
            "application/json"
        )
        backend.upload(
            LibraryCloudPaths.rootIndexPath(),
            LibraryCloudCrypto.encodeJson(rootIndex, config.password),
            "application/json"
        )
    }

    private suspend fun downloadRootIndex(backend: LibraryCloudBackend, config: LibraryContainerConfig): LibraryRootIndex {
        val bytes = backend.downloadOrNull(LibraryCloudPaths.rootIndexPath()) ?: return LibraryRootIndex()
        return GSON.fromJsonObject<LibraryRootIndex>(LibraryCloudCrypto.decodeString(bytes, config.password))
            .getOrDefault(LibraryRootIndex())
    }

    private suspend fun downloadBookIndex(
        backend: LibraryCloudBackend,
        config: LibraryContainerConfig,
        bookKey: String
    ): LibraryBookIndex {
        val bytes = backend.downloadOrNull(LibraryCloudPaths.bookIndexPath(bookKey)) ?: return LibraryBookIndex(bookKey = bookKey)
        return GSON.fromJsonObject<LibraryBookIndex>(LibraryCloudCrypto.decodeString(bytes, config.password))
            .getOrDefault(LibraryBookIndex(bookKey = bookKey))
    }

    private fun LibraryRootIndex.upsertBook(book: Book, bookKey: String, updatedAt: Long): LibraryRootIndex {
        val summary = LibraryBookSummary(
            bookKey = bookKey,
            name = book.name,
            author = book.getRealAuthor(),
            normalizedName = LibraryCloudKeys.normalize(book.name),
            normalizedAuthor = LibraryCloudKeys.normalize(book.getRealAuthor()),
            indexPath = LibraryCloudPaths.bookIndexPath(bookKey),
            updatedAt = updatedAt
        )
        val map = books.associateBy { it.bookKey }.toMutableMap()
        map[bookKey] = summary
        return copy(books = map.values.sortedByDescending { it.updatedAt }, updatedAt = updatedAt)
    }
}
