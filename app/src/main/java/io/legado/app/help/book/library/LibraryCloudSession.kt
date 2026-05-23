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
        downloadCurrentChapterV3(backend, chapter)?.let { return it }
        for (path in currentChapterPaths(chapter)) {
            val payload = readPayloadOrNull(backend, path) ?: continue
            if (LibraryCloudKeys.payloadMatches(book, chapter, payload)) {
                return payload.content.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private suspend fun downloadCurrentChapterV3(
        backend: LibraryCloudBackend,
        chapter: BookChapter
    ): String? {
        val manifest = readManifestOrNull(backend, v3ManifestPath(chapter)) ?: return null
        if (!LibraryCloudKeys.manifestMatches(book, chapter, manifest)) return null
        val variant = selectVariant(manifest, chapter) ?: return null
        val payload = readPayloadV3OrNull(backend, variant.payloadPath) ?: return null
        if (!LibraryCloudKeys.payloadMatches(book, chapter, payload)) return null
        return payload.content.takeIf { it.isNotBlank() }
    }

    suspend fun listChapterVersions(chapter: BookChapter): List<LibraryCloudChapterVersion> {
        val cfg = config ?: return emptyList()
        if (state != LibraryCloudState.READY) return emptyList()
        val backend = LibraryCloudBackend(cfg)
        val versions = mutableListOf<LibraryCloudChapterVersion>()
        LibraryCloudKeys.bookKeys(book).forEach { bookKey ->
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
        }
        return versions
            .distinctBy {
                listOf(
                    it.payload.sourceKey,
                    it.payload.sourceBookUrl,
                    it.payload.normalizedTitle,
                    it.payload.relaxedTitle,
                    it.payload.contentHash
                ).joinToString("\u001F")
            }
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

    private suspend fun readPayloadV3OrNull(
        backend: LibraryCloudBackend,
        path: String
    ): LibraryChapterPayloadV3? {
        val cfg = config ?: return null
        if (path.isBlank()) return null
        return runCatching {
            val bytes = backend.downloadOrNull(path) ?: return null
            val json = LibraryCloudCrypto.decodeString(bytes, cfg.password)
            GSON.fromJsonObject<LibraryChapterPayloadV3>(json).getOrThrow()
        }.onFailure {
            AppLog.put("读取书库v3章节失败 ${book.name} $path\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private suspend fun readManifestOrNull(
        backend: LibraryCloudBackend,
        path: String
    ): LibraryChapterManifestV3? {
        val cfg = config ?: return null
        return runCatching {
            val bytes = backend.downloadOrNull(path) ?: return null
            val json = LibraryCloudCrypto.decodeString(bytes, cfg.password)
            GSON.fromJsonObject<LibraryChapterManifestV3>(json).getOrThrow()
        }.onFailure {
            AppLog.put("读取书库v3清单失败 ${book.name} $path\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun selectVariant(
        manifest: LibraryChapterManifestV3,
        chapter: BookChapter
    ): LibraryChapterVariantV3? {
        return manifest.variants
            .filter { it.payloadPath.isNotBlank() && LibraryCloudKeys.variantMatches(chapter, it) }
            .sortedWith(
                compareBy<LibraryChapterVariantV3> {
                    if (it.sourceUrl == book.origin || it.sourceUrl.isBlank()) 0 else 1
                }.thenBy { kotlin.math.abs(it.chapterIndex - chapter.index) }
                    .thenByDescending { it.updatedAt }
            )
            .firstOrNull()
    }

    private fun v3ManifestPath(chapter: BookChapter): String {
        return LibraryCloudPaths.v3ManifestPath(
            LibraryCloudKeys.bookKey(book),
            LibraryCloudKeys.libraryChapterKey(chapter)
        )
    }

    private fun currentChapterPaths(chapter: BookChapter): List<String> {
        return LibraryCloudKeys.bookKeys(book).flatMap { bookKey ->
            LibraryCloudKeys.matchKeys(chapter).map { matchKey ->
                LibraryCloudPaths.currentChapterPath(bookKey, matchKey)
            }
        }.distinct()
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
    READY,
    ERROR
}
