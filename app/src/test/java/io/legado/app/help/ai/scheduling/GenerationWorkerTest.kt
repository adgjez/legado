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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
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
 * - 用 `runBlocking` + `Dispatchers.Default`（真实线程），不用 runTest 虚拟时间
 *   （advanceUntilIdle 不等 Room 真实 IO，会导致 runLoop 内 dao 调用未完成就断言）
 * - `CoroutineScope(SupervisorJob())` 跑 runLoop，processTask 子协程异常不互相影响
 * - [waitForCondition] 真实轮询等待终态（timeout 5s）
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class GenerationWorkerTest {

    private lateinit var db: TestSchedulingDatabase
    private lateinit var dao: NovelVideoDao
    private var originalDaoProvider: (() -> NovelVideoDao)? = null

    @Before
    fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, TestSchedulingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.novelVideoDao
        originalDaoProvider = GenerationQueue.daoProvider
        GenerationQueue.daoProvider = { dao }
        // P6b：重置共享 orphan guard + 清空共享 capacity/slots（多 worker 共享状态需测试间隔离）
        GenerationWorker.resetOrphanSweepGuard()
        SharedSchedulingState.capacity.clear()
        SharedSchedulingState.slots.clear()
        SharedSchedulingState.preserveOnShutdown = false
    }

    @After
    fun tearDown() {
        originalDaoProvider?.let { GenerationQueue.daoProvider = it }
        db.close()
        GenerationWorker.resetOrphanSweepGuard()
        SharedSchedulingState.capacity.clear()
        SharedSchedulingState.slots.clear()
        SharedSchedulingState.preserveOnShutdown = false
    }

    /** 真实轮询等待条件成立（timeout 5s）。condition 为 suspend 以支持查 DB。 */
    private suspend fun waitForCondition(
        timeoutMs: Long = 5000,
        condition: suspend () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(20)
        }
        return condition()
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ============================================================
    // runLoop 主循环
    // ============================================================

    @Test
    fun runLoopNoJobExitsCleanlyWhenCancelled() = runBlocking {
        val worker = GenerationWorker(workerId = "test", generator = { _, _ -> })
        val scope = newScope()
        // isCancelled 立即返回 true → runLoop 不进入循环体，直接正常返回（不抛异常）。
        // 无 job 时的 idle delay=HEARTBEAT_INTERVAL_MS(10s)，若让循环跑一轮会超 5s timeout，
        // 故直接验证 isCancelled=true 时 runLoop 干净退出。
        val runLoopJob = scope.launch { worker.runLoop(scope) { true } }

        val ok = waitForCondition { !runLoopJob.isActive }
        scope.cancel()

        assertTrue("runLoop 应在 isCancelled=true 时立即正常退出", ok)
    }

    // ============================================================
    // claim → processTask → markSucceeded 完整流程
    // ============================================================

    @Test
    fun claimProcessMarkSucceededFlow() = runBlocking {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        val completed = AtomicBoolean(false)
        val generatorCalls = AtomicInteger(0)
        val worker = GenerationWorker(
            workerId = "test",
            generator = { _, _ ->
                generatorCalls.incrementAndGet()
                completed.set(true)
            }
        )
        val scope = newScope()
        scope.launch { worker.runLoop(scope) { completed.get() } }

        assertTrue("应等到 job COMPLETED", waitForCondition { dao.getJob("job_1")?.status == NovelVideoJobStatus.COMPLETED })
        scope.cancel()

        assertEquals("generator 应被调用 1 次", 1, generatorCalls.get())
        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.COMPLETED, reloaded?.status)
        assertEquals("workerId 终态应清空", null, reloaded?.workerId)
    }

    // ============================================================
    // processTask 抛异常 → markFailed
    // ============================================================

    @Test
    fun processTaskThrowsExceptionMarksFailed() = runBlocking {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        val failed = AtomicBoolean(false)
        val worker = GenerationWorker(
            workerId = "test",
            generator = { _, _ ->
                failed.set(true)
                throw RuntimeException("boom")
            }
        )
        val scope = newScope()
        scope.launch { worker.runLoop(scope) { failed.get() } }

        assertTrue("应等到 job FAILED", waitForCondition { dao.getJob("job_1")?.status == NovelVideoJobStatus.FAILED })
        scope.cancel()

        val reloaded = dao.getJob("job_1")
        assertEquals(NovelVideoJobStatus.FAILED, reloaded?.status)
        assertEquals("boom", reloaded?.errorMessage)
    }

    // ============================================================
    // 0-rows（已被 cancel）→ markCancelled 兜底
    // ============================================================

    @Test
    fun processTaskZeroRowsFallbackKeepsCancelled() = runBlocking {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        val done = AtomicBoolean(false)
        // generator 执行时并发把 job 标 CANCELLED，markSucceeded 应 false → markCancelled 兜底
        val worker = GenerationWorker(
            workerId = "test",
            generator = { id, _ ->
                dao.markJobCancelledIfActive(id)
                done.set(true)
            }
        )
        val scope = newScope()
        scope.launch { worker.runLoop(scope) { done.get() } }

        assertTrue("应等到 job CANCELLED", waitForCondition { dao.getJob("job_1")?.status == NovelVideoJobStatus.CANCELLED })
        scope.cancel()

        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
    }

    // ============================================================
    // CancellationException → markCancelled + 重抛
    // ============================================================

    @Test
    fun processTaskCancellationExceptionMarksCancelled() = runBlocking {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        val cancelled = AtomicBoolean(false)
        val worker = GenerationWorker(
            workerId = "test",
            generator = { _, _ ->
                cancelled.set(true)
                throw CancellationException("simulated cancel")
            }
        )
        val scope = newScope()
        scope.launch { worker.runLoop(scope) { cancelled.get() } }

        assertTrue("应等到 job CANCELLED", waitForCondition { dao.getJob("job_1")?.status == NovelVideoJobStatus.CANCELLED })
        scope.cancel()

        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
    }

    /**
     * 第五轮审查 R10 回归修复：preserveOnShutdown=true 时（系统超时/销毁），
     * processTask 的 CancellationException 路径只 releaseLease，保留 GENERATING 中间态，
     * 不 markCancelled，让 orphan sweep 兜底恢复。
     */
    @Test
    fun processTaskCancellationWithPreserveOnShutdownKeepsIntermediateState() = runBlocking {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        val cancelled = AtomicBoolean(false)
        val worker = GenerationWorker(
            workerId = "test",
            generator = { _, _ ->
                cancelled.set(true)
                throw CancellationException("simulated system timeout")
            }
        )
        // 模拟系统超时/销毁场景
        SharedSchedulingState.preserveOnShutdown = true

        val scope = newScope()
        scope.launch { worker.runLoop(scope) { cancelled.get() } }

        // 等 generator 被调用（processTask 进入 catch 路径）
        assertTrue("应等到 generator 被调用", waitForCondition { cancelled.get() })
        // 等释放完成（releaseLease 清空 workerId）
        assertTrue("应等到 releaseLease 完成（workerId 清空）",
            waitForCondition { dao.getJob("job_1")?.workerId == null })
        scope.cancel()

        val job = dao.getJob("job_1")
        assertEquals("preserveOnShutdown=true 时 job 应保持 GENERATING（中间态）",
            NovelVideoJobStatus.GENERATING, job?.status)
        assertNull("workerId 应被 releaseLease 清空", job?.workerId)
    }

    // ============================================================
    // handleOrphanTasksOnStart 调 resumeJob
    // ============================================================

    @Test
    fun handleOrphanTasksOnStartCallsResumeJob() = runBlocking {
        val staleHeartbeat = System.currentTimeMillis() - GenerationQueue.ORPHAN_HEARTBEAT_TIMEOUT_MS - 1000
        dao.insertJob(NovelVideoJob(
            id = "orphan_1",
            status = NovelVideoJobStatus.GENERATING,
            workerId = "dead-worker",
            workerHeartbeatAt = staleHeartbeat,
            createdAt = 1000
        ))
        val resumeDone = AtomicBoolean(false)
        val resumeCalls = AtomicInteger(0)
        val worker = GenerationWorker(
            workerId = "test",
            resumeJob = { _, _ ->
                resumeCalls.incrementAndGet()
                resumeDone.set(true)
            }
        )
        val scope = newScope()
        scope.launch { worker.runLoop(scope) { resumeDone.get() } }

        assertTrue("应等到 orphan COMPLETED", waitForCondition { dao.getJob("orphan_1")?.status == NovelVideoJobStatus.COMPLETED })
        scope.cancel()

        assertEquals("resumeJob 应被调用 1 次", 1, resumeCalls.get())
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("orphan_1")?.status)
    }

    @Test
    fun handleOrphanTasksOnStartResumeThrowsMarksFailed() = runBlocking {
        val staleHeartbeat = System.currentTimeMillis() - GenerationQueue.ORPHAN_HEARTBEAT_TIMEOUT_MS - 1000
        dao.insertJob(NovelVideoJob(
            id = "orphan_1",
            status = NovelVideoJobStatus.GENERATING,
            workerId = "dead-worker",
            workerHeartbeatAt = staleHeartbeat,
            createdAt = 1000
        ))
        val failed = AtomicBoolean(false)
        val worker = GenerationWorker(
            workerId = "test",
            resumeJob = { _, _ ->
                failed.set(true)
                throw NotImplementedError("P4 未实现")
            }
        )
        val scope = newScope()
        scope.launch { worker.runLoop(scope) { failed.get() } }

        assertTrue("应等到 orphan FAILED", waitForCondition { dao.getJob("orphan_1")?.status == NovelVideoJobStatus.FAILED })
        scope.cancel()

        val reloaded = dao.getJob("orphan_1")
        assertEquals(NovelVideoJobStatus.FAILED, reloaded?.status)
        // worker 存 e.message（"P4 未实现"）而非 class name，断言消息被透传
        assertTrue("errorMessage 应含异常消息", reloaded?.errorMessage?.contains("未实现") == true)
    }

    // ============================================================
    // 池满时 claim 不返回该 provider 的 job
    // ============================================================

    @Test
    fun poolFullExcludesProviderFromClaim() = runBlocking {
        // capacity 默认：未知 provider → DEFAULTS[video]=1
        // grok capacity=1，占满后应被排除，claim 转向 agnes
        dao.insertJob(NovelVideoJob(id = "job_grok", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000, providerId = "grok"))
        dao.insertJob(NovelVideoJob(id = "job_agnes", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000, providerId = "agnes"))

        val processed = mutableSetOf<String>()
        val agnesDone = AtomicBoolean(false)
        val worker = GenerationWorker(
            workerId = "test",
            generator = { id, _ ->
                synchronized(processed) { processed.add(id) }
                if (id == "job_grok") {
                    // 占住 grok slot 不返回，模拟长任务
                    awaitCancellation()
                } else {
                    agnesDone.set(true)
                }
            }
        )
        val scope = newScope()
        scope.launch { worker.runLoop(scope) { agnesDone.get() } }

        assertTrue("应等到 agnes COMPLETED", waitForCondition { dao.getJob("job_agnes")?.status == NovelVideoJobStatus.COMPLETED })
        // grok 仍 inflight，验证状态后取消 scope
        assertEquals("grok 应仍 GENERATING（inflight 占位）", NovelVideoJobStatus.GENERATING, dao.getJob("job_grok")?.status)
        scope.cancel()

        assertTrue("agnes 应被处理", synchronized(processed) { "job_agnes" in processed })
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("job_agnes")?.status)
    }

    // ============================================================
    // requestCancel 取消 inflight job
    // ============================================================

    @Test
    fun requestCancelCancelsInflightJob() = runBlocking {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        val generatorStarted = AtomicBoolean(false)
        val worker = GenerationWorker(
            workerId = "test",
            generator = { _, _ ->
                generatorStarted.set(true)
                awaitCancellation() // 挂起直到被取消
            }
        )
        val scope = newScope()
        // isCancelled 恒 false：runLoop 不自行退出，靠 requestCancel 取消 inflight job
        scope.launch { worker.runLoop(scope) { false } }

        // 等 job GENERATING 且 generator 已启动。
        // generator 在 processTask 内执行，而 slots.register 在 processTask 启动前同步完成，
        // 故 generatorStarted=true 保证 slot 已登记，避免 requestCancel 查不到 slot 的竞态。
        assertTrue("应等到 job GENERATING 且 generator 启动",
            waitForCondition {
                dao.getJob("job_1")?.status == NovelVideoJobStatus.GENERATING && generatorStarted.get()
            })

        worker.requestCancel("job_1")
        assertTrue("应等到 job CANCELLED", waitForCondition { dao.getJob("job_1")?.status == NovelVideoJobStatus.CANCELLED })
        scope.cancel()

        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
    }
}
