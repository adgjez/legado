package io.legado.app.help.book.library

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookCloudEntryMode
import io.legado.app.help.book.BookCloudEntryModeStore
import io.legado.app.help.book.isLocal
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object LibraryCloudSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLocks = ConcurrentHashMap<String, Mutex>()
    private val sessions = ConcurrentHashMap<String, LibraryCloudSession>()
    private val activeBooks = ConcurrentHashMap<String, Boolean>()

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
        val config = readConfig(book)
        val key = sessionKey(config, book)
        if (key != null) {
            sessions[key]?.let { return it }
        }
        val session = LibraryCloudSession.open(book, config)
        if (key != null) {
            sessions[key] = session
        }
        return session
    }

    suspend fun refreshSession(book: Book): LibraryCloudSession {
        val config = readConfig(book)
        val key = sessionKey(config, book)
        if (key != null) sessions.remove(key)
        return openSession(book)
    }

    fun setCloudReadingActive(book: Book, active: Boolean) {
        val key = activeKey(book)
        if (active) {
            activeBooks[key] = true
        } else {
            activeBooks.remove(key)
        }
    }

    fun isCloudReadingActive(book: Book): Boolean {
        return activeBooks[activeKey(book)] == true
    }

    suspend fun tryCloudFirst(book: Book, chapter: BookChapter): String? {
        val session = openSession(book)
        return session.downloadCurrentChapter(chapter)
    }

    suspend fun tryCloudFallback(book: Book, chapter: BookChapter): String? {
        readConfig(book) ?: return null
        val session = openSession(book)
        return session.downloadCurrentChapter(chapter)
    }

    suspend fun tryActiveCloud(book: Book, chapter: BookChapter): String? {
        if (!isCloudReadingActive(book)) return null
        return tryCloudFirst(book, chapter)
    }

    private fun readConfig(book: Book): LibraryContainerConfig? {
        if (BookCloudEntryModeStore.get(book.bookUrl) != BookCloudEntryMode.LIBRARY_CHAPTER) {
            return null
        }
        return LibraryContainerManager.readContainer()
    }

    private fun shouldUpload(book: Book, chapter: BookChapter, content: String): Boolean {
        if (book.isLocal) return false
        if (chapter.isVolume) return false
        if (content.isBlank()) return false
        if (content.startsWith("获取正文失败") || content.startsWith("加载正文失败")) return false
        if (!NetworkUtils.isAvailable()) return false
        return true
    }

    private fun sessionKey(config: LibraryContainerConfig?, book: Book): String? {
        config ?: return null
        return "${config.id}\u001F${LibraryCloudKeys.sharedBookKey(book)}"
    }

    private fun activeKey(book: Book): String {
        return "${LibraryCloudKeys.bookKey(book)}\u001F${book.bookUrl}"
    }

    private suspend fun uploadChapter(
        config: LibraryContainerConfig,
        book: Book,
        chapter: BookChapter,
        content: String
    ) {
        val backend = LibraryCloudBackend(config)
        val exactBookKey = LibraryCloudKeys.bookKey(book)
        val chapterKey = LibraryCloudKeys.chapterKey(chapter)
        val sourceKey = LibraryCloudKeys.sourceKey(book.origin)
        val now = System.currentTimeMillis()
        val payload = LibraryChapterPayloadV2(
            bookKey = exactBookKey,
            chapterKey = chapterKey,
            titleKey = LibraryCloudKeys.titleKey(chapter),
            relaxedTitleKey = LibraryCloudKeys.relaxedTitleKey(chapter),
            sourceKey = sourceKey,
            name = book.name,
            author = book.getRealAuthor(),
            normalizedName = LibraryCloudKeys.normalize(book.name),
            normalizedAuthor = LibraryCloudKeys.normalize(book.getRealAuthor()),
            title = chapter.title,
            normalizedTitle = LibraryCloudKeys.normalize(chapter.title),
            relaxedTitle = LibraryCloudKeys.relaxedTitle(chapter.title),
            chapterIndex = chapter.index,
            sourceUrl = book.origin,
            sourceName = book.originName,
            sourceBookUrl = book.bookUrl,
            sourceChapterIdentity = chapter.contentCacheIdentity(),
            contentHash = LibraryCloudKeys.contentHash(content),
            content = content,
            updatedAt = now
        )
        val bytes = LibraryCloudCrypto.encodeJson(payload, config.password, gzip = true)
        LibraryCloudKeys.bookKeys(book).forEach { bookKey ->
            val wroteVariant = LibraryCloudKeys.variantMatchKeys(chapter)
                .map { matchKey ->
                    val variantPath = LibraryCloudPaths.variantChapterPath(bookKey, matchKey, sourceKey)
                    if (remoteHasSameContent(backend, config, variantPath, payload)) {
                        false
                    } else {
                        backend.upload(variantPath, bytes, "application/json")
                        true
                    }
                }
                .any { it }
            LibraryCloudKeys.matchKeys(chapter).forEach { matchKey ->
                val currentPath = LibraryCloudPaths.currentChapterPath(bookKey, matchKey)
                if (wroteVariant || !backend.exists(currentPath)) {
                    backend.upload(currentPath, bytes, "application/json")
                }
            }
        }
        sessions.remove(sessionKey(config, book))
    }

    private suspend fun remoteHasSameContent(
        backend: LibraryCloudBackend,
        config: LibraryContainerConfig,
        path: String,
        payload: LibraryChapterPayloadV2
    ): Boolean {
        val bytes = backend.downloadOrNull(path) ?: return false
        return runCatching {
            val json = LibraryCloudCrypto.decodeString(bytes, config.password)
            val remote = GSON.fromJsonObject<LibraryChapterPayloadV2>(json).getOrThrow()
            remote.sourceKey == payload.sourceKey &&
                remote.normalizedTitle == payload.normalizedTitle &&
                remote.relaxedTitle == payload.relaxedTitle &&
                remote.contentHash == payload.contentHash
        }.getOrDefault(false)
    }
}
