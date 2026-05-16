package io.legado.app.ui.video.config

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogVideoSettingsBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.VideoPlay
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.viewbindingdelegate.viewBinding

class SettingsDialog(private val context: Context, private val callBack: CallBack? = null) :
    BaseDialogFragment(R.layout.dialog_video_settings) {
    private val binding by viewBinding(DialogVideoSettingsBinding::bind)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }

    @SuppressLint("SetTextI18n")
    private fun initData() {
        binding.run {
            tvPressSpeed.text = (VideoPlay.longPressSpeed / 10.0f).toPressSpeedStr()
            cbAutoPlay.isChecked = VideoPlay.autoPlay
            cbStartFull.isChecked = VideoPlay.startFull
            cbFullBottomProgress.isChecked = VideoPlay.fullBottomProgressBar
            tvDanmakuBaseUrl.text = "弹幕接口: ${VideoPlay.danmakuBaseUrl}"
            tvDanmakuAppId.text = "弹幕 AppId: ${VideoPlay.danmakuAppId.ifBlank { "未设置" }}"
            tvDanmakuAppSecret.text = "弹幕 AppSecret: ${if (VideoPlay.danmakuAppSecret.isBlank()) "未设置" else "已设置"}"
            tvDanmakuCompatibleUrl.text = "兼容弹幕源: ${VideoPlay.danmakuCompatibleUrl.ifBlank { "未设置" }}"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.run {
            cbAutoPlay.setOnCheckedChangeListener { _, isChecked ->
                VideoPlay.autoPlay = isChecked
                ctStartFull.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
            cbStartFull.setOnCheckedChangeListener { _, isChecked ->
                VideoPlay.startFull = isChecked
            }
            cbFullBottomProgress.setOnCheckedChangeListener { _, isChecked ->
                VideoPlay.fullBottomProgressBar = isChecked
            }
            tvPressSpeed.setOnClickListener { _ ->
                NumberPickerDialog(requireContext(), true)
                    .setTitle(getString(R.string.press_speed))
                    .setMaxValue(60)
                    .setMinValue(5)
                    .setValue(VideoPlay.longPressSpeed)
                    .setCustomButton((R.string.btn_default_s)) {
                        VideoPlay.longPressSpeed = 30
                        tvPressSpeed.text = 3.0f.toPressSpeedStr()
                    }
                    .show {
                        VideoPlay.longPressSpeed = it
                        tvPressSpeed.text = (it / 10.0f).toPressSpeedStr()
                    }
            }
            tvDanmakuBaseUrl.setOnClickListener {
                showTextConfig("弹幕接口", VideoPlay.danmakuBaseUrl) {
                    VideoPlay.danmakuBaseUrl = it
                    initData()
                }
            }
            tvDanmakuAppId.setOnClickListener {
                showTextConfig("弹幕 AppId", VideoPlay.danmakuAppId) {
                    VideoPlay.danmakuAppId = it
                    initData()
                }
            }
            tvDanmakuAppSecret.setOnClickListener {
                showTextConfig("弹幕 AppSecret", VideoPlay.danmakuAppSecret) {
                    VideoPlay.danmakuAppSecret = it
                    initData()
                }
            }
            tvDanmakuCompatibleUrl.setOnClickListener {
                showTextConfig("兼容弹幕源", VideoPlay.danmakuCompatibleUrl) {
                    VideoPlay.danmakuCompatibleUrl = it
                    initData()
                }
            }
        }
    }

    private fun showTextConfig(title: String, value: String, onSave: (String) -> Unit) {
        val editBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            editView.minLines = 1
            editView.setText(value)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(title = title) {
            customView { editBinding.root }
            okButton {
                onSave(editBinding.editView.text?.toString().orEmpty())
            }
            cancelButton()
        }
    }

    private fun Float.toPressSpeedStr(): String {
        return context.getString(R.string.press_speed_summary, this)
    }
    interface CallBack {
//        fun upUi()
    }

}
