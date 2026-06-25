package io.legado.app.help.ai

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.webkit.URLUtil
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedVideo
import io.legado.app.data.entities.AiVideoGroup
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object AiVideoGalleryManager {

    const val DEFAULT_GROUP_ID = "default"
    const val VIDEO_URI_PREFIX = "ai-video://"
    const val SOURCE_TYPE_CHAT = "chat"
    const val SOURCE_TYPE_READ_INSERT = "read_insert"
    const val SOURCE_TYPE_CHARACTER_AVATAR = "character_avatar"
    const val SOURCE_TYPE_STORY_MODE = "story_mode"
    const val SOURCE_TYPE_GALLERY = "gallery"
    private const val DEFAULT_GROUP_NAME = "默认分组"
    private const val TEMP_KEEP_DAYS = 3L
    private const val MAX_VIDEO_BYTES = 200L * 1024 * 1024 // 200MB

    private val videoDir: File
        get() = File(appCtx.filesDir, "ai_videos").apply { mkdirs() }

    private val thumbsDir: File
        get() = File(videoDir, "thumbs").apply { mkdirs() }

    data class VideoMetadata(
        val bookName: String = "",
        val bookAuthor: String = "",
        val chapterIndex: Int = -1,
        val chapterTitle: String = "",
        val sourceType: String = "",
        val sourceText: String = ""
    ) {
        val bookKey: String
            get() = buildBookKey(bookName, bookAuthor)

        val chapterKey: String
            get() = buildChapterKey(bookKey, chapterIndex, chapterTitle)
    }

    suspend fun saveGeneratedVideo(
        videoSource: String,
        prompt: String,
        provider: AiVideoProviderConfig,
        model: String? = null,
        metadata: VideoMetadata = VideoMetadata()
    ): AiGeneratedVideo = withContext(Dispatchers.IO) {
        ensureDefaultGroup()
        cleanupExpiredTemporary()
        val id = UUID.randomUUID().toString()
        val file = File(videoDir, "$id.mp4")
        val byteCount = runCatching {
            writeVideoToFile(videoSource, provider, file)
        }.onFailure {
            runCatching { file.delete() }
        }.getOrThrow()
        if (byteCount <= 0L) {
            runCatching { file.delete() }
            error("Empty video body")
        }
        val thumbnailPath = runCatching {
            extractThumbnail(file.absolutePath)
        }.getOrElse { "" }
        val now = System.currentTimeMillis()
        val video = AiGeneratedVideo(
            id = id,
            name = promptName(prompt),
            prompt = prompt,
            providerId = provider.id,
            providerName = provider.displayName(),
            model = model?.takeIf { it.isNotBlank() }
                ?: provider.model.ifBlank { if (provider.type == AiVideoProviderConfig.TYPE_OPENAI) "sora" else "JS" },
            localPath = file.absolutePath,
            thumbnailPath = thumbnailPath,
            originalSource = sourceSummary(videoSource),
            bookKey = metadata.bookKey,
            bookName = metadata.bookName.trim(),
            bookAuthor = metadata.bookAuthor.trim(),
            chapterKey = metadata.chapterKey,
            chapterIndex = metadata.chapterIndex,
            chapterTitle = metadata.chapterTitle.trim(),
            sourceType = metadata.sourceType.trim(),
            sourceText = metadata.sourceText.trim().take(2000),
            createdAt = now,
            updatedAt = now
        )
        runCatching {
            appDb.aiGeneratedVideoDao.insert(video)
        }.onFailure {
            runCatching { file.delete() }
            runCatching { if (thumbnailPath.isNotBlank()) File(thumbnailPath).delete() }
        }.getOrThrow()
        video
    }

    suspend fun cleanupExpiredTemporary() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - TEMP_KEEP_DAYS * 24L * 60L * 60L * 1000L
        appDb.aiGeneratedVideoDao.expiredTemporary(cutoff).forEach { video ->
            deleteVideoFile(video)
            appDb.aiGeneratedVideoDao.delete(video.id)
        }
        reconcileStorage()
    }

    fun ensureDefaultGroup() {
        if (appDb.aiVideoGroupDao.get(DEFAULT_GROUP_ID) == null) {
            appDb.aiVideoGroupDao.insert(
                AiVideoGroup(
                    id = DEFAULT_GROUP_ID,
                    name = DEFAULT_GROUP_NAME,
                    sortOrder = 0
                )
            )
        }
    }

    fun listGroups(): List<AiVideoGroup> {
        ensureDefaultGroup()
        return appDb.aiVideoGroupDao.all()
    }

    fun createGroup(name: String): AiVideoGroup {
        ensureDefaultGroup()
        val cleanName = name.trim().ifBlank { DEFAULT_GROUP_NAME }
        val group = AiVideoGroup(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            sortOrder = appDb.aiVideoGroupDao.all().size
        )
        appDb.aiVideoGroupDao.insert(group)
        return group
    }

    fun getVideo(id: String): AiGeneratedVideo? {
        val video = appDb.aiGeneratedVideoDao.get(id) ?: return null
        if (!File(video.localPath).isFile) {
            appDb.aiGeneratedVideoDao.delete(id)
            return null
        }
        return video
    }

    fun videoUri(id: String): String {
        return VIDEO_URI_PREFIX + id.trim()
    }

    fun videoIdFromUri(src: String?): String? {
        val value = src?.trim().orEmpty()
        if (!value.startsWith(VIDEO_URI_PREFIX, ignoreCase = true)) return null
        return value.substring(VIDEO_URI_PREFIX.length).substringBefore('?').trim().takeIf { it.isNotBlank() }
    }

    fun resolveVideoFile(src: String?): File? {
        val id = videoIdFromUri(src) ?: return null
        val video = getVideo(id) ?: return null
        val file = File(video.localPath).takeIf { it.isFile } ?: return null
        appDb.aiGeneratedVideoDao.touchAccess(id, System.currentTimeMillis())
        return file
    }

    fun resolveThumbnailPath(id: String): String? {
        val video = getVideo(id) ?: return null
        return video.thumbnailPath.takeIf { it.isNotBlank() && File(it).isFile }
    }

    fun listVideos(filter: GalleryFilter): List<AiGeneratedVideo> {
        ensureDefaultGroup()
        return when (filter) {
            GalleryFilter.ALL -> appDb.aiGeneratedVideoDao.all()
            GalleryFilter.TEMPORARY -> appDb.aiGeneratedVideoDao.temporary()
            GalleryFilter.FAVORITE -> appDb.aiGeneratedVideoDao.favorites()
            is GalleryFilter.GROUP -> appDb.aiGeneratedVideoDao.byGroup(filter.groupId)
            is GalleryFilter.BOOK -> appDb.aiGeneratedVideoDao.byBook(filter.bookKey)
            is GalleryFilter.CHAPTER -> appDb.aiGeneratedVideoDao.byChapter(filter.chapterKey)
            is GalleryFilter.SOURCE_TYPE -> appDb.aiGeneratedVideoDao.bySourceType(filter.sourceType)
            is GalleryFilter.SEARCH -> appDb.aiGeneratedVideoDao.search("%${filter.keyword.trim()}%")
        }.filter { video ->
            val exists = File(video.localPath).isFile
            if (!exists) appDb.aiGeneratedVideoDao.delete(video.id)
            exists
        }
    }

    fun renameVideo(id: String, name: String) {
        val cleanName = name.trim().ifBlank { return }
        appDb.aiGeneratedVideoDao.rename(id, cleanName, System.currentTimeMillis())
    }

    fun setFavorite(id: String, favorite: Boolean, groupId: String?) {
        ensureDefaultGroup()
        val targetGroupId = if (favorite) {
            groupId?.takeIf { appDb.aiVideoGroupDao.get(it) != null } ?: DEFAULT_GROUP_ID
        } else {
            null
        }
        appDb.aiGeneratedVideoDao.setFavorite(id, favorite, targetGroupId, System.currentTimeMillis())
    }

    fun deleteGroup(id: String) {
        if (id == DEFAULT_GROUP_ID) return
        ensureDefaultGroup()
        appDb.runInTransaction {
            appDb.aiGeneratedVideoDao.moveGroup(id, DEFAULT_GROUP_ID, System.currentTimeMillis())
            appDb.aiVideoGroupDao.delete(id)
        }
    }

    fun deleteVideo(id: String) {
        val video = appDb.aiGeneratedVideoDao.get(id) ?: return
        deleteVideoFile(video)
        appDb.aiGeneratedVideoDao.delete(id)
    }

    fun moveVideosToGroup(ids: Collection<String>, groupId: String?) {
        ids.forEach { id ->
            setFavorite(id, true, groupId)
        }
    }

    fun deleteVideos(ids: Collection<String>) {
        ids.forEach(::deleteVideo)
    }

    private fun deleteVideoFile(video: AiGeneratedVideo) {
        runCatching {
            val file = File(video.localPath)
            if (file.isFile && file.parentFile?.canonicalPath == videoDir.canonicalPath) {
                file.delete()
            }
            if (video.thumbnailPath.isNotBlank()) {
                val thumb = File(video.thumbnailPath)
                if (thumb.isFile && thumb.parentFile?.canonicalPath == thumbsDir.canonicalPath) {
                    thumb.delete()
                }
            }
        }.onFailure {
            AppLog.put("删除 AI 视频失败: ${video.localPath}", it)
        }
    }

    private suspend fun writeVideoToFile(
        videoSource: String,
        provider: AiVideoProviderConfig,
        target: File
    ): Long {
        if (URLUtil.isValidUrl(videoSource)) {
            provider.videoDownloadClient().newCallResponse {
                url(videoSource)
                addHeaders(AiChatService.parseCustomHeaders(provider.headers))
            }.use { response ->
                if (!response.isSuccessful) error("${response.code} ${response.message}")
                response.body.contentLength().takeIf { it > MAX_VIDEO_BYTES }?.let {
                    error("Video is too large: $it bytes")
                }
                return copyToFileLimited(response.body.byteStream(), target)
            }
        }
        val file = File(videoSource)
        if (file.isFile) {
            if (file.length() > MAX_VIDEO_BYTES) error("Video is too large: ${file.length()} bytes")
            return copyToFileLimited(file.inputStream(), target)
        }
        error(
            "Unsupported video result: provider=${provider.displayName()}, " +
                "type=${provider.type}, source=${sourceSummary(videoSource)}"
        )
    }

    private fun copyToFileLimited(input: InputStream, target: File): Long {
        var copied = 0L
        target.outputStream().use { output ->
            input.use {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > MAX_VIDEO_BYTES) error("Video is too large: $copied bytes")
                    output.write(buffer, 0, read)
                }
            }
        }
        return copied
    }

    private fun extractThumbnail(videoPath: String): String {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            retriever.setDataSource(videoPath)
            frame = retriever.getFrameAtTime(0) ?: return ""
            scaled = Bitmap.createScaledBitmap(frame, 320, 180, true)
            val thumbFile = File(thumbsDir, "${UUID.randomUUID()}.jpg")
            thumbFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            thumbFile.absolutePath
        } catch (e: Exception) {
            AppLog.put("抽取视频缩略图失败: $videoPath", e)
            ""
        } finally {
            scaled?.recycle()
            frame?.recycle()
            runCatching { retriever.release() }
        }
    }

    private fun reconcileStorage() {
        val videos = appDb.aiGeneratedVideoDao.all()
        videos.forEach { video ->
            if (!File(video.localPath).isFile) {
                appDb.aiGeneratedVideoDao.delete(video.id)
            }
        }
        val validPaths = mutableSetOf<String>()
        videos.forEach { video ->
            runCatching { File(video.localPath).canonicalPath }.getOrNull()?.let { validPaths.add(it) }
            if (video.thumbnailPath.isNotBlank()) {
                runCatching { File(video.thumbnailPath).canonicalPath }.getOrNull()?.let { validPaths.add(it) }
            }
        }
        listOf(videoDir, thumbsDir).forEach { dir ->
            dir.listFiles()
                ?.filter { it.isFile }
                ?.forEach { file ->
                    val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
                    if (canonicalPath !in validPaths) {
                        runCatching { file.delete() }
                    }
                }
        }
    }

    private fun AiVideoProviderConfig.videoDownloadClient(): OkHttpClient {
        val timeout = validTimeout()
        return okHttpClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun promptName(prompt: String): String {
        return prompt.lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .trim()
            .take(36)
            .ifBlank { "AI 视频" }
    }

    private fun sourceSummary(source: String): String {
        if (source.startsWith("data:", true)) {
            return source.substringBefore(',', source).take(80)
        }
        return source.take(500)
    }

    fun buildBookKey(bookName: String, author: String): String {
        val name = normalizeKeyPart(bookName)
        val writer = normalizeKeyPart(author)
        return if (name.isBlank() && writer.isBlank()) "" else "$name|$writer"
    }

    fun buildChapterKey(bookKey: String, chapterIndex: Int, chapterTitle: String): String {
        val cleanBookKey = bookKey.trim()
        if (cleanBookKey.isBlank()) return ""
        val title = normalizeKeyPart(chapterTitle)
        return "$cleanBookKey|$chapterIndex|$title"
    }

    private fun normalizeKeyPart(value: String): String {
        return value
            .trim()
            .replace(Regex("""\s+"""), "")
            .lowercase(Locale.ROOT)
    }

    sealed class GalleryFilter {
        data object ALL : GalleryFilter()
        data object TEMPORARY : GalleryFilter()
        data object FAVORITE : GalleryFilter()
        data class GROUP(val groupId: String) : GalleryFilter()
        data class BOOK(val bookKey: String) : GalleryFilter()
        data class CHAPTER(val chapterKey: String) : GalleryFilter()
        data class SOURCE_TYPE(val sourceType: String) : GalleryFilter()
        data class SEARCH(val keyword: String) : GalleryFilter()
    }
}
