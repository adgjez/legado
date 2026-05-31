package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = LinearLayout(requireContext())
        view.background = requireContext().dialogSurfaceBackground
        view.clipToOutline = true
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadAloudPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readAloudPreferTag)
            .commit()
    }

    class ReadAloudPreferenceFragment : PreferenceFragment(),
        SpeakEngineDialog.CallBack,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val speakEngineSummary: String
            get() {
                val ttsEngine = ReadAloud.ttsEngine
                    ?: return getString(R.string.system_tts)
                if (StringUtils.isNumeric(ttsEngine)) {
                    return appDb.httpTTSDao.getName(ttsEngine.toLong())
                        ?: getString(R.string.system_tts)
                }
                return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
                    ?: getString(R.string.system_tts)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_aloud)
            upSpeakEngineSummary()
            findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                it.isEnabled = AppConfig.ignoreAudioFocus
            }
            updateAiRolePreferences()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.background = null
            listView.clipToPadding = true
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                PreferKey.ttsEngine -> showDialogFragment(SpeakEngineDialog())
                "sysTtsConfig" -> IntentHelp.openTTSSetting()
                PreferKey.aiReadAloudRoleThreadCount -> showAiRoleNumberDialog(
                    title = "AI分角色线程数",
                    value = AppConfig.aiReadAloudRoleThreadCount,
                    min = 1,
                    max = 8
                ) {
                    AppConfig.aiReadAloudRoleThreadCount = it
                    updateAiRolePreferences()
                }
                PreferKey.aiReadAloudRoleContextParagraphs -> showAiRoleNumberDialog(
                    title = "AI分角色上下文段数",
                    value = AppConfig.aiReadAloudRoleContextParagraphs,
                    min = 0,
                    max = 20
                ) {
                    AppConfig.aiReadAloudRoleContextParagraphs = it
                    updateAiRolePreferences()
                }
                PreferKey.aiReadAloudRolePrompt -> showAiRolePromptDialog()
            }
            return super.onPreferenceTreeClick(preference)
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.ignoreAudioFocus -> {
                    findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                        it.isEnabled = AppConfig.ignoreAudioFocus
                    }
                }

                PreferKey.aiReadAloudRoleEnabled,
                PreferKey.aiReadAloudRoleMode,
                PreferKey.aiReadAloudRoleThreadCount,
                PreferKey.aiReadAloudRoleContextParagraphs,
                PreferKey.aiReadAloudRolePrompt -> {
                    updateAiRolePreferences()
                }
            }
        }

        private fun upPreferenceSummary(preference: Preference?, value: String) {
            when (preference) {
                is ListPreference -> {
                    val index = preference.findIndexOfValue(value)
                    preference.summary = if (index >= 0) preference.entries[index] else null
                }

                else -> {
                    preference?.summary = value
                }
            }
        }

        override fun upSpeakEngineSummary() {
            upPreferenceSummary(
                findPreference(PreferKey.ttsEngine),
                speakEngineSummary
            )
        }

        private fun updateAiRolePreferences() {
            val enabled = AppConfig.aiReadAloudRoleEnabled
            val fullMode = AppConfig.aiReadAloudRoleMode == AppConfig.AI_READ_ALOUD_ROLE_MODE_FULL
            findPreference<Preference>(PreferKey.aiReadAloudRoleMode)?.let {
                it.isEnabled = enabled
                upPreferenceSummary(it, AppConfig.aiReadAloudRoleMode)
            }
            findPreference<Preference>(PreferKey.aiReadAloudRoleThreadCount)?.let {
                it.isEnabled = enabled
                it.isVisible = !fullMode
                it.summary = AppConfig.aiReadAloudRoleThreadCount.toString()
            }
            findPreference<Preference>(PreferKey.aiReadAloudRoleContextParagraphs)?.let {
                it.isEnabled = enabled
                it.isVisible = !fullMode
                it.summary = AppConfig.aiReadAloudRoleContextParagraphs.toString()
            }
            findPreference<Preference>(PreferKey.aiReadAloudRolePrompt)?.let {
                it.isEnabled = enabled
                it.summary = AppConfig.aiReadAloudRolePrompt
                    .lineSequence()
                    .firstOrNull()
                    ?.take(40)
                    ?.ifBlank { null }
                    ?: "未设置"
            }
        }

        private fun showAiRoleNumberDialog(
            title: String,
            value: Int,
            min: Int,
            max: Int,
            onValue: (Int) -> Unit
        ) {
            NumberPickerDialog(requireContext())
                .setTitle(title)
                .setMinValue(min)
                .setMaxValue(max)
                .setValue(value)
                .show(onValue)
        }

        private fun showAiRolePromptDialog() {
            val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "可选。补充角色判断规则、旁白/台词标注偏好等。"
                editView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editView.minLines = 6
                editView.setText(AppConfig.aiReadAloudRolePrompt)
                editView.setSelection(editView.text?.length ?: 0)
            }
            alert("预注入分角色提示词") {
                customView { binding.root }
                okButton {
                    val value = binding.editView.text?.toString().orEmpty()
                    if (value.length > 4000) {
                        toastOnUi("提示词最多 4000 字")
                        return@okButton
                    }
                    AppConfig.aiReadAloudRolePrompt = value
                    updateAiRolePreferences()
                }
                cancelButton()
            }
        }
    }
}
