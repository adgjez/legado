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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * 技术要点（与 [GenerationWorkerTest] 一致）：
 * - runBlocking + Dispatchers.Default（真实线程，非 runTest 虚拟时间）
 * - waitForCondition 真实轮询等待终态
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
    }

    @After
    fun tearDown() {
        originalDaoProvider?.let { GenerationQueue.daoProvider = it }
        db.close()
        GenerationWorker.resetOrphanSweepGuard()
        SharedSchedulingState.capacity.clear()
        SharedSchedulingState.slots.clear()
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
    // 两 worker 同时认领不同 provider 的 job
    // ============================================================

    @Test
    fun twoWorkersClaimDifferentProviderJobsConcurrently() = runBlocking {
        // 两个不同 provider 的 job，各自 capacity=1（DEFAULTS），可并行
        dao.insertJob(NovelVideoJob(id = "job_grok", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000, providerId = "grok"))
        dao.insertJob(NovelVideoJob(id = "job_agnes", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000, providerId = "agnes"))

        val bothGenerating = AtomicInteger(0)
        val grokDone = AtomicBoolean(false)
        val agnesDone = AtomicBoolean(false)

        // generator 挂起直到两个 job 都进入 GENERATING，然后各自完成
        val generator: suspend (String, () -> Boolean) -> Unit = { jobId, _ ->
            bothGenerating.incrementAndGet()
            // 等两个 job 都被 claim（bothGenerating=2）才继续，验证并发
            waitForCondition { bothGenerating.get() >= 2 }
            if (jobId == "job_grok") grokDone.set(true) else agnesDone.set(true)
        }

        val worker1 = GenerationWorker(workerId = "worker-1", generator = generator)
        val worker2 = GenerationWorker(workerId = "worker-2", generator = generator)

        val scope1 = newScope()
        val scope2 = newScope()
        scope1.launch { worker1.runLoop(scope1) { grokDone.get() && agnesDone.get() } }
        scope2.launch { worker2.runLoop(scope2) { grokDone.get() && agnesDone.get() } }

        assertTrue("应等到 job_grok COMPLETED", waitForCondition { dao.getJob("job_grok")?.status == NovelVideoJobStatus.COMPLETED })
        assertTrue("应等到 job_agnes COMPLETED", waitForCondition { dao.getJob("job_agnes")?.status == NovelVideoJobStatus.COMPLETED })
        scope1.cancel()
        scope2.cancel()

        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("job_grok")?.status)
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("job_agnes")?.status)
        assertEquals("两个 job 应同时进入 GENERATING（并发）", 2, bothGenerating.get())
    }

    // ============================================================
    // 共享 slots 保证同 provider 不突破 capacity 上限
    // ============================================================

    @Test
    fun sharedSlotsEnforceCapacityAcrossWorkers() = runBlocking {
        // 同一 provider 的 2 个 job，capacity=1（DEFAULTS），只能串行
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000, providerId = "grok"))
        dao.insertJob(NovelVideoJob(id = "job_2", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000, providerId = "grok"))

        val inflightPeak = AtomicInteger(0)
        val currentInflight = AtomicInteger(0)
        val job1Done = AtomicBoolean(false)
        val job2Done = AtomicBoolean(false)

        val generator: suspend (String, () -> Boolean) -> Unit = { jobId, _ ->
            val count = currentInflight.incrementAndGet()
            // 更新峰值并发
            synchronized(inflightPeak) {
                if (count > inflightPeak.get()) inflightPeak.set(count)
            }
            if (jobId == "job_1") {
                // job_1 占住 slot 不返回，等一会儿验证 job_2 不会被同时认领
                delay(500)
                job1Done.set(true)
            } else {
                job2Done.set(true)
            }
            currentInflight.decrementAndGet()
        }

        val worker1 = GenerationWorker(workerId = "worker-1", generator = generator)
        val worker2 = GenerationWorker(workerId = "worker-2", generator = generator)

        val scope1 = newScope()
        val scope2 = newScope()
        scope1.launch { worker1.runLoop(scope1) { job1Done.get() && job2Done.get() } }
        scope2.launch { worker2.runLoop(scope2) { job1Done.get() && job2Done.get() } }

        assertTrue("应等到 job_1 COMPLETED", waitForCondition { dao.getJob("job_1")?.status == NovelVideoJobStatus.COMPLETED })
        // 等 job_2 也完成（job_1 完成后释放 slot，job_2 才能被认领）
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
        dao.insertJob(NovelVideoJob(id = "job_1", status = NovelVideoJobStatus.DRAFTING, createdAt = 1000))
        dao.insertJob(NovelVideoJob(id = "job_2", status = NovelVideoJobStatus.DRAFTING, createdAt = 2000, providerId = "agnes"))

        val job1Started = AtomicBoolean(false)
        val job2Started = AtomicBoolean(false)
        val job2Done = AtomicBoolean(false)

        val generator: suspend (String, () -> Boolean) -> Unit = { jobId, _ ->
            if (jobId == "job_1") {
                job1Started.set(true)
                // 挂起直到被取消
                kotlinx.coroutines.awaitCancellation()
            } else {
                job2Started.set(true)
                job2Done.set(true)
            }
        }

        val worker1 = GenerationWorker(workerId = "worker-1", generator = generator)
        val worker2 = GenerationWorker(workerId = "worker-2", generator = generator)

        val scope1 = newScope()
        val scope2 = newScope()
        scope1.launch { worker1.runLoop(scope1) { false } }
        scope2.launch { worker2.runLoop(scope2) { job2Done.get() } }

        // 等两个 job 都进入 GENERATING
        assertTrue("应等到 job_1 GENERATING", waitForCondition {
            dao.getJob("job_1")?.status == NovelVideoJobStatus.GENERATING && job1Started.get()
        })
        assertTrue("应等到 job_2 GENERATING", waitForCondition {
            dao.getJob("job_2")?.status == NovelVideoJobStatus.GENERATING && job2Started.get()
        })

        // 通过共享 slots 取消 job_1（模拟 Service.cancelJobInternal 路径）
        SharedSchedulingState.slots.getJob("job_1")?.cancel()

        assertTrue("应等到 job_1 CANCELLED", waitForCondition { dao.getJob("job_1")?.status == NovelVideoJobStatus.CANCELLED })
        assertTrue("应等到 job_2 COMPLETED（不受 job_1 取消影响）", waitForCondition { dao.getJob("job_2")?.status == NovelVideoJobStatus.COMPLETED })
        scope1.cancel()
        scope2.cancel()

        assertEquals(NovelVideoJobStatus.CANCELLED, dao.getJob("job_1")?.status)
        assertEquals(NovelVideoJobStatus.COMPLETED, dao.getJob("job_2")?.status)
    }
}
