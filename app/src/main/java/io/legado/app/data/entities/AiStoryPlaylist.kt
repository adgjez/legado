package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_story_playlists")
data class AiStoryPlaylist(
    @PrimaryKey
    val id: String,
    val bookKey: String = "",
    val bookName: String = "",
    val chapterKey: String = "",
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val sceneCount: Int = 0,
    val totalDuration: Long = 0L,
    val bgmAudioId: String = "",
    val status: String = "pending", // pending/processing/done/failed
    val createdAt: Long = System.currentTimeMillis()
)
