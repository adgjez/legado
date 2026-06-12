package io.legado.app.ui.dict.rule

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.databinding.ActivityDictRuleBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.compose.replaceByIndex
import io.legado.app.ui.widget.compose.replaceFirst
import io.legado.app.ui.widget.compose.replaceMatching
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class DictRuleActivity : VMBaseActivity<ActivityDictRuleBinding, DictRuleViewModel>(),
    SelectActionBar.CallBack {

    override val viewModel by viewModels<DictRuleViewModel>()
    override val binding by viewBinding(ActivityDictRuleBinding::inflate)
    private val importRecordKey = "dictRuleUrls"
    private val rulesState = mutableStateListOf<DictRule>()
    private val selectedNames = mutableStateOf<Set<String>>(emptySet())
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportDictRuleDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportDictRuleDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showComposeConfirmDialog(
                title = getString(R.string.export_success),
                message = if (uri.toString().isAbsUrl()) {
                    DirectLinkUpload.getSummary()
                } else {
                    null
                },
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.cancel),
                onPositive = { sendToClip(uri.toString()) }
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        initSelectActionView()
        observeDictRuleData()
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                DictRuleScreen(
                    rules = rulesState,
                    selectedNames = selectedNames.value,
                    onToggleSelection = ::toggleSelection,
                    onToggleEnabled = ::toggleEnabled,
                    onEdit = ::editRule,
                    onDelete = ::deleteRule
                )
            }
        }
        container.addView(cv, index)
    }

    private fun initSelectActionView() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.dict_rule_sel)
        binding.selectActionBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_enable_selection -> {
                    val selected = rulesState.filter { it.name in selectedNames.value }
                    updateSelectedEnabled(enabled = true)
                    viewModel.enableSelection(*selected.toTypedArray())
                }
                R.id.menu_disable_selection -> {
                    val selected = rulesState.filter { it.name in selectedNames.value }
                    updateSelectedEnabled(enabled = false)
                    viewModel.disableSelection(*selected.toTypedArray())
                }
                R.id.menu_export_selection -> {
                    val selected = rulesState.filter { it.name in selectedNames.value }
                    exportResult.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(
                            "exportDictRule.json",
                            GSON.toJson(selected).toByteArray(),
                            "application/json"
                        )
                    }
                }
            }
            true
        }
        binding.selectActionBar.setCallBack(this)
    }

    private fun observeDictRuleData() {
        lifecycleScope.launch {
            appDb.dictRuleDao.flowAll().catch {
                AppLog.put("字典规则获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect { rules ->
                rulesState.replaceByIndex(rules)
                // Clean up selected names for deleted rules
                val currentNames = rules.map { it.name }.toSet()
                selectedNames.value = selectedNames.value.filter { it in currentNames }.toSet()
                upCountView()
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.dict_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> showDialogFragment<DictRuleEditDialog>()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_qr -> qrCodeResult.launch()
            R.id.menu_import_default -> viewModel.importDefault()
            R.id.menu_help -> showHelp("dictRuleHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onClickSelectBarMainAction() {
        val selected = rulesState.filter { it.name in selectedNames.value }
        viewModel.delete(*selected.toTypedArray())
    }

    override fun selectAll(selectAll: Boolean) {
        selectedNames.value = if (selectAll) {
            rulesState.map { it.name }.toSet()
        } else {
            emptySet()
        }
        upCountView()
    }

    override fun revertSelection() {
        val allNames = rulesState.map { it.name }.toSet()
        selectedNames.value = allNames - selectedNames.value
        upCountView()
    }

    private fun toggleSelection(rule: DictRule) {
        selectedNames.value = selectedNames.value.toMutableSet().apply {
            if (contains(rule.name)) remove(rule.name) else add(rule.name)
        }
        upCountView()
    }

    private fun toggleEnabled(rule: DictRule, enabled: Boolean) {
        val updated = rule.copy(enabled = enabled)
        rulesState.replaceFirst({ it.name == rule.name }) { updated }
        viewModel.update(updated)
    }

    private fun updateSelectedEnabled(enabled: Boolean) {
        val names = selectedNames.value
        if (names.isEmpty()) return
        rulesState.replaceMatching({ it.name in names }) { it.copy(enabled = enabled) }
    }

    private fun editRule(rule: DictRule) {
        showDialogFragment(DictRuleEditDialog(rule.name))
    }

    private fun deleteRule(rule: DictRule) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + rule.name,
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { viewModel.delete(rule) }
        )
    }

    private fun upCountView() {
        binding.selectActionBar.upCountView(
            selectedNames.value.size,
            rulesState.size
        )
    }

    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        showComposeTextInputDialog(
            title = getString(R.string.import_on_line),
            hint = "url",
            initialValue = "",
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotBlank()) {
                    if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                        cacheUrls.add(0, text)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportDictRuleDialog(text))
                }
            }
        )
    }
}
