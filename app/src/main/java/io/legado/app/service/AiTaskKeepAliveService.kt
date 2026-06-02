package io.legado.app.service

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.help.ai.AiTaskKeepAlive
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.activityPendingIntent
import splitties.systemservices.notificationManager

class AiTaskKeepAliveService : BaseService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return super.onStartCommand(intent, flags, startId)
            }
            ACTION_REFRESH -> {
                upNotification()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun startForegroundNotification() {
        startForeground(NotificationId.AiTaskService, createNotification().build())
    }

    private fun upNotification() {
        notificationManager.notify(NotificationId.AiTaskService, createNotification().build())
    }

    private fun createNotification(): NotificationCompat.Builder {
        val count = AiTaskKeepAlive.activeCount.coerceAtLeast(1)
        val text = if (count > 1) {
            "还有 $count 个 AI 任务运行中"
        } else {
            AiTaskKeepAlive.title
        }
        return NotificationCompat.Builder(this, AppConst.channelIdAiTask)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle("AI任务处理中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(activityPendingIntent<MainActivity>("aiTask"))
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationManager.cancel(NotificationId.AiTaskService)
    }

    companion object {
        const val ACTION_REFRESH = "io.legado.app.action.AI_TASK_REFRESH"
        const val ACTION_STOP = "io.legado.app.action.AI_TASK_STOP"
    }
}
