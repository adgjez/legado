package io.legado.app.help.book.library

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject

data class LibraryCloudSession(
    val config: LibraryContainerConfig?,
    val book: Book,
    val state: LibraryCloudState,
    val rootIndex: LibraryRootIndex? = null,
    val bookIndex: LibraryBookIndex? = null,
    val errorMessage: String? = null
) {

    val isEnabled: Boolean get() = config != null

    suspend fun downloadChapter(chapter: BookChapter): String? {
        return downloadCurrentChapter(chapter)
    }

    suspend fun downloadCurrentChapter(chapter: BookChapter): String? {
        val cfg = config ?: return null
        if (state != LibraryCloudState.READY) return null
        val backend = LibraryCloudBackend(cfg)
        val bookKey = LibraryCloudKeys.bookKey(book)
        for (matchKey in LibraryCloudKeys.matchKeys(chapter)) {
            val path = LibraryCloudPaths.currentChapterPath(bookKey, matchKey)
            val payload = readPayloadOrNull(backend, path) ?: continue
            if (LibraryCloudKeys.payloadMatches(book, chapter, payload)) {
                return payload.content.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    suspend fun listChapterVersions(chapter: BookChapter): List<LibraryCloudChapterVersion> {
        val cfg = config ?: return emptyList()
        if (state != LibraryCloudState.READY) return emptyList()
        val backend = LibraryCloudBackend(cfg)
        val bookKey = LibraryCloudKeys.bookKey(book)
        val versions = mutableListOf<LibraryCloudChapterVersion>()
        LibraryCloudKeys.matchKeys(chapter).forEach { matchKey ->
            val prefix = LibraryCloudPaths.variantsPrefix(bookKey, matchKey)
            val files = runCatching { backend.list(prefix) }.getOrElse {
                AppLog.put("列出书库章节版本失败 ${book.name} ${chapter.title}\n${it.localizedMessage}", it)
                emptyList()
            }
            files.forEach { file ->
                val payload = readPayloadOrNull(backend, file.path) ?: return@forEach
                if (LibraryCloudKeys.payloadMatches(book, chapter, payload)) {
                    versions += LibraryCloudChapterVersion(file.path, payload, matchKey.kind)
                }
            }
        }
        return versions
            .distinctBy { it.path }
            .sortedWith(
                compareBy<LibraryCloudChapterVersion> {
                    if (it.payload.sourceUrl == book.origin || it.payload.sourceUrl.isBlank()) 0 else 1
                }.thenBy { kotlin.math.abs(it.payload.chapterIndex - chapter.index) }
                    .thenBy { it.payload.sourceName.ifBlank { it.payload.sourceUrl } }
                    .thenByDescending { it.payload.updatedAt }
            )
    }

    suspend fun downloadChapter(version: LibraryCloudChapterVersion): String? {
        val cfg = config ?: return null
        val backend = LibraryCloudBackend(cfg)
        val payload = readPayloadOrNull(backend, version.path) ?: return null
        return payload.content.takeIf { it.isNotBlank() }
    }

    suspend fun downloadChapter(item: LibraryChapterItem): String? {
        if (!item.cached || item.remotePath.isBlank()) return null
        val cfg = config ?: return null
        return runCatching {
            val bytes = LibraryCloudBackend(cfg).download(item.remotePath)
            val json = LibraryCloudCrypto.decodeString(bytes, cfg.password)
            GSON.fromJsonObject<LibraryChapterPayload>(json).getOrThrow().content.takeIf { it.isNotBlank() }
        }.onFailure {
            AppLog.put("下载旧书库章节失败 ${book.name} ${item.title}\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    fun cachedItemFor(chapter: BookChapter): LibraryChapterItem? {
        val index = bookIndex ?: return null
        val key = LibraryCloudKeys.chapterKey(chapter)
        val title = LibraryCloudKeys.normalize(chapter.title)
        return index.chapters
            .asSequence()
            .filter { it.cached && it.remotePath.isNotBlank() }
            .filter { it.chapterKey == key || it.normalizedTitle == title }
            .sortedWith(
                compareBy<LibraryChapterItem> { if (it.sourceUrl == book.origin || it.sourceUrl.isBlank()) 0 else 1 }
                    .thenBy { kotlin.math.abs(it.chapterIndex - chapter.index) }
                    .thenByDescending { it.updatedAt }
            )
            .firstOrNull()
    }

    private suspend fun readPayloadOrNull(
        backend: LibraryCloudBackend,
        path: String
    ): LibraryChapterPayloadV2? {
        val cfg = config ?: return null
        return runCatching {
            val bytes = backend.downloadOrNull(path) ?: return null
            val json = LibraryCloudCrypto.decodeString(bytes, cfg.password)
            GSON.fromJsonObject<LibraryChapterPayloadV2>(json).getOrThrow()
        }.onFailure {
            AppLog.put("读取书库章节失败 ${book.name} $path\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    companion object {
        suspend fun open(book: Book, config: LibraryContainerConfig?): LibraryCloudSession {
            if (config == null) return LibraryCloudSession(null, book, LibraryCloudState.DISABLED)
            if (!NetworkUtils.isAvailable()) {
                return LibraryCloudSession(config, book, LibraryCloudState.ERROR, errorMessage = "网络不可用")
            }
            return LibraryCloudSession(config, book, LibraryCloudState.READY)
        }
    }
}

enum class LibraryCloudState {
    DISABLED,
    NO_ROOT_INDEX,
    NO_BOOK_MATCH,
    NO_BOOK_INDEX,
    READY,
    ERROR
}
