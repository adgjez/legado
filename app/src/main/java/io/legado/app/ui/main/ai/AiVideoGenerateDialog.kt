package io.legado.app.ui.main.ai

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAiVideoGenerateBinding
import io.legado.app.help.ai.AiVideoGalleryManager
import io.legado.app.help.ai.AiVideoService
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * AI 视频生成对话框
 */
class AiVideoGenerateDialog : BaseDialogFragment() {

    override val binding by viewBinding(DialogAiVideoGenerateBinding::inflate)

    private val book by lazy {
        (parentFragment as? io.legado.app.ui.book.info.BookInfoActivity)?.viewModel?.book
            ?: (activity as? io.legado.app.ui.book.info.BookInfoActivity)?.viewModel?.book
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        super.onFragmentCreated(view, savedInstanceState)
        binding.tvProviderName.text = AppConfig.aiCurrentVideoProvider?.displayName()
            ?: getString(R.string.ai_video_no_provider)
        binding.btnSubmit.setOnClickListener { submit() }
    }

    private fun submit() {
        val prompt = binding.etPrompt.text?.toString().orEmpty().trim()
        if (prompt.isBlank()) {
            toastOnUi(R.string.ai_video_prompt_hint)
            return
        }
        val negative = binding.etNegativePrompt.text?.toString().orEmpty().trim()
        val duration = binding.etDuration.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val aspect = when (binding.chipAspect.checkedChipId) {
            R.id.chip_16_9 -> "16:9"
            R.id.chip_9_16 -> "9:16"
            R.id.chip_1_1 -> "1:1"
            R.id.chip_4_3 -> "4:3"
            else -> "16:9"
        }
        val firstFrame = binding.etFirstFrame.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val metadata = AiVideoGalleryManager.VideoMetadata(
            bookName = book?.name.orEmpty(),
            bookAuthor = book?.author.orEmpty(),
            sourceType = "user_prompt"
        )
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                AiVideoService.submitAndStore(
                    prompt = prompt,
                    negativePrompt = negative,
                    firstFrame = firstFrame,
                    durationSec = duration,
                    aspectRatio = aspect,
                    metadata = metadata
                )
                toastOnUi(R.string.ai_video_submit)
                dismissAllowingStateLoss()
            } catch (e: Throwable) {
                alert(getString(R.string.ai_video_submit_failed, e.message ?: e.javaClass.simpleName)) {
                    okButton()
                }
            }
        }
    }
}
