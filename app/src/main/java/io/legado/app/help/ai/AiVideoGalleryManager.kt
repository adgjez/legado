package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedVideo
import io.legado.app.data.entities.AiVideoGroup
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import io.legado.app.utils.getPrefInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.util.UUID

/**
 * AI 视频画廊管理：
 *  - 落盘目录 filesDir/ai_videos/{id}.mp4 + 封面 {id}_cover.jpg
 *  - 轮询完成后下载视频文件 + 抽帧/下载封面
 *  - 30 天未收藏自动清理（与 AiImageGalleryManager 策略一致）
 */
object AiVideoGalleryManager {

    const val DEFAULT_GROUP_ID = "default"
    private const val DEFAULT_GROUP_NAME = "默认分组"
    private const val TEMP_KEEP_DAYS = 30L
    private const val MAX_VIDEO_BYTES = 200L * 1024L * 1024L

    private val videoDir: File
        get() = File(appCtx.filesDir, "ai_videos").apply { mkdirs() }

    data class VideoMetadata(
        val bookName: String = "",
        val bookAuthor: String = "",
        val chapterIndex: Int = -1,
        val chapterTitle: String = "",
        val characterId: Long = 0L,
        val characterName: String = "",
        val sourceType: String = "",
        val sourceText: String = ""
    ) {
        val bookKey: String
            get() = buildBookKey(bookName, bookAuthor)

        val chapterKey: String
            get() = buildChapterKey(bookKey, chapterIndex, chapterTitle)
    }

    /**
     * 在任务提交后立即落 pending 行（用于记录与后续轮询跟踪）。
     * 标注为 suspend 并切换到 IO 调度器，确保 Room 的写操作不会落在主线程。
     */
    suspend fun saveSubmittedTask(
        prompt: String,
        negativePrompt: String,
        provider: AiVideoProviderConfig,
        model: String?,
        externalTaskId: String,
        durationSec: Int = 0,
        aspectRatio: String = "",
        firstFrame: String? = null,
        metadata: VideoMetadata = VideoMetadata()
    ): AiGeneratedVideo = withContext(Dispatchers.IO) {
        ensureDefaultGroup()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val row = AiGeneratedVideo(
            id = id,
            name = promptName(prompt),
            prompt = prompt,
            negativePrompt = negativePrompt.trim(),
            providerId = provider.id,
            providerName = provider.displayName(),
            model = model?.takeIf { it.isNotBlank() } ?: provider.model.ifBlank { provider.type },
            externalTaskId = externalTaskId,
            status = AiGeneratedVideo.STATUS_PENDING,
            progress = 0,
            aspectRatio = aspectRatio,
            bookKey = metadata.bookKey,
            bookName = metadata.bookName.trim(),
            bookAuthor = metadata.bookAuthor.trim(),
            chapterKey = metadata.chapterKey,
            chapterIndex = metadata.chapterIndex,
            chapterTitle = metadata.chapterTitle.trim(),
            characterId = metadata.characterId,
            characterName = metadata.characterName.trim(),
            sourceType = metadata.sourceType.trim(),
            sourceText = metadata.sourceText.trim().take(2000),
            createdAt = now,
            updatedAt = now,
            metadataJson = if (firstFrame.isNullOrBlank()) "" else JSONObject().put("firstFrame", firstFrame).toString()
        )
        appDb.aiGeneratedVideoDao.insert(row)
        row
    }

    fun updateStatus(id: String, status: String, failReason: String = "", progress: Int = 0) {
        appDb.aiGeneratedVideoDao.updateStatus(
            id = id,
            status = status,
            failReason = failReason,
            progress = progress,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun updateProgress(id: String, progress: Int) {
        appDb.aiGeneratedVideoDao.updateProgress(id, progress.coerceIn(0, 100), System.currentTimeMillis())
    }

    /**
     * 轮询成功时调用：下载视频 + 封面到本地，更新数据库。
     * 返回最终落盘的 AiGeneratedVideo 行。
     */
    suspend fun saveCompletedVideo(
        videoId: String,
        videoUrl: String?,
        coverUrl: String?,
        provider: AiVideoProviderConfig,
        durationMs: Long,
        width: Int,
        height: Int,
        sizeBytes: Long
    ): AiGeneratedVideo = withContext(Dispatchers.IO) {
        val row = appDb.aiGeneratedVideoDao.get(videoId)
            ?: error("Video row not found: $videoId")
        val id = row.id
        val videoFile = File(videoDir, "$id.mp4")
        val coverFile = File(videoDir, "${id}_cover.jpg")
        val writtenVideoUrl = videoUrl?.takeIf { it.isNotBlank() }
            ?: error("Provider returned SUCCESS but videoUrl is null/empty for $videoId")
        // 下载视频文件，失败则抛异常让调用方标记 FAILED
        runCatching {
            AiVideoApi.downloadToFile(
                url = writtenVideoUrl,
                target = videoFile,
                headers = buildHeaderMap(provider),
                timeoutMs = 10 * 60 * 1000L
            )
        }.onFailure {
            runCatching { videoFile.delete() }
            throw it
        }
        // 校验下载结果
        if (!videoFile.isFile || videoFile.length() == 0L) {
            runCatching { videoFile.delete() }
            error("Video download succeeded but file is missing or empty for $videoId")
        }
        val coverSrc = coverUrl?.takeIf { it.isNotBlank() }
        if (coverSrc != null) {
            runCatching {
                AiVideoApi.downloadToFile(
                    url = coverSrc,
                    target = coverFile,
                    headers = buildHeaderMap(provider),
                    timeoutMs = 60_000L
                )
            }.onFailure {
                runCatching { coverFile.delete() }
                AppLog.put("下载 AI 视频封面失败: $coverSrc", it)
            }
        }
        val now = System.currentTimeMillis()
        val newRow = row.copy(
            localPath = videoFile.absolutePath,
            remoteUrl = writtenVideoUrl.orEmpty(),
            coverPath = if (coverFile.isFile) coverFile.absolutePath else "",
            durationMs = durationMs.coerceAtLeast(0L),
            width = width.coerceAtLeast(0),
            height = height.coerceAtLeast(0),
            sizeBytes = sizeBytes.coerceAtLeast(0).let { if (it > 0) it else videoFile.length() },
            status = AiGeneratedVideo.STATUS_SUCCESS,
            progress = 100,
            completedAt = now,
            updatedAt = now
        )
        appDb.aiGeneratedVideoDao.insert(newRow)
        newRow
    }

    fun ensureDefaultGroup() {
        if (appDb.aiVideoGroupDao.get(DEFAULT_GROUP_ID) == null) {
            appDb.aiVideoGroupDao.insert(
                AiVideoGroup(
                    id = DEFAULT_GROUP_ID,
                    name = DEFAULT_GROUP_NAME,
                    order = 0
                )
            )
        }
    }

    /**
     * 按保留天数清理过期记录。
     * 注意：本地文件是否存在的检查已下沉到 [listVideosWithCleanup] 中惰性处理，
     * 这里不再做全表扫描，避免在大库上产生 O(N) 磁盘 I/O。
     */
    suspend fun cleanupExpired() = withContext(Dispatchers.IO) {
        val keepDays = appCtx.getPrefInt(PreferKey.aiVideoKeepTempDays, TEMP_KEEP_DAYS.toInt())
            .coerceIn(1, 365)
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 60L * 60L * 1000L
        appDb.aiGeneratedVideoDao.deleteOlderThan(
            statuses = listOf(
                AiGeneratedVideo.STATUS_FAILED,
                AiGeneratedVideo.STATUS_CANCELLED,
                AiGeneratedVideo.STATUS_SUCCESS
            ),
            before = cutoff
        )
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
            order = appDb.aiVideoGroupDao.all().size
        )
        appDb.aiVideoGroupDao.insert(group)
        return group
    }

    /**
     * 同步读取一条视频记录。不在内部做删除，删除请调用 [cleanupMissingLocalFiles]。
     */
    fun getVideo(id: String): AiGeneratedVideo? {
        return appDb.aiGeneratedVideoDao.get(id)
    }

    /**
     * 异步读取一条视频记录：若本地文件已丢失则一并清理。
     * 调用方应位于 IO / 非主线程上下文。
     */
    suspend fun getVideoWithCleanup(id: String): AiGeneratedVideo? = withContext(Dispatchers.IO) {
        val v = appDb.aiGeneratedVideoDao.get(id) ?: return@withContext null
        if (v.localPath.isNotBlank() && !File(v.localPath).isFile) {
            appDb.aiGeneratedVideoDao.delete(id)
            return@withContext null
        }
        v
    }

    fun listVideos(): List<AiGeneratedVideo> {
        ensureDefaultGroup()
        return appDb.aiGeneratedVideoDao.all()
    }

    /**
     * 异步版本的 [listVideos]，会清理本地文件已不存在的记录。
     * 调用方应位于 IO / 非主线程上下文。
     */
    suspend fun listVideosWithCleanup(): List<AiGeneratedVideo> = withContext(Dispatchers.IO) {
        ensureDefaultGroup()
        val all = appDb.aiGeneratedVideoDao.all()
        all.filter { v ->
            if (v.localPath.isNotBlank() && !File(v.localPath).isFile) {
                appDb.aiGeneratedVideoDao.delete(v.id)
                false
            } else true
        }
    }

    fun listByStatus(status: String): List<AiGeneratedVideo> {
        return appDb.aiGeneratedVideoDao.byStatusSingle(status)
    }

    fun listFavorites(): List<AiGeneratedVideo> = appDb.aiGeneratedVideoDao.favorites()

    fun listByGroup(groupId: String): List<AiGeneratedVideo> = appDb.aiVideoGroupDao.get(groupId)?.let {
        appDb.aiGeneratedVideoDao.byGroup(groupId)
    } ?: emptyList()

    fun listByBook(bookKey: String): List<AiGeneratedVideo> = appDb.aiGeneratedVideoDao.byBook(bookKey)

    fun listByCharacter(characterId: Long): List<AiGeneratedVideo> = appDb.aiGeneratedVideoDao.byCharacter(characterId)

    fun listBySourceType(sourceType: String): List<AiGeneratedVideo> = appDb.aiGeneratedVideoDao.bySourceType(sourceType)

    fun search(keyword: String): List<AiGeneratedVideo> {
        val q = keyword.trim()
        if (q.isBlank()) return listVideos()
        return appDb.aiGeneratedVideoDao.search("%$q%")
    }

    fun rename(id: String, name: String) {
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

    fun delete(id: String) {
        val v = appDb.aiGeneratedVideoDao.get(id) ?: return
        runCatching {
            val file = File(v.localPath)
            if (file.isFile && file.parentFile?.canonicalPath == videoDir.canonicalPath) {
                file.delete()
            }
            val cover = File(v.coverPath)
            if (cover.isFile && cover.parentFile?.canonicalPath == videoDir.canonicalPath) {
                cover.delete()
            }
        }.onFailure {
            AppLog.put("删除 AI 视频文件失败: ${v.localPath}", it)
        }
        appDb.aiGeneratedVideoDao.delete(id)
    }

    fun deleteGroup(id: String) {
        if (id == DEFAULT_GROUP_ID) return
        ensureDefaultGroup()
        appDb.runInTransaction {
            appDb.aiGeneratedVideoDao.moveGroup(id, DEFAULT_GROUP_ID, System.currentTimeMillis())
            appDb.aiVideoGroupDao.delete(id)
        }
    }

    private fun buildHeaderMap(provider: AiVideoProviderConfig): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (provider.apiKey.isNotBlank()) {
            headers["Authorization"] = "Bearer ${provider.apiKey.trim()}"
        }
        headers.putAll(AiChatService.parseCustomHeaders(provider.headers))
        return headers
    }

    private fun promptName(prompt: String): String {
        val cleaned = prompt.trim().replace("\\s+".toRegex(), " ")
        return if (cleaned.length > 30) cleaned.substring(0, 30) + "…" else cleaned.ifBlank { "未命名" }
    }

    private fun buildBookKey(bookName: String, bookAuthor: String): String {
        if (bookName.isBlank() && bookAuthor.isBlank()) return ""
        return "${bookName.trim()}||${bookAuthor.trim()}"
    }

    private fun buildChapterKey(bookKey: String, chapterIndex: Int, chapterTitle: String): String {
        if (bookKey.isBlank()) return ""
        return "$bookKey#${chapterIndex}#${chapterTitle.trim()}"
    }
}
