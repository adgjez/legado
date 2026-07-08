package io.legado.app.help.ai.scheduling

import android.app.Application
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.dao.NovelVideoDao
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
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
 * [NovelVideoDao] P1 调度层方法的 Robolectric 集成测试。
 *
 * 验证 claim/lease/mark/requeue/orphan 系列方法在真实 SQLite 上行为正确，
 * 重点测 0-rows-cancelled 协议（WHERE 守卫防终态覆写）和 lease 心跳机制。
 *
 * 对照 ArcReel task_repo.py 的 claim SQL（L213-247）+ lease（L960-1002）+ orphan（L698-703）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class GenerationQueueDaoTest {

    private lateinit var db: TestSchedulingDatabase
    private lateinit var dao: NovelVideoDao

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, TestSchedulingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.novelVideoDao
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ============================================================
    // claimNextJob —— 原子认领
    // ============================================================

    @Test
    fun claimNextJobNoJobReturnsZero() = runTest {
        val affected = dao.claimNextJob("worker-1", System.currentTimeMillis())
        assertEquals("无 job 时应返回 0", 0, affected)
    }

    @Test
    fun claimNextJobDraftingJobClaimedSuccessfully() = runTest {
        val job = NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000)
        dao.insertJob(job)

        val now = 5000L
        val affected = dao.claimNextJob("worker-1", now)

        assertEquals("应认领成功", 1, affected)
        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.GENERATING, reloaded?.status)
        assertEquals("worker-1", reloaded?.workerId)
        assertEquals(now, reloaded?.workerHeartbeatAt)
        assertEquals("attempts 应递增", 1, reloaded?.attempts)
    }

    @Test
    fun claimNextJobPicksEarliestByCreatedAt() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_late", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000))
        dao.insertJob(NovelVideoJob(id = "job_early", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))

        val affected = dao.claimNextJob("worker-1", System.currentTimeMillis())
        assertEquals(1, affected)

        val running = dao.getRunningJobs().first { it.workerId == "worker-1" }
        assertEquals("应取最早的 job", "job_early", running.id)
    }

    @Test
    fun claimNextJobExcludesScreenplayPendingReview() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_review", status = NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW, createdAt = 1000))
        dao.insertJob(NovelVideoJob(id = "job_draft", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000))

        val affected = dao.claimNextJob("worker-1", System.currentTimeMillis())
        assertEquals(1, affected)

        val claimed = dao.getRunningJobs().first { it.workerId == "worker-1" }
        assertEquals("不应认领 PENDING_REVIEW", "job_draft", claimed.id)
    }

    @Test
    fun claimNextJobExcludesPaused() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_paused", status = NovelVideoJobStatus.PAUSED, createdAt = 1000))

        val affected = dao.claimNextJob("worker-1", System.currentTimeMillis())
        assertEquals("PAUSED 不应被认领", 0, affected)
    }

    @Test
    fun claimNextJobExcludesProvidersFiltersByProviderId() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_grok", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000, providerId = "grok"))
        dao.insertJob(NovelVideoJob(id = "job_agnes", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000, providerId = "agnes"))

        val affected = dao.claimNextJobExcludingProviders("worker-1", System.currentTimeMillis(), listOf("grok"))
        assertEquals(1, affected)

        val claimed = dao.getRunningJobs().first { it.workerId == "worker-1" }
        assertEquals("应跳过池满的 grok", "job_agnes", claimed.id)
    }

    @Test
    fun claimNextJobRespectsAvailableAtDelay() = runTest {
        // availableAt = now + 1000，尚未可调度
        dao.insertJob(NovelVideoJob(id = "job_delayed", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000, availableAt = 5000))

        val affected = dao.claimNextJob("worker-1", now = 3000)
        assertEquals("availableAt 未到不应被认领", 0, affected)

        // 时间到了
        val affected2 = dao.claimNextJob("worker-1", now = 5001)
        assertEquals(1, affected2)
    }

    // ============================================================
    // renewLease —— 心跳续约
    // ============================================================

    @Test
    fun renewLeaseUpdatesHeartbeatWhenWorkerIdMatches() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.GENERATING, workerId = "worker-1", workerHeartbeatAt = 1000))

        val affected = dao.renewLease("job_1", "worker-1", 2000)
        assertEquals(1, affected)
        assertEquals(2000L, dao.getJob("job_1")?.workerHeartbeatAt)
    }

    @Test
    fun renewLeaseRejectsMismatchedWorkerId() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.GENERATING, workerId = "worker-1", workerHeartbeatAt = 1000))

        val affected = dao.renewLease("job_1", "worker-2", 2000)
        assertEquals("workerId 不匹配应 0-rows", 0, affected)
        assertEquals("heartbeat 不应被更新", 1000L, dao.getJob("job_1")?.workerHeartbeatAt)
    }

    @Test
    fun renewLeaseRejectsTerminalStatus() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.COMPLETED, workerId = "worker-1", workerHeartbeatAt = 1000))

        val affected = dao.renewLease("job_1", "worker-1", 2000)
        assertEquals("终态不应续约", 0, affected)
    }

    // ============================================================
    // releaseLease —— 释放 lease
    // ============================================================

    @Test
    fun releaseLeaseClearsWorkerIdAndHeartbeat() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.GENERATING, workerId = "worker-1", workerHeartbeatAt = 1000))

        dao.releaseLease("job_1", 2000)

        val reloaded = dao.getJob("job_1")
        assertNull("workerId 应清空", reloaded?.workerId)
        assertNull("heartbeat 应清空", reloaded?.workerHeartbeatAt)
        assertEquals("status 不应变", NovelVideoJobStatus.GENERATING, reloaded?.status)
    }

    // ============================================================
    // markJobSucceededIfRunning —— 0-rows-cancelled 协议
    // ============================================================

    @Test
    fun markSucceededReturnsOneForRunningJob() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.GENERATING, workerId = "w"))

        val affected = dao.markJobSucceededIfRunning("job_1", NovelVideoJobStatus.COMPLETED, "/path/out.mp4", null, 60000)
        assertEquals(1, affected)

        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.COMPLETED, reloaded?.status)
        assertEquals("/path/out.mp4", reloaded?.outputPath)
        assertEquals(60000L, reloaded?.totalDurationMs)
        assertNull("workerId 应清空", reloaded?.workerId)
    }

    @Test
    fun markSucceededReturnsZeroForCancelledJob() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.CANCELLED))

        val affected = dao.markJobSucceededIfRunning("job_1", NovelVideoJobStatus.COMPLETED, null, null, null)
        assertEquals("终态不应被覆写", 0, affected)
    }

    // ============================================================
    // markJobFailedIfRunning —— 0-rows-cancelled 协议
    // ============================================================

    @Test
    fun markFailedReturnsOneForRunningJob() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.GENERATING, workerId = "w"))

        val affected = dao.markJobFailedIfRunning("job_1", NovelVideoJobStatus.FAILED, "some error")
        assertEquals(1, affected)

        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.FAILED, reloaded?.status)
        assertEquals("some error", reloaded?.errorMessage)
        assertNull("workerId 应清空", reloaded?.workerId)
    }

    @Test
    fun markFailedReturnsZeroForCompletedJob() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.COMPLETED))

        val affected = dao.markJobFailedIfRunning("job_1", NovelVideoJobStatus.FAILED, "late error")
        assertEquals(0, affected)
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("job_1")?.status)
    }

    // ============================================================
    // markJobCancelledIfActive
    // ============================================================

    @Test
    fun markCancelledReturnsOneForGeneratingJob() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.GENERATING, workerId = "w"))

        val affected = dao.markJobCancelledIfActive("job_1")
        assertEquals(1, affected)
        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
        assertNull(dao.getJob("job_1")?.workerId)
    }

    @Test
    fun markCancelledReturnsZeroForCompletedJob() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.COMPLETED))

        val affected = dao.markJobCancelledIfActive("job_1")
        assertEquals(0, affected)
    }

    // ============================================================
    // markJobFinalStatusClearingLease —— 第五轮审查 R14
    // ============================================================

    @Test
    fun markJobFinalStatusClearingLeaseClearsWorkerId() = runTest {
        dao.insertJob(NovelVideoJob(
            id = "job_1",
            status = NovelVideoJobStatus.MERGING,
            workerId = "worker-1",
            workerHeartbeatAt = 1000,
            outputPath = "/tmp/merged.mp4",
            totalDurationMs = 5000
        ))

        val affected = dao.markJobFinalStatusClearingLease(
            "job_1", NovelVideoJobStatus.COMPLETED, null
        )
        assertEquals("MERGING → COMPLETED 应 1-rows", 1, affected)

        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.COMPLETED, reloaded?.status)
        assertNull("R14：终态 workerId 应清空", reloaded?.workerId)
        assertNull("R14：终态 heartbeat 应清空", reloaded?.workerHeartbeatAt)
        assertEquals("outputPath 应保留（由 mergeCompletedSegments 写入）",
            "/tmp/merged.mp4", reloaded?.outputPath)
        assertEquals("totalDurationMs 应保留",
            5000L, reloaded?.totalDurationMs)
    }

    @Test
    fun markJobFinalStatusClearingLeaseReturnsZeroForTerminalJob() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.FAILED))

        val affected = dao.markJobFinalStatusClearingLease(
            "job_1", NovelVideoJobStatus.COMPLETED, null
        )
        assertEquals("终态不应被覆写", 0, affected)
    }

    // ============================================================
    // requeueJob —— 池满回队
    // ============================================================

    @Test
    fun requeueFlipsRunningBackToDraftingWithDelay() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.GENERATING, workerId = "w", workerHeartbeatAt = 1000))

        val affected = dao.requeueJob("job_1", availableAt = 5000, now = 3000)
        assertEquals(1, affected)

        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.DRAFTING, reloaded?.status)
        assertNull("workerId 应清空", reloaded?.workerId)
        assertEquals(5000L, reloaded?.availableAt)
    }

    @Test
    fun requeueRejectsNonRunningJob() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING))

        val affected = dao.requeueJob("job_1", availableAt = 5000)
        assertEquals("非 running 不应回队", 0, affected)
    }

    // ============================================================
    // getOrphanJobs —— 心跳超时判定
    // ============================================================

    @Test
    fun getOrphanJobsReturnsJobsWithStaleHeartbeat() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_fresh", status = NovelVideoJobStatus.GENERATING, workerId = "w", workerHeartbeatAt = 9000))
        dao.insertJob(NovelVideoJob(id = "job_stale", status = NovelVideoJobStatus.GENERATING, workerId = "w", workerHeartbeatAt = 1000))

        // threshold = 5000：job_stale (heartbeat=1000 < 5000) 是孤儿；job_fresh (9000 >= 5000) 不是
        val orphans = dao.getOrphanJobs(heartbeatTimeoutBefore = 5000)
        assertEquals(1, orphans.size)
        assertEquals("job_stale", orphans[0].id)
    }

    @Test
    fun getOrphanJobsReturnsJobsWithNullHeartbeat() = runTest {
        // 进程崩溃后重启：job 状态是 generating 但 workerHeartbeatAt 是 null（老数据或迁移）
        dao.insertJob(NovelVideoJob(id = "job_null_hb", status = NovelVideoJobStatus.GENERATING, workerHeartbeatAt = null))

        val orphans = dao.getOrphanJobs(heartbeatTimeoutBefore = System.currentTimeMillis())
        assertEquals("null heartbeat 应判为孤儿", 1, orphans.size)
    }

    @Test
    fun getOrphanJobsExcludesCompletedJobs() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_done", status = NovelVideoJobStatus.COMPLETED, workerHeartbeatAt = 1000))

        val orphans = dao.getOrphanJobs(heartbeatTimeoutBefore = 5000)
        assertTrue("终态 job 不应算孤儿", orphans.isEmpty())
    }

    // ============================================================
    // updateSegmentProviderJobId / clearSegmentProviderJobId
    // ============================================================

    @Test
    fun updateSegmentProviderJobIdPersistsProviderInfo() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1"))
        dao.insertSegment(io.legado.app.data.entities.NovelVideoSegment(id = "seg_1", jobId = "job_1"))

        dao.updateSegmentProviderJobId("seg_1", providerJobId = "agnes-task-123", providerId = "agnes")

        val seg = dao.getSegmentsByJob("job_1").first()
        assertEquals("agnes-task-123", seg.providerJobId)
        assertEquals("agnes", seg.providerId)
    }

    @Test
    fun clearSegmentProviderJobIdNullsProviderJobId() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1"))
        dao.insertSegment(io.legado.app.data.entities.NovelVideoSegment(id = "seg_1", jobId = "job_1", providerJobId = "agnes-task-123"))

        dao.clearSegmentProviderJobId("seg_1")

        val seg = dao.getSegmentsByJob("job_1").first()
        assertNull(seg.providerJobId)
    }

    // ============================================================
    // updateSegmentStatusIfNotTerminal —— P6 双 ViewModel 并发防护
    // ============================================================

    @Test
    fun updateSegmentStatusIfNotTerminalRejectsCompletedSegment() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1"))
        dao.insertSegment(io.legado.app.data.entities.NovelVideoSegment(
            id = "seg_1", jobId = "job_1",
            status = io.legado.app.data.entities.NovelVideoSegmentStatus.VIDEO_COMPLETED
        ))

        val affected = dao.updateSegmentStatusIfNotTerminal("seg_1", io.legado.app.data.entities.NovelVideoSegmentStatus.PENDING, null)
        assertEquals("终态 segment 不应被覆写", 0, affected)
    }

    @Test
    fun updateSegmentStatusIfNotTerminalAllowsPendingToUpdate() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1"))
        dao.insertSegment(io.legado.app.data.entities.NovelVideoSegment(
            id = "seg_1", jobId = "job_1",
            status = io.legado.app.data.entities.NovelVideoSegmentStatus.PENDING
        ))

        val affected = dao.updateSegmentStatusIfNotTerminal("seg_1", io.legado.app.data.entities.NovelVideoSegmentStatus.IMAGE_GENERATING, null)
        assertEquals(1, affected)
    }
}

@Database(
    entities = [
        NovelVideoJob::class,
        io.legado.app.data.entities.NovelVideoSegment::class,
        io.legado.app.data.entities.NovelVideoCharacterSheet::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TestSchedulingDatabase : RoomDatabase() {
    abstract val novelVideoDao: NovelVideoDao
}
