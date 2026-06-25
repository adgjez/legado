package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_gen_failure_logs",
    indices = [
        Index(value = ["modality"], name = "idx_failure_modality"),
        Index(value = ["providerId"], name = "idx_failure_provider"),
        Index(value = ["createdAt"], name = "idx_failure_created")
    ]
)
data class AiGenFailureLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val modality: String,        // "image" / "video" / "audio" / "text_sanitize"
    @ColumnInfo(defaultValue = "")
    val providerId: String = "",
    @ColumnInfo(defaultValue = "")
    val providerName: String = "",
    @ColumnInfo(defaultValue = "")
    val model: String = "",
    @ColumnInfo(defaultValue = "")
    val prompt: String = "",
    @ColumnInfo(defaultValue = "")
    val errorMessage: String = "",
    @ColumnInfo(defaultValue = "")
    val errorType: String = "",  // "timeout" / "network" / "api_error" / "unknown"
    @ColumnInfo(defaultValue = "")
    val bookKey: String = "",
    @ColumnInfo(defaultValue = "-1")
    val chapterIndex: Int = -1,
    @ColumnInfo(defaultValue = "0")
    val retryCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis()
)
