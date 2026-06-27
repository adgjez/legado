package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_purified_text_cache",
    indices = [
        Index(
            name = "idx_purify_cache",
            value = ["bookKey", "chapterIndex", "intensity"],
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
    val providerId: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
