package io.legado.app.ui.config

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityAiProviderEditBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.ItemS3ContainerBinding
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.main.ai.AI_API_MODE_CHAT_COMPLETIONS
import io.legado.app.ui.main.ai.AI_API_MODE_RESPONSES
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiProviderEditActivity : BaseActivity<ActivityAiProviderEditBinding>() {

    override val binding by viewBinding(ActivityAiProviderEditBinding::inflate)
    private val modelAdapter by lazy { ModelAdapter() }
    private val waitDialog by lazy { WaitDialog(this) }
    private var providerId: String? = null
    private var apiMode: String = AI_API_MODE_CHAT_COMPLETIONS
    private var currentTab: String = TAB_CONFIG

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        val provider = currentProvider()
        apiMode = normalizeApiMode(provider?.apiMode)
        bindProvider(provider)
        initViews()
        switchTab(TAB_CONFIG)
    }

    override fun onDestroy() {
        super.onDestroy()
        waitDialog.dismiss()
    }

    private fun initViews() {
        binding.run {
        modelRecyclerView.layoutManager = LinearLayoutManager(this@AiProviderEditActivity)
        modelRecyclerView.adapter = modelAdapter
        (modelRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        tvModelSummary.applyUiLabelStyle(this@AiProviderEditActivity)
        tvModelSummary.setTextColor(secondaryTextColor)
        listOf(tvApiMode, btnSave, btnAddModel, btnFetchModels).forEach { it.background = actionBackground() }
        tvApiMode.setOnClickListener { showApiModeSelector() }
        btnProviderConfig.setOnClickListener { switchTab(TAB_CONFIG) }
        btnModelManage.setOnClickListener { switchTab(TAB_MODEL) }
        btnSave.setOnClickListener { saveProvider(showToast = true) }
        btnAddModel.setOnClickListener { showEditModelDialog() }
        btnFetchModels.setOnClickListener { fetchModels() }
        renderApiMode()
            reloadModels()
        }
    }

    private fun bindProvider(provider: AiProviderConfig?) {
        binding.run {
            editProviderName.setText(provider?.name.orEmpty())
            editProviderBaseUrl.setText(provider?.baseUrl.orEmpty())
            editProviderApiKey.setText(provider?.apiKey.orEmpty())
            editProviderHeaders.setText(provider?.headers.orEmpty())
            cbPromptCache.isChecked = provider?.promptCache ?: false
        }
    }

    private fun switchTab(tab: String) {
        binding.run {
            currentTab = tab
            val isModel = tab == TAB_MODEL
            providerConfigContainer.isVisible = !isModel
            modelManageContainer.isVisible = isModel
            btnSave.isVisible = !isModel
            modelActionBar.isVisible = isModel
            btnProviderConfig.isSelected = !isModel
            btnModelManage.isSelected = isModel
            btnProviderConfig.setTextColor(if (!isModel) accentColor else primaryTextColor)
            btnModelManage.setTextColor(if (isModel) accentColor else primaryTextColor)
            if (isModel) {
                if (currentProviderOrSave() == null) {
                    switchTab(TAB_CONFIG)
                } else {
                    reloadModels()
                }
            }
        }
    }

    private fun showApiModeSelector() {
        val modes = listOf(AI_API_MODE_CHAT_COMPLETIONS, AI_API_MODE_RESPONSES)
        val labels = listOf(getString(R.string.ai_provider_mode_chat), getString(R.string.ai_provider_mode_responses))
        showDialogFragment(
            ComposeActionListDialog.create(
                title = getString(R.string.ai_api_mode),
                labels = labels,
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    modes.getOrNull(index)?.let { selectedMode ->
                        apiMode = selectedMode
                        renderApiMode()
                    }
                }
            )
        )
    }

    private fun renderApiMode() {
        binding.tvApiMode.text = "${getString(R.string.ai_api_mode)}: " + getString(
            if (apiMode == AI_API_MODE_RESPONSES) R.string.ai_provider_mode_responses
            else R.string.ai_provider_mode_chat
        )
    }

    private fun saveProvider(showToast: Boolean): AiProviderConfig? {
        val name = binding.editProviderName.text?.toString()?.trim().orEmpty()
        val baseUrl = binding.editProviderBaseUrl.text?.toString()?.trim().orEmpty()
        val apiKey = binding.editProviderApiKey.text?.toString()?.trim().orEmpty()
        val headers = binding.editProviderHeaders.text?.toString()?.trim().orEmpty()
        when {
            name.isEmpty() -> {
                toastOnUi(R.string.ai_provider_name_required)
                return null
            }
            baseUrl.isEmpty() -> {
                toastOnUi(R.string.ai_provider_url_required)
                return null
            }
        }
        val providers = AppConfig.aiProviderList.toMutableList()
        val oldProvider = currentProvider()
        val updated = oldProvider?.copy(
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            headers = headers,
            apiMode = apiMode,
            promptCache = binding.cbPromptCache.isChecked
        ) ?: AiProviderConfig(
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            headers = headers,
            apiMode = apiMode,
            promptCache = binding.cbPromptCache.isChecked
        )
        val index = providers.indexOfFirst { it.id == updated.id }
        if (index >= 0) providers[index] = updated else providers.add(updated)
        AppConfig.aiProviderList = providers
        providerId = updated.id
        notifyAiConfigChanged()
        if (showToast) toastOnUi(R.string.ai_provider_saved)
        reloadModels()
        return updated
    }

    private fun currentProviderOrSave(): AiProviderConfig? = currentProvider() ?: saveProvider(showToast = false)

    private fun currentProvider(): AiProviderConfig? {
        val id = providerId ?: return null
        return AppConfig.aiProviderList.firstOrNull { it.id == id }
    }

    private fun reloadModels() {
        val provider = currentProvider()
        val models = provider?.let { p -> AppConfig.aiModelConfigList.filter { it.providerId == p.id } }.orEmpty()
        binding.tvModelSummary.text = if (provider == null) {
            getString(R.string.ai_current_provider_summary_empty)
        } else {
            "${provider.name} · ${getString(R.string.ai_manage_models_summary, models.size)}"
        }
        modelAdapter.setItems(models)
    }

    private fun showEditModelDialog(model: AiModelConfig? = null) {
        val provider = currentProviderOrSave() ?: return
        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_model_input_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT
            editView.setText(model?.modelId.orEmpty())
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(title = getString(if (model == null) R.string.ai_add_model else R.string.ai_edit_model)) {
            customView { dialogBinding.root }
            okButton {
                saveModel(provider.id, model, dialogBinding.editView.text?.toString().orEmpty())
            }
            cancelButton()
        }
    }

    private fun saveModel(providerId: String, oldModel: AiModelConfig?, modelId: String) {
        val trimmedModelId = modelId.trim()
        if (trimmedModelId.isEmpty()) return
        val models = AppConfig.aiModelConfigList.toMutableList()
        val exists = models.any { it.providerId == providerId && it.modelId == trimmedModelId && it.id != oldModel?.id }
        if (exists) {
            toastOnUi(R.string.ai_model_exists)
            return
        }
        val updated = oldModel?.copy(providerId = providerId, modelId = trimmedModelId)
            ?: AiModelConfig(providerId = providerId, modelId = trimmedModelId)
        val index = models.indexOfFirst { it.id == updated.id }
        if (index >= 0) models[index] = updated else models.add(updated)
        AppConfig.aiModelConfigList = models
        AppConfig.aiCurrentModelId = updated.id
        notifyAiConfigChanged()
        reloadModels()
        toastOnUi(if (oldModel == null) R.string.ai_model_added else R.string.ai_model_saved)
    }

    private fun showModelActions(model: AiModelConfig) {
        val actions = listOf(getString(R.string.ai_set_current), getString(R.string.edit), getString(R.string.delete))
        showDialogFragment(
            ComposeActionListDialog.create(
                title = model.modelId,
                labels = actions,
                dangerIndices = setOf(2),
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    when (index) {
                        0 -> {
                            AppConfig.aiCurrentModelId = model.id
                            notifyAiConfigChanged()
                            reloadModels()
                        }
                        1 -> showEditModelDialog(model)
                        2 -> confirmRemoveModel(model)
                    }
                }
            )
        )
    }

    private fun confirmRemoveModel(model: AiModelConfig) {
        alert(title = model.modelId, message = getString(R.string.ai_remove_model_confirm)) {
            okButton {
                AppConfig.aiModelConfigList = AppConfig.aiModelConfigList.filterNot { it.id == model.id }
                notifyAiConfigChanged()
                reloadModels()
                toastOnUi(R.string.ai_model_removed)
            }
            cancelButton()
        }
    }

    private fun fetchModels() {
        val provider = currentProviderOrSave() ?: return
        waitDialog.setText(R.string.loading)
        waitDialog.show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AiChatService.fetchModels(provider) }
            }
            waitDialog.dismiss()
            result.onSuccess { modelIds ->
                if (modelIds.isEmpty()) toastOnUi(R.string.ai_fetch_models_empty)
                else showFetchedModelSelector(provider.id, modelIds)
            }.onFailure {
                toastOnUi(getString(R.string.ai_fetch_models_failed, it.localizedMessage ?: "Error"))
            }
        }
    }

    private fun showFetchedModelSelector(providerId: String, modelIds: List<String>) {
        val fetchedModelIds = modelIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (fetchedModelIds.isEmpty()) {
            toastOnUi(R.string.ai_fetch_models_empty)
            return
        }
        val existingIds = AppConfig.aiModelConfigList
            .filter { it.providerId == providerId }
            .map { it.modelId }
            .toSet()
        val selectedIds = linkedSetOf<String>()
        var selectionMode = false
        var filteredModelIds = fetchedModelIds
        var dialog: AlertDialog? = null
        lateinit var adapter: RecyclerView.Adapter<FetchedModelViewHolder>
        lateinit var summaryView: TextView
        lateinit var addSelectedButton: TextView

        fun refreshSelectionState() {
            summaryView.text = if (selectionMode) {
                getString(R.string.ai_fetch_models_selected_hint, selectedIds.size)
            } else {
                getString(R.string.ai_fetch_models_long_press_hint)
            }
            addSelectedButton.isEnabled = selectedIds.isNotEmpty()
            addSelectedButton.alpha = if (selectedIds.isNotEmpty()) 1f else 0.45f
            addSelectedButton.text = if (selectedIds.isEmpty()) {
                getString(R.string.ai_fetch_models_add_selected_empty)
            } else {
                getString(R.string.ai_fetch_models_add_selected, selectedIds.size)
            }
        }

        fun toggleSelection(modelId: String) {
            selectionMode = true
            if (!selectedIds.add(modelId)) {
                selectedIds.remove(modelId)
            }
            refreshSelectionState()
            adapter.notifyDataSetChanged()
        }

        adapter = object : RecyclerView.Adapter<FetchedModelViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FetchedModelViewHolder {
                return FetchedModelViewHolder(parent)
            }

            override fun getItemCount(): Int = filteredModelIds.size

            override fun onBindViewHolder(holder: FetchedModelViewHolder, position: Int) {
                val modelId = filteredModelIds[position]
                holder.bind(
                    modelId = modelId,
                    existing = modelId in existingIds,
                    selected = modelId in selectedIds,
                    selectionMode = selectionMode,
                    onClick = {
                        if (selectionMode) {
                            toggleSelection(modelId)
                        } else {
                            dialog?.dismiss()
                            appendFetchedModels(providerId, listOf(modelId))
                        }
                    },
                    onLongClick = {
                        toggleSelection(modelId)
                        true
                    }
                )
            }
        }

        val searchView = SearchView(this).apply {
            queryHint = getString(R.string.screen_find)
            isIconified = false
            isSubmitButtonEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = UiCorner.searchRadius(10f)
                setColor(ContextCompat.getColor(this@AiProviderEditActivity, R.color.background_menu))
            }
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true

                override fun onQueryTextChange(newText: String?): Boolean {
                    val key = newText.orEmpty().trim()
                    filteredModelIds = if (key.isBlank()) {
                        fetchedModelIds
                    } else {
                        fetchedModelIds.filter { it.contains(key, ignoreCase = true) }
                    }
                    adapter.notifyDataSetChanged()
                    return true
                }
            })
        }

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AiProviderEditActivity)
            this.adapter = adapter
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                360.dpToPx()
            ).apply {
                topMargin = 10.dpToPx()
            }
        }

        summaryView = TextView(this).apply {
            applyUiLabelStyle(this@AiProviderEditActivity)
            setTextColor(secondaryTextColor)
            textSize = 13f
            setPadding(2.dpToPx(), 8.dpToPx(), 2.dpToPx(), 0)
        }

        val addAllButton = TextView(this).apply {
            text = getString(R.string.ai_add_all_models)
            gravity = Gravity.CENTER
            setTextColor(primaryTextColor)
            textSize = 14f
            typeface = this@AiProviderEditActivity.uiTypeface()
            background = actionBackground()
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
            setOnClickListener {
                dialog?.dismiss()
                appendFetchedModels(providerId, fetchedModelIds)
            }
        }

        addSelectedButton = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(primaryTextColor)
            textSize = 14f
            typeface = this@AiProviderEditActivity.uiTypeface()
            background = actionBackground()
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
            setOnClickListener {
                if (selectedIds.isEmpty()) return@setOnClickListener
                dialog?.dismiss()
                appendFetchedModels(providerId, selectedIds.toList())
            }
        }

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(addAllButton, LinearLayout.LayoutParams(0, 44.dpToPx(), 1f))
            addView(addSelectedButton, LinearLayout.LayoutParams(0, 44.dpToPx(), 1f).apply {
                marginStart = 10.dpToPx()
            })
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiCorner.opaqueRounded(
                ContextCompat.getColor(this@AiProviderEditActivity, R.color.background_card),
                UiCorner.panelRadius(this@AiProviderEditActivity)
            )
            setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 12.dpToPx())
            addView(TextView(this@AiProviderEditActivity).apply {
                text = getString(R.string.ai_add_model_from_list)
                applyUiSectionTitleStyle(this@AiProviderEditActivity)
                textSize = 18f
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(2.dpToPx(), 0, 2.dpToPx(), 12.dpToPx())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 32.dpToPx()))
            addView(searchView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 42.dpToPx()))
            addView(summaryView)
            addView(recyclerView)
            addView(actionBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dpToPx()).apply {
                topMargin = 10.dpToPx()
            })
        }
        refreshSelectionState()
        dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun appendFetchedModels(providerId: String, modelIds: List<String>) {
        val oldModels = AppConfig.aiModelConfigList
        val existingIds = oldModels.filter { it.providerId == providerId }.map { it.modelId }.toSet()
        val newModels = modelIds.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot { it in existingIds }
            .map { AiModelConfig(providerId = providerId, modelId = it) }
        if (newModels.isEmpty()) {
            toastOnUi(R.string.ai_fetch_models_no_new)
            return
        }
        AppConfig.aiModelConfigList = oldModels + newModels
        if (AppConfig.aiCurrentModelId.isNullOrBlank()) {
            AppConfig.aiCurrentModelId = newModels.first().id
        }
        notifyAiConfigChanged()
        reloadModels()
        toastOnUi(getString(R.string.ai_fetch_models_success, newModels.size))
    }

    private fun notifyAiConfigChanged() {
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
    }

    private fun normalizeApiMode(value: String?): String {
        return if (value?.trim() == AI_API_MODE_RESPONSES) AI_API_MODE_RESPONSES else AI_API_MODE_CHAT_COMPLETIONS
    }

    private fun actionBackground() = UiCorner.actionSelector(
        ContextCompat.getColor(this, R.color.background_card),
        ContextCompat.getColor(this, R.color.background_menu),
        UiCorner.actionRadius(this)
    )

    private inner class FetchedModelViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LinearLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4.dpToPx()
                bottomMargin = 4.dpToPx()
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 54.dpToPx()
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
        }
    ) {
        private val checkBox = CheckBox(parent.context).apply {
            isClickable = false
            isFocusable = false
        }
        private val titleView = TextView(parent.context).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            applyUiSectionTitleStyle(this@AiProviderEditActivity)
            textSize = 15f
        }
        private val stateView = TextView(parent.context).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            applyUiLabelStyle(this@AiProviderEditActivity)
            textSize = 12f
        }

        init {
            (itemView as LinearLayout).apply {
                addView(checkBox, LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(titleView, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                    addView(stateView, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 4.dpToPx()
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        fun bind(
            modelId: String,
            existing: Boolean,
            selected: Boolean,
            selectionMode: Boolean,
            onClick: () -> Unit,
            onLongClick: () -> Boolean
        ) {
            val fillColor = if (selected) {
                ContextCompat.getColor(this@AiProviderEditActivity, R.color.background_menu)
            } else {
                Color.TRANSPARENT
            }
            itemView.background = UiCorner.actionSelector(
                fillColor,
                ContextCompat.getColor(this@AiProviderEditActivity, R.color.background_menu),
                UiCorner.actionRadius(this@AiProviderEditActivity)
            )
            checkBox.isVisible = selectionMode
            checkBox.isChecked = selected
            titleView.text = modelId
            stateView.text = when {
                existing -> getString(R.string.ai_fetch_models_existing)
                selectionMode -> getString(R.string.ai_fetch_models_click_toggle)
                else -> getString(R.string.ai_fetch_models_click_add_long_select)
            }
            stateView.setTextColor(if (existing) accentColor else secondaryTextColor)
            itemView.setOnClickListener { onClick() }
            itemView.setOnLongClickListener { onLongClick() }
        }
    }

    private inner class ModelAdapter :
        RecyclerAdapter<AiModelConfig, ItemS3ContainerBinding>(this@AiProviderEditActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemS3ContainerBinding {
            return ItemS3ContainerBinding.inflate(inflater, parent, false).apply {
                root.background = UiCorner.panelRounded(
                    root.context,
                    ContextCompat.getColor(root.context, R.color.background_card),
                    UiCorner.panelRadius(root.context)
                )
                btnMore.background = actionBackground()
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemS3ContainerBinding,
            item: AiModelConfig,
            payloads: MutableList<Any>
        ) = binding.run {
            val provider = currentProvider()
            val isCurrent = item.id == AppConfig.aiCurrentModelId
            tvName.text = item.modelId
            tvPath.text = provider?.name.orEmpty()
            tvCapacity.text = provider?.baseUrl.orEmpty()
            tvState.text = getString(if (isCurrent) R.string.ai_current_model else R.string.ai_model)
            tvSelected.visibility = if (isCurrent) View.VISIBLE else View.GONE
            tvSelected.setTextColor(ContextCompat.getColor(this@AiProviderEditActivity, R.color.accent))
            tvName.applyUiSectionTitleStyle(this@AiProviderEditActivity)
            tvPath.applyUiLabelStyle(this@AiProviderEditActivity)
            tvCapacity.applyUiLabelStyle(this@AiProviderEditActivity)
            tvState.applyUiLabelStyle(this@AiProviderEditActivity)
            listOf(tvPath, tvCapacity, tvState).forEach { it.setTextColor(secondaryTextColor) }
            btnMore.setOnClickListener { showModelActions(item) }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemS3ContainerBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.bindingAdapterPosition - getHeaderCount())?.let { showModelActions(it) }
            }
        }
    }

    companion object {
        const val EXTRA_PROVIDER_ID = "providerId"
        private const val TAB_CONFIG = "config"
        private const val TAB_MODEL = "model"
    }
}
