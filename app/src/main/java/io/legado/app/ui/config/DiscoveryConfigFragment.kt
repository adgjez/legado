package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.widget.compose.showComposeSingleChoiceDialog
import io.legado.app.utils.postEvent

class DiscoveryConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.discovery_settings_title

    override fun buildPageSpec(): SettingPageSpec {
        val useModern = booleanSetting(PreferKey.modernDiscoveryPage, true)
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.showDiscovery,
                            title = getString(R.string.show_discovery),
                            checked = booleanSetting(PreferKey.showDiscovery, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.showDiscovery, it)
                            },
                            searchKeys = listOf(KEY_SEARCH_JUMP_SHOW_DISCOVERY)
                        ),
                        SettingChoiceSpec(
                            key = KEY_DISCOVERY_MODE,
                            title = getString(R.string.modern_discovery_page),
                            options = pageModeOptions(
                                entriesRes = R.array.discovery_page_mode_entries,
                                valuesRes = R.array.discovery_page_mode_values
                            ),
                            selectedValue = if (useModern) PAGE_MODE_MODERN else PAGE_MODE_LEGACY,
                            summary = pageModeLabel(
                                entriesRes = R.array.discovery_page_mode_entries,
                                valuesRes = R.array.discovery_page_mode_values,
                                selectedValue = if (useModern) PAGE_MODE_MODERN else PAGE_MODE_LEGACY
                            ),
                            onSelected = {
                                updateBooleanSetting(
                                    PreferKey.modernDiscoveryPage,
                                    it == PAGE_MODE_MODERN
                                )
                            },
                            searchKeys = listOf(
                                PreferKey.modernDiscoveryPage,
                                KEY_SEARCH_JUMP_DISCOVERY_MODE
                            )
                        ),
                        SettingActionSpec(
                            key = PreferKey.discoveryPageLayout,
                            title = getString(R.string.discovery_page_layout),
                            summary = discoveryLayoutSummary(),
                            visible = useModern,
                            onClick = ::showDiscoveryLayoutDialog
                        )
                    )
                )
            )
        )
    }

    override fun normalizeTargetKey(rawKey: String): String {
        return when (rawKey) {
            KEY_SEARCH_JUMP_SHOW_DISCOVERY -> PreferKey.showDiscovery
            PreferKey.modernDiscoveryPage,
            KEY_SEARCH_JUMP_DISCOVERY_MODE -> KEY_DISCOVERY_MODE
            else -> rawKey
        }
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.showDiscovery -> postEvent(EventBus.NOTIFY_MAIN, true)
            PreferKey.modernDiscoveryPage,
            PreferKey.discoveryPageLayout -> postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }

    private fun showDiscoveryLayoutDialog() {
        showComposeSingleChoiceDialog(
            title = getString(R.string.discovery_page_layout),
            labels = DISCOVERY_LAYOUT_VALUES.map(::discoveryLayoutLabel),
            selectedIndex = DISCOVERY_LAYOUT_VALUES.indexOf(AppConfig.discoveryPageLayout),
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { index ->
                val value = DISCOVERY_LAYOUT_VALUES.getOrNull(index) ?: return@showComposeSingleChoiceDialog
                AppConfig.discoveryPageLayout = value
                refreshSettings()
            }
        )
    }

    private fun discoveryLayoutSummary(): String {
        return getString(
            R.string.discovery_page_layout_summary,
            discoveryLayoutLabel(AppConfig.discoveryPageLayout)
        )
    }

    private fun discoveryLayoutLabel(value: Int): String {
        return when (value) {
            2 -> getString(R.string.discovery_page_layout_waterfall)
            3 -> getString(R.string.discovery_page_layout_grid)
            else -> getString(R.string.discovery_page_layout_list)
        }
    }

    private fun pageModeOptions(
        entriesRes: Int,
        valuesRes: Int
    ): List<SettingChoiceOption> {
        val entries = resources.getStringArray(entriesRes)
        val values = resources.getStringArray(valuesRes)
        return values.mapIndexed { index, value ->
            SettingChoiceOption(
                value = value,
                label = entries.getOrElse(index) { value }
            )
        }
    }

    private fun pageModeLabel(
        entriesRes: Int,
        valuesRes: Int,
        selectedValue: String
    ): String {
        return pageModeOptions(entriesRes, valuesRes)
            .firstOrNull { it.value == selectedValue }
            ?.label
            ?.toString()
            .orEmpty()
    }

    companion object {
        private const val PAGE_MODE_MODERN = "modern"
        private const val PAGE_MODE_LEGACY = "legacy"
        private const val KEY_DISCOVERY_MODE = "modernDiscoveryMode"
        private const val KEY_SEARCH_JUMP_SHOW_DISCOVERY = "search_jump_showDiscovery"
        private const val KEY_SEARCH_JUMP_DISCOVERY_MODE = "search_jump_modernDiscoveryMode"
        private val DISCOVERY_LAYOUT_VALUES = listOf(1, 2, 3)
    }
}
