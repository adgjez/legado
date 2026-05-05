package io.legado.app.ui.about

import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

enum class ReadRecordComponentType(@StringRes val titleRes: Int) {
    OVERVIEW(R.string.read_record_component_overview),
    HEATMAP(R.string.read_record_component_heatmap),
    RECENT_BOOKS(R.string.read_record_component_recent_books),
    DAILY_RECORDS(R.string.read_record_component_daily_records);

    companion object {
        fun fromKey(key: String?): ReadRecordComponentType? {
            return entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
        }
    }
}

data class ReadRecordComponentItem(
    val type: ReadRecordComponentType,
    var enabled: Boolean
)

object ReadRecordComponents {

    private val defaultOrder = listOf(
        ReadRecordComponentType.OVERVIEW,
        ReadRecordComponentType.HEATMAP,
        ReadRecordComponentType.RECENT_BOOKS,
        ReadRecordComponentType.DAILY_RECORDS
    )

    fun load(): MutableList<ReadRecordComponentItem> {
        val raw = appCtx.getPrefString(PreferKey.readRecordComponents).orEmpty().trim()
        if (raw.isEmpty()) {
            return defaultOrder.map { ReadRecordComponentItem(it, true) }.toMutableList()
        }
        val parsed = raw.split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":")
                val type = ReadRecordComponentType.fromKey(parts.getOrNull(0)?.trim())
                val enabled = parts.getOrNull(1)?.trim() != "0"
                type?.let { ReadRecordComponentItem(it, enabled) }
            }
            .toMutableList()
        defaultOrder.forEach { type ->
            if (parsed.none { it.type == type }) {
                parsed += ReadRecordComponentItem(type, true)
            }
        }
        return parsed
    }

    fun save(items: List<ReadRecordComponentItem>) {
        val normalized = items.distinctBy { it.type }.ifEmpty {
            defaultOrder.map { ReadRecordComponentItem(it, true) }
        }
        val raw = normalized.joinToString(",") {
            "${it.type.name}:${if (it.enabled) 1 else 0}"
        }
        appCtx.putPrefString(PreferKey.readRecordComponents, raw)
    }
}
