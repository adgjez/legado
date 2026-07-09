package io.legado.app.ui.novelvideo

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.help.ai.NovelVideoParams
import io.legado.app.service.NovelVideoService
import io.legado.app.utils.openFileUri
import io.legado.app.utils.share
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * 任务详情 VM：通过 Room Flow 监听单个 job + 其 segments + character sheets。
 *
 * 服务运行状态由 Activity 在 EventBus 回调中调 [refreshServiceState] 同步。
 */
class NovelVideoJobDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val _job = MutableStateFlow<NovelVideoJob?>(null)
    val job: StateFlow<NovelVideoJob?> = _job.asStateFlow()

    private val _segments = MutableStateFlow<List<io.legado.app.data.entities.NovelVideoSegment>>(emptyList())
    val segments: StateFlow<List<io.legado.app.data.entities.NovelVideoSegment>> = _segments.asStateFlow()

    private val _characters = MutableStateFlow<List<io.legado.app.data.entities.NovelVideoCharacterSheet>>(emptyList())
    val characters: StateFlow<List<io.legado.app.data.entities.NovelVideoCharacterSheet>> = _characters.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _params = MutableStateFlow(NovelVideoParams())
    val params: StateFlow<NovelVideoParams> = _params.asStateFlow()

    private var boundJobId: String? = null

    /**
     * M9：bind 幂等——同一 jobId 不重复订阅 Flow，避免旋转后收集器累积。
     */
    fun bind(jobId: String) {
        if (boundJobId == jobId) return
        boundJobId = jobId
        viewModelScope.launch {
            appDb.novelVideoDao.getJobFlow(jobId).collectLatest {
                _job.value = it
                _params.value = it?.let { NovelVideoParams.fromJob(it) } ?: NovelVideoParams()
            }
        }
        viewModelScope.launch {
            appDb.novelVideoDao.getSegmentsByJobFlow(jobId).collectLatest { _segments.value = it }
        }
        viewModelScope.launch {
            appDb.novelVideoDao.getCharacterSheetsByJobFlow(jobId).collectLatest { _characters.value = it }
        }
        _serviceRunning.value = NovelVideoService.isRun
    }

    fun refreshServiceState() {
        _serviceRunning.value = NovelVideoService.isRun
    }

    /**
     * 删除任务。委托 [NovelVideoJobOps.deleteJob]。
     */
    suspend fun deleteJob(jobId: String) = NovelVideoJobOps.deleteJob(jobId)

    /**
     * 重试任务。委托 [NovelVideoJobOps.retryJob]。
     */
    suspend fun retryJob(jobId: String) = NovelVideoJobOps.retryJob(jobId)

    /**
     * 取消任务。委托 [NovelVideoJobOps.cancelJob]。
     */
    suspend fun cancelJob(jobId: String) = NovelVideoJobOps.cancelJob(jobId)

    companion object {
        // P6：jobOpMutex 已上移至 NovelVideoJobOps 共享单例（修复双 ViewModel 并发覆写）。
    }

    /**
     * 用系统播放器打开合并后的视频文件。
     * @return true 表示已成功派发 Intent；false 表示无可用文件或派发失败。
     */
    fun openOutputVideo(context: Context, job: NovelVideoJob?): Boolean {
        val path = job?.outputPath?.takeIf { it.isNotBlank() } ?: return false
        val file = File(path)
        if (!file.isFile) return false
        return runCatching {
            val uri = if (path.startsWith("content://")) {
                Uri.parse(path)
            } else if (path.startsWith("http", ignoreCase = true)) {
                Uri.parse(path)
            } else {
                FileProvider.getUriForFile(context, AppConst.authority, file)
            }
            context.openFileUri(uri, "video/*")
            true
        }.getOrElse { false }
    }

    /**
     * 分享合并后的视频文件。
     * @return true 表示已成功派发 Intent；false 表示无可用文件。
     */
    fun shareOutputVideo(context: Context, job: NovelVideoJob?): Boolean {
        val path = job?.outputPath?.takeIf { it.isNotBlank() } ?: return false
        val file = File(path)
        if (!file.isFile) return false
        return runCatching {
            context.share(file, "video/*")
            true
        }.getOrElse { false }
    }
}
