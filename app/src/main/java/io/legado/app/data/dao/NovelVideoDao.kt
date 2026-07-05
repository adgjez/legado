package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.NovelVideoCharacterSheet
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoSegment

@Dao
interface NovelVideoDao {

    /* ----------------------------- Job ----------------------------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: NovelVideoJob)

    @Update
    suspend fun updateJob(job: NovelVideoJob)

    @Query("SELECT * FROM novel_video_jobs WHERE id = :jobId LIMIT 1")
    suspend fun getJob(jobId: String): NovelVideoJob?

    @Query("SELECT * FROM novel_video_jobs ORDER BY createdAt DESC")
    suspend fun getAllJobs(): List<NovelVideoJob>

    @Query("SELECT * FROM novel_video_jobs WHERE status IN ('drafting','screenplay_pending_review','screenplay_confirmed','generating','merging','paused') ORDER BY updatedAt DESC")
    suspend fun getRunningJobs(): List<NovelVideoJob>

    @Query("SELECT * FROM novel_video_jobs WHERE status IN ('completed','partial_failed') ORDER BY updatedAt DESC")
    suspend fun getCompletedJobs(): List<NovelVideoJob>

    @Query("SELECT * FROM novel_video_jobs WHERE status IN ('failed','cancelled') ORDER BY updatedAt DESC")
    suspend fun getFailedJobs(): List<NovelVideoJob>

    @Query("SELECT * FROM novel_video_jobs WHERE bookUrl = :bookUrl ORDER BY createdAt DESC")
    suspend fun getJobsByBook(bookUrl: String): List<NovelVideoJob>

    @Query("UPDATE novel_video_jobs SET status = :status, updatedAt = :time WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: String, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_jobs SET status = :status, errorMessage = :err, updatedAt = :time WHERE id = :jobId")
    suspend fun updateJobStatusWithError(jobId: String, status: String, err: String?, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_jobs SET outputPath = :outputPath, coverPath = :coverPath, totalDurationMs = :durationMs, status = :status, updatedAt = :time WHERE id = :jobId")
    suspend fun updateJobOutput(jobId: String, outputPath: String?, coverPath: String?, durationMs: Long?, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_jobs SET draftJson = :draftJson, status = :status, updatedAt = :time WHERE id = :jobId")
    suspend fun updateJobDraft(jobId: String, draftJson: String?, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_jobs SET screenplayJson = :screenplayJson, status = :status, updatedAt = :time WHERE id = :jobId")
    suspend fun updateJobScreenplay(jobId: String, screenplayJson: String?, status: String, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM novel_video_jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: String)

    @Query("DELETE FROM novel_video_jobs WHERE bookUrl = :bookUrl")
    suspend fun deleteJobsByBook(bookUrl: String)

    /* --------------------------- Segment --------------------------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<NovelVideoSegment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: NovelVideoSegment)

    @Update
    suspend fun updateSegment(segment: NovelVideoSegment)

    @Query("SELECT * FROM novel_video_segments WHERE jobId = :jobId ORDER BY chapterIndex ASC, sceneId ASC")
    suspend fun getSegmentsByJob(jobId: String): List<NovelVideoSegment>

    @Query("SELECT * FROM novel_video_segments WHERE jobId = :jobId AND chapterIndex = :chapterIndex ORDER BY sceneId ASC")
    suspend fun getSegmentsByChapter(jobId: String, chapterIndex: Int): List<NovelVideoSegment>

    @Query("SELECT * FROM novel_video_segments WHERE jobId = :jobId AND status = :status ORDER BY chapterIndex ASC, sceneId ASC")
    suspend fun getSegmentsByStatus(jobId: String, status: String): List<NovelVideoSegment>

    @Query("SELECT * FROM novel_video_segments WHERE jobId = :jobId AND status IN ('pending','image_generating','image_completed','video_generating','failed') ORDER BY chapterIndex ASC, sceneId ASC LIMIT 1")
    suspend fun getNextPendingSegment(jobId: String): NovelVideoSegment?

    @Query("UPDATE novel_video_segments SET status = :status, errorMessage = :err, retryCount = retryCount + 1, updatedAt = :time WHERE id = :segmentId")
    suspend fun updateSegmentStatus(segmentId: String, status: String, err: String?, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_segments SET imageUrl = :imageUrl, status = :status, updatedAt = :time WHERE id = :segmentId")
    suspend fun updateSegmentImage(segmentId: String, imageUrl: String?, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_segments SET videoUrl = :videoUrl, localVideoPath = :localPath, durationMs = :durationMs, status = :status, updatedAt = :time WHERE id = :segmentId")
    suspend fun updateSegmentVideo(segmentId: String, videoUrl: String?, localPath: String?, durationMs: Long?, status: String, time: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) AS total, SUM(CASE WHEN status = 'video_completed' THEN 1 ELSE 0 END) AS completed, SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END) AS failed FROM novel_video_segments WHERE jobId = :jobId")
    suspend fun getSegmentProgress(jobId: String): SegmentProgress

    /* ------------------------ CharacterSheet ----------------------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterSheet(sheet: NovelVideoCharacterSheet)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterSheets(sheets: List<NovelVideoCharacterSheet>)

    @Update
    suspend fun updateCharacterSheet(sheet: NovelVideoCharacterSheet)

    @Query("SELECT * FROM novel_video_character_sheets WHERE jobId = :jobId ORDER BY role ASC")
    suspend fun getCharacterSheetsByJob(jobId: String): List<NovelVideoCharacterSheet>

    @Query("SELECT * FROM novel_video_character_sheets WHERE jobId = :jobId AND status = 'completed' ORDER BY role ASC")
    suspend fun getCompletedCharacterSheets(jobId: String): List<NovelVideoCharacterSheet>

    @Query("UPDATE novel_video_character_sheets SET combinedViewUrl = :url, localPath = :localPath, status = :status, updatedAt = :time WHERE id = :id")
    suspend fun updateCharacterSheetImage(id: String, url: String?, localPath: String?, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_character_sheets SET status = :status, errorMessage = :err, updatedAt = :time WHERE id = :id")
    suspend fun updateCharacterSheetStatus(id: String, status: String, err: String?, time: Long = System.currentTimeMillis())
}

/** 任务进度统计。 */
data class SegmentProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0
) {
    /** 进度百分比 0..100。 */
    val progressPercent: Int
        get() = if (total == 0) 0 else ((completed + failed) * 100 / total)

    /** 是否失败段过多（>50%）。 */
    val isMajorityFailed: Boolean
        get() = total > 0 && failed * 2 > total
}
