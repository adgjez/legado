package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_gen_vouchers",
    indices = [
        Index(value = ["taskId"], name = "idx_voucher_task"),
        Index(value = ["createdAt"], name = "idx_voucher_created")
    ]
)
data class AiGenVoucher(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long,
    val modality: String,
    @ColumnInfo(defaultValue = "")
    val providerId: String = "",
    @ColumnInfo(defaultValue = "")
    val providerName: String = "",
    @ColumnInfo(defaultValue = "")
    val model: String = "",
    @ColumnInfo(defaultValue = "0")
    val costEstimate: Double = 0.0,
    @ColumnInfo(defaultValue = "0")
    val costActual: Double = 0.0,
    @ColumnInfo(defaultValue = "USD")
    val currency: String = "USD",
    @ColumnInfo(defaultValue = "0")
    val durationSeconds: Int = 0,
    @ColumnInfo(defaultValue = "1")
    val success: Boolean = true,
    @ColumnInfo(defaultValue = "")
    val notes: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis()
)
