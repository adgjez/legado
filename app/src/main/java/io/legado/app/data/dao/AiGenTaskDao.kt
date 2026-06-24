package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiGenTask

@Dao
interface AiGenTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(task: AiGenTask): Long

    @Query("select * from ai_gen_tasks where id = :id")
    fun get(id: Long): AiGenTask?

    @Query("select * from ai_gen_tasks where status in ('pending','submitted','processing','downloading') order by priority desc, createdAt asc")
    fun activeTasks(): List<AiGenTask>

    @Query("select * from ai_gen_tasks where parentTaskId = :parentId order by createdAt asc")
    fun byParent(parentId: Long): List<AiGenTask>

    @Query("select * from ai_gen_tasks where modality = :modality and status in ('pending','submitted','processing','downloading') order by priority desc, createdAt asc")
    fun activeByModality(modality: String): List<AiGenTask>

    @Query("update ai_gen_tasks set status = :status, updatedAt = :updatedAt where id = :id")
    fun updateStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("update ai_gen_tasks set status = :status, progress = :progress, previewUrl = :previewUrl, updatedAt = :updatedAt where id = :id")
    fun updateProgress(id: Long, status: String, progress: Int, previewUrl: String, updatedAt: Long = System.currentTimeMillis())

    @Query("update ai_gen_tasks set emotionalHint = :hint, updatedAt = :updatedAt where id = :id")
    fun updateEmotionalHint(id: Long, hint: String, updatedAt: Long = System.currentTimeMillis())

    @Query("update ai_gen_tasks set status = :status, resultId = :resultId, resultPath = :resultPath, costActual = :costActual, updatedAt = :updatedAt where id = :id")
    fun updateResult(id: Long, status: String, resultId: String, resultPath: String, costActual: Double, updatedAt: Long = System.currentTimeMillis())

    @Query("update ai_gen_tasks set status = :status, errorMessage = :error, retryCount = :retryCount, updatedAt = :updatedAt where id = :id")
    fun updateError(id: Long, status: String, error: String, retryCount: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("update ai_gen_tasks set lastAccessTime = :time where id = :id")
    fun touchAccess(id: Long, time: Long)

    @Query("delete from ai_gen_tasks where id = :id")
    fun delete(id: Long)

    @Query("delete from ai_gen_tasks where createdAt < :cutoff and status in ('done','failed','cancelled')")
    fun deleteOlderThan(cutoff: Long)
}
