package io.legado.app.ui.book.read

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefStringSet

object ContentSelectConfig {

    const val ACTION_WEB_SEARCH = "web_search"
    const val ACTION_REPLACE = "replace"
    const val ACTION_COPY = "copy"
    const val ACTION_BOOKMARK = "bookmark"
    const val ACTION_ALOUD = "aloud"
    const val ACTION_DICT = "dict"
    const val ACTION_ASK_AI = "ask_ai"

    private val oldDefaultActions = setOf(
        ACTION_REPLACE,
        ACTION_COPY,
        ACTION_BOOKMARK,
        ACTION_ALOUD,
        ACTION_DICT,
        ACTION_ASK_AI
    )

    val defaultActions = setOf(
        ACTION_WEB_SEARCH,
        ACTION_REPLACE,
        ACTION_COPY,
        ACTION_BOOKMARK,
        ACTION_ALOUD,
        ACTION_DICT,
        ACTION_ASK_AI
    )

    val defaultOpenValues = listOf("", ACTION_WEB_SEARCH, ACTION_DICT, ACTION_ASK_AI)
    private val removedActionIds = setOf("generate_image")

    fun selectedActionIds(context: Context): Set<String> {
        val saved = context.getPrefStringSet(PreferKey.contentSelectActions, null)
            ?.filterNot { it in removedActionIds }
            ?.toSet()
            ?: return defaultActions
        return if (saved == oldDefaultActions) defaultActions else saved
    }
}
