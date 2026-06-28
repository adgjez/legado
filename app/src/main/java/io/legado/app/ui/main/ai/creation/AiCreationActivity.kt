package io.legado.app.ui.main.ai.creation

import android.os.Bundle
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAiCreationBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiCreationActivity : BaseActivity<ActivityAiCreationBinding>(
    fullScreen = false,
    imageBg = false
) {

    override val binding by viewBinding(ActivityAiCreationBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            AiCreationRoute(onBack = { finish() })
        }
    }
}