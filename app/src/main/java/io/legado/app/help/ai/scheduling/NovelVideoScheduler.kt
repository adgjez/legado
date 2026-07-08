package io.legado.app.help.ai.scheduling

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * P5：小说视频生成调度入口。
 *
 * 负责：
 * - 注册/取消 WorkManager 周期巡检任务 [OrphanRecoveryWorker]（每 15 min）。
 *
 * 设计要点：
 * - 使用 [ExistingPeriodicWorkPolicy.KEEP]：同名任务已存在时保留旧任务，
 *   保证重复调用 [schedulePeriodicOrphanSweep] 幂等（App 每次启动都会调一次）。
 * - 15 min 是 Android PeriodicWork 最低间隔，Doze 下可能进一步延迟；
 *   仅作为前台服务挂掉后的兜底，正常路径靠 GenerationWorker 自身心跳续约。
 * - WorkManager 用默认 on-demand 初始化（App 未实现 Configuration.Provider），
 *   首次调用 [WorkManager.getInstance] 会触发其内部初始化。
 */
object NovelVideoScheduler {

    /**
     * 注册周期孤儿巡检任务（幂等，重复调用安全）。
     *
     * 应在 [android.app.Application.onCreate] 中调用一次。
     */
    fun schedulePeriodicOrphanSweep(context: Context) {
        val request = PeriodicWorkRequestBuilder<OrphanRecoveryWorker>(
            15, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            OrphanRecoveryWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * 取消周期孤儿巡检任务。
     *
     * 当前无调用方（任务全生命周期常驻），保留以备后续禁用场景使用。
     */
    fun cancelPeriodicOrphanSweep(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(OrphanRecoveryWorker.WORK_NAME)
    }
}
