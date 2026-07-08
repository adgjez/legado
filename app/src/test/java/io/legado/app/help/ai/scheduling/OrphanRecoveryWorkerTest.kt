package io.legado.app.help.ai.scheduling

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * [OrphanRecoveryWorker] / [sweepOrphans] 的单元测试（P5）。
 *
 * 验证：
 * - 无孤儿时不启动服务，返回 success
 * - 有孤儿时启动服务（仅一次，不论数量），返回 success
 * - findOrphans 抛异常时吞掉并返回 success（不阻塞下一周期），不启动服务
 *
 * 技术：直接调顶层 [sweepOrphans]，注入 findOrphans / startService lambda，
 * 避开 WorkManager / WorkerParameters 构造。用 Robolectric 提供 Context
 * （sweepOrphans 把 context 透传给 startService）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrphanRecoveryWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun makeOrphan(id: String): NovelVideoJob = NovelVideoJob(
        id = id,
        bookUrl = "book-$id",
        status = NovelVideoJobStatus.GENERATING,
        workerId = "dead-worker"
    )

    @Test
    fun `无孤儿时返回 success 且不启动服务`() = runBlocking {
        val startCount = AtomicInteger(0)
        val result = sweepOrphans(
            context = context,
            findOrphans = { emptyList() },
            startService = { startCount.incrementAndGet() }
        )
        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(0, startCount.get())
    }

    @Test
    fun `有孤儿时启动服务一次并返回 success`() = runBlocking {
        val startCount = AtomicInteger(0)
        val result = sweepOrphans(
            context = context,
            findOrphans = { listOf(makeOrphan("a"), makeOrphan("b"), makeOrphan("c")) },
            startService = { startCount.incrementAndGet() }
        )
        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(1, startCount.get())
    }

    @Test
    fun `findOrphans 抛异常时吞掉返回 success 且不启动服务`() = runBlocking {
        val startCount = AtomicInteger(0)
        val result = sweepOrphans(
            context = context,
            findOrphans = { throw IllegalStateException("db boom") },
            startService = { startCount.incrementAndGet() }
        )
        // 异常被吞，仍返回 success（巡检失败不阻塞下一周期）
        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(0, startCount.get())
    }

    @Test
    fun `单个孤儿时启动服务一次`() = runBlocking {
        val startCount = AtomicInteger(0)
        val result = sweepOrphans(
            context = context,
            findOrphans = { listOf(makeOrphan("solo")) },
            startService = { startCount.incrementAndGet() }
        )
        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(1, startCount.get())
    }
}
