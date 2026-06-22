package io.legado.app.ui.video

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogVideoAiSettingsBinding
import io.legado.app.help.ai.video.VideoAiSettings
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx

/**
 * P4 AI 视频增强设置对话框。
 *
 * 字幕开关 + 语言选择 + 章节标记开关。
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
        val subtitleEnabled = appCtx.getPrefBoolean(PreferKey.videoAiSubtitleEnabled, false)
        val language = appCtx.getPrefString(PreferKey.videoAiSubtitleLanguage) ?: "zh-CN"
        val chapterEnabled = appCtx.getPrefBoolean(PreferKey.videoAiChapterMarkerEnabled, true)

        binding.switchSubtitle.isChecked = subtitleEnabled
        binding.tvLanguageValue.text = language
        binding.switchChapterMarker.isChecked = chapterEnabled

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

        binding.switchChapterMarker.setOnCheckedChangeListener { _, isChecked ->
            appCtx.putPrefBoolean(PreferKey.videoAiChapterMarkerEnabled, isChecked)
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        val settings = VideoAiSettings(
            subtitleEnabled = appCtx.getPrefBoolean(PreferKey.videoAiSubtitleEnabled, false),
            subtitleLanguage = appCtx.getPrefString(PreferKey.videoAiSubtitleLanguage) ?: "zh-CN",
            chapterMarkerEnabled = appCtx.getPrefBoolean(PreferKey.videoAiChapterMarkerEnabled, true)
        )
        onSettingsChanged?.invoke(settings)
    }
}
