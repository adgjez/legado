package io.legado.app.ui.novelvideo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityNovelVideoBookDetailBinding
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 书详情页（子项目 C）：聚合展示一本书的章节覆盖 + 任务 + 整部视频。
 *
 * 跳转协议：
 * - [EXTRA_BOOK_URL]（必填）+ [EXTRA_BOOK_NAME]（必填，TopAppBar 标题用）。
 *
 * 新建任务：FAB 启动 [NovelVideoTaskCenterActivity] 并预填 bookUrl（复用其现有的预填机制，
 * 避免重构 NovelVideoJobConfigSheet 硬绑 TaskCenterVM 的依赖）。
 */
class NovelVideoBookDetailActivity :
    VMBaseActivity<ActivityNovelVideoBookDetailBinding, NovelVideoBookDetailViewModel>() {

    override val binding by viewBinding(ActivityNovelVideoBookDetailBinding::inflate)
    override val viewModel: NovelVideoBookDetailViewModel by viewModels()

    private val bookUrl: String by lazy { intent.getStringExtra(EXTRA_BOOK_URL).orEmpty() }
    private val bookName: String by lazy { intent.getStringExtra(EXTRA_BOOK_NAME).orEmpty() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.bind(bookUrl)
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            LegadoComposeTheme {
                NovelVideoBookDetailScreen(
                    viewModel = viewModel,
                    bookName = bookName.ifBlank { "书籍详情" },
                    onBack = { finish() },
                    onOpenJobDetail = { job ->
                        startActivity(NovelVideoJobDetailActivity.newIntent(this, job.id))
                    },
                    onNewTask = {
                        // 预填 bookUrl 启动任务中心新建任务（sheet 硬绑 TaskCenterVM，这里复用其预填机制）
                        val intent = Intent(this, NovelVideoTaskCenterActivity::class.java).apply {
                            putExtra(NovelVideoTaskCenterActivity.EXTRA_BOOK_URL, bookUrl)
                            putExtra(NovelVideoTaskCenterActivity.EXTRA_BOOK_NAME, bookName)
                        }
                        startActivity(intent)
                    },
                    onOpenCompilation = { c ->
                        viewModel.openCompilation(this, c)
                    },
                    onShareCompilation = { c ->
                        viewModel.shareCompilation(this, c)
                    },
                    onDeleteCompilation = { c ->
                        lifecycleScope.launch {
                            viewModel.deleteCompilation(c.id)
                        }
                    }
                )
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<String>(
            EventBus.NOVEL_VIDEO_PROGRESS,
            EventBus.NOVEL_VIDEO_COMPLETED,
            EventBus.NOVEL_VIDEO_FAILED,
            EventBus.NOVEL_VIDEO_REVIEW_READY
        ) {
            viewModel.refreshServiceState()
        }
    }

    companion object {
        const val EXTRA_BOOK_URL = "bookUrl"
        const val EXTRA_BOOK_NAME = "bookName"

        fun newIntent(context: Context, bookUrl: String, bookName: String): Intent =
            Intent(context, NovelVideoBookDetailActivity::class.java).apply {
                putExtra(EXTRA_BOOK_URL, bookUrl)
                putExtra(EXTRA_BOOK_NAME, bookName)
            }
    }
}
