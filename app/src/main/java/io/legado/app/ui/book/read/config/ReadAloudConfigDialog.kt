package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.SeekBarPreference
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import org.json.JSONObject

private const val KEY_AI_READ_ALOUD_BGM_MANAGE = "aiReadAloudBgmManage"

enum class ReadAloudConfigGroup(
    val title: String,
    val preferenceKeys: Set<String>
) {
    Base(
        "\u57fa\u7840",
        setOf(
            PreferKey.ignoreAudioFocus,
            PreferKey.pauseReadAloudWhilePhoneCalls,
            PreferKey.readAloudWakeLock,
            "mediaButtonPerNext"
        )
    ),
    Reading(
        "\u6717\u8bfb",
        setOf(
            PreferKey.readAloudByPage,
            PreferKey.streamReadAloudAudio,
            PreferKey.ttsFollowSys,
            PreferKey.ttsSpeechRate
        )
    ),
    AiRole(
        "\u591a\u89d2\u8272",
        setOf(
            PreferKey.aiReadAloudRoleEnabled,
            PreferKey.aiReadAloudRoleModelId,
            PreferKey.aiReadAloudAutoCreateCharacters,
            PreferKey.aiReadAloudRoleMode,
            PreferKey.aiReadAloudRolePreprocess,
            PreferKey.aiReadAloudRoleThreadCount,
            PreferKey.aiReadAloudRoleContextParagraphs,
            PreferKey.aiReadAloudRoleMergeGapParagraphs,
            PreferKey.aiReadAloudRolePrompt,
            PreferKey.aiReadAloudBgmEnabled,
            KEY_AI_READ_ALOUD_BGM_MANAGE
        )
    ),
    Engine(
        "\u5f15\u64ce",
        setOf(
            PreferKey.ttsEngine,
            "sysTtsConfig"
        )
    );

    companion object {
        val allPreferenceKeys: Set<String> = values().flatMap { it.preferenceKeys }.toSet()
    }
}

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"
    private val groupTabs = arrayListOf<Pair<ReadAloudConfigGroup, TextView>>()
    private var selectedGroup = ReadAloudConfigGroup.Base

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            val maxHeight = minOf(
                (resources.displayMetrics.heightPixels * 0.72f).toInt(),
                620.dpToPx()
            ).coerceAtLeast(360.dpToPx())
            setLayout(0.9f, maxHeight)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        groupTabs.clear()
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = requireContext().dialogSurfaceBackground
            clipToOutline = true
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 8.dpToPx())
        }
        val tabBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            setBackgroundResource(R.drawable.bg_bookshelf_tag_track)
        }
        ReadAloudConfigGroup.values().forEach { group ->
            val tab = TextView(requireContext()).apply {
                text = group.title
                gravity = Gravity.CENTER
                textSize = 14f
                isSingleLine = true
                background = UiCorner.actionSelector(
                    Color.TRANSPARENT,
                    ContextCompat.getColor(requireContext(), R.color.background_card),
                    UiCorner.actionRadius(requireContext())
                )
                setOnClickListener { selectGroup(group) }
            }
            groupTabs.add(group to tab)
            tabBar.addView(
                tab,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
        root.addView(
            tabBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                42.dpToPx()
            ).apply {
                bottomMargin = 8.dpToPx()
            }
        )
        root.addView(
            FrameLayout(requireContext()).apply { id = R.id.tag1 },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        container?.addView(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadAloudPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(R.id.tag1, preferenceFragment, readAloudPreferTag)
            .commit()
        selectGroup(selectedGroup)
    }

    private fun selectGroup(group: ReadAloudConfigGroup) {
        selectedGroup = group
        groupTabs.forEach { (tabGroup, tab) ->
            val selected = tabGroup == group
            tab.isSelected = selected
            tab.setTextColor(
                if (selected) requireContext().primaryColor
                else ContextCompat.getColor(requireContext(), R.color.primaryText)
            )
        }
        (childFragmentManager.findFragmentByTag(readAloudPreferTag) as? ReadAloudPreferenceFragment)
            ?.selectGroup(group)
    }

    class ReadAloudPreferenceFragment : PreferenceFragment(),
        SpeakEngineDialog.CallBack,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private var selectedGroup = ReadAloudConfigGroup.Base

        private val speakEngineSummary: String
            get() {
                val route = SpeechRoute.fromTtsEngineValue(ReadAloud.ttsEngine)
                val engineName = when (route.engineType) {
                    SpeechRoute.ENGINE_HTTP -> route.engineValue.toLongOrNull()
                        ?.let { appDb.httpTTSDao.getName(it) }
                        ?: "HTTP TTS"
                    SpeechRoute.ENGINE_SYSTEM -> route.speakerName.ifBlank { getString(R.string.system_tts) }
                    else -> getString(R.string.system_tts)
                }
                return buildList {
                    add(engineName)
                    route.speakerName
                        .takeIf { it.isNotBlank() && it != engineName }
                        ?.let(::add)
                    route.emotionName.takeIf { it.isNotBlank() }?.let(::add)
                }.joinToString(" · ")
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_aloud)
            upSpeakEngineSummary()
            findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                it.isEnabled = AppConfig.ignoreAudioFocus
            }
            updateSpeechRatePreferences()
            updateAiRolePreferences()
            selectGroup(selectedGroup)
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
                PreferKey.aiReadAloudRoleModelId -> showAiRoleModelDialog()
                PreferKey.aiReadAloudRoleThreadCount -> showAiRoleNumberDialog(
                    title = "并发请求数",
                    value = AppConfig.aiReadAloudRoleThreadCount,
                    min = 1,
                    max = 4
                ) {
                    AppConfig.aiReadAloudRoleThreadCount = it
                    updateAiRolePreferences()
                }
                PreferKey.aiReadAloudRoleContextParagraphs -> showAiRoleNumberDialog(
                    title = "上下文段落数",
                    value = AppConfig.aiReadAloudRoleContextParagraphs,
                    min = 0,
                    max = 20
                ) {
                    AppConfig.aiReadAloudRoleContextParagraphs = it
                    updateAiRolePreferences()
                }
                PreferKey.aiReadAloudRoleMergeGapParagraphs -> showAiRoleNumberDialog(
                    title = "相隔段落合并",
                    value = AppConfig.aiReadAloudRoleMergeGapParagraphs,
                    min = 0,
                    max = 10
                ) {
                    AppConfig.aiReadAloudRoleMergeGapParagraphs = it
                    updateAiRolePreferences()
                }
                PreferKey.aiReadAloudRolePreprocess -> showMultiRolePreprocessRulesDialog()
                PreferKey.aiReadAloudRolePrompt -> showMultiRolePromptDialog()
                KEY_AI_READ_ALOUD_BGM_MANAGE -> startActivity(
                    android.content.Intent(requireContext(), ReadAloudBgmManageActivity::class.java)
                )
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

                PreferKey.ttsFollowSys,
                PreferKey.ttsSpeechRate -> {
                    updateSpeechRatePreferences()
                    applySpeechRate()
                    selectGroup(selectedGroup)
                }

                PreferKey.aiReadAloudRoleEnabled,
                PreferKey.aiReadAloudRoleModelId,
                PreferKey.aiReadAloudAutoCreateCharacters,
                PreferKey.aiReadAloudRoleMode,
                PreferKey.aiReadAloudRolePreprocess,
                PreferKey.aiReadAloudRoleThreadCount,
                PreferKey.aiReadAloudRoleContextParagraphs,
                PreferKey.aiReadAloudRoleMergeGapParagraphs,
                PreferKey.aiReadAloudRolePrompt -> {
                    updateAiRolePreferences()
                    selectGroup(selectedGroup)
                }
                PreferKey.aiReadAloudBgmEnabled -> {
                    updateAiRolePreferences()
                    selectGroup(selectedGroup)
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

        fun selectGroup(group: ReadAloudConfigGroup) {
            selectedGroup = group
            findPreference<Preference>("readAloudConfigCategory")?.title = group.title
            val fullMode = AppConfig.aiReadAloudRoleMode == AppConfig.AI_READ_ALOUD_ROLE_MODE_FULL
            ReadAloudConfigGroup.allPreferenceKeys.forEach { key ->
                val preference = findPreference<Preference>(key) ?: return@forEach
                val visibleInGroup = key in group.preferenceKeys
                val visibleForMode = when (key) {
                    PreferKey.aiReadAloudRoleThreadCount -> !fullMode
                    PreferKey.aiReadAloudRoleContextParagraphs -> !fullMode
                    PreferKey.aiReadAloudRoleMergeGapParagraphs -> !fullMode
                    else -> true
                }
                preference.isVisible = visibleInGroup && visibleForMode
            }
        }

        private fun updateSpeechRatePreferences() {
            val followSystem = AppConfig.ttsFlowSys
            findPreference<SeekBarPreference>(PreferKey.ttsSpeechRate)?.let {
                it.value = AppConfig.ttsSpeechRate.coerceIn(0, 45)
                it.isEnabled = !followSystem
                it.summary = formatSpeechRate(AppConfig.ttsSpeechRate)
            }
        }

        private fun applySpeechRate() {
            ReadAloud.upTtsSpeechRate(requireContext())
            if (!BaseReadAloudService.pause) {
                ReadAloud.pause(requireContext())
                ReadAloud.resume(requireContext())
            }
        }

        private fun updateAiRolePreferences() {
            val enabled = AppConfig.aiReadAloudRoleEnabled
            val hasModel = AppConfig.aiReadAloudRoleModelConfig != null
            val fullMode = AppConfig.aiReadAloudRoleMode == AppConfig.AI_READ_ALOUD_ROLE_MODE_FULL
            findPreference<SwitchPreference>(PreferKey.aiReadAloudRoleEnabled)?.let {
                it.isEnabled = hasModel
                it.isChecked = enabled
                it.summary = if (hasModel) "后台分析当前章节的旁白和角色片段，并缓存结果" else "请先选择多角色模型"
            }
            findPreference<Preference>(PreferKey.aiReadAloudRoleModelId)?.let {
                it.summary = modelLabel(AppConfig.aiReadAloudRoleModelConfig)
            }
            findPreference<SwitchPreference>(PreferKey.aiReadAloudAutoCreateCharacters)?.let {
                it.isEnabled = enabled
                it.isChecked = AppConfig.aiReadAloudAutoCreateCharacters
                it.summary = if (AppConfig.aiReadAloudAutoCreateCharacters) {
                    "新角色会自动建卡，未配置发言人时会从可用目录稳定分配"
                } else {
                    "只使用已有角色卡，不自动创建或分配发言人"
                }
            }
            findPreference<Preference>(PreferKey.aiReadAloudRoleMode)?.let {
                it.isEnabled = enabled
                upPreferenceSummary(it, AppConfig.aiReadAloudRoleMode)
            }
            findPreference<Preference>(PreferKey.aiReadAloudRolePreprocess)?.let {
                it.isEnabled = enabled
                val prefix = if (AppConfig.aiReadAloudRoleUsingDefaultPreprocessRules) "内置默认 · " else ""
                it.summary = prefix + "引号、冒号、跨段对话、心理活动等预切分规则"
            }
            findPreference<Preference>(PreferKey.aiReadAloudRoleThreadCount)?.let {
                it.isEnabled = enabled
                it.isVisible = !fullMode
                it.summary = "${AppConfig.aiReadAloudRoleThreadCount} 个并发请求，越高越快但不省 Token"
            }
            findPreference<Preference>(PreferKey.aiReadAloudRoleContextParagraphs)?.let {
                it.isEnabled = enabled
                it.isVisible = !fullMode
                it.summary = "上下各 ${AppConfig.aiReadAloudRoleContextParagraphs} 段"
            }
            findPreference<Preference>(PreferKey.aiReadAloudRoleMergeGapParagraphs)?.let {
                it.isEnabled = enabled
                it.isVisible = !fullMode
                it.summary = "${AppConfig.aiReadAloudRoleMergeGapParagraphs} 段内合并为同一请求"
            }
            findPreference<Preference>(PreferKey.aiReadAloudRolePrompt)?.let {
                it.isEnabled = enabled
                val prefix = if (AppConfig.aiReadAloudRoleUsingDefaultPrompt) "内置默认 · " else ""
                it.summary = prefix + AppConfig.aiReadAloudRolePrompt
                    .lineSequence()
                    .firstOrNull()
                    ?.take(40)
                    ?.ifBlank { null }
                    .orEmpty()
            }
            findPreference<SwitchPreference>(PreferKey.aiReadAloudBgmEnabled)?.let {
                it.isEnabled = enabled
                it.isChecked = AppConfig.aiReadAloudBgmEnabled
                it.summary = if (AppConfig.aiReadAloudBgmEnabled) {
                    "AI 可按段落范围选择配乐，朗读时淡入淡出"
                } else {
                    "关闭后不会请求或播放章节配乐"
                }
            }
            findPreference<Preference>(KEY_AI_READ_ALOUD_BGM_MANAGE)?.let {
                it.isEnabled = enabled
                it.summary = "${appDb.readAloudBgmDao.enabledTracks().size} 首可用音乐"
            }
        }

        private fun showAiRoleModelDialog() {
            val models = AppConfig.aiModelConfigList
            if (models.isEmpty()) {
                toastOnUi(R.string.ai_no_models)
                return
            }
            val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
            requireContext().selector(
                "多角色模型",
                models.map { model ->
                    val label = providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                        ?.let { "${model.modelId} - $it" }
                        ?: model.modelId
                    if (model.id == AppConfig.aiReadAloudRoleModelId) "$label ✓" else label
                }
            ) { _, _, index ->
                AppConfig.aiReadAloudRoleModelId = models[index].id
                updateAiRolePreferences()
                selectGroup(selectedGroup)
            }
        }

        private fun modelLabel(model: io.legado.app.ui.main.ai.AiModelConfig?): String {
            model ?: return "未配置"
            val providerName = AppConfig.aiProviderList.firstOrNull { it.id == model.providerId }
                ?.name
                ?.takeIf { it.isNotBlank() }
            return providerName?.let { "${model.modelId} - $it" } ?: model.modelId
        }

        private fun formatSpeechRate(value: Int): String {
            return ((value.coerceIn(0, 45) + 5) / 10f).toString()
        }

        private fun showAiRoleNumberDialog(
            title: String,
            value: Int,
            min: Int,
            max: Int,
            onValue: (Int) -> Unit
        ) {
            val displayTitle = when (max) {
                8 -> "多角色线程数"
                40 -> "每批段落数"
                else -> title
            }
            NumberPickerDialog(requireContext())
                .setTitle(displayTitle)
                .setMinValue(min)
                .setMaxValue(max)
                .setValue(value)
                .show(onValue)
        }

        private fun showMultiRolePromptDialog() {
            val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "内置默认会自动生效。这里可补充角色判断规则、旁白/台词标注偏好等。"
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
                neutralButton("恢复默认") {
                    AppConfig.aiReadAloudRolePrompt = ""
                    updateAiRolePreferences()
                }
                cancelButton()
            }
        }

        private fun showMultiRolePreprocessRulesDialog() {
            val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "JSON 规则。可调整 quotePairs、sentencePunctuation、thoughtCuePatterns、dialogueMinLength、emphasisMaxLength、colonCueMaxLength。"
                editView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editView.minLines = 10
                editView.setText(AppConfig.aiReadAloudRolePreprocessRules)
                editView.setSelection(editView.text?.length ?: 0)
            }
            alert("预处理规则") {
                customView { binding.root }
                okButton {
                    val value = binding.editView.text?.toString().orEmpty()
                    if (value.length > 8000) {
                        toastOnUi("预处理规则最多 8000 字")
                        return@okButton
                    }
                    if (value.isNotBlank()) {
                        runCatching { JSONObject(value) }.onFailure {
                            toastOnUi("预处理规则不是有效 JSON")
                            return@okButton
                        }
                    }
                    AppConfig.aiReadAloudRolePreprocessRules = value
                    updateAiRolePreferences()
                }
                neutralButton("恢复默认") {
                    AppConfig.aiReadAloudRolePreprocessRules = ""
                    updateAiRolePreferences()
                }
                cancelButton()
            }
        }

        private fun showAiRolePromptDialog() {
            val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "内置默认会自动生效。这里可补充角色判断规则、旁白/台词标注偏好等。"
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
                neutralButton("恢复默认") {
                    AppConfig.aiReadAloudRolePrompt = ""
                    updateAiRolePreferences()
                }
                cancelButton()
            }
        }

    }
}
