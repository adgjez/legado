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
        if (!canUpload(book, chapter, content)) return
        val config = LibraryContainerManager.matchForSource(book.origin) ?: return
        val uploadContent = LibraryCloudContent.toUploadText(content)
        if (!shouldUploadContent(config, uploadContent)) return
        val lockKey = "${config.id}\u001F${LibraryCloudKeys.bookKey(book)}\u001F${LibraryCloudKeys.libraryChapterKey(chapter)}"
        val lock = uploadLocks.getOrPut(lockKey) { Mutex() }
        scope.launch {
            lock.withLock {
                runCatching {
                    uploadChapter(config, book, chapter, uploadContent)
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

    private fun canUpload(book: Book, chapter: BookChapter, content: String): Boolean {
        if (book.isLocal) return false
        if (chapter.isVolume) return false
        if (content.isBlank()) return false
        if (content.startsWith("获取正文失败") || content.startsWith("加载正文失败")) return false
        if (!NetworkUtils.isAvailable()) return false
        return true
    }

    private fun shouldUploadContent(config: LibraryContainerConfig, content: String): Boolean {
        if (content.isBlank()) return false
        val minChars = config.minUploadChars
        if (minChars <= 0) return true
        return LibraryCloudContent.meaningfulCharCount(content) >= minChars
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
        val bookKey = LibraryCloudKeys.bookKey(book)
        val chapterKey = LibraryCloudKeys.libraryChapterKey(chapter)
        val sourceKey = LibraryCloudKeys.sourceKey(book.origin)
        val now = System.currentTimeMillis()
        val manifestPath = LibraryCloudPaths.v3ManifestPath(bookKey, chapterKey)
        val payloadPath = LibraryCloudPaths.v3PayloadPath(bookKey, chapterKey, sourceKey)
        val payload = LibraryChapterPayloadV3(
            bookKey = bookKey,
            chapterKey = chapterKey,
            sourceKey = sourceKey,
            name = book.name,
            author = book.getRealAuthor(),
            normalizedName = LibraryCloudKeys.normalize(book.name),
            normalizedAuthor = LibraryCloudKeys.normalize(book.getRealAuthor()),
            title = chapter.title,
            normalizedTitle = LibraryCloudKeys.normalize(chapter.title),
            relaxedTitle = LibraryCloudKeys.relaxedTitle(chapter.title),
            ordinalTitle = LibraryCloudKeys.chapterOrdinal(chapter.title).orEmpty(),
            chapterIndex = chapter.index,
            sourceUrl = book.origin,
            sourceName = book.originName,
            sourceBookUrl = book.bookUrl,
            sourceChapterIdentity = chapter.contentCacheIdentity(),
            contentHash = LibraryCloudKeys.contentHash(content),
            content = content,
            updatedAt = now
        )
        val remoteManifest = readManifestOrNull(backend, config, manifestPath)
        if (remoteManifest?.variants?.any {
                it.sourceKey == sourceKey && it.contentHash == payload.contentHash && it.payloadPath.isNotBlank()
            } == true
        ) {
            return
        }
        val variant = payload.toVariant(payloadPath)
        backend.upload(
            payloadPath,
            LibraryCloudCrypto.encodeJson(payload, config.password, gzip = true),
            "application/json"
        )
        val manifest = mergeManifest(remoteManifest, payload, variant)
        backend.upload(
            manifestPath,
            LibraryCloudCrypto.encodeJson(manifest, config.password, gzip = true),
            "application/json"
        )
        sessions.remove(sessionKey(config, book))
    }

    private suspend fun readManifestOrNull(
        backend: LibraryCloudBackend,
        config: LibraryContainerConfig,
        path: String
    ): LibraryChapterManifestV3? {
        val bytes = backend.downloadOrNull(path) ?: return null
        return runCatching {
            val json = LibraryCloudCrypto.decodeString(bytes, config.password)
            GSON.fromJsonObject<LibraryChapterManifestV3>(json).getOrThrow()
        }.onFailure {
            AppLog.put("读取书库章节清单失败 $path\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun mergeManifest(
        remote: LibraryChapterManifestV3?,
        payload: LibraryChapterPayloadV3,
        variant: LibraryChapterVariantV3
    ): LibraryChapterManifestV3 {
        val variants = remote?.variants.orEmpty()
            .filter { it.sourceKey.isNotBlank() && it.sourceKey != variant.sourceKey }
            .plus(variant)
            .sortedWith(
                compareByDescending<LibraryChapterVariantV3> { it.updatedAt }
                    .thenBy { it.sourceName.ifBlank { it.sourceUrl } }
            )
        return LibraryChapterManifestV3(
            bookKey = payload.bookKey,
            name = payload.name,
            author = payload.author,
            normalizedName = payload.normalizedName,
            normalizedAuthor = payload.normalizedAuthor,
            chapterKey = payload.chapterKey,
            title = payload.title,
            normalizedTitle = payload.normalizedTitle,
            relaxedTitle = payload.relaxedTitle,
            ordinalTitle = payload.ordinalTitle,
            variants = variants,
            updatedAt = payload.updatedAt
        )
    }

    private fun LibraryChapterPayloadV3.toVariant(payloadPath: String): LibraryChapterVariantV3 {
        return LibraryChapterVariantV3(
            sourceKey = sourceKey,
            sourceUrl = sourceUrl,
            sourceName = sourceName,
            sourceBookUrl = sourceBookUrl,
            sourceChapterIdentity = sourceChapterIdentity,
            chapterIndex = chapterIndex,
            title = title,
            normalizedTitle = normalizedTitle,
            relaxedTitle = relaxedTitle,
            ordinalTitle = ordinalTitle,
            contentHash = contentHash,
            payloadPath = payloadPath,
            updatedAt = updatedAt
        )
    }
}
