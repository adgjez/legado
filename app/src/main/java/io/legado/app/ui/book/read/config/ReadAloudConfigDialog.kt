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
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import io.legado.app.help.readaloud.role.ReadAloudQuotePair
import io.legado.app.help.readaloud.role.ReadAloudRolePreprocessor
import io.legado.app.help.readaloud.role.ReadAloudSoundEffectPreprocessor
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

private const val KEY_AI_READ_ALOUD_BGM_MANAGE = "aiReadAloudBgmManage"
private const val KEY_AI_READ_ALOUD_USAGE_RECORDS = "aiReadAloudUsageRecords"
private const val KEY_READ_ALOUD_SPEAKER_MANAGE = "readAloudSpeakerManage"

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
            KEY_AI_READ_ALOUD_USAGE_RECORDS,
            PreferKey.aiReadAloudBgmEnabled,
            KEY_AI_READ_ALOUD_BGM_MANAGE,
            PreferKey.aiReadAloudBgmVolume,
            PreferKey.aiReadAloudSfxVolume
        )
    ),
    Engine(
        "\u5f15\u64ce",
        setOf(
            PreferKey.ttsEngine,
            KEY_READ_ALOUD_SPEAKER_MANAGE,
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
                KEY_READ_ALOUD_SPEAKER_MANAGE -> showDialogFragment(SpeakerGroupManageDialog())
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
                KEY_AI_READ_ALOUD_USAGE_RECORDS -> startActivity(
                    android.content.Intent(requireContext(), AiReadAloudUsageRecordActivity::class.java)
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
                    postReadAloudConfigChanged(
                        if (key == PreferKey.aiReadAloudRoleEnabled) {
                            EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE
                        } else {
                            EventBus.READ_ALOUD_CONFIG_SCOPE_SPEECH
                        }
                    )
                }
                PreferKey.aiReadAloudBgmEnabled,
                PreferKey.aiReadAloudBgmVolume,
                PreferKey.aiReadAloudSfxVolume -> {
                    updateAiRolePreferences()
                    selectGroup(selectedGroup)
                    postReadAloudConfigChanged(EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO)
                }
            }
        }

        private fun postReadAloudConfigChanged(scope: String) {
            if (!BaseReadAloudService.isRun) return
            postEvent(
                EventBus.READ_ALOUD_CONFIG_CHANGED,
                Bundle().apply {
                    putString(EventBus.READ_ALOUD_CONFIG_SCOPE, scope)
                }
            )
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
            findPreference<Preference>(KEY_AI_READ_ALOUD_USAGE_RECORDS)?.let {
                val count = appDb.aiReadAloudUsageRecordDao.list(limit = 1000).size
                it.summary = if (count > 0) "$count 条记录，可筛选和批量删除" else "暂无消耗记录"
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
                val bgmCount = appDb.readAloudBgmDao.enabledTracksByType(io.legado.app.data.entities.ReadAloudBgmTrack.TYPE_BGM).size
                val sfxCount = appDb.readAloudBgmDao.enabledTracksByType(io.legado.app.data.entities.ReadAloudBgmTrack.TYPE_SFX).size
                it.summary = "$bgmCount 首配乐 · $sfxCount 个音效"
            }
            findPreference<SeekBarPreference>(PreferKey.aiReadAloudBgmVolume)?.let {
                it.isEnabled = enabled && AppConfig.aiReadAloudBgmEnabled
                it.value = AppConfig.aiReadAloudBgmVolume
                it.summary = "当前 ${AppConfig.aiReadAloudBgmVolume}%"
            }
            findPreference<SeekBarPreference>(PreferKey.aiReadAloudSfxVolume)?.let {
                it.isEnabled = enabled && AppConfig.aiReadAloudBgmEnabled
                it.value = AppConfig.aiReadAloudSfxVolume
                it.summary = "当前 ${AppConfig.aiReadAloudSfxVolume}%"
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
            val config = ReadAloudPreprocessRuleConfig.current()
            val items = listOf(
                "引号与句末规则 · ${config.quotePairs.size} 组引号",
                "心理活动提示词 · ${config.thoughtCuePatterns.size} 条",
                "判断阈值 · 台词 ${config.dialogueMinLength} / 强调 ${config.emphasisMaxLength}",
                "音效候选规则 · ${config.soundEffectCuePatterns.size} 个候选词",
                "试运行预处理",
                "恢复默认"
            )
            requireContext().selector("预处理规则", items) { _, _, index ->
                when (index) {
                    0 -> showPreprocessQuoteDialog(config)
                    1 -> showRuleListEditor(
                        title = "心理活动提示词",
                        values = config.thoughtCuePatterns,
                        hint = "每行一个提示词，例如：心道",
                        onSave = { savePreprocessConfig(config.copy(thoughtCuePatterns = it)) }
                    )
                    2 -> showPreprocessThresholdDialog(config)
                    3 -> showSoundEffectRuleDialog(config)
                    4 -> showPreprocessTrialDialog()
                    5 -> {
                        AppConfig.aiReadAloudRolePreprocessRules = ""
                        updateAiRolePreferences()
                        toastOnUi("已恢复内置预处理规则")
                    }
                }
            }
        }

        private fun showPreprocessQuoteDialog(config: ReadAloudPreprocessRuleConfig) {
            val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "每行一组引号，格式：开引号 空格 闭引号，例如：\n“ ”\n「 」"
                editView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editView.minLines = 6
                editView.setText(config.quotePairs.joinToString("\n") { "${it.open} ${it.close}" })
                editView.setSelection(editView.text?.length ?: 0)
            }
            alert("引号与句末规则") {
                customView { binding.root }
                okButton {
                    val pairs = parseQuotePairs(binding.editView.text?.toString().orEmpty())
                    if (pairs.isEmpty()) {
                        toastOnUi("至少保留一组引号")
                        return@okButton
                    }
                    savePreprocessConfig(config.copy(quotePairs = pairs))
                }
                neutralButton("句末符号") {
                    showSentencePunctuationDialog(config)
                }
                cancelButton()
            }
        }

        private fun showSentencePunctuationDialog(config: ReadAloudPreprocessRuleConfig) {
            val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "直接填写句末符号，例如：。！？…!?"
                editView.inputType = InputType.TYPE_CLASS_TEXT
                editView.setText(config.sentencePunctuation)
                editView.setSelection(editView.text?.length ?: 0)
            }
            alert("句末符号") {
                customView { binding.root }
                okButton {
                    val value = binding.editView.text?.toString().orEmpty().trim()
                    if (value.isBlank()) {
                        toastOnUi("句末符号不能为空")
                        return@okButton
                    }
                    savePreprocessConfig(config.copy(sentencePunctuation = value))
                }
                cancelButton()
            }
        }

        private fun showPreprocessThresholdDialog(config: ReadAloudPreprocessRuleConfig) {
            val items = listOf(
                "台词最短长度 · ${config.dialogueMinLength}",
                "强调文本最大长度 · ${config.emphasisMaxLength}",
                "冒号前提示最大长度 · ${config.colonCueMaxLength}",
                "跨段引号合并 · ${if (config.mergeCrossParagraphQuote) "开启" else "关闭"}",
                "音效上下文字符数 · ${config.soundEffectContextChars}"
            )
            requireContext().selector("判断阈值", items) { _, _, index ->
                when (index) {
                    0 -> showPreprocessNumberDialog("台词最短长度", config.dialogueMinLength, 1, 80) {
                        savePreprocessConfig(config.copy(dialogueMinLength = it))
                    }
                    1 -> showPreprocessNumberDialog("强调文本最大长度", config.emphasisMaxLength, 1, 80) {
                        savePreprocessConfig(config.copy(emphasisMaxLength = it))
                    }
                    2 -> showPreprocessNumberDialog("冒号前提示最大长度", config.colonCueMaxLength, 1, 80) {
                        savePreprocessConfig(config.copy(colonCueMaxLength = it))
                    }
                    3 -> savePreprocessConfig(config.copy(mergeCrossParagraphQuote = !config.mergeCrossParagraphQuote))
                    4 -> showPreprocessNumberDialog("音效上下文字符数", config.soundEffectContextChars, 8, 120) {
                        savePreprocessConfig(config.copy(soundEffectContextChars = it))
                    }
                }
            }
        }

        private fun showSoundEffectRuleDialog(config: ReadAloudPreprocessRuleConfig) {
            val items = listOf(
                "音效候选词 · ${config.soundEffectCuePatterns.size} 条",
                "音效排除词 · ${config.soundEffectExcludePatterns.size} 条",
                "上下文字符数 · ${config.soundEffectContextChars}"
            )
            requireContext().selector("音效候选规则", items) { _, _, index ->
                when (index) {
                    0 -> showRuleListEditor(
                        title = "音效候选词",
                        values = config.soundEffectCuePatterns,
                        hint = "每行一个候选词，例如：吱呀、敲门声、枪声",
                        onSave = { savePreprocessConfig(config.copy(soundEffectCuePatterns = it)) }
                    )
                    1 -> showRuleListEditor(
                        title = "音效排除词",
                        values = config.soundEffectExcludePatterns,
                        hint = "每行一个排除词，例如：心声、名声",
                        onSave = { savePreprocessConfig(config.copy(soundEffectExcludePatterns = it)) }
                    )
                    2 -> showPreprocessNumberDialog("音效上下文字符数", config.soundEffectContextChars, 8, 120) {
                        savePreprocessConfig(config.copy(soundEffectContextChars = it))
                    }
                }
            }
        }

        private fun showRuleListEditor(
            title: String,
            values: List<String>,
            hint: String,
            onSave: (List<String>) -> Unit
        ) {
            val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = hint
                editView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editView.minLines = 8
                editView.setText(values.joinToString("\n"))
                editView.setSelection(editView.text?.length ?: 0)
            }
            alert(title) {
                customView { binding.root }
                okButton {
                    val items = parseRuleItems(binding.editView.text?.toString().orEmpty())
                    if (items.isEmpty()) {
                        toastOnUi("至少保留一条规则")
                        return@okButton
                    }
                    onSave(items)
                }
                cancelButton()
            }
        }

        private fun showPreprocessNumberDialog(
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

        private fun showPreprocessTrialDialog() {
            val sample = """
                江艳凑在杨间身边小心翼翼地问道：
                “那栋房子真的有鬼啊？”
                门轴忽然吱呀一声响了起来。
                他心道：“事情不太对。”
            """.trimIndent()
            val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "粘贴几段正文，试运行当前预处理规则。"
                editView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editView.minLines = 10
                editView.setText(sample)
                editView.setSelection(editView.text?.length ?: 0)
            }
            alert("预处理试运行") {
                customView { binding.root }
                okButton {
                    val value = binding.editView.text?.toString().orEmpty()
                    if (value.isBlank()) {
                        toastOnUi("请先输入正文")
                        return@okButton
                    }
                    showPreprocessTrialResult(value)
                }
                cancelButton()
            }
        }

        private fun showPreprocessTrialResult(text: String) {
            val paragraphs = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            val roleResult = ReadAloudRolePreprocessor.process(paragraphs)
            val sfxResult = ReadAloudSoundEffectPreprocessor.process(paragraphs)
            val message = buildString {
                appendLine("角色 unit：${roleResult.units.size}")
                roleResult.units.take(40).forEachIndexed { index, unit ->
                    append(index + 1)
                    append(". ")
                    append(unit.kind)
                    append(" · ")
                    append(if (unit.needsAi) "需要 AI" else unit.characterName.ifBlank { unit.roleType })
                    append(" · P")
                    append(unit.firstParagraphIndex + 1)
                    append(" · ")
                    appendLine(unit.text.replace('\n', ' ').take(80))
                }
                if (roleResult.units.size > 40) appendLine("……")
                appendLine()
                appendLine("音效候选：${sfxResult.candidates.size}")
                sfxResult.candidates.take(30).forEachIndexed { index, item ->
                    append(index + 1)
                    append(". ")
                    append(item.cue)
                    append(" · P")
                    append(item.paragraphIndex + 1)
                    append(" · ")
                    appendLine(item.context.take(80))
                }
            }.take(6000)
            alert("试运行结果") {
                setMessage(message)
                okButton()
            }
        }


        private fun savePreprocessConfig(config: ReadAloudPreprocessRuleConfig) {
            val json = config.toJsonString()
            if (json.length > 8000) {
                toastOnUi("预处理规则最多 8000 字")
                return
            }
            AppConfig.aiReadAloudRolePreprocessRules = json
            updateAiRolePreferences()
            toastOnUi("已保存预处理规则")
        }

        private fun parseQuotePairs(value: String): List<ReadAloudQuotePair> {
            return value.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                    when {
                        parts.size >= 2 -> ReadAloudQuotePair(parts[0].take(1), parts[1].take(1))
                        line.length >= 2 -> ReadAloudQuotePair(line.take(1), line.takeLast(1))
                        else -> null
                    }
                }
                .distinct()
                .toList()
        }

        private fun parseRuleItems(value: String): List<String> {
            return value.lineSequence()
                .flatMap { it.split(',', '，', '、').asSequence() }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
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
