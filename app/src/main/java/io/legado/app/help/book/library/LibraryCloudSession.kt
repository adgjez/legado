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

    fun cachedItemFor(chapter: BookChapter): LibraryChapterItem? {
        val index = bookIndex ?: return null
        val key = LibraryCloudKeys.chapterKey(chapter)
        index.chapters.firstOrNull {
            it.chapterKey == key && it.cached && it.remotePath.isNotBlank()
        }?.let { return it }
        val title = LibraryCloudKeys.normalize(chapter.title)
        val urlHash = LibraryCloudKeys.urlHash(chapter)
        val strict = index.chapters.filter {
            it.cached &&
                it.remotePath.isNotBlank() &&
                it.chapterIndex == chapter.index &&
                it.normalizedTitle == title &&
                it.urlHash == urlHash
        }
        if (strict.size == 1) return strict.first()
        val byIndexTitle = index.chapters.filter {
            it.cached &&
                it.remotePath.isNotBlank() &&
                it.chapterIndex == chapter.index &&
                it.normalizedTitle == title
        }
        return byIndexTitle.singleOrNull()
    }

    suspend fun downloadChapter(chapter: BookChapter): String? {
        val item = cachedItemFor(chapter) ?: return null
        val cfg = config ?: return null
        return runCatching {
            val bytes = LibraryCloudBackend(cfg).download(item.remotePath)
            val json = LibraryCloudCrypto.decodeString(bytes, cfg.password)
            GSON.fromJsonObject<LibraryChapterPayload>(json).getOrThrow().content.takeIf { it.isNotBlank() }
        }.onFailure {
            AppLog.put("下载书库章节失败 ${book.name} ${chapter.title}\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    companion object {
        suspend fun open(book: Book, config: LibraryContainerConfig?): LibraryCloudSession {
            if (config == null) return LibraryCloudSession(null, book, LibraryCloudState.DISABLED)
            if (!NetworkUtils.isAvailable()) return LibraryCloudSession(config, book, LibraryCloudState.ERROR, errorMessage = "网络不可用")
            return runCatching {
                val backend = LibraryCloudBackend(config)
                val rootBytes = backend.downloadOrNull(LibraryCloudPaths.rootIndexPath())
                    ?: return LibraryCloudSession(config, book, LibraryCloudState.NO_ROOT_INDEX)
                val root = GSON.fromJsonObject<LibraryRootIndex>(
                    LibraryCloudCrypto.decodeString(rootBytes, config.password)
                ).getOrThrow()
                val normalizedName = LibraryCloudKeys.normalize(book.name)
                val normalizedAuthor = LibraryCloudKeys.normalize(book.getRealAuthor())
                val summary = root.books.firstOrNull {
                    it.normalizedName == normalizedName && it.normalizedAuthor == normalizedAuthor
                } ?: return LibraryCloudSession(config, book, LibraryCloudState.NO_BOOK_MATCH, rootIndex = root)
                val indexBytes = backend.downloadOrNull(summary.indexPath)
                    ?: return LibraryCloudSession(config, book, LibraryCloudState.NO_BOOK_INDEX, rootIndex = root)
                val bookIndex = GSON.fromJsonObject<LibraryBookIndex>(
                    LibraryCloudCrypto.decodeString(indexBytes, config.password)
                ).getOrThrow()
                LibraryCloudSession(config, book, LibraryCloudState.READY, root, bookIndex)
            }.getOrElse {
                AppLog.put("读取书库目录失败 ${book.name}\n${it.localizedMessage}", it)
                LibraryCloudSession(config, book, LibraryCloudState.ERROR, errorMessage = it.localizedMessage)
            }
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
