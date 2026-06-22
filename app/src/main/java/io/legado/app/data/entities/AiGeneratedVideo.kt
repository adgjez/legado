package io.legado.app.data.entities

import androidx.room.ColumnInfo
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
    @ColumnInfo(defaultValue = "")
    val negativePrompt: String = "",
    val providerId: String,
    val providerName: String,
    val model: String,
    @ColumnInfo(defaultValue = "")
    val localPath: String = "",
    @ColumnInfo(defaultValue = "")
    val remoteUrl: String = "",
    @ColumnInfo(defaultValue = "")
    val coverPath: String = "",
    @ColumnInfo(defaultValue = "0")
    val durationMs: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val width: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val height: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val sizeBytes: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val aspectRatio: String = "",
    @ColumnInfo(defaultValue = "-1")
    val seed: Long = -1L,
    @ColumnInfo(defaultValue = "")
    val bookKey: String = "",
    @ColumnInfo(defaultValue = "")
    val bookName: String = "",
    @ColumnInfo(defaultValue = "")
    val bookAuthor: String = "",
    @ColumnInfo(defaultValue = "")
    val chapterKey: String = "",
    @ColumnInfo(defaultValue = "-1")
    val chapterIndex: Int = -1,
    @ColumnInfo(defaultValue = "")
    val chapterTitle: String = "",
    @ColumnInfo(defaultValue = "0")
    val characterId: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val characterName: String = "",
    @ColumnInfo(defaultValue = "")
    val sourceType: String = "",
    @ColumnInfo(defaultValue = "")
    val sourceText: String = "",
    @ColumnInfo(defaultValue = "pending")
    val status: String = STATUS_PENDING,
    @ColumnInfo(defaultValue = "")
    val failReason: String = "",
    @ColumnInfo(defaultValue = "0")
    val progress: Int = 0,
    @ColumnInfo(defaultValue = "")
    val externalTaskId: String = "",
    @ColumnInfo(defaultValue = "")
    val metadataJson: String = "",
    @ColumnInfo(defaultValue = "0")
    val favorite: Boolean = false,
    val groupId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
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
