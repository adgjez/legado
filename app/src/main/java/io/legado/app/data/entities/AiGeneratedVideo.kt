package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_generated_videos",
    indices = [
        Index("groupId"),
        Index("favorite"),
        Index("createdAt"),
        Index("bookKey"),
        Index("chapterKey"),
        Index("sourceType"),
        Index("generationMode"),
        Index("parentVideoId"),
        Index(value = ["bookKey", "chapterIndex"], name = "idx_video_book_chapter"),
        Index(value = ["favorite", "lastAccessTime"], name = "idx_video_lru")
    ]
)
data class AiGeneratedVideo(
    @PrimaryKey
    val id: String,
    @ColumnInfo(defaultValue = "")
    val name: String,
    @ColumnInfo(defaultValue = "")
    val prompt: String,
    @ColumnInfo(defaultValue = "")
    val negativePrompt: String = "",
    @ColumnInfo(defaultValue = "")
    val providerId: String,
    @ColumnInfo(defaultValue = "")
    val providerName: String,
    @ColumnInfo(defaultValue = "")
    val model: String,
    @ColumnInfo(defaultValue = "")
    val localPath: String,
    @ColumnInfo(defaultValue = "")
    val thumbnailPath: String = "",
    @ColumnInfo(defaultValue = "0")
    val duration: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val width: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val height: Int = 0,
    @ColumnInfo(defaultValue = "")
    val originalSource: String = "",
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
    @ColumnInfo(defaultValue = "")
    val sourceType: String = "",
    @ColumnInfo(defaultValue = "")
    val sourceText: String = "",
    @ColumnInfo(defaultValue = "text_to_video")
    val generationMode: String = "text_to_video",
    val inputImageId: String? = null,
    val tailImageId: String? = null,
    val referenceImageId: String? = null,
    @ColumnInfo(defaultValue = "")
    val cameraControl: String = "",
    @ColumnInfo(defaultValue = "")
    val remoteTaskId: String = "",
    @ColumnInfo(defaultValue = "0")
    val needsTranscode: Boolean = false,
    val parentVideoId: String? = null,
    @ColumnInfo(defaultValue = "")
    val costActual: String = "",
    @ColumnInfo(defaultValue = "0")
    val favorite: Boolean = false,
    val groupId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val lastAccessTime: Long = System.currentTimeMillis()
)
