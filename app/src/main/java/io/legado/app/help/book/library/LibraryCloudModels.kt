package io.legado.app.help.book.library

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.lib.cloud.S3Container
import io.legado.app.utils.MD5Utils
import java.util.Locale

data class LibraryContainerConfig(
    val container: S3Container = S3Container(prefix = LibraryCloudPaths.ROOT_DIR),
    val password: String? = null,
    val sourceUrls: Set<String> = emptySet(),
    val priority: LibrarySyncPriority = LibrarySyncPriority.SOURCE_FIRST
) {
    val id: String get() = container.id

    fun normalized(): LibraryContainerConfig {
        return copy(
            container = container.normalized().copy(
                prefix = container.prefix.trim().trim('/').ifBlank { LibraryCloudPaths.ROOT_DIR }
            ),
            password = password?.takeIf { it.isNotBlank() },
            sourceUrls = sourceUrls.filter { it.isNotBlank() }.toSet(),
            priority = LibrarySyncPriority.SOURCE_FIRST
        )
    }

    fun matchesSource(sourceUrl: String?): Boolean {
        return !sourceUrl.isNullOrBlank() && sourceUrls.contains(sourceUrl)
    }
}

enum class LibrarySyncPriority {
    SOURCE_FIRST,
    CLOUD_FIRST
}

data class LibraryRootIndex(
    val version: Int = 1,
    val books: List<LibraryBookSummary> = emptyList(),
    val updatedAt: Long = 0L
)

data class LibraryBookSummary(
    val bookKey: String = "",
    val name: String = "",
    val author: String = "",
    val normalizedName: String = "",
    val normalizedAuthor: String = "",
    val indexPath: String = "",
    val updatedAt: Long = 0L
)

data class LibraryBookIndex(
    val version: Int = 1,
    val bookKey: String = "",
    val name: String = "",
    val author: String = "",
    val normalizedName: String = "",
    val normalizedAuthor: String = "",
    val chapters: List<LibraryChapterItem> = emptyList(),
    val updatedAt: Long = 0L
)

data class LibraryChapterItem(
    val chapterKey: String = "",
    val chapterIndex: Int = 0,
    val title: String = "",
    val normalizedTitle: String = "",
    val urlHash: String = "",
    val identityHash: String = "",
    val sourceUrl: String = "",
    val sourceName: String = "",
    val sourceBookUrl: String = "",
    val remotePath: String = "",
    val cached: Boolean = false,
    val updatedAt: Long = 0L
)

data class LibraryChapterPayload(
    val version: Int = 1,
    val bookKey: String = "",
    val chapterKey: String = "",
    val name: String = "",
    val author: String = "",
    val title: String = "",
    val content: String = "",
    val updatedAt: Long = 0L
)

object LibraryCloudPaths {
    const val ROOT_DIR = "Library"
    const val ROOT_INDEX = "library_index.json"

    fun rootIndexPath(): String = ROOT_INDEX

    fun bookIndexPath(bookKey: String): String = "books/$bookKey/index.json"

    fun chapterPath(bookKey: String, chapterKey: String): String {
        return "books/$bookKey/chapters/$chapterKey.json"
    }
}

object LibraryCloudKeys {
    fun normalize(value: String?): String {
        return value.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
    }

    fun bookKey(book: Book): String {
        return bookKey(book.name, book.getRealAuthor())
    }

    fun bookKey(name: String, author: String): String {
        return MD5Utils.md5Encode("${normalize(name)}\u001F${normalize(author)}")
    }

    fun chapterKey(chapter: BookChapter): String {
        return MD5Utils.md5Encode(
            listOf(
                chapter.index.toString(),
                normalize(chapter.title),
                chapter.contentCacheIdentity()
            ).joinToString("\u001F")
        )
    }

    fun sourceChapterKey(sourceUrl: String, chapterKey: String): String {
        return MD5Utils.md5Encode("${normalize(sourceUrl)}\u001F$chapterKey")
    }

    fun urlHash(chapter: BookChapter): String {
        return MD5Utils.md5Encode(runCatching { chapter.getAbsoluteURL() }.getOrElse { chapter.url })
    }

    fun identityHash(chapter: BookChapter): String {
        return MD5Utils.md5Encode(chapter.contentCacheIdentity())
    }
}
