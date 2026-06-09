package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.utils.postEvent

class SubscriptionConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.subscription_settings_title

    override fun buildPageSpec(): SettingPageSpec {
        val useModern = booleanSetting(PreferKey.modernRssPage, true)
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.showRss,
                            title = getString(R.string.show_rss),
                            checked = booleanSetting(PreferKey.showRss, false),
                            onCheckedChange = { updateBooleanSetting(PreferKey.showRss, it) },
                            searchKeys = listOf(KEY_SEARCH_JUMP_SHOW_RSS)
                        ),
                        SettingChoiceSpec(
                            key = KEY_RSS_MODE,
                            title = getString(R.string.modern_rss_page),
                            summary = getString(R.string.modern_rss_page_summary),
                            options = pageModeOptions(
                                entriesRes = R.array.rss_page_mode_entries,
                                valuesRes = R.array.page_mode_values
                            ),
                            selectedValue = if (useModern) PAGE_MODE_MODERN else PAGE_MODE_LEGACY,
                            onSelected = {
                                updateBooleanSetting(
                                    PreferKey.modernRssPage,
                                    it == PAGE_MODE_MODERN
                                )
                            },
                            searchKeys = listOf(
                                PreferKey.modernRssPage,
                                KEY_SEARCH_JUMP_MODERN_RSS_PAGE,
                                KEY_SEARCH_JUMP_RSS_MODE
                            )
                        )
                    )
                )
            )
        )
    }

    override fun normalizeTargetKey(rawKey: String): String {
        return when (rawKey) {
            KEY_SEARCH_JUMP_SHOW_RSS -> PreferKey.showRss
            PreferKey.modernRssPage,
            KEY_SEARCH_JUMP_MODERN_RSS_PAGE,
            KEY_SEARCH_JUMP_RSS_MODE -> KEY_RSS_MODE
            else -> rawKey
        }
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.showRss -> postEvent(EventBus.NOTIFY_MAIN, true)
            PreferKey.modernRssPage,
            PreferKey.mergeDiscoveryRss -> postEvent(EventBus.NOTIFY_MAIN, false)
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

    companion object {
        private const val PAGE_MODE_MODERN = "modern"
        private const val PAGE_MODE_LEGACY = "legacy"
        private const val KEY_RSS_MODE = "modernRssMode"
        private const val KEY_SEARCH_JUMP_SHOW_RSS = "search_jump_showRss"
        private const val KEY_SEARCH_JUMP_MODERN_RSS_PAGE = "search_jump_modernRssPage"
        private const val KEY_SEARCH_JUMP_RSS_MODE = "search_jump_modernRssMode"
    }
}
