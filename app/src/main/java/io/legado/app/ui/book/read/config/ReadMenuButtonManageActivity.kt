package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.read.ReadMenuButtonConfig
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class ReadMenuButtonManageActivity : BaseActivity<ActivityThemeManageBinding>(),
    ItemTouchCallback.Callback {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)
    private val adapter = ButtonAdapter()
    private var layout = ReadMenuButtonConfig.defaultLayout()
    private var rowIndex = 0

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun initView() = binding.run {
        titleBar.title = getString(R.string.read_menu_button_manage)
        tabBar.background = UiCorner.opaqueRounded(
            ContextCompat.getColor(this@ReadMenuButtonManageActivity, R.color.background_menu),
            UiCorner.panelRadius(this@ReadMenuButtonManageActivity)
        )
        listOf(btnDay, btnNight).forEach {
            it.background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                ContextCompat.getColor(this@ReadMenuButtonManageActivity, R.color.background_card),
                UiCorner.actionRadius(this@ReadMenuButtonManageActivity)
            )
        }
        btnDay.text = getString(R.string.read_menu_first_row)
        btnNight.text = getString(R.string.read_menu_second_row)
        btnAdd.text = getString(R.string.read_menu_add_button)
        btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this@ReadMenuButtonManageActivity, R.color.background_card),
            ContextCompat.getColor(this@ReadMenuButtonManageActivity, R.color.background_menu),
            UiCorner.actionRadius(this@ReadMenuButtonManageActivity)
        )
        btnAdd.setOnClickListener { showAddButtonDialog() }
        tvSummary.applyUiLabelStyle(this@ReadMenuButtonManageActivity)
        tvSummary.setTextColor(secondaryTextColor)
        recyclerView.layoutManager = LinearLayoutManager(this@ReadMenuButtonManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        ItemTouchHelper(ItemTouchCallback(this@ReadMenuButtonManageActivity).apply {
            isCanDrag = true
        }).attachToRecyclerView(recyclerView)
        btnDay.setOnClickListener {
            if (rowIndex != 0) {
                rowIndex = 0
                load()
            }
        }
        btnNight.setOnClickListener {
            if (rowIndex != 1) {
                rowIndex = 1
                load()
            }
        }
        root.applyUiBodyTypefaceDeep(this@ReadMenuButtonManageActivity.uiTypeface())
        updateTabs()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_RESET, 0, R.string.reset).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_RESET -> {
                resetLayout()
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun load() {
        layout = ReadMenuButtonConfig.load(this)
        adapter.items = currentRow()
        binding.tvSummary.text = getString(R.string.read_menu_button_summary)
        updateTabs()
    }

    private fun updateTabs() = binding.run {
        btnDay.isSelected = rowIndex == 0
        btnNight.isSelected = rowIndex == 1
        btnDay.setTextColor(if (rowIndex == 0) accentColor else primaryTextColor)
        btnNight.setTextColor(if (rowIndex == 1) accentColor else primaryTextColor)
    }

    private fun currentRow(): List<ReadMenuButtonConfig.ButtonRef> {
        return if (rowIndex == 0) layout.firstRow else layout.secondRow
    }

    private fun saveCurrentRow(row: List<ReadMenuButtonConfig.ButtonRef>) {
        layout = if (rowIndex == 0) {
            layout.copy(firstRow = row)
        } else {
            layout.copy(secondRow = row)
        }
        ReadMenuButtonConfig.save(this, layout)
        adapter.items = currentRow()
    }

    private fun saveLayout(newLayout: ReadMenuButtonConfig.ButtonLayout) {
        layout = newLayout
        ReadMenuButtonConfig.save(this, layout)
        adapter.items = currentRow()
    }

    private fun showAddButtonDialog() {
        val usedIds = (layout.firstRow + layout.secondRow)
            .filter { it.type == ReadMenuButtonConfig.TYPE_BUILTIN }
            .map { it.id }
            .toSet()
        val candidates = builtinCandidates().filterNot { it.id in usedIds }
        if (candidates.isEmpty()) {
            toastOnUi(R.string.read_menu_no_available_button)
            return
        }
        selector(
            getString(R.string.read_menu_add_button),
            candidates.map { buttonTitle(it) }
        ) { _, index ->
            val row = currentRow().toMutableList()
            row.add(candidates[index])
            saveCurrentRow(row)
        }
    }

    private fun builtinCandidates(): List<ReadMenuButtonConfig.ButtonRef> {
        return buildList {
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.SEARCH))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.AUTO_PAGE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.REPLACE_RULE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.NIGHT_THEME))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.CATALOG))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.READ_ALOUD))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.READ_STYLE))
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.SETTING))
            if (AppConfig.aiAssistantEnabled) {
                add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.READ_ASSISTANT))
            }
            add(ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES))
        }
    }

    private fun moveToOtherRow(ref: ReadMenuButtonConfig.ButtonRef) {
        val first = layout.firstRow.toMutableList()
        val second = layout.secondRow.toMutableList()
        if (rowIndex == 0) {
            first.remove(ref)
            second.add(ref)
        } else {
            second.remove(ref)
            first.add(ref)
        }
        saveLayout(ReadMenuButtonConfig.ButtonLayout(first, second))
    }

    private fun deleteButton(ref: ReadMenuButtonConfig.ButtonRef) {
        alert(R.string.delete) {
            setMessage(R.string.del_msg)
            yesButton {
                val row = currentRow().toMutableList()
                row.remove(ref)
                saveCurrentRow(row)
            }
            noButton()
        }
    }

    private fun resetLayout() {
        alert(R.string.reset) {
            setMessage(R.string.del_msg)
            yesButton {
                saveLayout(ReadMenuButtonConfig.defaultLayout())
            }
            noButton()
        }
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val row = currentRow().toMutableList()
        if (srcPosition !in row.indices || targetPosition !in row.indices) return false
        val item = row.removeAt(srcPosition)
        row.add(targetPosition, item)
        saveCurrentRow(row)
        return true
    }

    private fun buttonTitle(ref: ReadMenuButtonConfig.ButtonRef): String {
        ref.titleOverride.trim().takeIf { it.isNotBlank() }?.let { return it }
        if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) return ref.id
        return when (ref.id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> getString(R.string.search_content)
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> getString(R.string.auto_next_page)
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> getString(R.string.replace_rule_title)
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> getString(R.string.dark_theme)
            ReadMenuButtonConfig.Builtin.CATALOG -> getString(R.string.chapter_list)
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> getString(R.string.read_aloud)
            ReadMenuButtonConfig.Builtin.READ_STYLE -> getString(R.string.interface_setting)
            ReadMenuButtonConfig.Builtin.SETTING -> getString(R.string.setting)
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> getString(R.string.ai_assistant)
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> getString(R.string.paragraph_rule)
            else -> ref.id
        }
    }

    private fun buttonIconRes(ref: ReadMenuButtonConfig.ButtonRef): Int {
        if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) return R.drawable.ic_custom
        return when (ref.id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> R.drawable.ic_search
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> R.drawable.ic_auto_page
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> R.drawable.ic_find_replace
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> R.drawable.ic_brightness
            ReadMenuButtonConfig.Builtin.CATALOG -> R.drawable.ic_toc
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> R.drawable.ic_read_aloud
            ReadMenuButtonConfig.Builtin.READ_STYLE -> R.drawable.ic_interface_setting
            ReadMenuButtonConfig.Builtin.SETTING -> R.drawable.ic_settings
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> R.drawable.ic_bottom_ai_assistant
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> R.drawable.ic_code
            else -> R.drawable.ic_custom
        }
    }

    private inner class ButtonAdapter : RecyclerView.Adapter<ButtonViewHolder>() {
        var items: List<ReadMenuButtonConfig.ButtonRef> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ButtonViewHolder {
            return ButtonViewHolder(
                ItemThemePackageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ButtonViewHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class ButtonViewHolder(
        private val itemBinding: ItemThemePackageBinding
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(ref: ReadMenuButtonConfig.ButtonRef) = itemBinding.run {
            root.background = UiCorner.opaqueRounded(
                ContextCompat.getColor(this@ReadMenuButtonManageActivity, R.color.background_card),
                UiCorner.panelRadius(this@ReadMenuButtonManageActivity)
            )
            ivPreview.setImageResource(buttonIconRes(ref))
            ivPreview.setColorFilter(primaryTextColor)
            ivPreview.setBackgroundColor(Color.TRANSPARENT)
            tvName.text = buttonTitle(ref)
            tvSource.text = if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) {
                getString(R.string.read_menu_custom_button)
            } else {
                getString(R.string.read_menu_builtin_button)
            }
            tvInfo.text = getString(
                if (rowIndex == 0) R.string.read_menu_first_row else R.string.read_menu_second_row
            )
            tvName.applyUiSectionTitleStyle(this@ReadMenuButtonManageActivity)
            tvInfo.applyUiLabelStyle(this@ReadMenuButtonManageActivity)
            tvSource.setTextColor(secondaryTextColor)
            btnApply.text = getString(
                if (rowIndex == 0) R.string.read_menu_move_to_second
                else R.string.read_menu_move_to_first
            )
            btnEdit.visibility = View.GONE
            btnMore.text = getString(R.string.delete)
            listOf(btnApply, btnMore).forEach {
                it.background = UiCorner.actionSelector(
                    ContextCompat.getColor(this@ReadMenuButtonManageActivity, R.color.background_menu),
                    ContextCompat.getColor(this@ReadMenuButtonManageActivity, R.color.background_card),
                    UiCorner.actionRadius(this@ReadMenuButtonManageActivity)
                )
                it.typeface = this@ReadMenuButtonManageActivity.uiTypeface()
            }
            btnApply.setOnClickListener { moveToOtherRow(ref) }
            btnMore.setOnClickListener { deleteButton(ref) }
            root.setOnClickListener { moveToOtherRow(ref) }
        }
    }

    companion object {
        private const val MENU_RESET = 1
    }
}
