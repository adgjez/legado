package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_generated_images",
    indices = [
        Index("groupId"),
        Index("favorite"),
        Index("createdAt"),
        Index("bookKey"),
        Index("chapterKey"),
        Index("characterId"),
        Index("sourceType"),
        Index(name = "idx_image_book_chapter", value = ["bookKey", "chapterIndex"])
    ]
)
data class AiGeneratedImage(
    @PrimaryKey
    val id: String,
    val name: String,
    val prompt: String,
    val providerId: String,
    val providerName: String,
    val model: String,
    val localPath: String,
    val originalSource: String = "",
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
    val favorite: Boolean = false,
    val groupId: String? = null,
    val genTaskId: String? = null,
    val generationMode: String? = null,
    val inputImageId: String? = null,
    val negativePrompt: String? = null,
    val referenceImageId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
