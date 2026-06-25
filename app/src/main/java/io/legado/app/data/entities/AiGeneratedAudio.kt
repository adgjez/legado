package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_generated_audios",
    indices = [
        Index("groupId"),
        Index("favorite"),
        Index("createdAt"),
        Index("bookKey"),
        Index("audioType"),
        Index(value = ["bookKey", "chapterIndex"], name = "idx_audio_book_chapter"),
        Index(value = ["favorite", "lastAccessTime"], name = "idx_audio_lru")
    ]
)
data class AiGeneratedAudio(
    @PrimaryKey
    val id: String,
    @ColumnInfo(defaultValue = "")
    val name: String,
    @ColumnInfo(defaultValue = "")
    val prompt: String,
    @ColumnInfo(defaultValue = "")
    val providerId: String,
    @ColumnInfo(defaultValue = "")
    val providerName: String,
    @ColumnInfo(defaultValue = "")
    val model: String,
    @ColumnInfo(defaultValue = "")
    val localPath: String,
    @ColumnInfo(defaultValue = "0")
    val duration: Long = 0L,
    @ColumnInfo(defaultValue = "mp3")
    val format: String = "mp3",
    @ColumnInfo(defaultValue = "music")
    val audioType: String = "music", // music/sfx/speech
    @ColumnInfo(defaultValue = "")
    val inputText: String = "",
    @ColumnInfo(defaultValue = "0")
    val costActual: Double = 0.0,
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
    val remoteTaskId: String = "",
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
