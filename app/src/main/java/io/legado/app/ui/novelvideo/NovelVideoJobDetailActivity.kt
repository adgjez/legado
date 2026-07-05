package io.legado.app.ui.novelvideo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityNovelVideoJobDetailBinding
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 任务详情宿主 Activity。
 *
 * 接收 [EXTRA_JOB_ID] 后由 [NovelVideoJobDetailViewModel.bind] 订阅 Room Flow。
 * UI 全部由 Compose 实现（[NovelVideoJobDetailScreen]）。
 */
class NovelVideoJobDetailActivity :
    VMBaseActivity<ActivityNovelVideoJobDetailBinding, NovelVideoJobDetailViewModel>() {

    override val binding by viewBinding(ActivityNovelVideoJobDetailBinding::inflate)
    override val viewModel: NovelVideoJobDetailViewModel by viewModels()

    private val jobId: String by lazy { intent.getStringExtra(EXTRA_JOB_ID).orEmpty() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (jobId.isBlank()) {
            finish()
            return
        }
        viewModel.bind(jobId)
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            LegadoComposeTheme {
                NovelVideoJobDetailScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onOpenReview = { job ->
                        startActivity(NovelVideoScreenplayReviewActivity.newIntent(this, job.id))
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
            EventBus.NOVEL_VIDEO_REVIEW_READY,
            EventBus.NOVEL_VIDEO_SEGMENT_UPDATED
        ) {
            viewModel.refreshServiceState()
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "jobId"

        fun newIntent(context: Context, jobId: String): Intent =
            Intent(context, NovelVideoJobDetailActivity::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
            }
    }
}
