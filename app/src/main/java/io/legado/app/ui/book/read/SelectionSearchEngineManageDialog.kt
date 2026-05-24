package io.legado.app.ui.book.read

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogSelectionSearchEngineManageBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick

class SelectionSearchEngineManageDialog(
    private val onChanged: (() -> Unit)? = null
) : BaseDialogFragment(R.layout.dialog_selection_search_engine_manage) {

    private val binding by viewBinding(DialogSelectionSearchEngineManageBinding::bind)
    private var engines: MutableList<ContentSelectConfig.SearchEngine> = mutableListOf()

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.72f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        binding.toolBar.setBackgroundColor(primaryColor)
        engines = ContentSelectConfig.searchEngines(requireContext()).toMutableList()
        renderList()
        binding.tvCancel.onClick { dismissAllowingStateLoss() }
        binding.tvAdd.onClick { showEditDialog(null) }
        binding.tvRestoreDefault.onClick {
            engines = ContentSelectConfig.defaultSearchEngines.toMutableList()
            saveAndRefresh()
        }
    }

    private fun renderList() = binding.listContainer.run {
        removeAllViews()
        engines.forEach { engine ->
            addView(engineRow(engine))
        }
    }

    private fun engineRow(engine: ContentSelectConfig.SearchEngine): View {
        val palette = ReaderSheetStyle.resolve(requireContext())
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 10.dpToPx())
            background = UiCorner.panelRounded(
                requireContext(),
                ContextCompat.getColor(requireContext(), R.color.background_card),
                UiCorner.panelRadius(requireContext())
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dpToPx()
            }
            addView(TextView(requireContext()).apply {
                text = engine.name
                textSize = 16f
                maxLines = 1
                setTextColor(palette.textColor)
            })
            addView(TextView(requireContext()).apply {
                text = engine.url
                textSize = 12f
                setTextColor(requireContext().secondaryTextColor)
                setPadding(0, 5.dpToPx(), 0, 6.dpToPx())
            })
            if (!engine.hideCss.isNullOrBlank()) {
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.selection_search_engine_hide_css_enabled)
                    textSize = 12f
                    setTextColor(palette.accentColor)
                    setPadding(0, 0, 0, 6.dpToPx())
                })
            }
            addView(LinearLayout(requireContext()).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                addView(rowButton(getString(R.string.edit)) {
                    showEditDialog(engine)
                })
                addView(rowButton(getString(R.string.delete)) {
                    confirmDelete(engine)
                })
            })
        }
    }

    private fun rowButton(text: String, click: () -> Unit): TextView {
        val palette = ReaderSheetStyle.resolve(requireContext())
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(palette.accentColor)
            setPadding(12.dpToPx(), 7.dpToPx(), 12.dpToPx(), 7.dpToPx())
            background = UiCorner.actionSelector(
                ContextCompat.getColor(requireContext(), R.color.background_menu),
                ContextCompat.getColor(requireContext(), R.color.background_card),
                UiCorner.actionRadius(requireContext())
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 8.dpToPx()
            }
            setOnClickListener { click() }
        }
    }

    private fun showEditDialog(engine: ContentSelectConfig.SearchEngine?) {
        val nameEdit = EditText(requireContext()).apply {
            hint = getString(R.string.selection_search_engine_name)
            setSingleLine(true)
            setText(engine?.name.orEmpty())
        }
        val urlEdit = EditText(requireContext()).apply {
            hint = getString(R.string.selection_search_engine_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(engine?.url.orEmpty())
        }
        val cssEdit = EditText(requireContext()).apply {
            hint = getString(R.string.selection_search_engine_hide_css_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 8
            setSingleLine(false)
            setHorizontallyScrolling(false)
            setText(engine?.hideCss.orEmpty())
        }
        val input = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 0)
            addView(nameEdit)
            addView(urlEdit)
            addView(cssEdit)
        }
        alert {
            setTitle(if (engine == null) R.string.add else R.string.edit)
            customView { input }
            okButton {
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val url = urlEdit.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || url.isBlank()) {
                    toastOnUi(R.string.cannot_empty)
                    return@okButton
                }
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    toastOnUi(R.string.selection_search_engine_url_error)
                    return@okButton
                }
                val edited = ContentSelectConfig.SearchEngine(
                    id = engine?.id ?: "engine_${System.currentTimeMillis()}",
                    name = name,
                    url = url,
                    hideCss = cssEdit.text?.toString()?.trim().orEmpty()
                )
                val index = engines.indexOfFirst { it.id == edited.id }
                if (index >= 0) {
                    engines[index] = edited
                } else {
                    engines += edited
                }
                saveAndRefresh()
            }
            cancelButton()
        }
    }

    private fun confirmDelete(engine: ContentSelectConfig.SearchEngine) {
        alert {
            setTitle(R.string.delete)
            setMessage(engine.name)
            yesButton {
                engines.removeAll { it.id == engine.id }
                if (engines.isEmpty()) {
                    engines = ContentSelectConfig.defaultSearchEngines.toMutableList()
                }
                saveAndRefresh()
            }
            noButton()
        }
    }

    private fun saveAndRefresh() {
        ContentSelectConfig.saveSearchEngines(requireContext(), engines)
        engines = ContentSelectConfig.searchEngines(requireContext()).toMutableList()
        renderList()
        onChanged?.invoke()
    }
}
