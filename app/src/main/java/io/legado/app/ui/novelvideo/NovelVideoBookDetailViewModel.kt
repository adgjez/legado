package io.legado.app.ui.novelvideo

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.NovelVideoCompilation
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.help.ai.BookNovelVideoSummary
import io.legado.app.help.ai.computeCoverage
import io.legado.app.help.ai.NovelVideoParams
import io.legado.app.data.entities.Book
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
 * 书详情页 VM（子项目 C）。
 *
 * 聚合一本书的：章节覆盖图（[computeCoverage]）+ 任务列表 + 整部视频列表。
 * 监听 Room Flow，任务状态变化时自动刷新覆盖图与概要。
 *
 * 任务操作（创建/删除/重试/取消/编译/删整部视频）委托 [NovelVideoJobOps]，
 * 与 [NovelVideoTaskCenterViewModel] 共享同一份实现。
 */
class NovelVideoBookDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val _summary = MutableStateFlow<BookNovelVideoSummary?>(null)
    val summary: StateFlow<BookNovelVideoSummary?> = _summary.asStateFlow()

    private val _jobs = MutableStateFlow<List<NovelVideoJob>>(emptyList())
    val jobs: StateFlow<List<NovelVideoJob>> = _jobs.asStateFlow()

    private val _compilations = MutableStateFlow<List<NovelVideoCompilation>>(emptyList())
    val compilations: StateFlow<List<NovelVideoCompilation>> = _compilations.asStateFlow()

    /** 章节索引 → 标题（长按色块时惰性加载）。 */
    private val _chapterTitles = MutableStateFlow<Map<Int, String>>(emptyMap())
    val chapterTitles: StateFlow<Map<Int, String>> = _chapterTitles.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private var boundBookUrl: String? = null

    /**
     * 绑定 bookUrl 并开始监听。幂等——同一 bookUrl 不重复订阅。
     */
    fun bind(bookUrl: String) {
        if (boundBookUrl == bookUrl) return
        boundBookUrl = bookUrl
        viewModelScope.launch {
            appDb.novelVideoDao.getJobsByBookFlow(bookUrl).collectLatest { jobsList ->
                _jobs.value = jobsList
                _summary.value = recomputeSummary(bookUrl, jobsList)
            }
        }
        viewModelScope.launch {
            appDb.novelVideoDao.getCompilationsByBookFlow(bookUrl).collectLatest {
                _compilations.value = it
                // 整部视频数变化时也要重算 summary（compilationCount）
                _summary.value = _summary.value?.let { s ->
                    s.copy(compilationCount = it.size)
                } ?: recomputeSummary(bookUrl, _jobs.value)
            }
        }
        _serviceRunning.value = NovelVideoService.isRun
    }

    /** 由 Activity 的 EventBus observer 调用以同步服务运行状态。 */
    fun refreshServiceState() {
        _serviceRunning.value = NovelVideoService.isRun
    }

    fun startService() {
        NovelVideoService.start(getApplication())
        _serviceRunning.value = true
    }

    /**
     * 重算 summary：从 DB 取 book（书名/封面）+ totalChapters + compilationCount。
     */
    private suspend fun recomputeSummary(bookUrl: String, jobs: List<NovelVideoJob>): BookNovelVideoSummary {
        val book: Book? = withContext(Dispatchers.IO) {
            runCatching { appDb.bookDao.getBook(bookUrl) }.getOrNull()
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

    /** 惰性加载单章标题（长按色块时调）。 */
    fun loadChapterTitle(bookUrl: String, chapterIndex: Int) {
        if (_chapterTitles.value.containsKey(chapterIndex)) return
        viewModelScope.launch {
            val chapter: BookChapter? = withContext(Dispatchers.IO) {
                runCatching { appDb.bookChapterDao.getChapter(bookUrl, chapterIndex) }.getOrNull()
            }
            chapter?.title?.let { title ->
                _chapterTitles.value = _chapterTitles.value + (chapterIndex to title)
            }
        }
    }

    /** 加载章节列表（供新建任务 sheet 用）。委托 [NovelVideoJobOps.loadChapters]。 */
    suspend fun loadChapters(bookUrl: String): List<BookChapter> = NovelVideoJobOps.loadChapters(bookUrl)

    /** 创建任务。委托 [NovelVideoJobOps.createJob]。 */
    suspend fun createJob(
        book: Book,
        chapterStartIndex: Int,
        chapterEndIndex: Int,
        chapters: List<BookChapter>,
        params: NovelVideoParams
    ): String = NovelVideoJobOps.createJob(book, chapterStartIndex, chapterEndIndex, chapters, params)

    /** 删除任务。委托 [NovelVideoJobOps.deleteJob]。 */
    suspend fun deleteJob(jobId: String) = NovelVideoJobOps.deleteJob(jobId)

    /** 重试任务。委托 [NovelVideoJobOps.retryJob]。 */
    suspend fun retryJob(jobId: String) = NovelVideoJobOps.retryJob(jobId)

    /** 取消任务。委托 [NovelVideoJobOps.cancelJob]。 */
    suspend fun cancelJob(jobId: String) = NovelVideoJobOps.cancelJob(jobId)

    /** 删除整部视频。委托 [NovelVideoJobOps.deleteCompilation]。 */
    suspend fun deleteCompilation(id: String) = NovelVideoJobOps.deleteCompilation(id)

    /** 打开整部视频。委托 [NovelVideoJobOps.openCompilation]。 */
    fun openCompilation(context: Context, c: NovelVideoCompilation?): Boolean =
        NovelVideoJobOps.openCompilation(context, c)

    /** 分享整部视频。委托 [NovelVideoJobOps.shareCompilation]。 */
    fun shareCompilation(context: Context, c: NovelVideoCompilation?): Boolean =
        NovelVideoJobOps.shareCompilation(context, c)
}
