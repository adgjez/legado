package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_gen_tasks",
    indices = [
        Index("modality"),
        Index("status"),
        Index("priority"),
        Index("createdAt"),
        Index("parentTaskId"),
        Index(value = ["status", "priority", "createdAt"], name = "idx_task_status_priority"),
        Index(value = ["parentTaskId", "modality"], name = "idx_task_parent")
    ]
)
data class AiGenTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val modality: String, // image/video/audio/text_sanitize
    val status: String = "pending", // pending/submitted/processing/downloading/done/failed/cancelled
    val priority: Int = 0, // 0=normal, 1=high, -1=low
    val parentTaskId: Long? = null,
    val providerId: String = "",
    val providerName: String = "",
    val model: String = "",
    val prompt: String = "",
    val negativePrompt: String = "",
    val inputImageId: String? = null,
    val referenceImageId: String? = null,
    val remoteTaskId: String = "",
    val resultId: String = "", // generated image/video/audio ID
    val resultPath: String = "",
    val previewUrl: String = "",
    val emotionalHint: String = "",
    val costEstimate: Double = 0.0,
    val costActual: Double = 0.0,
    val voucherId: String? = null,
    val bookKey: String = "",
    val chapterIndex: Int = -1,
    val sourceType: String = "",
    val progress: Int = 0,
    val errorMessage: String = "",
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessTime: Long = System.currentTimeMillis()
)
