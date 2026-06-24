package io.legado.app.data.entities

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
    val name: String,
    val prompt: String,
    val negativePrompt: String = "",
    val providerId: String,
    val providerName: String,
    val model: String,
    val localPath: String,
    val thumbnailPath: String = "",
    val duration: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val originalSource: String = "",
    val bookKey: String = "",
    val bookName: String = "",
    val bookAuthor: String = "",
    val chapterKey: String = "",
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val sourceType: String = "",
    val sourceText: String = "",
    val generationMode: String = "text_to_video",
    val inputImageId: String? = null,
    val tailImageId: String? = null,
    val referenceImageId: String? = null,
    val cameraControl: String = "",
    val remoteTaskId: String = "",
    val needsTranscode: Boolean = false,
    val parentVideoId: String? = null,
    val costActual: String = "",
    val favorite: Boolean = false,
    val groupId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessTime: Long = System.currentTimeMillis()
)
