package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.NovelVideoCharacterSheet
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.data.entities.NovelVideoSegment
import io.legado.app.data.entities.NovelVideoSegmentStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelVideoDao {

    /* ----------------------------- Job ----------------------------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: NovelVideoJob)

    @Update
    suspend fun updateJob(job: NovelVideoJob)

    @Query("SELECT * FROM novel_video_jobs WHERE id = :jobId LIMIT 1")
    suspend fun getJob(jobId: String): NovelVideoJob?

    @Query("SELECT * FROM novel_video_jobs WHERE id = :jobId LIMIT 1")
    fun getJobFlow(jobId: String): Flow<NovelVideoJob?>

    @Query("SELECT * FROM novel_video_jobs ORDER BY createdAt DESC")
    suspend fun getAllJobs(): List<NovelVideoJob>

    @Query("SELECT * FROM novel_video_jobs WHERE status IN ('drafting','screenplay_pending_review','screenplay_confirmed','generating','merging','paused') ORDER BY updatedAt DESC")
    suspend fun getRunningJobs(): List<NovelVideoJob>

    @Query("SELECT * FROM novel_video_jobs WHERE status IN ('drafting','screenplay_pending_review','screenplay_confirmed','generating','merging','paused') ORDER BY updatedAt DESC")
    fun getRunningJobsFlow(): Flow<List<NovelVideoJob>>

    @Query("SELECT * FROM novel_video_jobs WHERE status IN ('completed','partial_failed') ORDER BY updatedAt DESC")
    suspend fun getCompletedJobs(): List<NovelVideoJob>

    @Query("SELECT * FROM novel_video_jobs WHERE status IN ('completed','partial_failed') ORDER BY updatedAt DESC")
    fun getCompletedJobsFlow(): Flow<List<NovelVideoJob>>

    @Query("SELECT * FROM novel_video_jobs WHERE status IN ('failed','cancelled') ORDER BY updatedAt DESC")
    suspend fun getFailedJobs(): List<NovelVideoJob>

    @Query("SELECT * FROM novel_video_jobs WHERE status IN ('failed','cancelled') ORDER BY updatedAt DESC")
    fun getFailedJobsFlow(): Flow<List<NovelVideoJob>>

    @Query("SELECT * FROM novel_video_jobs WHERE bookUrl = :bookUrl ORDER BY createdAt DESC")
    suspend fun getJobsByBook(bookUrl: String): List<NovelVideoJob>

    @Query("UPDATE novel_video_jobs SET status = :status, updatedAt = :time WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: String, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_jobs SET status = :status, errorMessage = :err, updatedAt = :time WHERE id = :jobId")
    suspend fun updateJobStatusWithError(jobId: String, status: String, err: String?, time: Long = System.currentTimeMillis())

    /**
     * 条件更新终态：仅当 job 尚未进入终态时才更新。
     * @return 受影响行数（0 表示已被并发覆写为终态，调用方应跳过后续动作）
     */
    @Query("UPDATE novel_video_jobs SET status = :status, updatedAt = :time WHERE id = :jobId AND status NOT IN ('completed','failed','partial_failed','cancelled')")
    suspend fun updateJobFinalStatusIfNotFinished(jobId: String, status: String, time: Long = System.currentTimeMillis()): Int

    /**
     * 条件更新终态（带 errorMessage）：仅当 job 尚未进入终态时才更新。
     *
     * 用于 FAILED/CANCELLED 写入：避免覆写并发的终态（如取消已写 CANCELLED，失败路径不应覆写）。
     * @return 受影响行数（0 表示已被并发覆写为终态，调用方应跳过后续动作）
     */
    @Query("UPDATE novel_video_jobs SET status = :status, errorMessage = :err, updatedAt = :time WHERE id = :jobId AND status NOT IN ('completed','failed','partial_failed','cancelled')")
    suspend fun updateJobFinalStatusWithErrorIfNotFinished(jobId: String, status: String, err: String?, time: Long = System.currentTimeMillis()): Int

    /**
     * 重试专用：仅从终态（failed/partial_failed/cancelled）转换回 GENERATING。
     *
     * N1 修复（第四轮审查）：[updateJobFinalStatusWithErrorIfNotFinished] 的 WHERE 排除终态，
     * 无法把 FAILED→GENERATING（retryJob 场景）。本方法反向守卫：仅允许从终态转换，
     * 避免误把运行中的 job 覆写。Mutex 已保证 retry/cancel 串行化，无需担心并发。
     * @return 受影响行数（0 表示 job 不在终态，不应重试）
     */
    @Query("UPDATE novel_video_jobs SET status = :status, errorMessage = :err, updatedAt = :time WHERE id = :jobId AND status IN ('failed','partial_failed','cancelled')")
    suspend fun updateJobStatusForRetry(jobId: String, status: String, err: String?, time: Long = System.currentTimeMillis()): Int

    @Query("UPDATE novel_video_jobs SET outputPath = :outputPath, coverPath = :coverPath, totalDurationMs = :durationMs, status = :status, updatedAt = :time WHERE id = :jobId")
    suspend fun updateJobOutput(jobId: String, outputPath: String?, coverPath: String?, durationMs: Long?, status: String, time: Long = System.currentTimeMillis())

    /**
     * 条件部分更新 outputPath + 状态：仅当 job 尚未进入终态时才更新。
     *
     * 用于流水线中间态写入（如 MERGING），避免取消信号被覆写：
     * 若 markCancelledIfRunning 已写 CANCELLED，此处为 no-op，保持取消态。
     */
    @Query("UPDATE novel_video_jobs SET outputPath = :outputPath, coverPath = :coverPath, totalDurationMs = :durationMs, status = :status, updatedAt = :time WHERE id = :jobId AND status NOT IN ('completed','failed','partial_failed','cancelled')")
    suspend fun updateJobOutputIfNotFinished(jobId: String, outputPath: String?, coverPath: String?, durationMs: Long?, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_jobs SET draftJson = :draftJson, status = :status, updatedAt = :time WHERE id = :jobId")
    suspend fun updateJobDraft(jobId: String, draftJson: String?, status: String, time: Long = System.currentTimeMillis())

    /**
     * 条件部分更新 draftJson + 状态：仅当 job 尚未进入终态时才更新。
     * 避免取消后中间态（SCREENPLAY_PENDING_REVIEW）覆写 CANCELLED。
     */
    @Query("UPDATE novel_video_jobs SET draftJson = :draftJson, status = :status, updatedAt = :time WHERE id = :jobId AND status NOT IN ('completed','failed','partial_failed','cancelled')")
    suspend fun updateJobDraftIfNotFinished(jobId: String, draftJson: String?, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_jobs SET screenplayJson = :screenplayJson, status = :status, updatedAt = :time WHERE id = :jobId")
    suspend fun updateJobScreenplay(jobId: String, screenplayJson: String?, status: String, time: Long = System.currentTimeMillis())

    /**
     * 条件部分更新 screenplayJson + 状态：仅当 job 尚未进入终态时才更新。
     * 避免取消后中间态（SCREENPLAY_CONFIRMED）覆写 CANCELLED。
     */
    @Query("UPDATE novel_video_jobs SET screenplayJson = :screenplayJson, status = :status, updatedAt = :time WHERE id = :jobId AND status NOT IN ('completed','failed','partial_failed','cancelled')")
    suspend fun updateJobScreenplayIfNotFinished(jobId: String, screenplayJson: String?, status: String, time: Long = System.currentTimeMillis())

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

    @Query("SELECT * FROM novel_video_segments WHERE jobId = :jobId ORDER BY chapterIndex ASC, sceneId ASC")
    fun getSegmentsByJobFlow(jobId: String): Flow<List<NovelVideoSegment>>

    @Query("SELECT * FROM novel_video_segments WHERE jobId = :jobId AND chapterIndex = :chapterIndex ORDER BY sceneId ASC")
    suspend fun getSegmentsByChapter(jobId: String, chapterIndex: Int): List<NovelVideoSegment>

    @Query("SELECT * FROM novel_video_segments WHERE jobId = :jobId AND status = :status ORDER BY chapterIndex ASC, sceneId ASC")
    suspend fun getSegmentsByStatus(jobId: String, status: String): List<NovelVideoSegment>

    /**
     * 取下一个可恢复的 segment（含 failed 状态用于重试）。
     * 状态包含 pending/image_generating/image_completed/video_generating/failed，
     * 排除 video_completed（已完成）。
     */
    @Query("SELECT * FROM novel_video_segments WHERE jobId = :jobId AND status IN ('pending','image_generating','image_completed','video_generating','failed') ORDER BY chapterIndex ASC, sceneId ASC LIMIT 1")
    suspend fun getNextResumableSegment(jobId: String): NovelVideoSegment?

    @Query("UPDATE novel_video_segments SET status = :status, errorMessage = :err, updatedAt = :time WHERE id = :segmentId")
    suspend fun updateSegmentStatus(segmentId: String, status: String, err: String?, time: Long = System.currentTimeMillis())

    /** 标记 segment 失败并递增 retryCount；正常状态流转用 [updateSegmentStatus] 不递增。 */
    @Query("UPDATE novel_video_segments SET status = :status, errorMessage = :err, retryCount = retryCount + 1, updatedAt = :time WHERE id = :segmentId")
    suspend fun markSegmentFailed(segmentId: String, status: String = NovelVideoSegmentStatus.FAILED, err: String?, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_segments SET imageUrl = :imageUrl, status = :status, updatedAt = :time WHERE id = :segmentId")
    suspend fun updateSegmentImage(segmentId: String, imageUrl: String?, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_segments SET videoUrl = :videoUrl, localVideoPath = :localPath, durationMs = :durationMs, status = :status, updatedAt = :time WHERE id = :segmentId")
    suspend fun updateSegmentVideo(segmentId: String, videoUrl: String?, localPath: String?, durationMs: Long?, status: String, time: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN status = 'video_completed' THEN 1 ELSE 0 END), 0) AS completed, COALESCE(SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END), 0) AS failed FROM novel_video_segments WHERE jobId = :jobId")
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

    @Query("SELECT * FROM novel_video_character_sheets WHERE jobId = :jobId ORDER BY role ASC")
    fun getCharacterSheetsByJobFlow(jobId: String): Flow<List<NovelVideoCharacterSheet>>

    @Query("SELECT * FROM novel_video_character_sheets WHERE jobId = :jobId AND status = 'completed' ORDER BY role ASC")
    suspend fun getCompletedCharacterSheets(jobId: String): List<NovelVideoCharacterSheet>

    @Query("UPDATE novel_video_character_sheets SET combinedViewUrl = :url, localPath = :localPath, status = :status, updatedAt = :time WHERE id = :id")
    suspend fun updateCharacterSheetImage(id: String, url: String?, localPath: String?, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE novel_video_character_sheets SET status = :status, errorMessage = :err, updatedAt = :time WHERE id = :id")
    suspend fun updateCharacterSheetStatus(id: String, status: String, err: String?, time: Long = System.currentTimeMillis())

    /* --------------------- P1 调度层：claim/lease/mark --------------------- */

    /**
     * 原子认领下一个可调度 job（移植 ArcReel task_repo.py:213-247 的 SELECT+UPDATE 模式）。
     *
     * SQL 语义：在 RUNNING_STATES 中按 createdAt ASC 取最早的一个，原子 UPDATE 设
     * status='generating' + workerId + workerHeartbeatAt + attempts++，返回受影响行数。
     * - 排除 SCREENPLAY_PENDING_REVIEW / PAUSED（不可立即推进）
     * - availableAt <= now（回队延迟生效）
     *
     * @return 受影响行数（0=无可认领 job 或被并发抢走）
     */
    @Query(
        """
        UPDATE novel_video_jobs
        SET status = 'generating', workerId = :workerId, workerHeartbeatAt = :now,
            attempts = attempts + 1, updatedAt = :now
        WHERE id = (
            SELECT id FROM novel_video_jobs
            WHERE status IN ('drafting','screenplay_confirmed','generating','merging')
              AND availableAt <= :now
            ORDER BY createdAt ASC
            LIMIT 1
        )
        """
    )
    suspend fun claimNextJob(workerId: String, now: Long): Int

    /**
     * 原子认领下一个可调度 job（带 provider 黑名单过滤，池满场景）。
     * 排除 pool_full_providers 列表中的 provider。
     */
    @Query(
        """
        UPDATE novel_video_jobs
        SET status = 'generating', workerId = :workerId, workerHeartbeatAt = :now,
            attempts = attempts + 1, updatedAt = :now
        WHERE id = (
            SELECT id FROM novel_video_jobs
            WHERE status IN ('drafting','screenplay_confirmed','generating','merging')
              AND availableAt <= :now
              AND (providerId IS NULL OR providerId NOT IN (:excludeProviders))
            ORDER BY createdAt ASC
            LIMIT 1
        )
        """
    )
    suspend fun claimNextJobExcludingProviders(
        workerId: String,
        now: Long,
        excludeProviders: List<String>
    ): Int

    /**
     * 续约 lease（心跳）。仅当 workerId 匹配且 job 未进入终态时才更新。
     * @return 受影响行数（0=已失 lease 或已终态，worker 应停止处理该 job）
     */
    @Query(
        """
        UPDATE novel_video_jobs
        SET workerHeartbeatAt = :now, updatedAt = :now
        WHERE id = :jobId
          AND workerId = :workerId
          AND status NOT IN ('completed','failed','partial_failed','cancelled')
        """
    )
    suspend fun renewLease(jobId: String, workerId: String, now: Long): Int

    /**
     * 释放 lease（worker 主动放弃 job）。清空 workerId / workerHeartbeatAt。
     * 不改 status——让 job 保持当前运行态，下次可被重新 claim。
     */
    @Query("UPDATE novel_video_jobs SET workerId = NULL, workerHeartbeatAt = NULL, updatedAt = :now WHERE id = :jobId")
    suspend fun releaseLease(jobId: String, now: Long = System.currentTimeMillis())

    /**
     * 标记 job 成功（条件：当前在 running 态）。0-rows 表示已被并发终态覆写。
     * @return 受影响行数
     */
    @Query(
        """
        UPDATE novel_video_jobs
        SET status = :status, outputPath = :outputPath, coverPath = :coverPath,
            totalDurationMs = :durationMs, workerId = NULL, workerHeartbeatAt = NULL,
            updatedAt = :time
        WHERE id = :jobId
          AND status IN ('generating','merging','screenplay_confirmed','drafting')
        """
    )
    suspend fun markJobSucceededIfRunning(
        jobId: String,
        status: String,
        outputPath: String?,
        coverPath: String?,
        durationMs: Long?,
        time: Long = System.currentTimeMillis()
    ): Int

    /**
     * 标记 job 失败（条件：尚未进入终态）。0-rows 表示已被并发终态覆写。
     * @return 受影响行数
     */
    @Query(
        """
        UPDATE novel_video_jobs
        SET status = :status, errorMessage = :err, workerId = NULL, workerHeartbeatAt = NULL,
            updatedAt = :time
        WHERE id = :jobId
          AND status NOT IN ('completed','failed','partial_failed','cancelled')
        """
    )
    suspend fun markJobFailedIfRunning(
        jobId: String,
        status: String,
        err: String?,
        time: Long = System.currentTimeMillis()
    ): Int

    /**
     * 标记 job 取消（条件：当前在活跃态）。0-rows 表示已终态。
     * @return 受影响行数
     */
    @Query(
        """
        UPDATE novel_video_jobs
        SET status = :status, workerId = NULL, workerHeartbeatAt = NULL, updatedAt = :time
        WHERE id = :jobId
          AND status IN ('drafting','generating','merging','screenplay_confirmed','screenplay_pending_review')
        """
    )
    suspend fun markJobCancelledIfActive(
        jobId: String,
        status: String = NovelVideoJobStatus.CANCELLED,
        time: Long = System.currentTimeMillis()
    ): Int

    /**
     * 回队：把 running job 翻回 drafting（池满场景）。
     * 清空 workerId/heartbeat，设 availableAt 延迟重试。
     */
    @Query(
        """
        UPDATE novel_video_jobs
        SET status = 'drafting', workerId = NULL, workerHeartbeatAt = NULL,
            availableAt = :availableAt, updatedAt = :now
        WHERE id = :jobId AND status = 'generating'
        """
    )
    suspend fun requeueJob(
        jobId: String,
        availableAt: Long,
        now: Long = System.currentTimeMillis()
    ): Int

    /**
     * 查孤儿 job：状态在 running 但心跳超时（worker 已死）。
     */
    @Query(
        """
        SELECT * FROM novel_video_jobs
        WHERE status IN ('generating','merging')
          AND (workerHeartbeatAt IS NULL OR workerHeartbeatAt < :heartbeatTimeoutBefore)
        ORDER BY updatedAt ASC
        """
    )
    suspend fun getOrphanJobs(heartbeatTimeoutBefore: Long): List<NovelVideoJob>

    /** P0 segment provider_job_id 更新（Stage 6 提交后持久化）。 */
    @Query("UPDATE novel_video_segments SET providerJobId = :providerJobId, providerId = :providerId, updatedAt = :time WHERE id = :segmentId")
    suspend fun updateSegmentProviderJobId(
        segmentId: String,
        providerJobId: String?,
        providerId: String?,
        time: Long = System.currentTimeMillis()
    )

    /** P0 segment provider_job_id 清空（resume 完成后）。 */
    @Query("UPDATE novel_video_segments SET providerJobId = NULL, updatedAt = :time WHERE id = :segmentId")
    suspend fun clearSegmentProviderJobId(segmentId: String, time: Long = System.currentTimeMillis())

    /**
     * 条件更新 segment 状态：仅当 segment 未进入终态（video_completed/failed）时才更新。
     * P6 用于修复双 ViewModel 并发 retry+cancel 覆写问题。
     * @return 受影响行数
     */
    @Query(
        """
        UPDATE novel_video_segments
        SET status = :status, errorMessage = :err, updatedAt = :time
        WHERE id = :segmentId
          AND status NOT IN ('video_completed','failed')
        """
    )
    suspend fun updateSegmentStatusIfNotTerminal(
        segmentId: String,
        status: String,
        err: String?,
        time: Long = System.currentTimeMillis()
    ): Int
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
        // M3：原 `failed * 2 > total` 严格大于，50% 失败时误判 COMPLETED。
        // 改为 >= 让 50% 失败也标 PARTIAL_FAILED，避免用户看到"已完成"但视频缺失一半场景。
        get() = total > 0 && failed * 2 >= total
}
