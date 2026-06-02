package io.legado.app.help.ai

import android.content.Intent
import io.legado.app.service.AiTaskKeepAliveService
import io.legado.app.utils.startForegroundServiceCompat
import splitties.init.appCtx
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AiTaskKeepAlive {

    private val activeTasks = ConcurrentHashMap<String, String>()

    val activeCount: Int
        get() = activeTasks.size

    val title: String
        get() = activeTasks.values.lastOrNull() ?: "AI任务处理中"

    fun retain(title: String): String {
        val taskId = UUID.randomUUID().toString()
        activeTasks[taskId] = title.ifBlank { "AI任务处理中" }
        notifyService(AiTaskKeepAliveService.ACTION_REFRESH)
        return taskId
    }

    fun release(taskId: String?) {
        if (taskId.isNullOrBlank()) return
        activeTasks.remove(taskId)
        notifyService(
            if (activeTasks.isEmpty()) {
                AiTaskKeepAliveService.ACTION_STOP
            } else {
                AiTaskKeepAliveService.ACTION_REFRESH
            }
        )
    }

    private fun notifyService(action: String) {
        val intent = Intent(appCtx, AiTaskKeepAliveService::class.java).apply {
            this.action = action
        }
        appCtx.startForegroundServiceCompat(intent)
    }
}
