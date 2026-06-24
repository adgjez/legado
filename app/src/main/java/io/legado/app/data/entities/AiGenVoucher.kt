package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_gen_vouchers",
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["createdAt"])
    ]
)
data class AiGenVoucher(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long,
    val modality: String,
    val providerId: String = "",
    val providerName: String = "",
    val model: String = "",
    val costEstimate: Double = 0.0,
    val costActual: Double = 0.0,
    val currency: String = "USD",
    val durationSeconds: Int = 0,
    val success: Boolean = true,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
