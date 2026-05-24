package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogContentSelectMenuConfigBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.view.ThemeCheckBox
import io.legado.app.ui.book.read.ContentSelectConfig
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick

class ContentSelectMenuConfigDialog : BaseDialogFragment(R.layout.dialog_content_select_menu_config) {

    private val binding by viewBinding(DialogContentSelectMenuConfigBinding::bind)

    private data class MenuAction(val id: String, val checkBox: ThemeCheckBox)

    companion object {
        private val defaultOpenValues = ContentSelectConfig.defaultOpenValues
    }

    private val actions: List<MenuAction>
        get() = binding.run {
            listOf(
                MenuAction(ContentSelectConfig.ACTION_WEB_SEARCH, cbSearch),
                MenuAction(ContentSelectConfig.ACTION_REPLACE, cbReplace),
                MenuAction(ContentSelectConfig.ACTION_COPY, cbCopy),
                MenuAction(ContentSelectConfig.ACTION_BOOKMARK, cbBookmark),
                MenuAction(ContentSelectConfig.ACTION_ALOUD, cbAloud),
                MenuAction(ContentSelectConfig.ACTION_DICT, cbDict),
                MenuAction(ContentSelectConfig.ACTION_ASK_AI, cbAskAi),
            )
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        initData()
        binding.tvCancel.onClick {
            dismissAllowingStateLoss()
        }
        binding.tvOk.onClick {
            saveConfig()
        }
    }

    private fun initData() {
        val selected = ContentSelectConfig.selectedActionIds(requireContext())
        actions.forEach { action ->
            action.checkBox.isChecked = selected.contains(action.id)
        }
        val defaultOpen = requireContext().getPrefString(PreferKey.contentSelectDefaultOpen, "").orEmpty()
            .takeIf { it in defaultOpenValues }
            .orEmpty()
        val defaultIndex = defaultOpenValues.indexOf(defaultOpen).takeIf { it >= 0 } ?: 0
        binding.rgDefaultOpen.checkByIndex(defaultIndex)
    }

    private fun saveConfig() {
        val selected = actions
            .filter { it.checkBox.isChecked }
            .map { it.id }
            .toMutableSet()
        val defaultOpen = defaultOpenValues.getOrElse(binding.rgDefaultOpen.getCheckedIndex()) { "" }
        if (defaultOpen.isNotEmpty()) {
            selected += defaultOpen
        }
        if (selected.isEmpty()) {
            selected += ContentSelectConfig.ACTION_COPY
        }
        requireContext().putPrefStringSet(PreferKey.contentSelectActions, selected)
        requireContext().putPrefString(PreferKey.contentSelectDefaultOpen, defaultOpen)
        postEvent(EventBus.CONTENT_SELECT_MENU_CONFIG_CHANGED, true)
        dismissAllowingStateLoss()
    }
}
