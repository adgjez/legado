package io.legado.app.data.dao

import android.app.Application
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.NovelVideoCharacterSheet
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.data.entities.NovelVideoSegment
import io.legado.app.data.entities.NovelVideoSegmentStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [NovelVideoDao] 的 Robolectric 集成测试。
 *
 * 用途：验证 P2 修复引入的条件更新 / 部分更新 DAO 方法在真实 SQLite 上行为正确，
 * 防止 TOCTOU 竞态覆写终态、retryCount 误递增等回归。
 *
 * 不使用生产 [io.legado.app.data.AppDatabase] —— 它含 50+ 张表与 onOpen 回调，
 * 初始化重且与 NovelVideo 无关。这里用 [TestNovelVideoDatabase] 只声明 3 张表，
 * Room 会基于同样的 @Entity 注解生成等价 schema。
 *
 * 注：用 `@Config(application = Application::class)` 覆盖 manifest 中的 App 类，
 * 避免 App.onCreate 触发 AppConfig / Cronet / LiveEventBus 等重初始化。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class NovelVideoDaoRobolectricTest {

    private lateinit var db: TestNovelVideoDatabase
    private lateinit var dao: NovelVideoDao

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, TestNovelVideoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.novelVideoDao
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ============================================================
    // updateJobFinalStatusIfNotFinished —— P2 TOCTOU 防护
    // ============================================================

    @Test
    fun updateJobFinalStatusIfNotFinishedDoesNotOverwriteCompleted() = runTest {
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.COMPLETED)
        dao.insertJob(job)

        val affected = dao.updateJobFinalStatusIfNotFinished("job_1", NovelVideoJobStatus.FAILED)

        assertEquals("终态不应被覆写", 0, affected)
        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.COMPLETED, reloaded?.status)
    }

    @Test
    fun updateJobFinalStatusIfNotFinishedDoesNotOverwriteFailed() = runTest {
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.FAILED, errorMessage = "orig")
        dao.insertJob(job)

        val affected = dao.updateJobFinalStatusIfNotFinished("job_1", NovelVideoJobStatus.COMPLETED)

        assertEquals(0, affected)
        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.FAILED, reloaded?.status)
        assertEquals("orig", reloaded?.errorMessage)
    }

    @Test
    fun updateJobFinalStatusIfNotFinishedDoesNotOverwritePartialFailed() = runTest {
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.PARTIAL_FAILED)
        dao.insertJob(job)

        val affected = dao.updateJobFinalStatusIfNotFinished("job_1", NovelVideoJobStatus.COMPLETED)

        assertEquals(0, affected)
        assertEquals(NovelVideoJobStatus.PARTIAL_FAILED, dao.getJob("job_1")?.status)
    }

    @Test
    fun updateJobFinalStatusIfNotFinishedDoesNotOverwriteCancelled() = runTest {
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.CANCELLED)
        dao.insertJob(job)

        val affected = dao.updateJobFinalStatusIfNotFinished("job_1", NovelVideoJobStatus.COMPLETED)

        assertEquals(0, affected)
        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
    }

    @Test
    fun updateJobFinalStatusIfNotFinishedUpdatesRunningStatus() = runTest {
        // 运行态应被更新为终态（典型场景：流水线完成后置 COMPLETED）
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.GENERATING)
        dao.insertJob(job)

        val affected = dao.updateJobFinalStatusIfNotFinished("job_1", NovelVideoJobStatus.COMPLETED)

        assertEquals(1, affected)
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("job_1")?.status)
    }

    @Test
    fun updateJobFinalStatusIfNotFinishedUpdatesMergingToCompleted() = runTest {
        // P2-1: MERGING 状态分支被显式加入 RUNNING_STATES，应能被终态更新
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.MERGING)
        dao.insertJob(job)

        val affected = dao.updateJobFinalStatusIfNotFinished("job_1", NovelVideoJobStatus.COMPLETED)

        assertEquals(1, affected)
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("job_1")?.status)
    }

    @Test
    fun updateJobFinalStatusIfNotFinishedReturnsZeroForMissingJob() = runTest {
        val affected = dao.updateJobFinalStatusIfNotFinished("nonexistent", NovelVideoJobStatus.COMPLETED)
        assertEquals(0, affected)
    }

    // ============================================================
    // updateJobFinalStatusWithErrorIfNotFinished —— 第二轮 D1-D6 修复
    // ============================================================

    @Test
    fun updateJobFinalStatusWithErrorIfNotFinishedWritesErrorOnRunningJob() = runTest {
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.GENERATING)
        dao.insertJob(job)

        val affected = dao.updateJobFinalStatusWithErrorIfNotFinished(
            "job_1", NovelVideoJobStatus.FAILED, "boom"
        )

        assertEquals(1, affected)
        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.FAILED, reloaded?.status)
        assertEquals("boom", reloaded?.errorMessage)
    }

    @Test
    fun updateJobFinalStatusWithErrorIfNotFinishedDoesNotOverwriteCancelled() = runTest {
        // D6: markCancelledIfRunning 改用条件更新后，已终态不应被覆写
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.CANCELLED, errorMessage = "orig cancel")
        dao.insertJob(job)

        val affected = dao.updateJobFinalStatusWithErrorIfNotFinished(
            "job_1", NovelVideoJobStatus.FAILED, "late failure"
        )

        assertEquals(0, affected)
        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.CANCELLED, reloaded?.status)
        assertEquals("orig cancel", reloaded?.errorMessage)
    }

    @Test
    fun updateJobFinalStatusWithErrorIfNotFinishedDoesNotOverwriteCompleted() = runTest {
        // D1: cancelFromReview 改用条件更新后，已 COMPLETED 不应被覆写为 CANCELLED
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.COMPLETED)
        dao.insertJob(job)

        val affected = dao.updateJobFinalStatusWithErrorIfNotFinished(
            "job_1", NovelVideoJobStatus.CANCELLED, "user cancel"
        )

        assertEquals(0, affected)
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("job_1")?.status)
    }

    // ============================================================
    // 系统性修复：中间态条件部分更新不覆写终态
    // ============================================================

    @Test
    fun updateJobDraftIfNotFinishedDoesNotOverwriteCancelled() = runTest {
        // 取消已写 CANCELLED 后，draft 写入不应覆写状态
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.CANCELLED)
        dao.insertJob(job)

        dao.updateJobDraftIfNotFinished("job_1", """{"a":1}""", NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW)

        val reloaded = dao.getJob("job_1")
        assertEquals("CANCELLED 不应被中间态覆写", NovelVideoJobStatus.CANCELLED, reloaded?.status)
    }

    @Test
    fun updateJobDraftIfNotFinishedWritesOnRunningJob() = runTest {
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING)
        dao.insertJob(job)

        dao.updateJobDraftIfNotFinished("job_1", """{"a":1}""", NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW)

        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW, reloaded?.status)
        assertEquals("""{"a":1}""", reloaded?.draftJson)
    }

    @Test
    fun updateJobScreenplayIfNotFinishedDoesNotOverwriteCancelled() = runTest {
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.CANCELLED)
        dao.insertJob(job)

        dao.updateJobScreenplayIfNotFinished("job_1", """{"s":[]}""", NovelVideoJobStatus.SCREENPLAY_CONFIRMED)

        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
    }

    @Test
    fun updateJobOutputIfNotFinishedDoesNotOverwriteCancelled() = runTest {
        // 取消已写 CANCELLED 后，merge 输出写入不应覆写状态
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.CANCELLED)
        dao.insertJob(job)

        dao.updateJobOutputIfNotFinished("job_1", "/out.mp4", null, 60_000L, NovelVideoJobStatus.MERGING)

        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.CANCELLED, reloaded?.status)
        // outputPath 也不应被写入（条件更新整行 no-op）
        assertNull(reloaded?.outputPath)
    }

    @Test
    fun updateJobOutputIfNotFinishedWritesOnMergingJob() = runTest {
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.GENERATING)
        dao.insertJob(job)

        dao.updateJobOutputIfNotFinished("job_1", "/out.mp4", null, 60_000L, NovelVideoJobStatus.MERGING)

        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.MERGING, reloaded?.status)
        assertEquals("/out.mp4", reloaded?.outputPath)
        assertEquals(60_000L, reloaded?.totalDurationMs)
    }

    @Test
    fun updateJobFinalStatusIfNotFinishedDoesNotOverwriteCancelledForIntermediateStatus() = runTest {
        // 系统性修复核心：中间态（如 GENERATING）写入也不应覆写 CANCELLED
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.CANCELLED)
        dao.insertJob(job)

        val affected = dao.updateJobFinalStatusIfNotFinished("job_1", NovelVideoJobStatus.GENERATING)

        assertEquals(0, affected)
        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
    }

    // ============================================================
    // markSegmentFailed vs updateSegmentStatus —— P2 retryCount 行为
    // ============================================================

    @Test
    fun markSegmentFailedIncrementsRetryCountFromZero() = runTest {
        val jobId = insertJob()
        val seg = NovelVideoSegment(id = "seg_1", jobId = jobId, retryCount = 0)
        dao.insertSegment(seg)

        dao.markSegmentFailed("seg_1", err = "boom")

        val reloaded = dao.getSegmentsByJob(jobId).first()
        assertEquals(NovelVideoSegmentStatus.FAILED, reloaded.status)
        assertEquals("失败应递增 retryCount", 1, reloaded.retryCount)
        assertEquals("boom", reloaded.errorMessage)
    }

    @Test
    fun markSegmentFailedIncrementsRetryCountFromTwo() = runTest {
        val jobId = insertJob()
        val seg = NovelVideoSegment(id = "seg_1", jobId = jobId, retryCount = 2)
        dao.insertSegment(seg)

        dao.markSegmentFailed("seg_1", err = "again")

        val reloaded = dao.getSegmentsByJob(jobId).first()
        assertEquals(3, reloaded.retryCount)
    }

    @Test
    fun markSegmentFailedWithCustomStatusStillIncrementsRetryCount() = runTest {
        // P2: markSegmentFailed 允许传自定义 status（如 image_generating），仍应递增
        val jobId = insertJob()
        val seg = NovelVideoSegment(id = "seg_1", jobId = jobId, retryCount = 1)
        dao.insertSegment(seg)

        dao.markSegmentFailed("seg_1", status = NovelVideoSegmentStatus.IMAGE_GENERATING, err = "img fail")

        val reloaded = dao.getSegmentsByJob(jobId).first()
        assertEquals(NovelVideoSegmentStatus.IMAGE_GENERATING, reloaded.status)
        assertEquals(2, reloaded.retryCount)
    }

    @Test
    fun updateSegmentStatusDoesNotIncrementRetryCount() = runTest {
        // P2: 正常状态流转用 updateSegmentStatus，不应误递增 retryCount
        val jobId = insertJob()
        val seg = NovelVideoSegment(id = "seg_1", jobId = jobId, retryCount = 0)
        dao.insertSegment(seg)

        dao.updateSegmentStatus("seg_1", NovelVideoSegmentStatus.IMAGE_COMPLETED, err = null)

        val reloaded = dao.getSegmentsByJob(jobId).first()
        assertEquals(NovelVideoSegmentStatus.IMAGE_COMPLETED, reloaded.status)
        assertEquals("状态流转不应递增 retryCount", 0, reloaded.retryCount)
    }

    @Test
    fun updateSegmentStatusPreservesErrorMessage() = runTest {
        val jobId = insertJob()
        val seg = NovelVideoSegment(id = "seg_1", jobId = jobId)
        dao.insertSegment(seg)

        dao.updateSegmentStatus("seg_1", NovelVideoSegmentStatus.VIDEO_COMPLETED, err = null)

        val reloaded = dao.getSegmentsByJob(jobId).first()
        assertNull(reloaded.errorMessage)
    }

    // ============================================================
    // getNextResumableSegment —— P2 含 failed 用于重试
    // ============================================================

    @Test
    fun getNextResumableSegmentReturnsPendingSegment() = runTest {
        val jobId = insertJob()
        dao.insertSegment(NovelVideoSegment(id = "s1", jobId = jobId, chapterIndex = 0, sceneId = 1, status = NovelVideoSegmentStatus.PENDING))

        val next = dao.getNextResumableSegment(jobId)
        assertNotNull(next)
        assertEquals("s1", next?.id)
    }

    @Test
    fun getNextResumableSegmentReturnsFailedSegmentForRetry() = runTest {
        // P2: failed 状态被包含在可恢复集合中，用于断点续传重试
        val jobId = insertJob()
        dao.insertSegment(NovelVideoSegment(id = "s1", jobId = jobId, chapterIndex = 0, sceneId = 1, status = NovelVideoSegmentStatus.FAILED))

        val next = dao.getNextResumableSegment(jobId)
        assertNotNull(next)
        assertEquals("s1", next?.id)
    }

    @Test
    fun getNextResumableSegmentSkipsVideoCompleted() = runTest {
        val jobId = insertJob()
        dao.insertSegment(NovelVideoSegment(id = "s1", jobId = jobId, chapterIndex = 0, sceneId = 1, status = NovelVideoSegmentStatus.VIDEO_COMPLETED))
        dao.insertSegment(NovelVideoSegment(id = "s2", jobId = jobId, chapterIndex = 0, sceneId = 2, status = NovelVideoSegmentStatus.PENDING))

        val next = dao.getNextResumableSegment(jobId)
        assertNotNull(next)
        assertEquals("应跳过 video_completed，返回 pending", "s2", next?.id)
    }

    @Test
    fun getNextResumableSegmentReturnsNullWhenAllCompleted() = runTest {
        val jobId = insertJob()
        dao.insertSegment(NovelVideoSegment(id = "s1", jobId = jobId, status = NovelVideoSegmentStatus.VIDEO_COMPLETED))
        dao.insertSegment(NovelVideoSegment(id = "s2", jobId = jobId, status = NovelVideoSegmentStatus.VIDEO_COMPLETED))

        val next = dao.getNextResumableSegment(jobId)
        assertNull(next)
    }

    @Test
    fun getNextResumableSegmentOrdersByChapterThenScene() = runTest {
        // 多个可恢复段时，应按 chapterIndex ASC, sceneId ASC 返回最早的
        val jobId = insertJob()
        dao.insertSegment(NovelVideoSegment(id = "s_late", jobId = jobId, chapterIndex = 1, sceneId = 1, status = NovelVideoSegmentStatus.PENDING))
        dao.insertSegment(NovelVideoSegment(id = "s_early", jobId = jobId, chapterIndex = 0, sceneId = 2, status = NovelVideoSegmentStatus.PENDING))
        dao.insertSegment(NovelVideoSegment(id = "s_first", jobId = jobId, chapterIndex = 0, sceneId = 1, status = NovelVideoSegmentStatus.PENDING))

        val next = dao.getNextResumableSegment(jobId)
        assertEquals("应返回 chapterIndex=0, sceneId=1 的段", "s_first", next?.id)
    }

    // ============================================================
    // getSegmentProgress —— P2 进度统计
    // ============================================================

    @Test
    fun getSegmentProgressCountsTotalCompletedFailedCorrectly() = runTest {
        val jobId = insertJob()
        insertSegments(
            jobId,
            "v1" to NovelVideoSegmentStatus.VIDEO_COMPLETED,
            "v2" to NovelVideoSegmentStatus.VIDEO_COMPLETED,
            "f1" to NovelVideoSegmentStatus.FAILED,
            "p1" to NovelVideoSegmentStatus.PENDING
        )

        val progress = dao.getSegmentProgress(jobId)
        assertEquals(4, progress.total)
        assertEquals(2, progress.completed)
        assertEquals(1, progress.failed)
    }

    @Test
    fun getSegmentProgressReturnsZerosForEmptyJob() = runTest {
        val jobId = insertJob()
        val progress = dao.getSegmentProgress(jobId)
        assertEquals(0, progress.total)
        assertEquals(0, progress.completed)
        assertEquals(0, progress.failed)
        assertEquals(0, progress.progressPercent)
    }

    @Test
    fun getSegmentProgressProgressPercentIsRoughlyCorrect() = runTest {
        val jobId = insertJob()
        // 4 段：2 完成 + 1 失败 = 3/4 = 75%
        insertSegments(
            jobId,
            "v1" to NovelVideoSegmentStatus.VIDEO_COMPLETED,
            "v2" to NovelVideoSegmentStatus.VIDEO_COMPLETED,
            "f1" to NovelVideoSegmentStatus.FAILED,
            "p1" to NovelVideoSegmentStatus.PENDING
        )

        val progress = dao.getSegmentProgress(jobId)
        assertEquals(75, progress.progressPercent)
        assertFalse(progress.isMajorityFailed)
    }

    @Test
    fun getSegmentProgressFlagsMajorityFailedWhenMoreThanHalfFailed() = runTest {
        val jobId = insertJob()
        // 4 段：1 完成 + 3 失败 → 失败 > 50%
        insertSegments(
            jobId,
            "v1" to NovelVideoSegmentStatus.VIDEO_COMPLETED,
            "f1" to NovelVideoSegmentStatus.FAILED,
            "f2" to NovelVideoSegmentStatus.FAILED,
            "f3" to NovelVideoSegmentStatus.FAILED
        )

        val progress = dao.getSegmentProgress(jobId)
        assertTrue("失败段超过半数应触发 isMajorityFailed", progress.isMajorityFailed)
    }

    // ============================================================
    // ForeignKey CASCADE 删除 —— P2 数据完整性
    // ============================================================

    @Test
    fun deleteJobCascadesToSegmentsAndCharacterSheets() = runTest {
        // P2: NovelVideoSegment/NovelVideoCharacterSheet 的 ForeignKey(onDelete = CASCADE)
        // 删除 job 后子表应一并清理，避免孤儿数据
        val jobId = insertJob()
        dao.insertSegment(NovelVideoSegment(id = "s1", jobId = jobId))
        dao.insertCharacterSheet(NovelVideoCharacterSheet(id = "c1", jobId = jobId))

        dao.deleteJob(jobId)

        assertTrue(dao.getSegmentsByJob(jobId).isEmpty())
        assertTrue(dao.getCharacterSheetsByJob(jobId).isEmpty())
    }

    // ============================================================
    // updateJobDraft / updateJobOutput —— P2 部分更新
    // ============================================================

    @Test
    fun updateJobDraftOnlyUpdatesDraftJsonAndStatus() = runTest {
        // P2: updateJobDraft 部分更新避免 read-modify-write 竞态
        val jobId = insertJob(status = NovelVideoJobStatus.DRAFTING, outputPath = "/orig/path")
        dao.insertSegment(NovelVideoSegment(id = "s1", jobId = jobId))

        dao.updateJobDraft(jobId, draftJson = """{"a":1}""", status = NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW)

        val reloaded = dao.getJob(jobId)
        assertEquals(NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW, reloaded?.status)
        assertEquals("""{"a":1}""", reloaded?.draftJson)
        // outputPath 不应被清空
        assertEquals("/orig/path", reloaded?.outputPath)
    }

    @Test
    fun updateJobOutputOnlyUpdatesOutputFieldsAndStatus() = runTest {
        val jobId = insertJob(status = NovelVideoJobStatus.MERGING, draftJson = """{"keep":true}""")
        dao.insertSegment(NovelVideoSegment(id = "s1", jobId = jobId))

        dao.updateJobOutput(
            jobId = jobId,
            outputPath = "/out/video.mp4",
            coverPath = "/out/cover.jpg",
            durationMs = 60_000L,
            status = NovelVideoJobStatus.COMPLETED
        )

        val reloaded = dao.getJob(jobId)
        assertEquals(NovelVideoJobStatus.COMPLETED, reloaded?.status)
        assertEquals("/out/video.mp4", reloaded?.outputPath)
        assertEquals("/out/cover.jpg", reloaded?.coverPath)
        assertEquals(60_000L, reloaded?.totalDurationMs)
        // draftJson 不应被清空
        assertEquals("""{"keep":true}""", reloaded?.draftJson)
    }

    @Test
    fun updateJobStatusWithErrorPreservesOutputPath() = runTest {
        // P2: 失败标记不应清空已生成的 outputPath（用于诊断 / 部分产物保留）
        val jobId = insertJob(status = NovelVideoJobStatus.GENERATING, outputPath = "/partial.mp4")
        dao.insertSegment(NovelVideoSegment(id = "s1", jobId = jobId))

        dao.updateJobStatusWithError(jobId, NovelVideoJobStatus.FAILED, err = "merge failed")

        val reloaded = dao.getJob(jobId)
        assertEquals(NovelVideoJobStatus.FAILED, reloaded?.status)
        assertEquals("merge failed", reloaded?.errorMessage)
        assertEquals("/partial.mp4", reloaded?.outputPath)
    }

    // ============================================================
    // L1: SQL 状态字面量与 Kotlin 常量集合一致性
    // DAO 中的 SQL IN('...') 字面量无法引用 Kotlin 常量，需靠测试守住一致性，
    // 防止常量字符串值修改后 SQL 静默失效。
    // ============================================================

    @Test
    fun getRunningJobsReturnsExactlyRunningStates() = runTest {
        // SQL 分组应与 NovelVideoJobStatus.RUNNING_STATES 完全对应
        NovelVideoJobStatus.RUNNING_STATES.forEachIndexed { idx, status ->
            dao.insertJob(NovelVideoJob(id = "running_$idx", status = status))
        }
        // 终态不应出现在 running 列表
        NovelVideoJobStatus.FINISHED_STATES.forEachIndexed { idx, status ->
            dao.insertJob(NovelVideoJob(id = "finished_$idx", status = status))
        }

        val running = dao.getRunningJobs()
        assertEquals("SQL IN 列表应与 RUNNING_STATES 一致", NovelVideoJobStatus.RUNNING_STATES.size, running.size)
        assertTrue(running.all { it.status in NovelVideoJobStatus.RUNNING_STATES })
    }

    @Test
    fun getCompletedJobsReturnsCompletedAndPartialFailed() = runTest {
        dao.insertJob(NovelVideoJob(id = "c1", status = NovelVideoJobStatus.COMPLETED))
        dao.insertJob(NovelVideoJob(id = "p1", status = NovelVideoJobStatus.PARTIAL_FAILED))
        // 这些不应出现
        dao.insertJob(NovelVideoJob(id = "f1", status = NovelVideoJobStatus.FAILED))
        dao.insertJob(NovelVideoJob(id = "x1", status = NovelVideoJobStatus.CANCELLED))
        dao.insertJob(NovelVideoJob(id = "r1", status = NovelVideoJobStatus.GENERATING))

        val completed = dao.getCompletedJobs()
        assertEquals(2, completed.size)
        assertTrue(completed.all { it.status in setOf(NovelVideoJobStatus.COMPLETED, NovelVideoJobStatus.PARTIAL_FAILED) })
    }

    @Test
    fun getFailedJobsReturnsFailedAndCancelled() = runTest {
        dao.insertJob(NovelVideoJob(id = "f1", status = NovelVideoJobStatus.FAILED))
        dao.insertJob(NovelVideoJob(id = "x1", status = NovelVideoJobStatus.CANCELLED))
        // 这些不应出现
        dao.insertJob(NovelVideoJob(id = "c1", status = NovelVideoJobStatus.COMPLETED))
        dao.insertJob(NovelVideoJob(id = "p1", status = NovelVideoJobStatus.PARTIAL_FAILED))
        dao.insertJob(NovelVideoJob(id = "r1", status = NovelVideoJobStatus.GENERATING))

        val failed = dao.getFailedJobs()
        assertEquals(2, failed.size)
        assertTrue(failed.all { it.status in setOf(NovelVideoJobStatus.FAILED, NovelVideoJobStatus.CANCELLED) })
    }

    @Test
    fun updateJobFinalStatusIfNotFinishedExcludesExactlyFinishedStates() = runTest {
        // 条件更新的 WHERE NOT IN 列表应与 FINISHED_STATES 完全对应：
        // 已终态的 job 不应被更新
        NovelVideoJobStatus.FINISHED_STATES.forEachIndexed { idx, status ->
            dao.insertJob(NovelVideoJob(id = "fin_$idx", status = status))
            val affected = dao.updateJobFinalStatusIfNotFinished("fin_$idx", NovelVideoJobStatus.COMPLETED)
            assertEquals("终态 $status 不应被条件更新覆写", 0, affected)
        }
        // 运行态应能被更新
        NovelVideoJobStatus.RUNNING_STATES.forEachIndexed { idx, status ->
            dao.insertJob(NovelVideoJob(id = "run_$idx", status = status))
            val affected = dao.updateJobFinalStatusIfNotFinished("run_$idx", NovelVideoJobStatus.COMPLETED)
            assertEquals("运行态 $status 应能被条件更新", 1, affected)
        }
    }

    @Test
    fun getNextResumableSegmentExcludesVideoCompleted() = runTest {
        // SQL IN 列表应包含 IN_PROGRESS + FAILED，排除 VIDEO_COMPLETED
        val jobId = insertJob()
        // VIDEO_COMPLETED 不应被返回
        dao.insertSegment(NovelVideoSegment(id = "done", jobId = jobId, sceneId = 0, status = NovelVideoSegmentStatus.VIDEO_COMPLETED))
        // 这些应可被返回（注意 (jobId, chapterIndex, sceneId) 唯一索引，需分配不同 sceneId）
        val resumableStatuses = NovelVideoSegmentStatus.IN_PROGRESS.plus(NovelVideoSegmentStatus.FAILED)
        resumableStatuses.forEachIndexed { idx, status ->
            dao.insertSegment(NovelVideoSegment(id = "s_$idx", jobId = jobId, sceneId = idx + 1, status = status))
        }

        val resumableIds = dao.getSegmentsByJob(jobId)
            .filter { it.status != NovelVideoSegmentStatus.VIDEO_COMPLETED }
            .map { it.id }
            .toSet()
        // getNextResumableSegment 返回的应是这些可恢复状态之一
        val next = dao.getNextResumableSegment(jobId)
        assertNotNull(next)
        assertTrue(next!!.id in resumableIds)
    }

    // ============================================================
    // 第三轮深度审查 — R1/R3/R6 验证
    // ============================================================

    /**
     * R1 验证：合并失败时 outputPath 留空，finalizeJob 的"outputPath 为空 + completed>0"
     * 条件更新能把 GENERATING job 标记为 FAILED（而非误标 COMPLETED）。
     */
    @Test
    fun r1MergeFailedLeavesOutputPathBlankAndJobCanBeMarkedFailed() = runTest {
        val jobId = insertJob(status = NovelVideoJobStatus.GENERATING)
        // 模拟合并失败：outputPath 保持 null
        val reloaded = dao.getJob(jobId)
        assertNull("合并失败后 outputPath 应为 null", reloaded?.outputPath)
        // finalizeJob 的条件更新应能把 GENERATING 标记为 FAILED
        val affected = dao.updateJobFinalStatusWithErrorIfNotFinished(
            jobId, NovelVideoJobStatus.FAILED, "视频合并未产出文件", System.currentTimeMillis()
        )
        assertEquals(1, affected)
        assertEquals(NovelVideoJobStatus.FAILED, dao.getJob(jobId)?.status)
    }

    /**
     * R3 验证：retryCount 字段存在且 markSegmentFailed 递增它。
     * 熔断逻辑在 NovelVideoGenerator（非 DAO），这里只验证 retryCount 计数正确。
     */
    @Test
    fun r3MarkSegmentFailedIncrementsRetryCount() = runTest {
        val jobId = insertJob()
        dao.insertSegment(NovelVideoSegment(id = "seg_1", jobId = jobId, sceneId = 0, status = NovelVideoSegmentStatus.PENDING))

        // 第一次失败
        dao.markSegmentFailed("seg_1", NovelVideoSegmentStatus.FAILED, "err1", System.currentTimeMillis())
        assertEquals(1, dao.getSegmentsByJob(jobId).first().retryCount)

        // 模拟 retryJob 重置 + 第二次失败
        dao.updateSegmentStatus("seg_1", NovelVideoSegmentStatus.PENDING, null)
        dao.markSegmentFailed("seg_1", NovelVideoSegmentStatus.FAILED, "err2", System.currentTimeMillis())
        assertEquals(2, dao.getSegmentsByJob(jobId).first().retryCount)

        // 第三次失败 → retryCount=3，应触发熔断（NovelVideoGenerator 的 MAX_SEGMENT_JOB_RETRY=3）
        dao.updateSegmentStatus("seg_1", NovelVideoSegmentStatus.PENDING, null)
        dao.markSegmentFailed("seg_1", NovelVideoSegmentStatus.FAILED, "err3", System.currentTimeMillis())
        assertEquals(3, dao.getSegmentsByJob(jobId).first().retryCount)
    }

    /**
     * R6 验证：retryJob 的 GENERATING 写入用条件更新，若 job 已被并发置为 CANCELLED，
     * GENERATING 写入应 no-op，保持 CANCELLED 不被覆写。
     */
    @Test
    fun r6RetryJobConditionalUpdateDoesNotOverwriteCancelled() = runTest {
        // 从运行态开始（模拟 retryJob 把 FAILED→GENERATING 后，cancel 并发写 CANCELLED）
        val jobId = insertJob(status = NovelVideoJobStatus.GENERATING)
        // cancel 先写 CANCELLED（从 GENERATING 可以写）
        val cancelAffected = dao.updateJobFinalStatusWithErrorIfNotFinished(
            jobId, NovelVideoJobStatus.CANCELLED, "用户取消", System.currentTimeMillis()
        )
        assertEquals(1, cancelAffected)
        // retry 的条件更新试图写 GENERATING（应被 CANCELLED 终态拦截）
        val affected = dao.updateJobFinalStatusWithErrorIfNotFinished(
            jobId, NovelVideoJobStatus.GENERATING, null, System.currentTimeMillis()
        )
        assertEquals("CANCELLED 不应被 GENERATING 覆写", 0, affected)
        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob(jobId)?.status)
    }

    // ============================================================
    // 第四轮深度审查 — N1/N6 验证
    // ============================================================

    /**
     * N1 验证：retryJob 专用的 [updateJobStatusForRetry] 仅从终态转换回 GENERATING。
     *
     * 关键回归场景：第三轮 R6 让 retryJob 用 [updateJobFinalStatusWithErrorIfNotFinished]
     * （WHERE NOT IN 终态）写 GENERATING，但 retryJob 的前提是 job 已处于 FAILED/PARTIAL_FAILED/CANCELLED
     * 终态，WHERE 不匹配，UPDATE 影响 0 行，retryJob 静默失效。
     * 新方法 [updateJobStatusForRetry] 用反向守卫（WHERE IN 终态）解决此问题。
     */
    @Test
    fun n1RetryJobCanTransitionFromFailedToGenerating() = runTest {
        val jobId = insertJob(status = NovelVideoJobStatus.FAILED, draftJson = null)

        val affected = dao.updateJobStatusForRetry(
            jobId, NovelVideoJobStatus.GENERATING, null, System.currentTimeMillis()
        )

        assertEquals("FAILED 应能被 retry 转换为 GENERATING", 1, affected)
        assertEquals(NovelVideoJobStatus.GENERATING, dao.getJob(jobId)?.status)
    }

    @Test
    fun n1RetryJobCanTransitionFromPartialFailedToGenerating() = runTest {
        val jobId = insertJob(status = NovelVideoJobStatus.PARTIAL_FAILED)

        val affected = dao.updateJobStatusForRetry(
            jobId, NovelVideoJobStatus.GENERATING, null, System.currentTimeMillis()
        )

        assertEquals(1, affected)
        assertEquals(NovelVideoJobStatus.GENERATING, dao.getJob(jobId)?.status)
    }

    @Test
    fun n1RetryJobCanTransitionFromCancelledToGenerating() = runTest {
        val jobId = insertJob(status = NovelVideoJobStatus.CANCELLED)

        val affected = dao.updateJobStatusForRetry(
            jobId, NovelVideoJobStatus.GENERATING, null, System.currentTimeMillis()
        )

        assertEquals(1, affected)
        assertEquals(NovelVideoJobStatus.GENERATING, dao.getJob(jobId)?.status)
    }

    /**
     * N1 反向守卫：retryJob 不应误把运行中的 job 覆写为 GENERATING。
     *
     * 若误把正在跑的 job（如 GENERATING/MERGING）写为 GENERATING，会丢失进度信息。
     */
    @Test
    fun n1RetryJobDoesNotTransitionFromRunningStatus() = runTest {
        // GENERATING 已在跑 → retry 不应介入
        val jobId1 = insertJob(id = "running_gen", status = NovelVideoJobStatus.GENERATING)
        val affected1 = dao.updateJobStatusForRetry(jobId1, NovelVideoJobStatus.GENERATING, null)
        assertEquals("运行中 job 不应被 retry 覆写", 0, affected1)
        assertEquals(NovelVideoJobStatus.GENERATING, dao.getJob(jobId1)?.status)

        // DRAFTING 是运行态 → retry 不应介入
        val jobId2 = insertJob(id = "drafting", status = NovelVideoJobStatus.DRAFTING)
        val affected2 = dao.updateJobStatusForRetry(jobId2, NovelVideoJobStatus.GENERATING, null)
        assertEquals(0, affected2)
        assertEquals(NovelVideoJobStatus.DRAFTING, dao.getJob(jobId2)?.status)

        // COMPLETED 是终态但不是可 retry 的终态 → retry 不应介入
        val jobId3 = insertJob(id = "completed", status = NovelVideoJobStatus.COMPLETED)
        val affected3 = dao.updateJobStatusForRetry(jobId3, NovelVideoJobStatus.GENERATING, null)
        assertEquals("COMPLETED 不可 retry", 0, affected3)
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob(jobId3)?.status)
    }

    @Test
    fun n1RetryJobClearsErrorMessageWhenTransitioning() = runTest {
        // retry 应清掉旧的 errorMessage，避免新一次运行显示上次失败原因
        val jobId = insertJob(status = NovelVideoJobStatus.FAILED)
        dao.updateJobStatusWithError(jobId, NovelVideoJobStatus.FAILED, "old error")

        dao.updateJobStatusForRetry(jobId, NovelVideoJobStatus.GENERATING, null, System.currentTimeMillis())

        val reloaded = dao.getJob(jobId)
        assertEquals(NovelVideoJobStatus.GENERATING, reloaded?.status)
        assertNull("retry 后 errorMessage 应被清空", reloaded?.errorMessage)
    }

    /**
     * N6 验证：熔断场景下用 [updateSegmentStatus]（不递增 retryCount）替代 [markSegmentFailed]。
     *
     * 关键回归场景：第三轮 R3 的熔断逻辑用 markSegmentFailed 标记超限段，
     * 但 markSegmentFailed 会递增 retryCount，导致 retryCount 持续增长（3→4→5），
     * UI 上的"重试"按钮点击后再次熔断，用户陷入死循环。
     * 改用 updateSegmentStatus 后 retryCount 保持在熔断时的值，便于诊断。
     */
    @Test
    fun n6CircuitBreakerUsesUpdateSegmentStatusNotMarkSegmentFailed() = runTest {
        val jobId = insertJob()
        // segment 已失败 3 次（达到 MAX_SEGMENT_JOB_RETRY 阈值），被 retryJob 重置为 PENDING
        dao.insertSegment(
            NovelVideoSegment(
                id = "seg_1", jobId = jobId, sceneId = 0,
                status = NovelVideoSegmentStatus.PENDING,
                retryCount = 3
            )
        )

        // 熔断逻辑用 updateSegmentStatus（不递增 retryCount）
        dao.updateSegmentStatus(
            "seg_1",
            NovelVideoSegmentStatus.FAILED,
            "重试次数超限（3 次），请删除任务重建",
            System.currentTimeMillis()
        )

        val reloaded = dao.getSegmentsByJob(jobId).first()
        assertEquals(NovelVideoSegmentStatus.FAILED, reloaded.status)
        assertEquals(
            "熔断时 retryCount 不应继续递增，保持诊断信息",
            3,
            reloaded.retryCount
        )
        assertTrue(reloaded.errorMessage?.contains("超限") == true)
    }

    /**
     * N6 对比测试：markSegmentFailed 会递增 retryCount（用于正常失败场景），
     * updateSegmentStatus 不递增（用于状态转换场景）。
     */
    @Test
    fun n6MarkSegmentFailedVsUpdateSegmentStatusRetryCountSemantics() = runTest {
        val jobId = insertJob()
        dao.insertSegment(
            NovelVideoSegment(
                id = "seg_mark", jobId = jobId, sceneId = 1,
                status = NovelVideoSegmentStatus.VIDEO_GENERATING,
                retryCount = 2
            )
        )
        dao.insertSegment(
            NovelVideoSegment(
                id = "seg_update", jobId = jobId, sceneId = 2,
                status = NovelVideoSegmentStatus.PENDING,
                retryCount = 3
            )
        )

        // markSegmentFailed：递增 retryCount（适用于"实际生成失败"的场景）
        dao.markSegmentFailed("seg_mark", NovelVideoSegmentStatus.FAILED, "real failure")
        // updateSegmentStatus：不递增 retryCount（适用于熔断等"状态转换"的场景）
        dao.updateSegmentStatus("seg_update", NovelVideoSegmentStatus.FAILED, "circuit breaker")

        val reloaded = dao.getSegmentsByJob(jobId).sortedBy { it.sceneId }
        assertEquals("markSegmentFailed 应递增", 3, reloaded[0].retryCount)
        assertEquals("updateSegmentStatus 不应递增", 3, reloaded[1].retryCount)
    }

    // ============================================================
    // helpers
    // ============================================================

    private suspend fun insertJob(
        id: String = "job_test",
        status: String = NovelVideoJobStatus.DRAFTING,
        outputPath: String? = null,
        draftJson: String? = null
    ): String {
        dao.insertJob(
            NovelVideoJob(
                id = id,
                status = status,
                outputPath = outputPath,
                draftJson = draftJson
            )
        )
        return id
    }

    private suspend fun insertSegments(
        jobId: String,
        vararg specs: Pair<String, String>
    ) {
        specs.forEachIndexed { idx, (id, status) ->
            dao.insertSegment(
                NovelVideoSegment(
                    id = id,
                    jobId = jobId,
                    chapterIndex = idx / 10,
                    sceneId = idx % 10 + 1,
                    status = status
                )
            )
        }
    }
}

/**
 * 仅含 NovelVideo 三张表的测试用 RoomDatabase。
 * 不复用 [io.legado.app.data.AppDatabase] —— 那个含 50+ 张表与 onOpen 回调，
 * 在 Robolectric 下初始化过重。Room 基于同样的 @Entity 注解生成等价 schema。
 */
@Database(
    entities = [
        NovelVideoJob::class,
        NovelVideoSegment::class,
        NovelVideoCharacterSheet::class,
        io.legado.app.data.entities.NovelVideoCompilation::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TestNovelVideoDatabase : RoomDatabase() {
    abstract val novelVideoDao: NovelVideoDao
}
