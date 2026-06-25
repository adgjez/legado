package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_purified_text_cache",
    indices = [
        Index(
            value = ["bookKey", "chapterIndex", "intensity"],
            name = "idx_purify_cache",
            unique = true
        )
    ]
)
data class AiPurifiedTextCache(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookKey: String,
    val chapterIndex: Int,
    val intensity: Int,
    val contentHash: String,
    val sanitizedText: String,
    val originalLength: Int,
    val sanitizedLength: Int,
    @ColumnInfo(defaultValue = "")
    val providerId: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis()
)
