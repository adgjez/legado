package io.legado.app.help.update

import io.legado.app.help.coroutine.Coroutine
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.CoroutineScope

object AppUpdate {

    val giteeUpdate: AppUpdateInterface by lazy {
        AppUpdateGitee
    }
    val preferredUpdate: AppUpdateInterface by lazy {
        AppUpdateGitee
    }


    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

    fun isLatestVersionError(error: Throwable): Boolean {
        val message = error.message ?: return false
        return error is NoStackTraceException && message.contains("最新版本")
    }

}
