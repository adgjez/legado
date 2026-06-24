package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_gen_failure_logs",
    indices = [
        Index(value = ["modality"]),
        Index(value = ["providerId"]),
        Index(value = ["createdAt"])
    ]
)
data class AiGenFailureLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val modality: String,        // "image" / "video" / "audio" / "text_sanitize"
    val providerId: String = "",
    val providerName: String = "",
    val model: String = "",
    val prompt: String = "",
    val errorMessage: String = "",
    val errorType: String = "",  // "timeout" / "network" / "api_error" / "unknown"
    val bookKey: String = "",
    val chapterIndex: Int = -1,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
