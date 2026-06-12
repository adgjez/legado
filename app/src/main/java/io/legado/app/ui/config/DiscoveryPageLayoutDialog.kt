package io.legado.app.ui.config

import android.content.Context
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog

object DiscoveryPageLayoutDialog {

    private val layoutValues = listOf(1, 2, 3)

    fun bindSummary(context: Context, preference: Preference?) {
        preference?.summary = context.getString(
            R.string.discovery_page_layout_summary,
            label(context, AppConfig.discoveryPageLayout)
        )
    }

    fun show(context: Context, onChanged: () -> Unit) {
        val labels = layoutValues.map { label(context, it) }
        context.showComposeChoiceListDialog(
            title = context.getString(R.string.discovery_page_layout),
            labels = labels,
            selectedIndex = layoutValues.indexOf(AppConfig.discoveryPageLayout)
        ) { index ->
            val value = layoutValues.getOrNull(index) ?: return@showComposeChoiceListDialog
            AppConfig.discoveryPageLayout = value
            onChanged()
        }
    }

    private fun label(context: Context, value: Int): String {
        return when (value) {
            2 -> context.getString(R.string.discovery_page_layout_waterfall)
            3 -> context.getString(R.string.discovery_page_layout_grid)
            else -> context.getString(R.string.discovery_page_layout_list)
        }
    }
}
