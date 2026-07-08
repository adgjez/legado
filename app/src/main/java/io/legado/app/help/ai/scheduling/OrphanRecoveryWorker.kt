package io.legado.app.help.ai.scheduling

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.service.NovelVideoService
import kotlinx.coroutines.CancellationException

/**
 * P5：周期性孤儿 job 巡检 Worker。
 *
 * WorkManager 每 15 min（系统最低周期）触发一次 [doWork]：
 * 1. 调 [GenerationQueue.findOrphans] 查心跳超时的孤儿 job（worker 已死、lease 过期）。
 * 2. 若存在孤儿 → 拉起 [NovelVideoService]；服务启动时
 *    [GenerationWorker.handleOrphanTasksOnStart] 会调 [io.legado.app.help.ai.NovelVideoGenerator.resumeJob]
 *    恢复（优先 resumeVideo 续传，避免重复扣费）。
 *
 * 设计要点：
 * - Worker 自身不直接 resume，只负责"发现孤儿 + 拉起服务"，恢复逻辑集中在
 *   GenerationWorker / NovelVideoGenerator，避免重复实现与状态分裂。
 * - 15 min 是 Android PeriodicWork 最低间隔，Doze 下可能进一步延迟；
 *   前台服务运行时靠 GenerationWorker 自身心跳续约，不依赖此 Worker 兜底。
 * - 无论是否发现孤儿、是否异常，均返回 [androidx.work.Result.success]——
 *   巡检失败不应阻塞下一个周期（错误已记日志）。
 *
 * 可测试性：核心逻辑抽到顶层 [sweepOrphans]，doWork 用默认依赖调用它；
 * 单测直接调 [sweepOrphans] 注入 findOrphans / startService，无需构造 WorkerParameters。
 */
class OrphanRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = sweepOrphans(
        context = applicationContext,
        findOrphans = { GenerationQueue.findOrphans() },
        startService = { ctx ->
            runCatching { NovelVideoService.start(ctx) }
                .onFailure { AppLog.put("OrphanRecoveryWorker 拉起 NovelVideoService 失败：${it.message}") }
        }
    )

    companion object {
        /** 周期巡检任务名（enqueueUniquePeriodicWork 用，KEEP 策略保证幂等）。 */
        const val WORK_NAME = "novel_video_orphan_sweep"
    }
}

/**
 * 孤儿巡检核心逻辑（抽出便于单测）。
 *
 * @param context 用于启动服务的 Context
 * @param findOrphans 查孤儿 job（默认 [GenerationQueue.findOrphans]；测试可注入）
 * @param startService 启动 [NovelVideoService]（默认调静态 start；测试可注入记录调用）
 * @return 始终 [Result.success]（巡检失败不阻塞下一周期）
 */
@VisibleForTesting
internal suspend fun sweepOrphans(
    context: Context,
    findOrphans: suspend () -> List<NovelVideoJob>,
    startService: (Context) -> Unit
): Result {
    return try {
        val orphans = findOrphans()
        if (orphans.isNotEmpty()) {
            // 日志本身可能因 AppConfig 静态初始化失败，best-effort 不影响主流程
            runCatching { AppLog.put("OrphanRecoveryWorker 发现 ${orphans.size} 个孤儿 job，拉起 NovelVideoService") }
            startService(context)
        }
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        runCatching { AppLog.put("OrphanRecoveryWorker 巡检异常：${e.message}") }
        Result.success()
    }
}
