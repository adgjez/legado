package io.legado.app.help.config

import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx
import java.util.UUID

data class DiscoverySuite(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val alias: String = "",
    val sortOrder: Int = 0,
    val widgets: List<DiscoveryWidgetConfig> = emptyList()
) {
    val displayName: String
        get() = alias.ifBlank { name }.ifBlank { "未命名套件" }
}

data class DiscoveryWidgetConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: String = TYPE_BOOK_ROW,
    val title: String = "",
    val sourceTags: List<String> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val exploreKindKey: String = "",
    val limit: Int = 8,
    val sortOrder: Int = 0
) {
    companion object {
        const val TYPE_BOOK_ROW = "book_row"
        const val TYPE_BOOK_GRID = "book_grid"
        const val TYPE_RANK_CARD = "rank_card"
        const val TYPE_TAG_CHIPS = "tag_chips"
    }
}

object DiscoverySuiteConfig {

    var suites: List<DiscoverySuite>
        get() = normalizeSuites(
            GSON.fromJsonArray<DiscoverySuite>(
                appCtx.getPrefString(PreferKey.discoverySuiteList)
            ).getOrDefault(emptyList())
        )
        set(value) {
            val normalized = normalizeSuites(value)
            if (normalized.isEmpty()) {
                appCtx.removePref(PreferKey.discoverySuiteList)
                currentSuiteId = null
            } else {
                appCtx.putPrefString(PreferKey.discoverySuiteList, GSON.toJson(normalized))
                val current = currentSuiteId
                if (current.isNullOrBlank() || normalized.none { it.id == current }) {
                    currentSuiteId = normalized.first().id
                }
            }
        }

    var currentSuiteId: String?
        get() = appCtx.getPrefString(PreferKey.currentDiscoverySuiteId)
        set(value) {
            if (value.isNullOrBlank()) {
                appCtx.removePref(PreferKey.currentDiscoverySuiteId)
            } else {
                appCtx.putPrefString(PreferKey.currentDiscoverySuiteId, value)
            }
        }

    val currentSuite: DiscoverySuite?
        get() {
            val items = suites
            val current = currentSuiteId
            return items.firstOrNull { it.id == current } ?: items.firstOrNull()
        }

    fun addSuite(name: String): DiscoverySuite {
        val next = DiscoverySuite(
            name = name.trim().ifBlank { "发现套件" },
            sortOrder = suites.size
        )
        suites = suites + next
        currentSuiteId = next.id
        return next
    }

    fun updateSuite(suite: DiscoverySuite) {
        suites = suites.map { if (it.id == suite.id) suite else it }
    }

    fun deleteSuite(id: String) {
        suites = suites.filterNot { it.id == id }
    }

    fun moveSuite(id: String, offset: Int) {
        val list = suites.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + offset).coerceIn(0, list.lastIndex)
        if (from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        suites = list.mapIndexed { index, suite -> suite.copy(sortOrder = index) }
    }

    private fun normalizeSuites(value: List<DiscoverySuite>): List<DiscoverySuite> {
        return value
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedWith(compareBy<DiscoverySuite> { it.sortOrder }.thenBy { it.name })
            .mapIndexed { index, suite ->
                suite.copy(
                    name = suite.name.trim().ifBlank { "发现套件" },
                    alias = suite.alias.trim(),
                    sortOrder = index,
                    widgets = suite.widgets.sortedBy { it.sortOrder }
                )
            }
    }
}
