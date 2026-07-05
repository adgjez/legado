package io.legado.app.ui.novelvideo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityNovelVideoReviewBinding
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 剧本审阅宿主 Activity。
 *
 * 接收 [EXTRA_JOB_ID]，由 [NovelVideoScreenplayReviewViewModel.bind] 订阅 DB。
 * UI 全部由 Compose 实现（[NovelVideoScreenplayReviewScreen]）。
 */
class NovelVideoScreenplayReviewActivity :
    VMBaseActivity<ActivityNovelVideoReviewBinding, NovelVideoScreenplayReviewViewModel>() {

    override val binding by viewBinding(ActivityNovelVideoReviewBinding::inflate)
    override val viewModel: NovelVideoScreenplayReviewViewModel by viewModels()

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
                NovelVideoScreenplayReviewScreen(
                    viewModel = viewModel,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "jobId"

        fun newIntent(context: Context, jobId: String): Intent =
            Intent(context, NovelVideoScreenplayReviewActivity::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
            }
    }
}
