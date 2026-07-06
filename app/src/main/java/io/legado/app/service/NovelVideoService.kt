package io.legado.app.service

import android.content.Context
import android.content.Intent
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
         */
        fun start(@Suppress("UNUSED_PARAMETER") context: Context) {
            if (isRun) {
                postEvent(EventBus.NOVEL_VIDEO_PROGRESS, "")
                return
            }
            appCtx.startService<NovelVideoService> {
                action = IntentAction.start
            }
        }

        /** 停止整个服务：取消当前 job + 中止循环 + stopSelf。 */
        fun stop(@Suppress("UNUSED_PARAMETER") context: Context) {
            if (!isRun) return
            appCtx.startService<NovelVideoService> {
                action = IntentAction.stop
            }
        }

        /**
         * 取消当前正在跑的 job（不停止服务，自动接下一个）。
         * 若无 job 在跑则等价于 no-op。
         */
        fun cancelCurrentJob() {
            if (!isRun) return
            appCtx.startService<NovelVideoService> {
                action = IntentAction.remove
            }
        }
    }

    private var pipelineJob: Job? = null
    // 跨线程读写（IO 协程写、主线程 startForegroundNotification 读），需 @Volatile 保证可见性
    @Volatile
    private var notificationContent: String = appCtx.getString(R.string.service_starting)

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
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
        super.onDestroy()
        postEvent(EventBus.NOVEL_VIDEO_PROGRESS, "")
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

    /** 取下一个该跑的 job：优先 DRAFTING/SCREENPLAY_CONFIRMED/GENERATING，其次 SCREENPLAY_PENDING_REVIEW。 */
    private suspend fun pickNextJob(): String? {
        val running = appDb.novelVideoDao.getRunningJobs()
        if (running.isEmpty()) return null
        // 优先非"等待审阅"的（可以立即推进）
        val ready = running.firstOrNull {
            it.status != NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW
        }
        return (ready ?: running.first()).id
    }

    /**
     * 跑单个 job：重置 cancelFlag、调 Generator、捕获异常落库失败状态。
     */
    private suspend fun runOneJob(jobId: String) {
        currentJobId = jobId
        cancelFlag.set(false)
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
            // 若 job 已被并发写入终态（如用户已取消），不覆写，避免 CANCELLED 被改成 FAILED
            val cur = appDb.novelVideoDao.getJob(jobId)
            if (cur != null && cur.status in NovelVideoJobStatus.FINISHED_STATES) {
                AppLog.put("jobId=$jobId 已处于终态 ${cur.status}，不覆写为 FAILED")
            } else {
                appDb.novelVideoDao.updateJobStatusWithError(
                    jobId,
                    NovelVideoJobStatus.FAILED,
                    e.message,
                    System.currentTimeMillis()
                )
                postEvent(EventBus.NOVEL_VIDEO_FAILED, jobId)
            }
        } finally {
            currentJobId = null
            postEvent(EventBus.NOVEL_VIDEO_PROGRESS, "")
        }
    }

    /** 若 job 仍处于 RUNNING_STATES，标记为 CANCELLED。 */
    private suspend fun markCancelledIfRunning(jobId: String, reason: String?) {
        val job = appDb.novelVideoDao.getJob(jobId) ?: return
        if (job.status in NovelVideoJobStatus.RUNNING_STATES) {
            appDb.novelVideoDao.updateJobStatusWithError(
                jobId,
                NovelVideoJobStatus.CANCELLED,
                reason ?: "用户取消",
                System.currentTimeMillis()
            )
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
                val err = job.errorMessage?.takeIf { it.isNotBlank() }
                if (err != null) "${job.bookName} · $statusText · $err"
                else "${job.bookName} · $statusText"
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
        notificationBuilder.setContentText(notificationContent)
        notificationManager.notify(
            NotificationId.NovelVideoService,
            notificationBuilder.build()
        )
    }
}
