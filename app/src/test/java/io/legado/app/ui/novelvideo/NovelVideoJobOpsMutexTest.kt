package io.legado.app.ui.novelvideo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [NovelVideoJobOps] 共享互斥锁的单元测试（P6 修复缺口 1.4.4）。
 *
 * 验证：
 * - [NovelVideoJobOps.mutex] 对并发临界区互斥（两个协程不会同时进入）
 * - 模拟双 ViewModel 并发 retry + cancel 场景：共享锁串行化，操作不交错覆写
 *
 * 纯 Kotlin，无需 Robolectric（Mutex 是协程原语，不依赖 Android 框架）。
 */
class NovelVideoJobOpsMutexTest {

    /**
     * 两个并发协程各自进入 [NovelVideoJobOps.mutex] 临界区并自增一个共享计数器，
     * 临界区内 sleep 一段。若互斥失效，两协程会重叠，并发数峰值 > 1。
     */
    @Test
    fun `共享 mutex 对并发临界区互斥`() = runBlocking {
        val concurrency = AtomicInteger(0)
        val maxConcurrency = AtomicInteger(0)
        val enterCount = AtomicInteger(0)

        val jobs = (1..2).map {
            async(Dispatchers.Default) {
                NovelVideoJobOps.mutex.withLock {
                    enterCount.incrementAndGet()
                    val cur = concurrency.incrementAndGet()
                    // 记录峰值并发数
                    maxConcurrency.updateAndGet { old -> if (cur > old) cur else old }
                    delay(50) // 在锁内停留，制造重叠机会
                    concurrency.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals("两个协程都应进入临界区", 2, enterCount.get())
        assertTrue(
            "临界区内峰值并发应 <= 1（互斥），实际=${maxConcurrency.get()}",
            maxConcurrency.get() <= 1
        )
    }

    /**
     * 模拟 1.4.4 场景：任务中心 retry（重置 segment 为 GENERATING）与
     * 详情页 cancel（标记 CANCELLED）对同一 job 并发。共享 mutex 串行化后，
     * 两者不交错——最终状态由后执行者决定，不会出现 retry 半途被 cancel 覆写
     * segment 而 job 留在 GENERATING 的不一致态。
     *
     * 这里用简化模型验证「互斥保证操作原子可见」：retry 写 A，cancel 写 B，
     * 互斥下 job.status 不会停在 A 与 B 之间的中间态。
     */
    @Test
    fun `并发 retry 与 cancel 在共享 mutex 下不交错覆写`() = runBlocking {
        // 模拟 job 状态机：retry 期望终态 GENERATING，cancel 期望终态 CANCELLED
        // 用 int 表示：0=初始, 1=retry进行中, 2=retry完成(GENERATING), 3=cancel进行中, 4=cancel完成(CANCELLED)
        val state = AtomicInteger(0)
        val inconsistencies = AtomicInteger(0)

        suspend fun retryLike() {
            NovelVideoJobOps.mutex.withLock {
                state.set(1) // retry 进行中：重置 segments
                delay(30)
                state.set(2) // retry 完成：job=GENERATING
            }
        }
        suspend fun cancelLike() {
            NovelVideoJobOps.mutex.withLock {
                // 若互斥失效，此处 state 可能是 1（retry 半途）→ 不一致
                if (state.get() == 1) inconsistencies.incrementAndGet()
                state.set(3)
                delay(30)
                state.set(4) // cancel 完成：job=CANCELLED
            }
        }

        listOf(
            async(Dispatchers.Default) { retryLike() },
            async(Dispatchers.Default) { cancelLike() }
        ).awaitAll()

        assertEquals(
            "互斥下 cancel 不应在 retry 进行中（state=1）进入临界区",
            0,
            inconsistencies.get()
        )
        // 最终态为二者之一（后执行者获胜），不会停在中间态 1 或 3
        val finalState = state.get()
        assertTrue(
            "最终态应为 retry(2) 或 cancel(4)，实际=$finalState",
            finalState == 2 || finalState == 4
        )
    }
}
