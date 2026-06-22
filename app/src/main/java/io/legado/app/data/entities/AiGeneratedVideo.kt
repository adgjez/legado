package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 视频生成记录
 *
 * 状态机：pending → running → success / failed / cancelled
 * 视频文件落盘到 filesDir/ai_videos/{id}.mp4，封面 {id}_cover.jpg。
 * remoteUrl 可能过期，仅作展示用途。
 */
@Entity(
    tableName = "ai_generated_videos",
    indices = [
        Index("groupId"),
        Index("favorite"),
        Index("status"),
        Index("createdAt"),
        Index("bookKey"),
        Index("chapterKey"),
        Index("characterId"),
        Index("sourceType")
    ]
)
data class AiGeneratedVideo(
    @PrimaryKey
    val id: String,
    val name: String,
    val prompt: String,
    val negativePrompt: String = "",
    val providerId: String,
    val providerName: String,
    val model: String,
    val localPath: String = "",
    val remoteUrl: String = "",
    val coverPath: String = "",
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val aspectRatio: String = "",
    val seed: Long = -1L,
    val bookKey: String = "",
    val bookName: String = "",
    val bookAuthor: String = "",
    val chapterKey: String = "",
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val characterId: Long = 0L,
    val characterName: String = "",
    val sourceType: String = "",
    val sourceText: String = "",
    val status: String = STATUS_PENDING,
    val failReason: String = "",
    val progress: Int = 0,
    val externalTaskId: String = "",
    val metadataJson: String = "",
    val favorite: Boolean = false,
    val groupId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}
