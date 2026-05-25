package io.legado.app.help.update

import io.legado.app.help.coroutine.Coroutine
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.CoroutineScope

object AppUpdate {

    val giteeUpdate: AppUpdateInterface by lazy {
        AppUpdateGitee
    }
    val githubUpdate: AppUpdateInterface by lazy {
        AppUpdateGitHub
    }
    val preferredUpdate: AppUpdateInterface by lazy {
        PreferredAppUpdate
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

    private object PreferredAppUpdate : AppUpdateInterface {
        override fun check(scope: CoroutineScope): Coroutine<UpdateInfo> {
            return Coroutine.async(scope) {
                checkNow()
            }.timeout(15000)
        }

        private suspend fun checkNow(): UpdateInfo {
            return when (AppUpdateConfig.strategy) {
                AppUpdateConfig.STRATEGY_GITEE_ONLY -> AppUpdateGitee.checkNow()
                AppUpdateConfig.STRATEGY_GITHUB_ONLY -> AppUpdateGitHub.checkNow()
                else -> checkGiteeThenGithub()
            }
        }

        private suspend fun checkGiteeThenGithub(): UpdateInfo {
            return try {
                AppUpdateGitee.checkNow()
            } catch (giteeError: Throwable) {
                if (isLatestVersionError(giteeError)) {
                    throw giteeError
                }
                try {
                    AppUpdateGitHub.checkNow()
                } catch (githubError: Throwable) {
                    if (isLatestVersionError(githubError)) {
                        throw githubError
                    }
                    throw NoStackTraceException(
                        "Gitee 更新失败: ${giteeError.localizedMessage ?: giteeError.message ?: giteeError}\n" +
                            "GitHub 更新失败: ${githubError.localizedMessage ?: githubError.message ?: githubError}"
                    )
                }
            }
        }
    }

}
