package io.legado.app.ui.video

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.google.android.material.materialswitch.MaterialSwitch
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogVideoAiSettingsBinding
import io.legado.app.help.ai.video.VideoAiSettings
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx

/**
 * P4 AI 字幕设置对话框。
 *
 * 只有一个字幕开关 + 语言选择。
 */
class VideoAiSettingsDialog : BaseDialogFragment(R.layout.dialog_video_ai_settings) {

    private val binding by viewBinding(DialogVideoAiSettingsBinding::bind)

    var onSettingsChanged: ((VideoAiSettings) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val enabled = appCtx.getPrefBoolean(PreferKey.videoAiSubtitleEnabled, false)
        val language = appCtx.getPrefString(PreferKey.videoAiSubtitleLanguage) ?: "zh-CN"

        binding.switchSubtitle.isChecked = enabled
        binding.tvLanguageValue.text = language

        binding.btnLanguageZh.setOnClickListener {
            appCtx.putPrefString(PreferKey.videoAiSubtitleLanguage, "zh-CN")
            binding.tvLanguageValue.text = "zh-CN"
            notifyChanged()
        }
        binding.btnLanguageEn.setOnClickListener {
            appCtx.putPrefString(PreferKey.videoAiSubtitleLanguage, "en")
            binding.tvLanguageValue.text = "en"
            notifyChanged()
        }
        binding.btnLanguageAuto.setOnClickListener {
            appCtx.putPrefString(PreferKey.videoAiSubtitleLanguage, "auto")
            binding.tvLanguageValue.text = "auto"
            notifyChanged()
        }

        binding.switchSubtitle.setOnCheckedChangeListener { _, isChecked ->
            appCtx.putPrefBoolean(PreferKey.videoAiSubtitleEnabled, isChecked)
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        val settings = VideoAiSettings(
            subtitleEnabled = appCtx.getPrefBoolean(PreferKey.videoAiSubtitleEnabled, false),
            subtitleLanguage = appCtx.getPrefString(PreferKey.videoAiSubtitleLanguage) ?: "zh-CN"
        )
        onSettingsChanged?.invoke(settings)
    }
}
