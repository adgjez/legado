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
import io.legado.app.help.ai.NovelVideoGenerator
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startService
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 小说→视频生成的前台服务。
 *
 * 参照 [CacheBookService] / [ExportBookService] 的形态：
 * - 继承 [BaseService]，借助 [lifecycleScope] 管理协程
 * - 单 [pipelineJob] while 循环从 DB 拉 RUNNING_STATES 状态的 job 依次执行
 * - 通过 [IntentAction.start] 启动服务、[IntentAction.stop] 停止整个服务、[IntentAction.remove] 取消当前 job
 * - 进度通知：1 秒循环刷新当前 job 名 + 状态文案（与 CacheBookService 一致）
 * - 与 UI 通过 [EventBus.NOVEL_VIDEO_PROGRESS] 等事件通信
 *
 * 进程死亡不持久化（与 CacheBookService 一致）：重启后从 DB 中 RUNNING_STATES 的 job 恢复执行；
 * 若 job 已是 SCREENPLAY_PENDING_REVIEW，会立即重新挂起等待用户审阅。
 */
class NovelVideoService : BaseService() {

    companion object {
        @Volatile
        var isRun = false
            private set

        /** 当前正在跑的 jobId（仅用于 UI 显示和取消信号）；null 表示空闲。 */
        @Volatile
        var currentJobId: String? = null
            private set

        /** 取消信号；每个 job 启动前会重置。 */
        private val cancelFlag = AtomicBoolean(false)

        /**
         * 启动服务并尝试拉起下一个待处理 job。
         * 调用方无需关心当前是否已有 job 在跑——服务内部会去 DB 找下一个可推进的 job。
         *
         * R5 修复：不再仅靠 [isRun] 短路。原实现在 isRun=true 时直接 return 不调 startService，
         * 但 pipeline 可能在 stopSelf() 后、onDestroy() 前的窗口内 isRun 仍为 true，
         * 此时新建/重试任务会因短路而永不触发 ensurePipelineRunning，导致任务卡死。
         * 现在无论 isRun 状态都发 start Intent，由 [ensurePipelineRunning] 的
         * `pipelineJob?.isActive == true` 判断保证幂等。
         */
        fun start(@Suppress("UNUSED_PARAMETER") context: Context) {
            // M13：用 startForegroundServiceCompat 替代 startService，
            // 避免后台调用时 Android 8+ 抛 IllegalStateException
            val intent = Intent(appCtx, NovelVideoService::class.java).apply {
                action = IntentAction.start
            }
            appCtx.startForegroundServiceCompat(intent)
        }

        /** 停止整个服务：取消当前 job + 中止循环 + stopSelf。 */
        fun stop(@Suppress("UNUSED_PARAMETER") context: Context) {
            if (!isRun) return
            val intent = Intent(appCtx, NovelVideoService::class.java).apply {
                action = IntentAction.stop
            }
            appCtx.startForegroundServiceCompat(intent)
        }

        /**
         * 取消当前正在跑的 job（不停止服务，自动接下一个）。
         * 若无 job 在跑则等价于 no-op。
         */
        fun cancelCurrentJob() {
            if (!isRun) return
            val intent = Intent(appCtx, NovelVideoService::class.java).apply {
                action = IntentAction.remove
            }
            appCtx.startForegroundServiceCompat(intent)
        }
    }

    private var pipelineJob: Job? = null
    // 跨线程读写（IO 协程写、主线程 startForegroundNotification 读），需 @Volatile 保证可见性
    @Volatile
    private var notificationContent: String = appCtx.getString(R.string.service_starting)

    /**
     * R8：PARTIAL_WAKE_LOCK 保证息屏后 CPU 不休眠，避免 Doze 推迟 delay 导致流水线停滞。
     * 在 [runOneJob] 开始时 acquire，finally 中 release。参考 [AiTaskKeepAliveService]。
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
        // N2：改 PRIVATE，锁屏不显示书名和错误详情，保护用户阅读隐私
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        builder
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        // 1 秒循环：刷新通知文案 + 通知 UI 更新（与 CacheBookService 一致）
        // 跑在 IO：refreshNotificationContent 会查 Room
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                // 空闲时（无当前任务）跳过 DB 查询和通知刷新，降低耗电
                if (currentJobId != null) {
                    refreshNotificationContent()
                    upNotification()
                    postEvent(EventBus.NOVEL_VIDEO_PROGRESS, currentJobId.orEmpty())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.start -> ensurePipelineRunning()
                IntentAction.stop -> {
                    cancelFlag.set(true)
                    pipelineJob?.cancel()
                    stopSelf()
                }
                IntentAction.remove -> {
                    // 取消当前 job，但保持服务运行（pipeline loop 会自动接下一个）
                    // 注意：仅设 cancelFlag 供 generate 内部 checkCancelled 轮询；
                    // 不调 pipelineJob?.cancel()，否则会取消整个循环导致服务停止。
                    // 长 suspend 调用（如 chatStream）会在下一个 checkCancelled 点响应。
                    cancelFlag.set(true)
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
        currentJobId = null
        cancelFlag.set(true)
        pipelineJob?.cancel()
        pipelineJob = null
        // R8：释放 WakeLock
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        super.onDestroy()
        postEvent(EventBus.NOVEL_VIDEO_PROGRESS, "")
    }

    /**
     * R9：用户在 Recents 划掉 App 时不停止服务（若有 job 在跑）。
     * 原默认 true 会立即 stopSelf，当前 job 卡在 GENERATING。
     * 参考 [WebDavTaskService] 的同名重写。
     */
    override fun shouldStopOnTaskRemoved(): Boolean {
        // 有 job 在跑或 pipeline 活跃时保留服务；否则正常退出
        return pipelineJob?.isActive != true && currentJobId == null
    }

    /**
     * R10：Android 15+ dataSync 类型前台服务有 6 小时累计超时。
     * 原 [BaseService.onTimeout] 直接 stopSelf，当前 job 卡在中间态（GENERATING/MERGING）。
     * 这里不写 FAILED（保留中间态，让用户下次打开 App 时断点续传），仅记日志并 stopSelf。
     * 中间态已由 NovelVideoGenerator 的条件更新落库，重启后从 DB 恢复。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.put("NovelVideoService onTimeout startId=$startId fgsType=$fgsType，保留中间态后退出")
        // 不调 markCancelledIfRunning —— 保留 GENERATING/MERGING 等中间态，
        // 用户下次打开 App 触发 NovelVideoService.start 时会从 DB 恢复断点续传
        cancelFlag.set(true)
        pipelineJob?.cancel()
        stopSelf()
    }

    // ============================================================
    // 流水线循环
    // ============================================================

    /**
     * 启动 pipeline 循环（若未启动）。
     * 循环体每次从 DB 取一个 RUNNING_STATES 状态的 job，调 [NovelVideoGenerator.generate] 推进。
     * 取空则 stopSelf 退出。
     */
    private fun ensurePipelineRunning() {
        if (pipelineJob?.isActive == true) return
        pipelineJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val nextJobId = pickNextJob() ?: run {
                        // 无任务可做
                        notificationContent = getString(R.string.novel_video_no_pending)
                        upNotification()
                        stopSelf()
                        return@launch
                    }
                    runOneJob(nextJobId)
                }
            } finally {
                isRun = false
                postEvent(EventBus.NOVEL_VIDEO_PROGRESS, "")
            }
        }
    }

    /**
     * 取下一个该跑的 job。
     *
     * M8 修复：跳过 SCREENPLAY_PENDING_REVIEW 状态的 job，避免它阻塞调度。
     * 原实现在只有 PENDING_REVIEW job 时会取它并进入 awaitReviewConfirmation 长期挂起，
     * 导致后续新建的 DRAFTING job 无法推进。现在仅返回可立即推进的 job，
     * PENDING_REVIEW 由用户审阅后调 confirmScreenplay 触发 start 来推进。
     */
    private suspend fun pickNextJob(): String? {
        val running = appDb.novelVideoDao.getRunningJobs()
        if (running.isEmpty()) return null
        // 仅返回可立即推进的 job（排除等待审阅和已暂停）
        return running.firstOrNull {
            it.status != NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW &&
                it.status != NovelVideoJobStatus.PAUSED
        }?.id
    }

    /**
     * 跑单个 job：重置 cancelFlag、调 Generator、捕获异常落库失败状态。
     * R8：在 job 执行期间持有 WakeLock，避免 Doze 下 CPU 休眠导致流水线停滞。
     */
    private suspend fun runOneJob(jobId: String) {
        currentJobId = jobId
        cancelFlag.set(false)
        // R8：获取 WakeLock，保证 job 执行期间 CPU 不休眠
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
        postEvent(EventBus.NOVEL_VIDEO_PROGRESS, jobId)

        try {
            NovelVideoGenerator.generate(
                jobId = jobId,
                isCancelled = { cancelFlag.get() || pipelineJob?.isActive != true }
            )
        } catch (e: CancellationException) {
            // 取消：把 job 标记为 CANCELLED（若尚未终结）
            // 协程已处于 cancelling 态，suspend 调用会立即抛 CancellationException，
            // 必须用 NonCancellable 包裹才能完成 DB 写入
            withContext(NonCancellable) { markCancelledIfRunning(jobId, e.message) }
            // 不重新抛出 —— 让 while 循环判断是否继续取下一个 job
            if (pipelineJob?.isActive != true) {
                // 整个 pipeline 已被取消（IntentAction.stop），退出循环
                throw e
            }
        } catch (e: Throwable) {
            AppLog.put("小说→视频任务失败 jobId=$jobId", e)
            // 用条件更新：若 job 已被并发写入终态（如用户已取消），不覆写，
            // 避免 CANCELLED 被改成 FAILED。取代旧的 getJob+check 两步（有 TOCTOU 窗口）
            val affected = appDb.novelVideoDao.updateJobFinalStatusWithErrorIfNotFinished(
                jobId,
                NovelVideoJobStatus.FAILED,
                e.message,
                System.currentTimeMillis()
            )
            if (affected > 0) {
                postEvent(EventBus.NOVEL_VIDEO_FAILED, jobId)
            } else {
                AppLog.put("jobId=$jobId 已处于终态，不覆写为 FAILED")
            }
        } finally {
            currentJobId = null
            // R8：job 结束后释放 WakeLock（下一个 job 会重新获取）
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            postEvent(EventBus.NOVEL_VIDEO_PROGRESS, "")
        }
    }

    /**
     * 若 job 仍处于运行态，标记为 CANCELLED。
     *
     * 用条件更新（WHERE status NOT IN FINISHED_STATES）替代先查后写，
     * 把"检查 + 写入"合并为原子 SQL，消除 TOCTOU 窗口：
     * 旧实现在 getJob 与 updateJobStatusWithError 之间，finalizeJob 可能已写 COMPLETED，
     * 随后本方法会覆写为 CANCELLED。
     */
    private suspend fun markCancelledIfRunning(jobId: String, reason: String?) {
        val affected = appDb.novelVideoDao.updateJobFinalStatusWithErrorIfNotFinished(
            jobId,
            NovelVideoJobStatus.CANCELLED,
            reason ?: "用户取消",
            System.currentTimeMillis()
        )
        if (affected > 0) {
            // 用户主动取消不应弹"失败"通知；发 PROGRESS 让 UI 刷新列表看到 CANCELLED 状态
            postEvent(EventBus.NOVEL_VIDEO_PROGRESS, jobId)
        }
    }

    // ============================================================
    // 通知
    // ============================================================

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
        // M12：POST_NOTIFICATIONS 被拒时部分 OEM 会对 notify() 抛 SecurityException，
        // 不捕获会导致 1 秒刷新循环崩溃终止整个服务。仿 AudioPlayService 加 try/catch。
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
