package io.legado.app.data.entities

import androidx.room.ColumnInfo
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
    @ColumnInfo(defaultValue = "")
    val modality: String, // image/video/audio/text_sanitize
    @ColumnInfo(defaultValue = "pending")
    val status: String = "pending", // pending/submitted/processing/downloading/done/failed/cancelled
    @ColumnInfo(defaultValue = "0")
    val priority: Int = 0, // 0=normal, 1=high, -1=low
    val parentTaskId: Long? = null,
    @ColumnInfo(defaultValue = "")
    val providerId: String = "",
    @ColumnInfo(defaultValue = "")
    val providerName: String = "",
    @ColumnInfo(defaultValue = "")
    val model: String = "",
    @ColumnInfo(defaultValue = "")
    val prompt: String = "",
    @ColumnInfo(defaultValue = "")
    val negativePrompt: String = "",
    val inputImageId: String? = null,
    val referenceImageId: String? = null,
    @ColumnInfo(defaultValue = "")
    val remoteTaskId: String = "",
    @ColumnInfo(defaultValue = "")
    val resultId: String = "", // generated image/video/audio ID
    @ColumnInfo(defaultValue = "")
    val resultPath: String = "",
    @ColumnInfo(defaultValue = "")
    val previewUrl: String = "",
    @ColumnInfo(defaultValue = "")
    val emotionalHint: String = "",
    @ColumnInfo(defaultValue = "0")
    val costEstimate: Double = 0.0,
    @ColumnInfo(defaultValue = "0")
    val costActual: Double = 0.0,
    val voucherId: String? = null,
    @ColumnInfo(defaultValue = "")
    val bookKey: String = "",
    @ColumnInfo(defaultValue = "-1")
    val chapterIndex: Int = -1,
    @ColumnInfo(defaultValue = "")
    val sourceType: String = "",
    @ColumnInfo(defaultValue = "0")
    val progress: Int = 0,
    @ColumnInfo(defaultValue = "")
    val errorMessage: String = "",
    @ColumnInfo(defaultValue = "0")
    val retryCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val lastAccessTime: Long = System.currentTimeMillis()
)
