package io.legado.app.ui.config

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.ItemReadRecordComponentBinding
import io.legado.app.help.config.BookInfoComponentConfig
import io.legado.app.help.config.BookInfoComponentItem
import io.legado.app.help.config.BookInfoPageStyle
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BookInfoManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)
    private val adapter = ComponentAdapter()
    private lateinit var immersiveInfoView: View

    override fun onActivityCreated(savedInstanceState: android.os.Bundle?) {
        binding.titleBar.title = getString(R.string.book_info_manage)
        binding.root.applyUiBodyTypefaceDeep(uiTypeface())
        immersiveInfoView = createImmersiveInfoView()
        binding.root.addView(
            immersiveInfoView,
            binding.root.indexOfChild(binding.recyclerView)
        )
        binding.tabBar.visibility = View.VISIBLE
        binding.btnDay.text = getString(R.string.book_info_style_classic)
        binding.btnNight.text = getString(R.string.book_info_style_immersive)
        binding.btnAdd.text = getString(R.string.reset)
        binding.btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this, R.color.background_card),
            ContextCompat.getColor(this, R.color.background_menu),
            UiCorner.actionRadius(this)
        )
        binding.btnAdd.setOnClickListener {
            BookInfoComponentConfig.reset()
            adapter.reload()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        ItemTouchHelper(ItemTouchCallback(adapter).apply {
            isCanDrag = true
        }).attachToRecyclerView(binding.recyclerView)
        binding.btnDay.setOnClickListener {
            BookInfoComponentConfig.saveStyle(BookInfoPageStyle.CLASSIC)
            applyStyle(BookInfoPageStyle.CLASSIC)
        }
        binding.btnNight.setOnClickListener {
            BookInfoComponentConfig.saveStyle(BookInfoPageStyle.IMMERSIVE_COMPOSE)
            applyStyle(BookInfoPageStyle.IMMERSIVE_COMPOSE)
        }
        applyStyle(BookInfoComponentConfig.loadStyle())
    }

    private fun applyStyle(style: BookInfoPageStyle) = binding.run {
        val isClassic = style == BookInfoPageStyle.CLASSIC
        tvSummary.text = if (isClassic) {
            getString(R.string.book_info_components_hint)
        } else {
            getString(R.string.book_info_style_immersive_hint)
        }
        recyclerView.visibility = if (isClassic) View.VISIBLE else View.GONE
        btnAdd.visibility = if (isClassic) View.VISIBLE else View.GONE
        immersiveInfoView.visibility = if (isClassic) View.GONE else View.VISIBLE
        applyStyleTab(btnDay, selected = isClassic)
        applyStyleTab(btnNight, selected = !isClassic)
    }

    private fun applyStyleTab(view: TextView, selected: Boolean) {
        view.isSelected = selected
        view.setTextColor(
            if (selected) accentColor else ContextCompat.getColor(this, R.color.secondaryText)
        )
        view.applyUiSectionTitleStyle(this)
    }

    private fun createImmersiveInfoView(): View {
        val panelColor = ContextCompat.getColor(this, R.color.background_card)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            background = UiCorner.panelRounded(
                this@BookInfoManageActivity,
                panelColor,
                UiCorner.panelRadius(this@BookInfoManageActivity)
            )
            addView(TextView(context).apply {
                text = getString(R.string.book_info_style_immersive_title)
                textSize = 17f
                includeFontPadding = false
                setTextColor(ContextCompat.getColor(context, R.color.primaryText))
                applyUiSectionTitleStyle(context)
            })
            addView(TextView(context).apply {
                text = getString(R.string.book_info_style_immersive_desc)
                textSize = 13.5f
                setLineSpacing(2.dpToPx().toFloat(), 1f)
                setPadding(0, 10.dpToPx(), 0, 0)
                setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
                applyUiLabelStyle(context)
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 16.dpToPx())
            }
        }
    }

    private inner class ComponentAdapter :
        RecyclerView.Adapter<ComponentAdapter.Holder>(),
        ItemTouchCallback.Callback {

        private val pressedColor by lazy { ContextCompat.getColor(this@BookInfoManageActivity, R.color.background_menu) }
        private val items = mutableListOf<BookInfoComponentItem>()

        init {
            reload()
        }

        fun reload() {
            items.clear()
            items.addAll(BookInfoComponentConfig.load())
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                ItemReadRecordComponentBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
            if (srcPosition !in items.indices || targetPosition !in items.indices) return false
            val item = items.removeAt(srcPosition)
            items.add(targetPosition, item)
            notifyItemMoved(srcPosition, targetPosition)
            return true
        }

        override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            save()
        }

        private fun save() {
            BookInfoComponentConfig.save(items)
        }

        inner class Holder(
            private val itemBinding: ItemReadRecordComponentBinding
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: BookInfoComponentItem) = itemBinding.run {
                root.background = android.graphics.drawable.StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        UiCorner.opaqueRounded(pressedColor, UiCorner.panelRadius(this@BookInfoManageActivity))
                    )
                    addState(
                        intArrayOf(),
                        UiCorner.panelRounded(
                            this@BookInfoManageActivity,
                            ContextCompat.getColor(this@BookInfoManageActivity, R.color.background_card),
                            UiCorner.panelRadius(this@BookInfoManageActivity)
                        )
                    )
                }
                tvTitle.text = getString(item.type.titleRes)
                tvSubtitle.text = getString(item.type.hintRes)
                tvTitle.applyUiSectionTitleStyle(this@BookInfoManageActivity)
                tvSubtitle.applyUiLabelStyle(this@BookInfoManageActivity)
                cbEnabled.setOnCheckedChangeListener(null)
                cbEnabled.isChecked = item.enabled
                cbEnabled.setOnCheckedChangeListener { _, checked ->
                    if (!checked && items.count { it.enabled } <= 1) {
                        cbEnabled.isChecked = true
                        toastOnUi(R.string.book_info_component_keep_one)
                        return@setOnCheckedChangeListener
                    }
                    item.enabled = checked
                    save()
                }
                root.setOnClickListener {
                    cbEnabled.isChecked = !cbEnabled.isChecked
                }
                ivDrag.setColorFilter(ContextCompat.getColor(this@BookInfoManageActivity, R.color.secondaryText))
                cbEnabled.buttonTintList = android.content.res.ColorStateList.valueOf(accentColor)
            }
        }
    }
}
