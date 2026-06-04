package io.legado.app.ui.config

import android.os.Bundle
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAiWorldBookManageBinding
import io.legado.app.ui.main.ai.compose.AiWorldBookManageRoute
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiWorldBookManageActivity : BaseActivity<ActivityAiWorldBookManageBinding>(
    fullScreen = false,
    imageBg = false
) {

    override val binding by viewBinding(ActivityAiWorldBookManageBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            AiWorldBookManageRoute(
                initialTargetType = intent.getStringExtra(EXTRA_TARGET_TYPE).orEmpty(),
                initialTargetKey = intent.getStringExtra(EXTRA_TARGET_KEY).orEmpty(),
                onBack = ::finish
            )
        }
    }

    companion object {
        const val EXTRA_TARGET_TYPE = "targetType"
        const val EXTRA_TARGET_KEY = "targetKey"
    }
}
