package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_story_scenes")
data class AiStoryScene(
    @PrimaryKey
    val id: String,
    val playlistId: String,
    val sceneIndex: Int,
    val narrativeText: String = "",
    val visualPrompt: String = "",
    val cameraControl: String = "",
    val audioPrompt: String = "",
    val duration: Long = 5_000L,
    val imageId: String = "", // generated keyframe image
    val videoId: String = "", // generated video
    val audioId: String = "", // generated audio/sfx
    val status: String = "pending", // pending/image_generating/image_done/video_generating/video_done/failed
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
