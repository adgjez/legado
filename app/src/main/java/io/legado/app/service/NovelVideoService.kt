package io.legado.app.service

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.help.ai.scheduling.GenerationQueue
import io.legado.app.help.ai.scheduling.GenerationWorker
import io.legado.app.help.ai.scheduling.SharedSchedulingState
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager

/**
 * 小说→视频生成的前台服务（P6b 多 worker 版）。
 *
 * 重构自单 pipelineJob 串行模型，改为 N 个 [GenerationWorker] 并发：
 * - [ensurePipelineRunning] 启动 [WORKER_COUNT] 个 worker 协程，每个跑 [GenerationWorker.runLoop]
 * - worker 共享 [SharedSchedulingState]（capacity + slots），保证全局并发上限不突破
 * - orphan 扫描由 [GenerationWorker] companion guard 保证多 worker 下只扫一次
 * - [cancelJob] 精确取消指定 jobId（通过共享 slots 定位协程）；[cancelCurrentJob] 取消首个活跃 job
 * - 保留 [PARTIAL_WAKE_LOCK]、[onTimeout]、[shouldStopOnTaskRemoved]
 * - 无 inflight job 持续 [IDLE_STOP_DELAY_MS] 后自动 stopSelf（省电）
 *
 * 进程死亡不持久化：重启后从 DB 中 RUNNING_STATES 的 job 恢复执行（orphan sweep 兜底）。
 */
class NovelVideoService : BaseService() {

    companion object {
        /** 并发 worker 数量（受 CapacityTable per-provider 上限约束）。 */
        const val WORKER_COUNT = 2

        /** 无 inflight job 持续此时间后自动 stopSelf（15s，对齐 HEARTBEAT_INTERVAL_MS）。 */
        private const val IDLE_STOP_DELAY_MS = 15_000L

        /** Intent extra：指定要取消的 jobId（[IntentAction.remove] 用）。 */
        private const val EXTRA_JOB_ID = "jobId"

        @Volatile
        var isRun = false
            private set

        /**
         * 当前正在跑的 jobId（用于 UI 显示和取消信号）；null 表示空闲。
         * 多 worker 下可能有多个 job 并发，这里取首个活跃 job（通知显示用）。
         * 由通知刷新循环每秒从 [SharedSchedulingState.slots] 更新。
         */
        @Volatile
        var currentJobId: String? = null
            private set

        /**
         * 启动服务并拉起 worker 循环。
         * 调用方无需关心当前是否已有 job 在跑——[ensurePipelineRunning] 的幂等判断保证安全。
         */
        fun start(@Suppress("UNUSED_PARAMETER") context: Context) {
            val intent = Intent(appCtx, NovelVideoService::class.java).apply {
                action = IntentAction.start
            }
            appCtx.startForegroundServiceCompat(intent)
        }

        /** 停止整个服务：cancel worker scope + stopSelf。 */
        fun stop(@Suppress("UNUSED_PARAMETER") context: Context) {
            if (!isRun) return
            val intent = Intent(appCtx, NovelVideoService::class.java).apply {
                action = IntentAction.stop
            }
            appCtx.startForegroundServiceCompat(intent)
        }

        /**
         * 取消当前正在跑的 job（不停止服务，worker 会自动接下一个）。
         * 若无 job 在跑则等价于 no-op。
         */
        fun cancelCurrentJob() {
            currentJobId?.let { cancelJob(appCtx, it) }
        }

        /**
         * 取消指定 jobId（不停止服务）。
         * 若 job 正在跑（在 slots 中），取消其协程；若 job 处于 RUNNING_STATES 但不在 slots 中，
         * 直接标 CANCELLED。两路径都由 Service 统一处理，ViewModel 无需比较 currentJobId。
         */
        fun cancelJob(@Suppress("UNUSED_PARAMETER") context: Context, jobId: String) {
            if (!isRun) return
            val intent = Intent(appCtx, NovelVideoService::class.java).apply {
                action = IntentAction.remove
                putExtra(EXTRA_JOB_ID, jobId)
            }
            appCtx.startForegroundServiceCompat(intent)
        }
    }

    private var workerScope: CoroutineScope? = null

    @Volatile
    private var notificationContent: String = appCtx.getString(R.string.service_starting)

    /** 无 inflight job 的起始时间戳（0 表示当前有 inflight）。用于 idle stop 判断。 */
    @Volatile
    private var idleSinceMs: Long = 0L

    /**
     * R8：PARTIAL_WAKE_LOCK 保证息屏后 CPU 不休眠，避免 Doze 推迟 delay 导致流水线停滞。
     * 在通知刷新循环中管理：有 inflight job 时 acquire，无 inflight 时 release。
     */
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:NovelVideoService")
            .apply { setReferenceCounted(false) }
    }

    private val notificationBuilder by lazy {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdAiTask)
            .setSmallIcon(R.drawable.ic_play_outline_24dp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.novel_video))
            .setContentIntent(activityPendingIntent<MainActivity>("mainActivity"))
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<NovelVideoService>(IntentAction.stop)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        builder
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        // 1 秒循环：刷新通知文案 + 管理 wakeLock + idle stop 检测
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                tickNotificationAndIdle()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.start -> ensurePipelineRunning()
                IntentAction.stop -> {
                    stopAllWorkers()
                    stopSelf()
                }
                IntentAction.remove -> {
                    val targetJobId = intent.getStringExtra(EXTRA_JOB_ID)
                    if (targetJobId != null) {
                        cancelJobInternal(targetJobId)
                    } else {
                        // 兼容旧 cancelCurrentJob（无 EXTRA_JOB_ID）：取消 currentJobId
                        currentJobId?.let { cancelJobInternal(it) }
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        startForeground(NotificationId.NovelVideoService, notificationBuilder.build())
    }

    override fun onDestroy() {
        isRun = false
        val activeIds = SharedSchedulingState.slots.activeJobIds()
        workerScope?.cancel()
        workerScope = null
        SharedSchedulingState.slots.clear()
        currentJobId = null
        idleSinceMs = 0L
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        // best-effort 释放 lease（orphan sweep 会兜底）
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            activeIds.forEach { runCatching { GenerationQueue.releaseLease(it) } }
        }
        super.onDestroy()
        postEvent(EventBus.NOVEL_VIDEO_PROGRESS, "")
    }

    /**
     * R9：用户在 Recents 划掉 App 时不停止服务（若有 job 在跑）。
     */
    override fun shouldStopOnTaskRemoved(): Boolean {
        return workerScope?.isActive != true && currentJobId == null
    }

    /**
     * R10：Android 15+ dataSync 类型前台服务有 6 小时累计超时。
     * 保留中间态（不写 FAILED/CANCELLED），让用户下次打开 App 时断点续传。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.put("NovelVideoService onTimeout startId=$startId fgsType=$fgsType，保留中间态后退出")
        stopAllWorkers()
        stopSelf()
    }

    // ============================================================
    // Worker 生命周期
    // ============================================================

    /**
     * 启动 worker 循环（若未启动）。
     * 创建 [WORKER_COUNT] 个 [GenerationWorker]，每个跑独立 runLoop。
     * SupervisorJob 保证单个 worker 异常不影响其他 worker。
     */
    private fun ensurePipelineRunning() {
        if (workerScope?.isActive == true) return
        // 重置 orphan guard：每次启动都扫一次（进程重启恢复）
        GenerationWorker.resetOrphanSweepGuard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        workerScope = scope
        repeat(WORKER_COUNT) { i ->
            val worker = GenerationWorker(workerId = "worker-$i")
            scope.launch { worker.runLoop(scope) { !scope.isActive } }
        }
    }

    /**
     * 停止所有 worker：cancel scope + 清空 slots。
     * lease 释放由 onDestroy 的 best-effort 路径或 orphan sweep 兜底。
     */
    private fun stopAllWorkers() {
        workerScope?.cancel()
        workerScope = null
        SharedSchedulingState.slots.clear()
        idleSinceMs = 0L
    }

    /**
     * 取消指定 job（IntentAction.remove 处理）。
     * - 若 job 在 slots 中（正在跑）：cancel 协程，GenerationWorker.processTask 会 markCancelled
     * - 若 job 不在 slots 中但处于 RUNNING_STATES：直接标 CANCELLED
     */
    private fun cancelJobInternal(jobId: String) {
        // 先尝试取消协程（如果 job 正在跑）
        SharedSchedulingState.slots.getJob(jobId)?.cancel()
        // 异步标 CANCELLED（条件更新：若 processTask 已 mark 则 0-rows 安全）
        lifecycleScope.launch(Dispatchers.IO) {
            val affected = appDb.novelVideoDao.updateJobFinalStatusWithErrorIfNotFinished(
                jobId,
                NovelVideoJobStatus.CANCELLED,
                "用户取消",
                System.currentTimeMillis()
            )
            if (affected > 0) {
                postEvent(EventBus.NOVEL_VIDEO_PROGRESS, jobId)
            }
        }
    }

    // ============================================================
    // 通知 + idle 管理
    // ============================================================

    /**
     * 每秒 tick：更新 currentJobId + 管理 wakeLock + 刷新通知 + idle stop 检测。
     */
    private suspend fun tickNotificationAndIdle() {
        val activeIds = SharedSchedulingState.slots.activeJobIds()
        currentJobId = activeIds.firstOrNull()

        if (currentJobId != null) {
            // 有 inflight job：acquire wakeLock + 刷新通知 + 重置 idle 计时
            idleSinceMs = 0L
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
            }
            refreshNotificationContent()
            upNotification()
            postEvent(EventBus.NOVEL_VIDEO_PROGRESS, currentJobId.orEmpty())
        } else {
            // 无 inflight job：release wakeLock + idle stop 检测
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            if (idleSinceMs == 0L) {
                idleSinceMs = System.currentTimeMillis()
            }
            val idleDuration = System.currentTimeMillis() - idleSinceMs
            if (idleDuration >= IDLE_STOP_DELAY_MS && workerScope?.isActive == true) {
                // 连续无 inflight 超过阈值：停止服务
                notificationContent = getString(R.string.novel_video_no_pending)
                upNotification()
                workerScope?.cancel()
                workerScope = null
                stopSelf()
            }
        }
    }

    private suspend fun refreshNotificationContent() {
        val jobId = currentJobId
        notificationContent = if (jobId == null) {
            getString(R.string.novel_video_idle)
        } else {
            val job = appDb.novelVideoDao.getJob(jobId)
            if (job != null) {
                val statusText = describeStatus(job.status)
                "${job.bookName} · $statusText"
            } else {
                getString(R.string.novel_video_idle)
            }
        }
    }

    private fun describeStatus(status: String): String = when (status) {
        NovelVideoJobStatus.DRAFTING -> getString(R.string.novel_video_drafting)
        NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW -> getString(R.string.novel_video_review_ready)
        NovelVideoJobStatus.SCREENPLAY_CONFIRMED,
        NovelVideoJobStatus.GENERATING -> getString(R.string.novel_video_generating)
        NovelVideoJobStatus.MERGING -> getString(R.string.novel_video_merging)
        NovelVideoJobStatus.COMPLETED -> getString(R.string.novel_video_completed)
        NovelVideoJobStatus.FAILED -> getString(R.string.novel_video_failed)
        NovelVideoJobStatus.PARTIAL_FAILED -> getString(R.string.novel_video_failed)
        NovelVideoJobStatus.CANCELLED -> getString(R.string.novel_video_cancelled)
        else -> status
    }

    private fun upNotification() {
        // M12：POST_NOTIFICATIONS 被拒时部分 OEM 会对 notify() 抛 SecurityException
        try {
            notificationBuilder.setContentText(notificationContent)
            notificationManager.notify(
                NotificationId.NovelVideoService,
                notificationBuilder.build()
            )
        } catch (e: SecurityException) {
            AppLog.put("NovelVideoService 通知刷新被拒绝（POST_NOTIFICATIONS 未授权）", e)
        }
    }
}
