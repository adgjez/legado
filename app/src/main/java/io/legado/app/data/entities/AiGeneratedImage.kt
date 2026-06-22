package io.legado.app.data.entities

import androidx.room.ColumnInfo
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
        Index("sourceType")
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
    val favorite: Boolean = false,
    val groupId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
