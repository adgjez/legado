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
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * 删除任务。
     * M1：同时清理磁盘上的分镜视频与合并产物文件。
     */
    suspend fun deleteJob(jobId: String) = withContext(Dispatchers.IO) {
        jobOpMutex.withLock {
            appDb.novelVideoDao.deleteJob(jobId)
            runCatching {
                java.io.File(appCtx.filesDir, "novel_video/$jobId").deleteRecursively()
            }
        }
    }

    /**
     * 重试任务。
     *
     * R6 修复：用 Mutex 串行化对同一 job 的操作，避免 retry 与 cancel 并发覆写；
     *        状态推进改用条件更新，避免覆写并发的 CANCELLED。
     */
    suspend fun retryJob(jobId: String) = withContext(Dispatchers.IO) {
        jobOpMutex.withLock {
            val job = appDb.novelVideoDao.getJob(jobId) ?: return@withLock
            if (job.status == NovelVideoJobStatus.FAILED ||
                job.status == NovelVideoJobStatus.PARTIAL_FAILED ||
                job.status == NovelVideoJobStatus.CANCELLED
            ) {
                // M4+M5：智能重置非 VIDEO_COMPLETED 段，保留已生成的 imageUrl
                appDb.novelVideoDao.getSegmentsByJob(jobId)
                    .filter { it.status != io.legado.app.data.entities.NovelVideoSegmentStatus.VIDEO_COMPLETED }
                    .forEach { seg ->
                        val newStatus = if (seg.imageUrl != null) {
                            io.legado.app.data.entities.NovelVideoSegmentStatus.IMAGE_COMPLETED
                        } else {
                            io.legado.app.data.entities.NovelVideoSegmentStatus.PENDING
                        }
                        appDb.novelVideoDao.updateSegmentStatus(seg.id, newStatus, null)
                    }
                // N1 修复：用 updateJobStatusForRetry 从终态转换回 GENERATING
                appDb.novelVideoDao.updateJobStatusForRetry(
                    jobId, NovelVideoJobStatus.GENERATING, null, System.currentTimeMillis()
                )
            }
        }
        NovelVideoService.start(getApplication())
    }

    /** 取消任务。 */
    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        jobOpMutex.withLock {
            val job = appDb.novelVideoDao.getJob(jobId) ?: return@withLock
            if (job.id == NovelVideoService.currentJobId) {
                NovelVideoService.cancelCurrentJob()
            } else if (job.status in NovelVideoJobStatus.RUNNING_STATES) {
                // 用条件更新：若 getJob 与写入之间状态被并发改变为终态，不覆写
                appDb.novelVideoDao.updateJobFinalStatusWithErrorIfNotFinished(
                    jobId, NovelVideoJobStatus.CANCELLED, "用户手动取消", System.currentTimeMillis()
                )
            }
        }
    }

    companion object {
        /** R6：对同一 job 的 retry/cancel/delete 操作串行化，避免并发覆写状态。 */
        private val jobOpMutex = Mutex()
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
