package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import io.legado.app.constant.AppLog
import io.legado.app.service.NovelVideoService

/**
 * P5：开机自启接收器。
 *
 * 监听 [Intent.ACTION_BOOT_COMPLETED]：
 * - 设备开机/重启后，前台服务已被系统杀掉，可能残留 GENERATING/MERGING 状态的孤儿 job。
 * - 收到开机广播后拉起 [NovelVideoService]，由其内部的
 *   [io.legado.app.help.ai.scheduling.GenerationWorker.handleOrphanTasksOnStart]
 *   走 resumeVideo 续传路径恢复（优先续传避免重复扣费）。
 *
 * 注意：
 * - 仅处理 BOOT_COMPLETED，其它 action（如 MY_PACKAGE_REPLACED）不在此接收器职责内。
 * - [NovelVideoService.start] 内部使用 startForegroundServiceCompat，自带 Android 8+ 后台启动豁免。
 * - onReceive 内尽量轻量，仅触发 start；恢复逻辑在服务进程内异步执行。
 *
 * 可测试性：核心逻辑抽到顶层 [handleBoot]，onReceive 用默认依赖调用它；
 * 单测直接调 [handleBoot] 注入 startService，无需 Robolectric。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        handleBoot(
            action = intent.action,
            context = context,
            startService = { ctx ->
                runCatching { NovelVideoService.start(ctx) }
                    .onFailure { AppLog.put("BootReceiver 拉起 NovelVideoService 失败：${it.message}") }
            }
        )
    }
}

/**
 * 开机广播处理核心逻辑（抽出便于单测）。
 *
 * @param action 广播 action（仅 [Intent.ACTION_BOOT_COMPLETED] 触发）
 * @param context 用于启动服务的 Context
 * @param startService 启动 [NovelVideoService]（默认调静态 start；测试可注入记录调用）
 */
@VisibleForTesting
internal fun handleBoot(
    action: String?,
    context: Context,
    startService: (Context) -> Unit
) {
    if (action != Intent.ACTION_BOOT_COMPLETED) return
    startService(context)
}
