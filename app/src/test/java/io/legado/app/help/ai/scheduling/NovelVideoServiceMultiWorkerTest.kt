package io.legado.app.help.ai.scheduling

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.dao.NovelVideoDao
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P6b 多 worker 并发测试。
 *
 * 验证多个 [GenerationWorker] 实例共享 [SharedSchedulingState] 时的并发行为：
 * - 两 worker 同时认领不同 provider 的 job（不互相阻塞）
 * - 共享 slots 保证全局并发上限不突破
 * - cancel 通过共享 slots 精确取消指定 job
 *
 * **设计要点（避免时序 flaky）：**
 * - 用 [awaitCancellation] 让 generator 挂起，测试显式控制 job 何时完成
 * - 断言基于「job 被认领」(started flag) 而非 status==GENERATING（markSucceeded 可能在断言前完成）
 * - capacity 阻塞验证：job_1 占住 slot，等一段时间确认 job_2 没被认领，再 cancel job_1 释放 slot
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class NovelVideoServiceMultiWorkerTest {

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
    // 两 worker 同时认领不同 provider 的 job（不互相阻塞）
    // ============================================================

    @Test
    fun twoWorkersClaimDifferentProviderJobsConcurrently() = runBlocking {
        // 两个不同 provider 的 job，各自 capacity=1（DEFAULTS），可并行
        dao.insertJob(NovelVideoJob(id = "job_grok", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000, providerId = "grok"))
        dao.insertJob(NovelVideoJob(id = "job_agnes", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000, providerId = "agnes"))

        val grokStarted = AtomicBoolean(false)
        val agnesStarted = AtomicBoolean(false)
        val bothInflightSeen = AtomicBoolean(false)
        val inflightCount = AtomicInteger(0)
        val inflightPeak = AtomicInteger(0)

        // 两个 generator 都挂起，直到测试观察到并发后释放
        val releaseGrok = AtomicBoolean(false)
        val releaseAgnes = AtomicBoolean(false)

        val generator: suspend (String, () -> Boolean) -> Unit = { jobId, _ ->
            val count = inflightCount.incrementAndGet()
            synchronized(inflightPeak) { if (count > inflightPeak.get()) inflightPeak.set(count) }
            when (jobId) {
                "job_grok" -> {
                    grokStarted.set(true)
                    // 等待释放信号
                    waitForCondition { releaseGrok.get() }
                }
                "job_agnes" -> {
                    agnesStarted.set(true)
                    waitForCondition { releaseAgnes.get() }
                }
            }
            inflightCount.decrementAndGet()
        }

        val worker1 = GenerationWorker(workerId = "worker-1", generator = generator)
        val worker2 = GenerationWorker(workerId = "worker-2", generator = generator)

        val scope1 = newScope()
        val scope2 = newScope()
        scope1.launch { worker1.runLoop(scope1) { false } }
        scope2.launch { worker2.runLoop(scope2) { false } }

        // 等两个 job 都被认领（started flag，不依赖 status==GENERATING）
        assertTrue("应等到 job_grok 被认领", waitForCondition { grokStarted.get() })
        assertTrue("应等到 job_agnes 被认领", waitForCondition { agnesStarted.get() })

        // 观察到并发（两个都在 inflight）
        assertTrue("应观察到两 job 同时 inflight（并发）",
            waitForCondition { inflightCount.get() == 2 })
        bothInflightSeen.set(inflightCount.get() == 2)

        // 释放两个 job，让它们完成
        releaseGrok.set(true)
        releaseAgnes.set(true)

        assertTrue("应等到 job_grok COMPLETED", waitForCondition { dao.getJob("job_grok")?.status == NovelVideoJobStatus.COMPLETED })
        assertTrue("应等到 job_agnes COMPLETED", waitForCondition { dao.getJob("job_agnes")?.status == NovelVideoJobStatus.COMPLETED })
        scope1.cancel()
        scope2.cancel()

        assertTrue("两 job 应同时 inflight（并发执行）", bothInflightSeen.get())
        assertEquals("峰值并发应达到 2", 2, inflightPeak.get())
    }

    // ============================================================
    // 共享 slots 保证同 provider 不突破 capacity 上限
    // ============================================================

    @Test
    fun sharedSlotsEnforceCapacityAcrossWorkers() = runBlocking {
        // 同一 provider 的 2 个 job，capacity=1（DEFAULTS），只能串行
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000, providerId = "grok"))
        dao.insertJob(NovelVideoJob(id = "job_2", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000, providerId = "grok"))

        val job1Started = AtomicBoolean(false)
        val job2Started = AtomicBoolean(false)
        val inflightCount = AtomicInteger(0)
        val inflightPeak = AtomicInteger(0)
        val releaseJob1 = AtomicBoolean(false)

        val generator: suspend (String, () -> Boolean) -> Unit = { jobId, _ ->
            val count = inflightCount.incrementAndGet()
            synchronized(inflightPeak) { if (count > inflightPeak.get()) inflightPeak.set(count) }
            when (jobId) {
                "job_1" -> {
                    job1Started.set(true)
                    // job_1 占住 slot，直到测试释放
                    waitForCondition { releaseJob1.get() }
                }
                "job_2" -> {
                    job2Started.set(true)
                    // job_2 立即完成
                }
            }
            inflightCount.decrementAndGet()
        }

        val worker1 = GenerationWorker(workerId = "worker-1", generator = generator)
        val worker2 = GenerationWorker(workerId = "worker-2", generator = generator)

        val scope1 = newScope()
        val scope2 = newScope()
        scope1.launch { worker1.runLoop(scope1) { false } }
        scope2.launch { worker2.runLoop(scope2) { false } }

        // 等 job_1 被认领并占住 slot
        assertTrue("应等到 job_1 被认领", waitForCondition { job1Started.get() })
        assertEquals("job_1 应 GENERATING", NovelVideoJobStatus.GENERATING, dao.getJob("job_1")?.status)

        // 等一段时间，验证 job_2 没被认领（capacity=1 阻塞）
        delay(500)
        assertTrue("capacity=1 时 job_2 不应被认领", !job2Started.get())
        assertEquals("job_2 应仍 DRAFTING（被 capacity 阻塞）",
            NovelVideoJobStatus.DRAFTING, dao.getJob("job_2")?.status)
        assertEquals("峰值并发应 <=1（job_1 占住，job_2 被阻塞）", 1, inflightPeak.get())

        // 释放 job_1，slot 空出，job_2 应被认领
        releaseJob1.set(true)
        assertTrue("应等到 job_1 COMPLETED", waitForCondition { dao.getJob("job_1")?.status == NovelVideoJobStatus.COMPLETED })
        assertTrue("应等到 job_2 被认领（slot 释放后）", waitForCondition { job2Started.get() })
        assertTrue("应等到 job_2 COMPLETED", waitForCondition { dao.getJob("job_2")?.status == NovelVideoJobStatus.COMPLETED })
        scope1.cancel()
        scope2.cancel()

        assertTrue("同 provider capacity=1 时峰值并发应 <=1", inflightPeak.get() <= 1)
    }

    // ============================================================
    // 通过共享 slots 取消指定 job（模拟 Service.cancelJob 路径）
    // ============================================================

    @Test
    fun cancelViaSharedSlotsCancelsSpecifiedJob() = runBlocking {
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000, providerId = "grok"))
        dao.insertJob(NovelVideoJob(id = "job_2", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000, providerId = "agnes"))

        val job1Started = AtomicBoolean(false)
        val job2Started = AtomicBoolean(false)
        val releaseJob2 = AtomicBoolean(false)

        // 两个 job 都挂起，测试显式控制完成
        val generator: suspend (String, () -> Boolean) -> Unit = { jobId, _ ->
            when (jobId) {
                "job_1" -> {
                    job1Started.set(true)
                    awaitCancellation() // 挂起直到被取消
                }
                "job_2" -> {
                    job2Started.set(true)
                    waitForCondition { releaseJob2.get() }
                }
            }
        }

        val worker1 = GenerationWorker(workerId = "worker-1", generator = generator)
        val worker2 = GenerationWorker(workerId = "worker-2", generator = generator)

        val scope1 = newScope()
        val scope2 = newScope()
        scope1.launch { worker1.runLoop(scope1) { false } }
        scope2.launch { worker2.runLoop(scope2) { false } }

        // 等两个 job 都被认领（started flag，不依赖 status==GENERATING）
        assertTrue("应等到 job_1 被认领", waitForCondition { job1Started.get() })
        assertTrue("应等到 job_2 被认领", waitForCondition { job2Started.get() })

        // 确认两个 job 都在 GENERATING（cancel 前）
        assertEquals("job_1 应 GENERATING", NovelVideoJobStatus.GENERATING, dao.getJob("job_1")?.status)
        assertEquals("job_2 应 GENERATING", NovelVideoJobStatus.GENERATING, dao.getJob("job_2")?.status)

        // 通过共享 slots 取消 job_1（模拟 Service.cancelJobInternal 路径）
        SharedSchedulingState.slots.getJob("job_1")?.cancel()

        assertTrue("应等到 job_1 CANCELLED", waitForCondition { dao.getJob("job_1")?.status == NovelVideoJobStatus.CANCELLED })

        // job_2 不受影响，仍 GENERATING（还没释放）
        assertEquals("job_2 应仍 GENERATING（不受 job_1 取消影响）",
            NovelVideoJobStatus.GENERATING, dao.getJob("job_2")?.status)

        // 释放 job_2，验证它能正常完成
        releaseJob2.set(true)
        assertTrue("应等到 job_2 COMPLETED", waitForCondition { dao.getJob("job_2")?.status == NovelVideoJobStatus.COMPLETED })
        scope1.cancel()
        scope2.cancel()

        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("job_2")?.status)
        assertNotEquals("job_1 不应为 COMPLETED", NovelVideoJobStatus.COMPLETED, dao.getJob("job_1")?.status)
    }

    // ============================================================
    // worker 崩溃后 lease 释放，另一 worker 通过 orphan 恢复接管
    // ============================================================

    /**
     * 验证 plan.md P6 验收点 7：lease 过期后 job 可被其他 worker 认领。
     *
     * 场景：worker-0 崩溃遗留 status=generating + 心跳超时的 orphan job，
     * worker-1/worker-2 启动时通过 [GenerationWorker.handleOrphanTasksOnStart]
     * 扫描捡起 orphan（[GenerationQueue.findOrphans]），调 resumeJob 恢复。
     *
     * 关键验证：
     * - orphan 被 resumeJob 处理（orphanSweepDone companion guard 保证多 worker 只扫一次）
     * - orphan 是 generating 状态，不会被 claim（claim 只选 drafting/screenplay_confirmed）
     * - 正常 drafting job 被 claim + generator 处理，与 orphan 恢复并行不冲突
     */
    @Test
    fun crashedWorkerOrphanReclaimedByAnotherWorker() = runBlocking {
        // 预置 orphan job：worker-0 崩溃遗留（status=generating, heartbeat 已过期）
        val staleHeartbeat = System.currentTimeMillis() - GenerationQueue.ORPHAN_HEARTBEAT_TIMEOUT_MS - 1000
        dao.insertJob(NovelVideoJob(
            id = "orphan_job",
            status = NovelVideoJobStatus.GENERATING,
            workerId = "dead-worker",
            workerHeartbeatAt = staleHeartbeat,
            createdAt = 1000,
            providerId = "grok"
        ))
        // 正常 drafting job，供 claim 处理（不同 provider，可与 orphan 恢复并行）
        dao.insertJob(NovelVideoJob(id = "fresh_job", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000, providerId = "agnes"))

        val resumeCalls = AtomicInteger(0)
        val generatorCalls = AtomicInteger(0)
        val orphanDone = AtomicBoolean(false)
        val freshDone = AtomicBoolean(false)

        val sharedGenerator: suspend (String, () -> Boolean) -> Unit = { jobId, _ ->
            generatorCalls.incrementAndGet()
            if (jobId == "fresh_job") freshDone.set(true)
        }
        val sharedResume: suspend (String, () -> Boolean) -> Unit = { jobId, _ ->
            resumeCalls.incrementAndGet()
            if (jobId == "orphan_job") orphanDone.set(true)
        }

        val worker1 = GenerationWorker(workerId = "worker-1", generator = sharedGenerator, resumeJob = sharedResume)
        val worker2 = GenerationWorker(workerId = "worker-2", generator = sharedGenerator, resumeJob = sharedResume)

        val scope1 = newScope()
        val scope2 = newScope()
        scope1.launch { worker1.runLoop(scope1) { orphanDone.get() && freshDone.get() } }
        scope2.launch { worker2.runLoop(scope2) { orphanDone.get() && freshDone.get() } }

        // orphan 被 resumeJob 处理 → handleOrphanTasksOnStart 内 markSucceeded → COMPLETED
        assertTrue("应等到 orphan_job COMPLETED", waitForCondition { dao.getJob("orphan_job")?.status == NovelVideoJobStatus.COMPLETED })
        // fresh_job 被 claim + generator 处理 → processTask markSucceeded → COMPLETED
        assertTrue("应等到 fresh_job COMPLETED", waitForCondition { dao.getJob("fresh_job")?.status == NovelVideoJobStatus.COMPLETED })
        scope1.cancel()
        scope2.cancel()

        assertEquals("orphan 应被 resumeJob 接管（orphanSweepDone guard 保证只 1 次）", 1, resumeCalls.get())
        assertEquals("fresh_job 应被 generator 处理（claim 原子保证只 1 次）", 1, generatorCalls.get())
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("orphan_job")?.status)
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("fresh_job")?.status)
    }
}
