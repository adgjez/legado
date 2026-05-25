package io.legado.app.ui.about

import android.text.InputType
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.update.AppUpdateConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.dpToPx

object UpdateAcceleratorDialog {

    private data class StrategyOption(
        val label: String,
        val value: String
    )

    fun show(fragment: Fragment, onChanged: () -> Unit) {
        val context = fragment.requireContext()
        val strategies = listOf(
            StrategyOption("Gitee 优先，失败后 GitHub", AppUpdateConfig.STRATEGY_GITEE_THEN_GITHUB),
            StrategyOption("只使用 Gitee", AppUpdateConfig.STRATEGY_GITEE_ONLY),
            StrategyOption("只使用 GitHub", AppUpdateConfig.STRATEGY_GITHUB_ONLY)
        )
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dpToPx(), 8.dpToPx(), 14.dpToPx(), 4.dpToPx())
        }
        root.addView(sectionTitle(context, "更新通道"))

        val strategyGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        strategies.forEach { option ->
            strategyGroup.addView(
                RadioButton(context).apply {
                    id = View.generateViewId()
                    text = option.label
                    tag = option.value
                    minHeight = 42.dpToPx()
                    applyUiLabelStyle(context)
                    isChecked = AppUpdateConfig.strategy == option.value
                },
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        strategyGroup.setOnCheckedChangeListener { group, checkedId ->
            val value = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            AppUpdateConfig.strategy = value
            onChanged()
        }
        root.addView(strategyGroup)

        root.addView(sectionTitle(context, "GitHub 加速代理").apply {
            setPadding(0, 16.dpToPx(), 0, 6.dpToPx())
        })
        root.addView(
            TextView(context).apply {
                text = "代理模板支持 \${url} 占位符。未填写占位符时，会自动把原始 GitHub 地址拼到代理地址后面。"
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
                applyUiBodyTypefaceDeep(context.uiTypeface())
            }
        )

        val proxyGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 6.dpToPx(), 0, 4.dpToPx())
        }
        root.addView(proxyGroup)

        fun renderProxyGroup() {
            proxyGroup.setOnCheckedChangeListener(null)
            proxyGroup.removeAllViews()
            proxyGroup.addView(proxyRadioButton(context, "不使用代理", -1, AppUpdateConfig.githubProxyIndex < 0))
            AppUpdateConfig.githubProxyTemplates.forEachIndexed { index, proxy ->
                proxyGroup.addView(proxyRadioButton(context, proxy, index, AppUpdateConfig.githubProxyIndex == index))
            }
            proxyGroup.setOnCheckedChangeListener { group, checkedId ->
                val index = group.findViewById<RadioButton>(checkedId)?.tag as? Int ?: -1
                AppUpdateConfig.githubProxyIndex = index
                onChanged()
            }
        }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 2.dpToPx(), 0, 0)
        }
        actionRow.addView(actionButton(context, "添加代理") {
            showProxyEditDialog(fragment, null) { value ->
                val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                proxies += value
                AppUpdateConfig.githubProxyTemplates = proxies
                AppUpdateConfig.githubProxyIndex = AppUpdateConfig.githubProxyTemplates.indexOf(value)
                renderProxyGroup()
                onChanged()
            }
        })
        actionRow.addView(actionButton(context, "编辑") {
            val index = AppUpdateConfig.githubProxyIndex
            val oldValue = AppUpdateConfig.githubProxyTemplates.getOrNull(index) ?: return@actionButton
            showProxyEditDialog(fragment, oldValue) { value ->
                val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                proxies[index] = value
                AppUpdateConfig.githubProxyTemplates = proxies
                AppUpdateConfig.githubProxyIndex = AppUpdateConfig.githubProxyTemplates.indexOf(value)
                renderProxyGroup()
                onChanged()
            }
        })
        actionRow.addView(actionButton(context, "删除") {
            val index = AppUpdateConfig.githubProxyIndex
            val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
            if (index !in proxies.indices) return@actionButton
            proxies.removeAt(index)
            AppUpdateConfig.githubProxyTemplates = proxies
            AppUpdateConfig.githubProxyIndex = -1
            renderProxyGroup()
            onChanged()
        })
        root.addView(actionRow)
        renderProxyGroup()

        val scrollView = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        fragment.alert(title = "加速管理") {
            customView { scrollView }
            okButton {
                onChanged()
            }
        }
    }

    private fun sectionTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            includeFontPadding = false
            applyUiSectionTitleStyle(context)
            setPadding(0, 4.dpToPx(), 0, 6.dpToPx())
        }
    }

    private fun proxyRadioButton(context: Context, text: String, index: Int, checked: Boolean): RadioButton {
        return RadioButton(context).apply {
            id = View.generateViewId()
            tag = index
            this.text = text
            isChecked = checked
            minHeight = 42.dpToPx()
            maxLines = 2
            applyUiLabelStyle(context)
        }
    }

    private fun actionButton(context: Context, text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            includeFontPadding = false
            minHeight = 32.dpToPx()
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
            applyUiLabelStyle(context)
            background = UiCorner.actionSelector(
                android.graphics.Color.TRANSPARENT,
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.actionRadius(context)
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                32.dpToPx()
            ).apply {
                marginStart = 4.dpToPx()
            }
        }
    }

    private fun showProxyEditDialog(
        fragment: Fragment,
        oldValue: String?,
        onSaved: (String) -> Unit
    ) {
        val binding = DialogEditTextBinding.inflate(fragment.layoutInflater).apply {
            editView.hint = "https://proxy.example/\${url}"
            editView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editView.setSingleLine(false)
            editView.setText(oldValue.orEmpty())
            editView.setSelection(editView.text?.length ?: 0)
        }
        fragment.alert(title = if (oldValue == null) "添加代理" else "编辑代理") {
            customView { binding.root }
            okButton {
                val value = binding.editView.text?.toString()?.trim().orEmpty()
                if (value.isNotBlank()) {
                    onSaved(value)
                }
            }
            cancelButton()
        }
    }
}
