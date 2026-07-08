package io.legado.app.help.ai.scheduling

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.dao.NovelVideoDao
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * [GenerationWorker] 的 Robolectric 集成测试。
 *
 * 验证 P3 的主循环 + 0-rows-cancelled 协议 + orphan 恢复 + 池满过滤。
 *
 * 关键技术：
 * - [GenerationQueue.daoProvider] 注入 in-memory Room（[TestSchedulingDatabase]）
 * - [GenerationWorker] 的 `generator` / `resumeJob` 构造参数注入 mock lambda，
 *   绕过 `NovelVideoGenerator` object 不可 mock 的问题
 * - `backgroundScope`（SupervisorJob）跑 runLoop，processTask 子协程异常不互相影响
 * - `advanceUntilIdle()` 推进虚拟时间让挂起协程跑完
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class GenerationWorkerTest {

    private lateinit var db: TestSchedulingDatabase
    private lateinit var dao: NovelVideoDao
    private var originalDaoProvider: (() -> NovelVideoDao)? = null

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, TestSchedulingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.novelVideoDao
        // 注入测试 dao（@After 恢复，避免污染其他测试类）
        originalDaoProvider = GenerationQueue.daoProvider
        GenerationQueue.daoProvider = { dao }
    }

    @After
    fun tearDown() {
        originalDaoProvider?.let { GenerationQueue.daoProvider = it }
        db.close()
    }

    // ============================================================
    // runLoop 主循环
    // ============================================================

    @Test
    fun runLoopNoJobExitsCleanlyWhenCancelled() = runTest {
        // 无 job 时 runLoop 应进入 idle sleep，isCancelled=true 后正常退出
        val counter = AtomicInteger(0)
        val worker = GenerationWorker(workerId = "test", generator = { _, _ -> })

        backgroundScope.launch {
            worker.runLoop(backgroundScope) { counter.incrementAndGet() >= 2 }
        }
        advanceUntilIdle()

        assertTrue("runLoop 应正常退出（无异常）", counter.get() >= 2)
    }

    // ============================================================
    // claim → processTask → markSucceeded 完整流程
    // ============================================================

    @Test
    fun claimProcessMarkSucceededFlow() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        val generatorCalls = AtomicInteger(0)
        val worker = GenerationWorker(
            workerId = "test",
            generator = { _, _ -> generatorCalls.incrementAndGet() }
        )

        backgroundScope.launch {
            worker.runLoop(backgroundScope) { dao.getJob("job_1")?.status == NovelVideoJobStatus.COMPLETED }
        }
        advanceUntilIdle()

        assertEquals("generator 应被调用 1 次", 1, generatorCalls.get())
        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.COMPLETED, reloaded?.status)
        assertEquals("workerId 终态应清空", null, reloaded?.workerId)
    }

    // ============================================================
    // processTask 抛异常 → markFailed
    // ============================================================

    @Test
    fun processTaskThrowsExceptionMarksFailed() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        val worker = GenerationWorker(
            workerId = "test",
            generator = { _, _ -> throw RuntimeException("boom") }
        )

        backgroundScope.launch {
            worker.runLoop(backgroundScope) { dao.getJob("job_1")?.status == NovelVideoJobStatus.FAILED }
        }
        advanceUntilIdle()

        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.FAILED, reloaded?.status)
        assertEquals("boom", reloaded?.errorMessage)
    }

    // ============================================================
    // 0-rows（已被 cancel）→ markCancelled 兜底
    // ============================================================

    @Test
    fun processTaskZeroRowsFallbackKeepsCancelled() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        // generator 执行时并发把 job 标 CANCELLED，markSucceeded 应 0-rows → markCancelled 兜底
        val worker = GenerationWorker(
            workerId = "test",
            generator = { id, _ ->
                dao.markJobCancelledIfActive(id)
            }
        )

        backgroundScope.launch {
            worker.runLoop(backgroundScope) { dao.getJob("job_1")?.status == NovelVideoJobStatus.CANCELLED }
        }
        advanceUntilIdle()

        // job 应保持 CANCELLED（markSucceeded 0-rows，markCancelled 也 0-rows，最终 CANCELLED）
        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
    }

    // ============================================================
    // CancellationException → markCancelled + 重抛
    // ============================================================

    @Test
    fun processTaskCancellationExceptionMarksCancelled() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        val worker = GenerationWorker(
            workerId = "test",
            generator = { _, _ -> throw CancellationException("simulated cancel") }
        )

        backgroundScope.launch {
            worker.runLoop(backgroundScope) { dao.getJob("job_1")?.status == NovelVideoJobStatus.CANCELLED }
        }
        advanceUntilIdle()

        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
    }

    // ============================================================
    // handleOrphanTasksOnStart 调 resumeJob
    // ============================================================

    @Test
    fun handleOrphanTasksOnStartCallsResumeJob() = runTest {
        // 插入 orphan：generating + 心跳超时
        val staleHeartbeat = System.currentTimeMillis() - GenerationQueue.ORPHAN_HEARTBEAT_TIMEOUT_MS - 1000
        dao.insertJob(NovelVideoJob(
            id = "orphan_1",
            status = NovelVideoJobStatus.GENERATING,
            workerId = "dead-worker",
            workerHeartbeatAt = staleHeartbeat,
            createdAt = 1000
        ))
        val resumeCalls = AtomicInteger(0)
        val worker = GenerationWorker(
            workerId = "test",
            resumeJob = { _, _ -> resumeCalls.incrementAndGet() }
        )

        backgroundScope.launch {
            worker.runLoop(backgroundScope) { resumeCalls.get() >= 1 }
        }
        advanceUntilIdle()

        assertEquals("resumeJob 应被调用 1 次", 1, resumeCalls.get())
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("orphan_1")?.status)
    }

    @Test
    fun handleOrphanTasksOnStartResumeThrowsMarksFailed() = runTest {
        // orphan resume 抛 NotImplementedError（P3 占位行为）→ markFailed
        val staleHeartbeat = System.currentTimeMillis() - GenerationQueue.ORPHAN_HEARTBEAT_TIMEOUT_MS - 1000
        dao.insertJob(NovelVideoJob(
            id = "orphan_1",
            status = NovelVideoJobStatus.GENERATING,
            workerId = "dead-worker",
            workerHeartbeatAt = staleHeartbeat,
            createdAt = 1000
        ))
        val worker = GenerationWorker(
            workerId = "test",
            resumeJob = { _, _ -> throw NotImplementedError("P4 未实现") }
        )

        backgroundScope.launch {
            worker.runLoop(backgroundScope) { dao.getJob("orphan_1")?.status == NovelVideoJobStatus.FAILED }
        }
        advanceUntilIdle()

        val reloaded = dao.getJob("orphan_1")
        assertEquals(NovelVideoJobStatus.FAILED, reloaded?.status)
        assertTrue("errorMessage 应含 NotImplementedError", reloaded?.errorMessage?.contains("NotImplementedError") == true)
    }

    // ============================================================
    // 池满时 claim 不返回该 provider 的 job
    // ============================================================

    @Test
    fun poolFullExcludesProviderFromClaim() = runTest {
        // capacity 默认：未知 provider → DEFAULTS[video]=1
        // grok capacity=1，占满后应被排除，claim 转向 agnes
        dao.insertJob(NovelVideoJob(id = "job_grok", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000, providerId = "grok"))
        dao.insertJob(NovelVideoJob(id = "job_agnes", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000, providerId = "agnes"))

        val processed = mutableSetOf<String>()
        val worker = GenerationWorker(
            workerId = "test",
            generator = { id, _ ->
                processed.add(id)
                if (id == "job_grok") {
                    // 占住 grok slot 不返回，模拟长任务
                    awaitCancellation()
                }
                // agnes 立即返回，markSucceeded
            }
        )

        backgroundScope.launch {
            worker.runLoop(backgroundScope) { dao.getJob("job_agnes")?.status == NovelVideoJobStatus.COMPLETED }
        }
        advanceUntilIdle()

        assertTrue("agnes 应被处理", "job_agnes" in processed)
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("job_agnes")?.status)
        assertEquals("grok 应仍 GENERATING（inflight 占位）", NovelVideoJobStatus.GENERATING, dao.getJob("job_grok")?.status)
    }

    // ============================================================
    // requestCancel 取消 inflight job
    // ============================================================

    @Test
    fun requestCancelCancelsInflightJob() = runTest {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        val worker = GenerationWorker(
            workerId = "test",
            generator = { _, _ -> awaitCancellation() } // 挂起直到被取消
        )
        // 用计数器让 runLoop 跑 1 轮后退出（避免 claim SQL 重复认领 GENERATING job）
        val roundCounter = AtomicInteger(0)
        backgroundScope.launch {
            worker.runLoop(backgroundScope) { roundCounter.incrementAndGet() >= 2 }
        }
        advanceUntilIdle()
        // runLoop 已退出，processTask 挂起在 awaitCancellation，job 应为 GENERATING
        assertEquals(NovelVideoJobStatus.GENERATING, dao.getJob("job_1")?.status)

        worker.requestCancel("job_1")
        advanceUntilIdle()

        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
    }
}
