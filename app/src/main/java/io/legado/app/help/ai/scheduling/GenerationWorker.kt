package io.legado.app.help.ai.scheduling

import androidx.annotation.VisibleForTesting
import io.legado.app.help.ai.NovelVideoGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Generation Worker（移植 ArcReel `lib/generation_worker.py:433-1048`）。
 *
 * 主循环职责（每轮）：
 * 1. claimNextJob（带池满黑名单过滤）
 * 2. processTask：调 [NovelVideoGenerator.generate]，0-rows-cancelled 协议兜底
 * 3. 心跳续约（每 [GenerationQueue.HEARTBEAT_INTERVAL_MS] 续 lease）
 * 4. orphan 恢复（首次启动时扫一次）
 *
 * **0-rows-cancelled 协议**（移植 ADR 0006）：
 * - processTask 成功 → markSucceeded，0-rows（已被取消）→ markCancelled
 * - processTask 失败 → markFailed，0-rows（已被取消）→ markCancelled
 * - processTask 取消 → markCancelled
 * - 所有 DB 写入用 `withContext(NonCancellable)` 包裹，保证取消信号到达时 inner 跑完
 *
 * **单进程简化**（相比 ArcReel）：
 * - 删除 `worker_lease` 表多进程互斥（单进程无意义）
 * - 删除 `_ORPHAN_RESCAN_LEASE_LOST_MULT` lease flap 重扫（单进程无 flap）
 * - 保留 orphan 一次性扫描（进程重启时触发）
 *
 * **P6b 多 worker 支持**：
 * - [capacity] / [slots] 默认指向 [SharedSchedulingState] 的共享单例，
 *   多 worker 共享同一份容量/槽位台账，保证全局并发上限不突破。
 * - orphan 扫描用 companion 共享 [orphanSweepDone] guard，多 worker 下只扫一次；
 *   Service 每次 [ensurePipelineRunning] 调 [resetOrphanSweepGuard] 重置以支持重启。
 *
 * @param workerId worker 标识（单进程可用 "default"，多 worker 用 "worker-0"/"worker-1"）
 * @param generator 新任务处理入口（默认 [NovelVideoGenerator.generate]；测试可注入 mock）
 * @param resumeJob orphan 恢复入口（默认 [NovelVideoGenerator.resumeJob]；测试可注入 mock）
 * @param capacity 并发容量表（默认共享 [SharedSchedulingState.capacity]；测试可注入独立实例）
 * @param slots 槽位台账（默认共享 [SharedSchedulingState.slots]；测试可注入独立实例）
 * @see <a href="file:///tmp/arcreel/lib/generation_worker.py">ArcReel generation_worker.py</a>
 */
class GenerationWorker(
    private val workerId: String = "default",
    private val generator: suspend (jobId: String, isCancelled: () -> Boolean) -> Unit =
        { id, cancelled -> NovelVideoGenerator.generate(id, cancelled) },
    private val resumeJob: suspend (jobId: String, isCancelled: () -> Boolean) -> Unit =
        { id, cancelled -> NovelVideoGenerator.resumeJob(id, cancelled) },
    private val capacity: CapacityTable = SharedSchedulingState.capacity,
    private val slots: SlotTable = SharedSchedulingState.slots
) {
    /**
     * 运行 worker 主循环。阻塞直到 [isCancelled] 返回 true 或协程被取消。
     *
     * @param scope 用于启动 processTask 子协程的 scope
     * @param isCancelled 取消信号（如服务被停止）
     */
    suspend fun runLoop(scope: CoroutineScope, isCancelled: () -> Boolean) {
        while (!isCancelled() && coroutineContext.isActive) {
            try {
                // 首次启动时扫一次 orphan（进程重启恢复）。
                // 用 companion 共享 guard：多 worker 下只第一个进入的 worker 扫一次。
                if (orphanSweepDone.compareAndSet(false, true)) {
                    handleOrphanTasksOnStart()
                }

                val claimed = claimAndProcess(scope)
                if (!claimed) {
                    // 无可认领 job，idle sleep
                    delay(GenerationQueue.HEARTBEAT_INTERVAL_MS)
                } else {
                    // claim 成功，短暂 sleep 后继续下一轮
                    delay(50)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程被取消，退出循环
                throw e
            } catch (e: Throwable) {
                // 意外异常不退出循环，短暂 sleep 后重试
                delay(1000)
            }
        }
    }

    /**
     * claim 下一个 job 并启动 processTask。
     * @return true 表示 claim 成功并启动了处理；false 表示无可认领 job
     */
    private suspend fun claimAndProcess(scope: CoroutineScope): Boolean {
        // 池满黑名单：当前有 inflight 且已满的 provider
        val poolFull = PoolFullCalculator.calculate(CapacityTable.MEDIA_VIDEO, slots, capacity)

        val job = GenerationQueue.claimNextJob(workerId, excludeProviders = poolFull.toList())
            ?: return false

        val providerId = job.providerId ?: "default"
        val mediaType = CapacityTable.MEDIA_VIDEO

        // capacity=0 的 provider 不支持此 media → 标 failed
        val cap = capacity.get(providerId, mediaType)
        if (cap <= 0) {
            GenerationQueue.markFailed(
                job.id,
                TaskFailure.encodeFailure("provider_unsupported_media", mapOf("provider" to providerId))
            )
            return true
        }

        // 池满 → 回队（不 mark_failed，避免误杀）
        if (!slots.hasRoom(providerId, mediaType, cap)) {
            GenerationQueue.requeue(job.id)
            return true
        }

        // 登记 slot + 启动 processTask 子协程
        val processJob = scope.launch {
            try {
                processTask(job.id)
            } finally {
                slots.release(providerId, mediaType, job.id)
            }
        }
        slots.register(providerId, mediaType, job.id, processJob)
        return true
    }

    /**
     * 处理单个 job（移植 ArcReel `generation_worker.py:650-694` 的 `_process_task`）。
     *
     * 0-rows-cancelled 协议兜底：所有终态写入用 NonCancellable 包裹，
     * 取消信号到达时 inner DB 写入跑完再传播。
     */
    private suspend fun processTask(jobId: String) {
        // isCancelled 非 suspend lambda（generator 签名要求），只查 slots：
        // requestCancel → Job.cancel() → finally slots.release，findByJobId 返回 null 即取消信号。
        // 协程取消时 generator 内部 suspend 调用会抛 CancellationException，由下方 catch 处理。
        val isCancelled: () -> Boolean = { slots.findByJobId(jobId) == null }
        try {
            generator(jobId, isCancelled)
            // 成功：markSucceeded，false（0-rows 已被取消）→ markCancelled 兜底
            val succeeded = withContext(NonCancellableCtx) {
                GenerationQueue.markSucceeded(jobId, outputPath = null, coverPath = null, durationMs = null)
            }
            if (!succeeded) {
                withContext(NonCancellableCtx) { GenerationQueue.markCancelled(jobId) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消：markCancelled
            withContext(NonCancellableCtx) { GenerationQueue.markCancelled(jobId) }
            throw e
        } catch (e: Throwable) {
            // 失败：markFailed，false（0-rows 已被取消）→ markCancelled 兜底
            val marked = withContext(NonCancellableCtx) {
                GenerationQueue.markFailed(jobId, e.message ?: e::class.simpleName)
            }
            if (!marked) {
                withContext(NonCancellableCtx) { GenerationQueue.markCancelled(jobId) }
            }
        }
    }

    /**
     * 启动时扫描孤儿任务（移植 ArcReel `generation_worker.py:800-945`）。
     *
     * 进程重启后，之前的 running job 心跳已超时，需恢复。
     * 对每个孤儿：
     * - 调 [NovelVideoGenerator.generate]（它会根据 job.status 决定从哪个 stage 续跑）
     * - ResumeExpiredError / NotImplementedError → markFailed（结构化失败码）
     * - 其他异常 → markFailed
     */
    private suspend fun handleOrphanTasksOnStart() {
        val orphans = GenerationQueue.findOrphans()
        for (orphan in orphans) {
            try {
                // 清空旧 lease，让 resumeJob 正常推进
                GenerationQueue.releaseLease(orphan.id)
                // resumeJob 优先调 VideoBackend.resumeVideo 续传已提交任务（P4 实现）
                // P3 阶段抛 NotImplementedError，由 catch 兜底 markFailed
                resumeJob(orphan.id) { false }
                // resumeJob 正常完成不写终态（由 processTask 的 markSucceeded 负责），
                // orphan 路径直接 markSucceeded
                GenerationQueue.markSucceeded(orphan.id, outputPath = null, coverPath = null, durationMs = null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                GenerationQueue.markCancelled(orphan.id)
            } catch (e: Throwable) {
                GenerationQueue.markFailed(orphan.id, e.message ?: e::class.simpleName)
            }
        }
    }

    /**
     * 请求取消指定 job（cancel 路径）。
     * 通过 SlotTable 找到对应协程 Job 并 cancel。
     */
    fun requestCancel(jobId: String) {
        slots.getJob(jobId)?.cancel()
    }

    companion object {
        /**
         * orphan 扫描共享 guard（多 worker 下只扫一次）。
         * Service 每次 ensurePipelineRunning 调 [resetOrphanSweepGuard] 重置。
         */
        @VisibleForTesting
        internal val orphanSweepDone = AtomicBoolean(false)

        /** 重置 orphan 扫描 guard（Service 重启 / 测试 setUp 调用）。 */
        @VisibleForTesting
        internal fun resetOrphanSweepGuard() {
            orphanSweepDone.set(false)
        }
    }
}

/** NonCancellable 上下文别名（与 ArcReel `asyncio.shield` 等价）。 */
private val NonCancellableCtx = kotlinx.coroutines.NonCancellable

/**
 * 进程级共享调度状态（P6b）。
 *
 * 持有 [CapacityTable] / [SlotTable] 的单例，供多 [GenerationWorker] 共享，
 * 保证全局并发上限不因每个 worker 各自独立台账而突破。
 *
 * 测试若需隔离状态，可在 setUp 中调 `SharedSchedulingState.capacity.clear()` /
 * `SharedSchedulingState.slots.clear()`，或向 [GenerationWorker] 注入独立实例。
 */
object SharedSchedulingState {
    val capacity: CapacityTable = CapacityTable()
    val slots: SlotTable = SlotTable()
}
