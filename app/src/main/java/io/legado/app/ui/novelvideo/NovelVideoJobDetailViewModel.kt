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
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.help.ai.NovelVideoParams
import io.legado.app.service.NovelVideoService
import io.legado.app.utils.openFileUri
import io.legado.app.utils.share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    fun bind(jobId: String) {
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

    /** 删除任务。 */
    suspend fun deleteJob(jobId: String) = withContext(Dispatchers.IO) {
        appDb.novelVideoDao.deleteJob(jobId)
    }

    /** 重试任务。 */
    suspend fun retryJob(jobId: String) = withContext(Dispatchers.IO) {
        val job = appDb.novelVideoDao.getJob(jobId) ?: return@withContext
        if (job.status == NovelVideoJobStatus.FAILED ||
            job.status == NovelVideoJobStatus.PARTIAL_FAILED ||
            job.status == NovelVideoJobStatus.CANCELLED
        ) {
            val segs = appDb.novelVideoDao.getSegmentsByJob(jobId)
            segs.filter { it.status == io.legado.app.data.entities.NovelVideoSegmentStatus.FAILED }.forEach { seg ->
                appDb.novelVideoDao.updateSegmentStatus(seg.id, io.legado.app.data.entities.NovelVideoSegmentStatus.PENDING, null)
            }
            appDb.novelVideoDao.updateJobStatusWithError(
                jobId, NovelVideoJobStatus.GENERATING, null
            )
        }
        NovelVideoService.start(getApplication())
    }

    /** 取消任务。 */
    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        val job = appDb.novelVideoDao.getJob(jobId) ?: return@withContext
        if (job.id == NovelVideoService.currentJobId) {
            NovelVideoService.cancelCurrentJob()
        } else if (job.status in NovelVideoJobStatus.RUNNING_STATES) {
            appDb.novelVideoDao.updateJobStatusWithError(
                jobId, NovelVideoJobStatus.CANCELLED, "用户手动取消", System.currentTimeMillis()
            )
        }
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
