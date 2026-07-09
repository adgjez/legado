package io.legado.app.ui.novelvideo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.databinding.ActivityNovelVideoTaskCenterBinding
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 小说→视频 任务中心宿主 Activity。
 *
 * UI 全部由 Compose 实现（仅一个 ComposeView），具体结构见 [NovelVideoTaskCenterScreen]。
 *
 * 跳转协议：
 * - 入口可以传 [EXTRA_BOOK_URL] + [EXTRA_BOOK_NAME] 预填新建任务表单（来自 BookInfoActivity 入口）。
 * - 不带 extra 启动 = 纯任务中心（书架入口）。
 */
class NovelVideoTaskCenterActivity :
    VMBaseActivity<ActivityNovelVideoTaskCenterBinding, NovelVideoTaskCenterViewModel>() {

    override val binding by viewBinding(ActivityNovelVideoTaskCenterBinding::inflate)
    override val viewModel: NovelVideoTaskCenterViewModel by viewModels()

    /** 预填书籍信息；可为空。 */
    private val presetBookUrl: String? by lazy { intent.getStringExtra(EXTRA_BOOK_URL) }
    private val presetBookName: String? by lazy { intent.getStringExtra(EXTRA_BOOK_NAME) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            LegadoComposeTheme {
                NovelVideoTaskCenterScreen(
                    viewModel = viewModel,
                    presetBookUrl = presetBookUrl,
                    presetBookName = presetBookName,
                    onBack = { finish() },
                    onOpenJobDetail = { job ->
                        startActivity(NovelVideoJobDetailActivity.newIntent(this, job.id))
                    },
                    onOpenReview = { job ->
                        startActivity(NovelVideoScreenplayReviewActivity.newIntent(this, job.id))
                    },
                    onOpenBookDetail = { bookUrl, bookName ->
                        startActivity(NovelVideoBookDetailActivity.newIntent(this, bookUrl, bookName))
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

        /** 从其他入口启动任务中心，可选预填书籍。 */
        fun newIntent(
            context: Context,
            book: Book? = null
        ): Intent = Intent(context, NovelVideoTaskCenterActivity::class.java).apply {
            book?.let {
                putExtra(EXTRA_BOOK_URL, it.bookUrl)
                putExtra(EXTRA_BOOK_NAME, it.name)
            }
        }

        /** 从 Activity 启动的便利方法。 */
        fun launch(activity: Activity, book: Book? = null) {
            activity.startActivity(newIntent(activity, book))
        }
    }
}
