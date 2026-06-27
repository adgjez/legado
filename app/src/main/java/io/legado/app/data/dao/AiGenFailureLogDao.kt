package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiGenFailureLog

@Dao
interface AiGenFailureLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AiGenFailureLog): Long

    @Query("SELECT * FROM ai_gen_failure_logs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<AiGenFailureLog>

    @Query("SELECT * FROM ai_gen_failure_logs WHERE modality = :modality ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byModality(modality: String, limit: Int = 50): List<AiGenFailureLog>

    @Query("SELECT * FROM ai_gen_failure_logs WHERE providerId = :providerId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byProvider(providerId: String, limit: Int = 50): List<AiGenFailureLog>

    @Query("DELETE FROM ai_gen_failure_logs WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM ai_gen_failure_logs WHERE modality = :modality AND createdAt > :since")
    suspend fun countSince(modality: String, since: Long): Int
}
