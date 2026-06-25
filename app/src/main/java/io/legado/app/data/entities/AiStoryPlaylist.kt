package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_story_playlists")
data class AiStoryPlaylist(
    @PrimaryKey
    val id: String,
    @ColumnInfo(defaultValue = "")
    val bookKey: String = "",
    @ColumnInfo(defaultValue = "")
    val bookName: String = "",
    @ColumnInfo(defaultValue = "")
    val chapterKey: String = "",
    @ColumnInfo(defaultValue = "-1")
    val chapterIndex: Int = -1,
    @ColumnInfo(defaultValue = "")
    val chapterTitle: String = "",
    @ColumnInfo(defaultValue = "0")
    val sceneCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val totalDuration: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val bgmAudioId: String = "",
    @ColumnInfo(defaultValue = "pending")
    val status: String = "pending", // pending/processing/done/failed
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis()
)
