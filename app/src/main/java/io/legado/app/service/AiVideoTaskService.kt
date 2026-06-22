package io.legado.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedVideo
import io.legado.app.help.ai.AiVideoGalleryManager
import io.legado.app.help.ai.AiVideoProviderFactory
import io.legado.app.help.ai.VideoPollResult
import io.legado.app.help.ai.VideoStatus
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 视频任务服务：负责把 pending/running 的任务继续轮询 + 下载。
 *
 * 设计：
 *  - 任务进入单一线程池（限制并发）
 *  - 启动时扫表恢复所有 pending/running 任务
 *  - submit 时由 [io.legado.app.help.ai.AiVideoService] 调 startIfNeeded 启动
 *  - 状态更新通过 LiveEventBus 通知 UI
 */
class AiVideoTaskService : BaseService() {

    companion object {
        var isRun = false
            private set

        private val startLock = Any()

        /**
         * 外部提交任务后调用：保证 service 已启动。
         * 多次调用是安全的：单例 + 状态检查。
         */
        fun startIfNeeded(context: Context) {
            synchronized(startLock) {
                if (isRun) return
                val intent = Intent(context, AiVideoTaskService::class.java).apply {
                    action = IntentAction.start
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    private val pool by lazy {
        Executors.newFixedThreadPool(
            AppConfig.aiVideoMaxConcurrent.coerceIn(1, 4)
        ).asCoroutineDispatcher()
    }
    private val queue = ConcurrentLinkedQueue<String>()
    private val jobMap = HashMap<String, Job>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + pool)
    private val stopping = AtomicBoolean(false)
    private var notificationUpdateJob: Job? = null

    private val notificationBuilder by lazy {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdAiVideo)
            .setSmallIcon(R.drawable.ic_image)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.ai_video_task_service_title))
            .setContentIntent(activityPendingIntent<io.legado.app.ui.main.ai.AiVideoGalleryActivity>("aiVideoGallery"))
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<AiVideoTaskService>(IntentAction.stop)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        stopping.set(false)
        observeProgress()
        // 启动时恢复 pending/running 任务
        recoverTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.start -> ensureDispatcherRunning()
                IntentAction.stop -> {
                    stopping.set(true)
                    stopAllJobs()
                    stopSelf()
                }
                else -> ensureDispatcherRunning()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        isRun = false
        stopping.set(true)
        stopAllJobs()
        runCatching { scope.cancel() }
        runCatching { pool.cancel() }
        notificationUpdateJob?.cancel()
        super.onDestroy()
    }

    private fun observeProgress() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        val pending = appDb.aiGeneratedVideoDao.countByStatus(AiGeneratedVideo.STATUS_PENDING)
        val running = appDb.aiGeneratedVideoDao.countByStatus(AiGeneratedVideo.STATUS_RUNNING)
        val total = pending + running
        val text = if (total <= 0) {
            getString(R.string.ai_video_task_idle)
        } else {
            getString(R.string.ai_video_task_running, total)
        }
        notificationBuilder.setContentText(text)
        val notification = notificationBuilder.build()
        val nm = splitties.systemservices.notificationManager
        if (total > 0) {
            nm.notify(NotificationId.AiVideoTaskService, notification)
        } else {
            nm.cancel(NotificationId.AiVideoTaskService)
        }
    }

    private fun recoverTasks() {
        val pending = appDb.aiGeneratedVideoDao.byStatusSingle(AiGeneratedVideo.STATUS_PENDING)
        val running = appDb.aiGeneratedVideoDao.byStatusSingle(AiGeneratedVideo.STATUS_RUNNING)
        (pending + running).forEach { enqueueTask(it.id) }
    }

    private fun ensureDispatcherRunning() {
        // 已经有一个常驻 dispatcher 协程在跑
        scope.launch {
            while (isActive && !stopping.get()) {
                val next = queue.poll() ?: run {
                    delay(500)
                    return@run
                }
                if (jobMap[next]?.isActive == true) continue
                jobMap[next] = scope.launch {
                    runOne(next)
                }
            }
        }
    }

    private fun enqueueTask(videoId: String) {
        if (queue.contains(videoId)) return
        queue.offer(videoId)
        ensureDispatcherRunning()
    }

    private suspend fun runOne(videoId: String) {
        val row = appDb.aiGeneratedVideoDao.get(videoId) ?: return
        if (row.status == AiGeneratedVideo.STATUS_SUCCESS ||
            row.status == AiGeneratedVideo.STATUS_FAILED ||
            row.status == AiGeneratedVideo.STATUS_CANCELLED
        ) return
        if (row.externalTaskId.isBlank()) {
            AiVideoGalleryManager.updateStatus(videoId, AiGeneratedVideo.STATUS_FAILED, "no external task id")
            return
        }
        val provider = AppConfig.findEnabledVideoProvider(row.providerId) ?: run {
            AiVideoGalleryManager.updateStatus(
                videoId,
                AiGeneratedVideo.STATUS_FAILED,
                "Provider not found: ${row.providerId}"
            )
            return
        }
        val providerImpl = AiVideoProviderFactory.create(provider)
        val startedAt = System.currentTimeMillis()
        AiVideoGalleryManager.updateStatus(videoId, AiGeneratedVideo.STATUS_RUNNING)
        while (true) {
            if (stopping.get()) {
                AiVideoGalleryManager.updateStatus(
                    videoId, AiGeneratedVideo.STATUS_CANCELLED, "service stopped", 0
                )
                return
            }
            if (System.currentTimeMillis() - startedAt > provider.validMaxWaitMs()) {
                AiVideoGalleryManager.updateStatus(videoId, AiGeneratedVideo.STATUS_FAILED, "timeout")
                LiveEventBus.get(EventBus.AI_VIDEO_FAILED).post(Pair(videoId, "timeout"))
                return
            }
            val polled: VideoPollResult = try {
                providerImpl.poll(row.externalTaskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("AI video poll error: ${e.message}", e)
                VideoPollResult(
                    status = VideoStatus.FAILED,
                    failReason = e.message ?: e.javaClass.simpleName
                )
            }
            when (polled.status) {
                VideoStatus.SUCCESS -> {
                    runCatching {
                        AiVideoGalleryManager.saveCompletedVideo(
                            videoId = videoId,
                            videoUrl = polled.videoUrl,
                            coverUrl = polled.coverUrl,
                            provider = provider,
                            durationMs = polled.durationMs,
                            width = polled.width,
                            height = polled.height,
                            sizeBytes = polled.sizeBytes
                        )
                    }.onSuccess {
                        LiveEventBus.get(EventBus.AI_VIDEO_COMPLETED)
                            .post(Pair(videoId, it.id))
                    }.onFailure {
                        AppLog.put("AI video save failed", it)
                        AiVideoGalleryManager.updateStatus(
                            videoId,
                            AiGeneratedVideo.STATUS_FAILED,
                            it.message ?: "save failed"
                        )
                        LiveEventBus.get(EventBus.AI_VIDEO_FAILED)
                            .post(Pair(videoId, it.message ?: "save failed"))
                    }
                    return
                }
                VideoStatus.FAILED -> {
                    AiVideoGalleryManager.updateStatus(
                        videoId, AiGeneratedVideo.STATUS_FAILED,
                        polled.failReason ?: "unknown", 0
                    )
                    LiveEventBus.get(EventBus.AI_VIDEO_FAILED)
                        .post(Pair(videoId, polled.failReason ?: "unknown"))
                    return
                }
                VideoStatus.CANCELLED -> {
                    AiVideoGalleryManager.updateStatus(
                        videoId, AiGeneratedVideo.STATUS_CANCELLED,
                        "cancelled", polled.progress
                    )
                    return
                }
                else -> {
                    AiVideoGalleryManager.updateProgress(videoId, polled.progress)
                    LiveEventBus.get(EventBus.AI_VIDEO_PROGRESS)
                        .post(Pair(videoId, polled.progress))
                    delay(provider.validPollIntervalMs())
                }
            }
        }
    }

    private fun stopAllJobs() {
        jobMap.values.forEach { runCatching { it.cancel() } }
        jobMap.clear()
        queue.clear()
    }
}
