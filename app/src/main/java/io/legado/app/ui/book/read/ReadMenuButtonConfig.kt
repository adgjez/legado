package io.legado.app.ui.book.read

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

object ReadMenuButtonConfig {

    const val TYPE_BUILTIN = "builtin"
    const val TYPE_CUSTOM = "custom"

    object Builtin {
        const val SEARCH = "search"
        const val AUTO_PAGE = "autoPage"
        const val REPLACE_RULE = "replaceRule"
        const val NIGHT_THEME = "nightTheme"
        const val CATALOG = "catalog"
        const val READ_ALOUD = "readAloud"
        const val READ_STYLE = "readStyle"
        const val SETTING = "setting"
        const val READ_ASSISTANT = "readAssistant"
        const val PARAGRAPH_RULES = "paragraphRules"

        val ids = setOf(
            SEARCH,
            AUTO_PAGE,
            REPLACE_RULE,
            NIGHT_THEME,
            CATALOG,
            READ_ALOUD,
            READ_STYLE,
            SETTING,
            READ_ASSISTANT,
            PARAGRAPH_RULES
        )
    }

    data class ButtonRef(
        val type: String = TYPE_BUILTIN,
        val id: String = "",
        val titleOverride: String = "",
        val iconPath: String = ""
    )

    data class ButtonLayout(
        val firstRow: List<ButtonRef> = emptyList(),
        val secondRow: List<ButtonRef> = emptyList()
    )

    fun load(context: Context): ButtonLayout {
        val raw = context.getPrefString(PreferKey.readMenuButtonLayout).orEmpty()
        val parsed = if (raw.isBlank()) null else runCatching {
            GSON.fromJson(raw, ButtonLayout::class.java)
        }.getOrNull()
        return sanitize(parsed ?: defaultLayout())
    }

    fun save(context: Context, layout: ButtonLayout) {
        context.putPrefString(PreferKey.readMenuButtonLayout, GSON.toJson(sanitize(layout)))
    }

    fun defaultLayout(): ButtonLayout {
        return ButtonLayout(defaultFirstRow(), defaultSecondRow())
    }

    private fun defaultFirstRow(): List<ButtonRef> {
        return listOf(
            builtin(Builtin.SEARCH),
            builtin(Builtin.AUTO_PAGE),
            builtin(Builtin.REPLACE_RULE),
            builtin(Builtin.NIGHT_THEME)
        )
    }

    private fun defaultSecondRow(): List<ButtonRef> {
        return listOf(
            builtin(Builtin.CATALOG),
            builtin(Builtin.READ_ALOUD),
            builtin(Builtin.READ_STYLE),
            builtin(Builtin.SETTING)
        )
    }

    fun builtin(id: String): ButtonRef {
        return ButtonRef(type = TYPE_BUILTIN, id = id)
    }

    private fun sanitize(layout: ButtonLayout): ButtonLayout {
        val first = sanitizeRow(layout.firstRow)
        val second = sanitizeRow(layout.secondRow)
        return ButtonLayout(first, second)
    }

    private fun sanitizeRow(row: List<ButtonRef>): List<ButtonRef> {
        return row.filter { ref ->
            when (ref.type) {
                TYPE_BUILTIN -> ref.id in Builtin.ids
                TYPE_CUSTOM -> ref.id.toLongOrNull() != null
                else -> false
            }
        }
    }
}
