package io.legado.app.help.ai.scheduling

import io.legado.app.data.appDb
import io.legado.app.data.dao.NovelVideoDao
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus

/**
 * 持久化队列 facade（移植 ArcReel `lib/generation_queue.py`）。
 *
 * 封装 [NovelVideoDao] 的 claim/lease/mark 系列操作，提供 worker 层的高层语义。
 * 所有操作都返回受影响行数或 nullable job，调用方按 0-rows 协议处理并发竞态。
 *
 * **0-rows-cancelled 协议**（移植 ADR 0006）：所有终态写入由 SQL WHERE 守卫，
 * 任何「越权转移」静默 0-rows 失败。调用方检查返回值决定后续动作。
 *
 * 单进程前台服务模型下无需多 worker lease 互斥（ArcReel 的 `worker_lease` 表删除），
 * 但保留 workerId + workerHeartbeatAt 作为「worker 存活证明」，供 orphan 检测判定。
 *
 * @see <a href="file:///tmp/arcreel/lib/generation_queue.py">ArcReel generation_queue.py</a>
 * @see <a href="file:///tmp/arcreel/lib/db/repositories/task_repo.py">ArcReel task_repo.py</a>
 */
object GenerationQueue {

    /** lease 过期阈值：超过此时间未续约即判定 worker 已死（30s，比 ArcReel 10s 宽松，Android 后台限制）。 */
    const val LEASE_TTL_MS = 30_000L

    /** 心跳间隔：worker 主循环每轮续约（10s，ArcReel 3s）。 */
    const val HEARTBEAT_INTERVAL_MS = 10_000L

    /** 孤儿判定阈值：心跳超时此时间即算孤儿（LEASE_TTL × 5 = 2.5 min，容忍短暂卡顿）。 */
    const val ORPHAN_HEARTBEAT_TIMEOUT_MS = LEASE_TTL_MS * 5

    private val dao: NovelVideoDao get() = appDb.novelVideoDao

    /**
     * 原子认领下一个可调度 job。
     *
     * 实现两步：先 claimNextJob 原子 UPDATE（受影响行数 1=成功），再 getJob 查回详情。
     * 多 worker 并发时，UPDATE 的 WHERE 子查询保证只有一个 worker 抢到（其他 affected=0）。
     *
     * @param workerId 认领 worker 标识（单进程可用固定 "default"）
     * @param excludeProviders 池满黑名单（空 list 时无过滤）
     * @return 被认领的 job，或 null（无可调度 job / 被并发抢走）
     */
    suspend fun claimNextJob(workerId: String, excludeProviders: List<String> = emptyList()): NovelVideoJob? {
        val now = System.currentTimeMillis()
        val affected = if (excludeProviders.isEmpty()) {
            dao.claimNextJob(workerId, now)
        } else {
            dao.claimNextJobExcludingProviders(workerId, now, excludeProviders)
        }
        if (affected == 0) return null
        // claim 成功。查回 workerId 匹配的 running job（单 worker 下只有一个）。
        // 多 worker 时 workerId 唯一区分，按 workerId 过滤。
        return dao.getRunningJobs().firstOrNull { it.workerId == workerId }
    }

    /** 续约 lease（心跳）。返回 false 表示已失 lease 或已终态，worker 应停止处理。 */
    suspend fun renewLease(jobId: String, workerId: String): Boolean {
        val now = System.currentTimeMillis()
        return dao.renewLease(jobId, workerId, now) > 0
    }

    /** 释放 lease（worker 主动放弃，不改 status）。 */
    suspend fun releaseLease(jobId: String) {
        dao.releaseLease(jobId)
    }

    /**
     * 标记 job 成功。0-rows 表示已被并发终态覆写（如取消已写 CANCELLED）。
     * @return true 表示成功写入
     */
    suspend fun markSucceeded(
        jobId: String,
        outputPath: String?,
        coverPath: String?,
        durationMs: Long?,
        status: String = NovelVideoJobStatus.COMPLETED
    ): Boolean {
        return dao.markJobSucceededIfRunning(jobId, status, outputPath, coverPath, durationMs) > 0
    }

    /**
     * 标记 job 失败。0-rows 表示已被并发终态覆写。
     * @return true 表示成功写入
     */
    suspend fun markFailed(
        jobId: String,
        errorMessage: String?,
        status: String = NovelVideoJobStatus.FAILED
    ): Boolean {
        return dao.markJobFailedIfRunning(jobId, status, errorMessage) > 0
    }

    /**
     * 标记 job 取消。0-rows 表示已终态。
     * @return true 表示成功写入
     */
    suspend fun markCancelled(jobId: String): Boolean {
        return dao.markJobCancelledIfActive(jobId) > 0
    }

    /**
     * 回队：把 running job 翻回 drafting（池满场景）。
     * @param delayMs 延迟重试时间（默认 0 立即可调度）
     */
    suspend fun requeue(jobId: String, delayMs: Long = 0L) {
        val availableAt = System.currentTimeMillis() + delayMs
        dao.requeueJob(jobId, availableAt)
    }

    /**
     * 查孤儿 job：状态在 running 但心跳超时（worker 已死）。
     */
    suspend fun findOrphans(): List<NovelVideoJob> {
        val threshold = System.currentTimeMillis() - ORPHAN_HEARTBEAT_TIMEOUT_MS
        return dao.getOrphanJobs(threshold)
    }
}
