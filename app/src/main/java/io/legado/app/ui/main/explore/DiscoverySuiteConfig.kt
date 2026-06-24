package io.legado.app.ui.main.explore

import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.util.UUID

data class DiscoverySuiteConfig(
    val suites: List<DiscoverySuite> = emptyList()
)

data class DiscoverySuite(
    val id: String = "",
    val name: String = "",
    val alias: String = "",
    val order: Int = 0,
    val widgets: List<DiscoverySuiteWidget> = emptyList()
) {
    val displayName: String
        get() = alias.ifBlank { name }
}

data class DiscoverySuiteWidget(
    val id: String = "",
    val type: String = DiscoverySuiteWidgetType.BookList.value,
    val title: String = "",
    val targets: List<DiscoverySuiteWidgetTarget> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val tagUrls: List<String> = emptyList(),
    val displayLimit: Int = DEFAULT_WIDGET_DISPLAY_LIMIT,
    val order: Int = 0
)

data class DiscoverySuiteWidgetTarget(
    val sourceUrl: String = "",
    val tagUrl: String = "",
    val title: String = ""
)

enum class DiscoverySuiteWidgetType(val value: String) {
    BookList("book_list")
}

object DiscoverySuiteStore {
    private const val MAX_CONFIG_CHARS = 96 * 1024
    private const val MAX_SUITES = 20
    private const val MAX_WIDGETS_PER_SUITE = 50
    private const val MAX_URLS_PER_WIDGET = 30
    private const val MAX_TARGETS_PER_WIDGET = 30
    private const val MAX_NAME_CHARS = 40
    private const val MAX_TITLE_CHARS = 60
    private const val MAX_ID_CHARS = 64

    fun load(): DiscoverySuiteConfig {
        val raw = appCtx.getPrefString(PreferKey.discoverySuiteConfig).orEmpty()
        if (raw.isBlank() || raw.length > MAX_CONFIG_CHARS) {
            return DiscoverySuiteConfig()
        }
        return runCatching {
            GSON.fromJson(raw, DiscoverySuiteConfig::class.java)
        }.getOrNull()
            ?.sanitize()
            ?: DiscoverySuiteConfig()
    }

    fun save(config: DiscoverySuiteConfig) {
        val sanitized = config.sanitize()
        val json = GSON.toJson(sanitized)
        if (json.length <= MAX_CONFIG_CHARS) {
            appCtx.putPrefString(PreferKey.discoverySuiteConfig, json)
        }
    }

    fun selectedSuiteId(): String {
        return appCtx.getPrefString(PreferKey.selectedDiscoverySuiteId).orEmpty()
    }

    fun setSelectedSuiteId(id: String) {
        appCtx.putPrefString(PreferKey.selectedDiscoverySuiteId, id.take(MAX_ID_CHARS))
    }

    fun newSuite(name: String): DiscoverySuite {
        return DiscoverySuite(
            id = newId("suite"),
            name = name.cleanName().ifBlank { "Suite" },
            order = Int.MAX_VALUE
        )
    }

    fun newBookWidget(title: String): DiscoverySuiteWidget {
        return DiscoverySuiteWidget(
            id = newId("widget"),
            title = title.cleanTitle().ifBlank { "Books" },
            type = DiscoverySuiteWidgetType.BookList.value,
            order = Int.MAX_VALUE
        )
    }

    private fun DiscoverySuiteConfig.sanitize(): DiscoverySuiteConfig {
        val suites = suites
            .asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.order }
            .take(MAX_SUITES)
            .mapIndexed { index, suite ->
                suite.copy(
                    id = suite.id.take(MAX_ID_CHARS),
                    name = suite.name.cleanName().ifBlank { "Suite ${index + 1}" },
                    alias = suite.alias.cleanName(),
                    order = index,
                    widgets = suite.widgets
                        .asSequence()
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .sortedBy { it.order }
                        .take(MAX_WIDGETS_PER_SUITE)
                        .mapIndexed { widgetIndex, widget ->
                            widget.copy(
                                id = widget.id.take(MAX_ID_CHARS),
                                type = when (widget.type) {
                                    DiscoverySuiteWidgetType.BookList.value -> widget.type
                                    else -> DiscoverySuiteWidgetType.BookList.value
                                },
                                title = widget.title.cleanTitle()
                                    .ifBlank { "Books ${widgetIndex + 1}" },
                                targets = widget.targets.cleanTargets(),
                                sourceUrls = widget.sourceUrls.cleanUrls(),
                                tagUrls = widget.tagUrls.cleanUrls(),
                                displayLimit = widget.displayLimit.coerceIn(4, 60),
                                order = widgetIndex
                            )
                        }
                        .toList()
                )
            }
            .toList()
        return DiscoverySuiteConfig(suites)
    }

    private fun List<String>.cleanUrls(): List<String> {
        return asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_URLS_PER_WIDGET)
            .toList()
    }

    private fun List<DiscoverySuiteWidgetTarget>.cleanTargets(): List<DiscoverySuiteWidgetTarget> {
        return asSequence()
            .map {
                DiscoverySuiteWidgetTarget(
                    sourceUrl = it.sourceUrl.trim(),
                    tagUrl = it.tagUrl.trim(),
                    title = it.title.cleanTitle()
                )
            }
            .filter { it.sourceUrl.isNotEmpty() && it.tagUrl.isNotEmpty() }
            .distinctBy { "${it.sourceUrl}\n${it.tagUrl}" }
            .take(MAX_TARGETS_PER_WIDGET)
            .toList()
    }

    private fun String.cleanName(): String {
        return trim().take(MAX_NAME_CHARS)
    }

    private fun String.cleanTitle(): String {
        return trim().take(MAX_TITLE_CHARS)
    }

    private fun newId(prefix: String): String {
        return "$prefix-${UUID.randomUUID()}"
    }
}

const val DEFAULT_WIDGET_DISPLAY_LIMIT = 12
