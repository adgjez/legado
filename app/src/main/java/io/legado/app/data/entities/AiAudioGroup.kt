package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_audio_groups")
data class AiAudioGroup(
    @PrimaryKey
    val id: String,
    @ColumnInfo(defaultValue = "")
    val name: String,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0
)
