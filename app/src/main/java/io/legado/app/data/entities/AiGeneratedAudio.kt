package io.legado.app.data.entities

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
        Index(name = "idx_audio_book_chapter", value = ["bookKey", "chapterIndex"]),
        Index(name = "idx_audio_lru", value = ["favorite", "lastAccessTime"])
    ]
)
data class AiGeneratedAudio(
    @PrimaryKey
    val id: String,
    val name: String,
    val prompt: String,
    val providerId: String,
    val providerName: String,
    val model: String,
    val localPath: String,
    val duration: Long = 0L,
    val format: String = "mp3",
    val audioType: String = "music", // music/sfx/speech
    val inputText: String = "",
    val costActual: Double = 0.0,
    val bookKey: String = "",
    val bookName: String = "",
    val bookAuthor: String = "",
    val chapterKey: String = "",
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val sourceType: String = "",
    val remoteTaskId: String = "",
    val favorite: Boolean = false,
    val groupId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessTime: Long = System.currentTimeMillis()
)
