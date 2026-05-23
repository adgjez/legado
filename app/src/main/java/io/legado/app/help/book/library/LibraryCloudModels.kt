package io.legado.app.help.book.library

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.lib.cloud.S3Container
import io.legado.app.utils.MD5Utils
import java.text.Normalizer
import java.util.Locale

data class LibraryContainerConfig(
    val container: S3Container = S3Container(prefix = LibraryCloudPaths.ROOT_DIR),
    val password: String? = null,
    val sourceUrls: Set<String> = emptySet()
) {
    val id: String get() = container.id

    fun normalized(): LibraryContainerConfig {
        return copy(
            container = container.normalized().copy(
                prefix = container.prefix.trim().trim('/').ifBlank { LibraryCloudPaths.ROOT_DIR }
            ),
            password = password?.takeIf { it.isNotBlank() },
            sourceUrls = sourceUrls.filter { it.isNotBlank() }.toSet()
        )
    }

    fun matchesSource(sourceUrl: String?): Boolean {
        return !sourceUrl.isNullOrBlank() && sourceUrls.contains(sourceUrl)
    }
}

data class LibraryChapterPayloadV2(
    val version: Int = 2,
    val bookKey: String = "",
    val chapterKey: String = "",
    val titleKey: String = "",
    val relaxedTitleKey: String = "",
    val sourceKey: String = "",
    val name: String = "",
    val author: String = "",
    val normalizedName: String = "",
    val normalizedAuthor: String = "",
    val title: String = "",
    val normalizedTitle: String = "",
    val relaxedTitle: String = "",
    val chapterIndex: Int = -1,
    val sourceUrl: String = "",
    val sourceName: String = "",
    val sourceBookUrl: String = "",
    val sourceChapterIdentity: String = "",
    val contentHash: String = "",
    val content: String = "",
    val updatedAt: Long = 0L
)

data class LibraryCloudMatchKey(
    val kind: String,
    val key: String
)

data class LibraryCloudChapterVersion(
    val path: String,
    val payload: LibraryChapterPayloadV2,
    val matchKind: String
)

object LibraryCloudPaths {
    const val ROOT_DIR = "Library"
    const val V2_DIR = "v2"

    fun currentChapterPath(bookKey: String, matchKey: LibraryCloudMatchKey): String {
        return "$V2_DIR/books/$bookKey/chapters/${matchKey.kind}/${matchKey.key}/current.json.gz"
    }

    fun variantChapterPath(
        bookKey: String,
        matchKey: LibraryCloudMatchKey,
        sourceKey: String
    ): String {
        return "$V2_DIR/books/$bookKey/chapters/${matchKey.kind}/${matchKey.key}/variants/$sourceKey/current.json.gz"
    }

    fun variantsPrefix(bookKey: String, matchKey: LibraryCloudMatchKey): String {
        return "$V2_DIR/books/$bookKey/chapters/${matchKey.kind}/${matchKey.key}/variants/"
    }
}

object LibraryCloudKeys {
    fun normalize(value: String?): String {
        return Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
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

    fun sharedBookKey(book: Book): String {
        return sharedBookKey(book.name)
    }

    fun sharedBookKey(name: String): String {
        return MD5Utils.md5Encode("name\u001F${normalizeBookName(name)}")
    }

    fun bookKeys(book: Book): List<String> {
        return listOf(bookKey(book), sharedBookKey(book)).distinct()
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

    fun titleKey(chapter: BookChapter): String {
        return MD5Utils.md5Encode(normalize(chapter.title))
    }

    fun relaxedTitleKey(chapter: BookChapter): String {
        return MD5Utils.md5Encode(relaxedTitle(chapter.title))
    }

    fun sourceKey(sourceUrl: String?): String {
        return MD5Utils.md5Encode(normalize(sourceUrl))
    }

    fun matchKeys(chapter: BookChapter): List<LibraryCloudMatchKey> {
        val strict = LibraryCloudMatchKey("title", titleKey(chapter))
        val relaxed = LibraryCloudMatchKey("relaxed", relaxedTitleKey(chapter))
        return listOf(strict, relaxed).distinctBy { "${it.kind}\u001F${it.key}" }
    }

    fun variantMatchKeys(chapter: BookChapter): List<LibraryCloudMatchKey> {
        return matchKeys(chapter).sortedBy { if (it.kind == "relaxed") 0 else 1 }.take(1)
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

    fun contentHash(content: String): String {
        return MD5Utils.md5Encode(content)
    }

    fun relaxedTitle(value: String?): String {
        val normalized = normalize(value)
        val converted = Regex("[零〇一二两三四五六七八九十百千万]+").replace(normalized) {
            chineseNumberToLong(it.value)?.toString() ?: it.value
        }
        return converted
            .replace(Regex("(chapter|chap|volume|vol|正文|章节|第|章|回|节|卷|集|部|篇)"), "")
            .replace(Regex("[\\p{Punct}《》“”‘’（）【】、，。！？：；—·…]+"), "")
    }

    fun normalizeBookName(value: String?): String {
        return normalize(value)
            .replace(Regex("[\\p{Punct}《》“”‘’（）【】、，。！？：；—·…]+"), "")
    }

    fun payloadMatches(book: Book, chapter: BookChapter, payload: LibraryChapterPayloadV2): Boolean {
        if (payload.content.isBlank()) return false
        if (payload.name.isNotBlank() && normalizeBookName(payload.name) != normalizeBookName(book.name)) {
            return false
        }
        val strict = normalize(chapter.title)
        val relaxed = relaxedTitle(chapter.title)
        val payloadStrict = payload.normalizedTitle.ifBlank { normalize(payload.title) }
        val payloadRelaxed = payload.relaxedTitle.ifBlank { relaxedTitle(payload.title) }
        return (strict.isNotBlank() && strict == payloadStrict) ||
            (relaxed.isNotBlank() && relaxed == payloadRelaxed)
    }

    private fun chineseNumberToLong(value: String): Long? {
        if (value.isBlank()) return null
        var result = 0L
        var section = 0L
        var number = 0L
        value.forEach { char ->
            when (char) {
                '零', '〇' -> number = 0
                '一' -> number = 1
                '二', '两' -> number = 2
                '三' -> number = 3
                '四' -> number = 4
                '五' -> number = 5
                '六' -> number = 6
                '七' -> number = 7
                '八' -> number = 8
                '九' -> number = 9
                '十' -> {
                    section += (number.takeIf { it > 0 } ?: 1) * 10
                    number = 0
                }
                '百' -> {
                    section += (number.takeIf { it > 0 } ?: 1) * 100
                    number = 0
                }
                '千' -> {
                    section += (number.takeIf { it > 0 } ?: 1) * 1000
                    number = 0
                }
                '万' -> {
                    result += (section + number).coerceAtLeast(1) * 10000
                    section = 0
                    number = 0
                }
                else -> return null
            }
        }
        return result + section + number
    }
}
