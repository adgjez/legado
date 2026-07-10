package io.legado.app.ui.novelvideo

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.NovelVideoCompilation
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.help.ai.BookNovelVideoSummary
import io.legado.app.help.ai.NovelVideoParams
import io.legado.app.help.ai.computeCoverage
import io.legado.app.service.NovelVideoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务中心 VM：通过 Room Flow 监听任务列表；服务状态由 Activity 在 EventBus 回调中调
 * [refreshServiceState] 同步。
 */
class NovelVideoTaskCenterViewModel(app: Application) : AndroidViewModel(app) {

    private val _runningJobs = MutableStateFlow<List<NovelVideoJob>>(emptyList())
    val runningJobs: StateFlow<List<NovelVideoJob>> = _runningJobs.asStateFlow()

    private val _completedJobs = MutableStateFlow<List<NovelVideoJob>>(emptyList())
    val completedJobs: StateFlow<List<NovelVideoJob>> = _completedJobs.asStateFlow()

    private val _failedJobs = MutableStateFlow<List<NovelVideoJob>>(emptyList())
    val failedJobs: StateFlow<List<NovelVideoJob>> = _failedJobs.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _allBooks = MutableStateFlow<List<Book>>(emptyList())
    val allBooks: StateFlow<List<Book>> = _allBooks.asStateFlow()

    /** 整部视频（跨章节拼接产物）列表。子项目 E。 */
    private val _compilations = MutableStateFlow<List<NovelVideoCompilation>>(emptyList())
    val compilations: StateFlow<List<NovelVideoCompilation>> = _compilations.asStateFlow()

    /** 书架：所有有 novel-video 任务/整部视频的书聚合。子项目 C。 */
    private val _shelfBooks = MutableStateFlow<List<BookNovelVideoSummary>>(emptyList())
    val shelfBooks: StateFlow<List<BookNovelVideoSummary>> = _shelfBooks.asStateFlow()

    /** 书架是否已完成首次加载（区分「加载中」与「加载完确实空」）。 */
    private val _shelfLoaded = MutableStateFlow(false)
    val shelfLoaded: StateFlow<Boolean> = _shelfLoaded.asStateFlow()

    init {
        viewModelScope.launch { appDb.novelVideoDao.getRunningJobsFlow().collectLatest { _runningJobs.value = it } }
        viewModelScope.launch { appDb.novelVideoDao.getCompletedJobsFlow().collectLatest { _completedJobs.value = it } }
        viewModelScope.launch { appDb.novelVideoDao.getFailedJobsFlow().collectLatest { _failedJobs.value = it } }
        viewModelScope.launch { appDb.bookDao.flowAll().collectLatest { _allBooks.value = it } }
        viewModelScope.launch { appDb.novelVideoDao.getCompilationsFlow().collectLatest { _compilations.value = it } }
        viewModelScope.launch {
            appDb.novelVideoDao.getBookUrlsWithNovelVideoFlow().collectLatest { urls ->
                _shelfBooks.value = urls.map { buildShelfSummary(it) }
                _shelfLoaded.value = true
            }
        }
        _serviceRunning.value = NovelVideoService.isRun
    }

    /**
     * 构建单本书的书架聚合 summary（spec §7.1）。
     * 从 bookDao/bookChapterDao/novelVideoDao 取元数据 + jobs，调 [computeCoverage]。
     */
    private suspend fun buildShelfSummary(bookUrl: String): BookNovelVideoSummary {
        val book: Book? = withContext(Dispatchers.IO) {
            runCatching { appDb.bookDao.getBook(bookUrl) }.getOrNull()
        }
        val jobs = withContext(Dispatchers.IO) {
            runCatching { appDb.novelVideoDao.getJobsByBook(bookUrl) }.getOrDefault(emptyList())
        }
        val total = withContext(Dispatchers.IO) {
            runCatching { appDb.bookChapterDao.getChapterCount(bookUrl) }.getOrDefault(0)
        }
        val compCount = runCatching {
            appDb.novelVideoDao.getCompilationsByBookFlow(bookUrl).first().size
        }.getOrDefault(0)
        val coverPath = book?.let { runCatching { it.getDisplayCover() }.getOrNull() }
        return computeCoverage(
            bookUrl = bookUrl,
            bookName = book?.name ?: bookUrl,
            coverPath = coverPath,
            totalChapters = total,
            jobs = jobs,
            compilationCount = compCount
        )
    }

    /** 由 Activity 的 EventBus observer 调用以同步服务运行状态。 */
    fun refreshServiceState() {
        _serviceRunning.value = NovelVideoService.isRun
    }

    fun startService() {
        NovelVideoService.start(getApplication())
        _serviceRunning.value = true
    }

    fun stopService() {
        NovelVideoService.stop(getApplication())
        _serviceRunning.value = false
    }

    fun cancelCurrentJob() {
        NovelVideoService.cancelCurrentJob()
    }

    /** 加载指定书的章节列表（供 NewTask 配置 sheet 用）。 */
    suspend fun loadChapters(bookUrl: String): List<BookChapter> = NovelVideoJobOps.loadChapters(bookUrl)

    /**
     * 创建新任务：组装 [NovelVideoJob] 落库 + 拉起服务。委托 [NovelVideoJobOps.createJob]。
     * @return 新建的 jobId
     */
    suspend fun createJob(
        book: Book,
        chapterStartIndex: Int,
        chapterEndIndex: Int,
        chapters: List<BookChapter>,
        params: NovelVideoParams
    ): String = NovelVideoJobOps.createJob(book, chapterStartIndex, chapterEndIndex, chapters, params)

    /**
     * 删除任务：清 DB（FK CASCADE）+ 清磁盘文件。委托 [NovelVideoJobOps.deleteJob]。
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

    /**
     * 拼接多个已完成 job 为整部视频（子项目 E）。委托 [NovelVideoJobOps.compileJobs]。
     */
    suspend fun compileJobs(jobIds: List<String>, title: String? = null): Result<NovelVideoCompilation> =
        NovelVideoJobOps.compileJobs(jobIds, title)

    /**
     * 删除整部视频（清文件 + DB 行）。委托 [NovelVideoJobOps.deleteCompilation]。
     */
    suspend fun deleteCompilation(id: String) = NovelVideoJobOps.deleteCompilation(id)

    /** 用系统播放器打开整部视频。委托 [NovelVideoJobOps.openCompilation]。 */
    fun openCompilation(context: Context, c: NovelVideoCompilation?): Boolean =
        NovelVideoJobOps.openCompilation(context, c)

    /** 分享整部视频。委托 [NovelVideoJobOps.shareCompilation]。 */
    fun shareCompilation(context: Context, c: NovelVideoCompilation?): Boolean =
        NovelVideoJobOps.shareCompilation(context, c)

    companion object {
        // P6：jobOpMutex 已上移至 NovelVideoJobOps 共享单例（修复双 ViewModel 并发覆写）。
    }
}
