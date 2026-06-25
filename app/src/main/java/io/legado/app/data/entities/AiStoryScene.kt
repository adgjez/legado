package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_story_scenes")
data class AiStoryScene(
    @PrimaryKey
    val id: String,
    @ColumnInfo(defaultValue = "")
    val playlistId: String,
    @ColumnInfo(defaultValue = "0")
    val sceneIndex: Int,
    @ColumnInfo(defaultValue = "")
    val narrativeText: String = "",
    @ColumnInfo(defaultValue = "")
    val visualPrompt: String = "",
    @ColumnInfo(defaultValue = "")
    val cameraControl: String = "",
    @ColumnInfo(defaultValue = "")
    val audioPrompt: String = "",
    @ColumnInfo(defaultValue = "5000")
    val duration: Long = 5_000L,
    @ColumnInfo(defaultValue = "")
    val imageId: String = "", // generated keyframe image
    @ColumnInfo(defaultValue = "")
    val videoId: String = "", // generated video
    @ColumnInfo(defaultValue = "")
    val audioId: String = "", // generated audio/sfx
    @ColumnInfo(defaultValue = "pending")
    val status: String = "pending", // pending/image_generating/image_done/video_generating/video_done/failed
    @ColumnInfo(defaultValue = "")
    val error: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis()
)
