package io.legado.app.ui.rss.source.manage

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ActivityRssSourceBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.compose.replaceByIndex
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.applyTint
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 订阅源管理
 */
class RssSourceActivity : VMBaseActivity<ActivityRssSourceBinding, RssSourceViewModel>(),
    MenuItem.OnMenuItemClickListener,
    SelectActionBar.CallBack {

    override val binding by viewBinding(ActivityRssSourceBinding::inflate)
    override val viewModel by viewModels<RssSourceViewModel>()
    private val importRecordKey = "rssSourceRecordKey"
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var sourceFlowJob: Job? = null
    private var groups = arrayListOf<String>()
    private var groupMenu: SubMenu? = null
    private val sourcesState = mutableStateListOf<RssSource>()
    private val selectedUrls = mutableStateOf<Set<String>>(emptySet())
    private val isSelectMode = mutableStateOf(false)
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportRssSourceDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportRssSourceDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showComposeConfirmDialog(
                title = getString(R.string.export_success),
                message = if (uri.toString().isAbsUrl()) {
                    uri.toString() + "\n" + DirectLinkUpload.getSummary()
                } else {
                    uri.toString()
                },
                positiveText = getString(R.string.copy_text),
                negativeText = getString(R.string.cancel),
                onPositive = { sendToClip(uri.toString()) }
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        initSearchView()
        initGroupFlow()
        upSourceFlow()
        initSelectActionBar()
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
                RssSourceScreen(
                    sources = sourcesState,
                    selectedUrls = selectedUrls.value,
                    isSelectMode = isSelectMode.value,
                    onToggleSelect = ::toggleSourceSelection,
                    onToggleEnabled = ::toggleSourceEnabled,
                    onEdit = ::editSource,
                    onShowMenu = ::showSourceMenu
                )
            }
        }
        container.addView(cv, index)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_source, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> startActivity<RssSourceEditActivity>()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_qr -> qrCodeResult.launch()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_import_default -> viewModel.importDefault()
            R.id.menu_enabled_group -> {
                searchView.setQuery(getString(R.string.enabled), true)
            }

            R.id.menu_disabled_group -> {
                searchView.setQuery(getString(R.string.disabled), true)
            }

            R.id.menu_group_login -> {
                searchView.setQuery(getString(R.string.need_login), true)
            }

            R.id.menu_group_null -> {
                searchView.setQuery(getString(R.string.no_group), true)
            }

            R.id.menu_help -> showHelp("SourceMRssHelp")
            else -> if (item.groupId == R.id.source_group) {
                searchView.setQuery("group:${item.title}", true)
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initSearchView() {
        binding.titleBar.findViewById<SearchView>(R.id.search_view).let {
            it.applyTint(primaryTextColor)
            it.onActionViewExpanded()
            it.queryHint = getString(R.string.search_rss_source)
            it.clearFocus()
            it.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    return false
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    upSourceFlow(newText)
                    return false
                }
            })
        }
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.rss_source_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun initGroupFlow() {
        lifecycleScope.launch {
            appDb.rssSourceDao.flowGroups().conflate().collect {
                groups.clear()
                groups.addAll(it)
                upGroupMenu()
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_selection -> {
                val selection = getSelectedSources()
                updateSelectedEnabled(enabled = true)
                viewModel.enableSelection(selection)
            }
            R.id.menu_disable_selection -> {
                val selection = getSelectedSources()
                updateSelectedEnabled(enabled = false)
                viewModel.disableSelection(selection)
            }
            R.id.menu_add_group -> selectionAddToGroups()
            R.id.menu_remove_group -> selectionRemoveFromGroups()
            R.id.menu_top_sel -> {
                val selection = getSelectedSources()
                viewModel.topSource(*selection.toTypedArray())
            }
            R.id.menu_bottom_sel -> {
                val selection = getSelectedSources()
                viewModel.bottomSource(*selection.toTypedArray())
            }
            R.id.menu_export_selection -> {
                val selection = getSelectedSources()
                viewModel.saveToFile(selection) { file, name ->
                    exportResult.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(
                            name, file, "application/json"
                        )
                    }
                }
            }
            R.id.menu_share_source -> {
                val selection = getSelectedSources()
                viewModel.saveToFile(selection) { file, _ ->
                    share(file)
                }
            }
            R.id.menu_check_selected_interval -> checkSelectedInterval()
        }
        return true
    }

    private fun selectionAddToGroups() {
        showComposeTextInputDialog(
            title = getString(R.string.add_group),
            hint = getString(R.string.group_name),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotEmpty()) {
                    val selection = getSelectedSources()
                    viewModel.selectionAddToGroups(selection, text)
                }
            }
        )
    }

    private fun selectionRemoveFromGroups() {
        showComposeTextInputDialog(
            title = getString(R.string.remove_group),
            hint = getString(R.string.group_name),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotEmpty()) {
                    val selection = getSelectedSources()
                    viewModel.selectionRemoveFromGroups(selection, text)
                }
            }
        )
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedUrls.value = sourcesState.map { it.sourceUrl }.toSet()
        } else {
            selectedUrls.value = emptySet()
        }
        isSelectMode.value = selectedUrls.value.isNotEmpty()
        upCountView()
    }

    override fun revertSelection() {
        val allUrls = sourcesState.map { it.sourceUrl }.toSet()
        selectedUrls.value = allUrls - selectedUrls.value
        isSelectMode.value = selectedUrls.value.isNotEmpty()
        upCountView()
    }

    override fun onClickSelectBarMainAction() {
        delSourceDialog()
    }

    private fun delSourceDialog() {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                val selection = getSelectedSources()
                viewModel.del(*selection.toTypedArray())
                selectedUrls.value = emptySet()
                isSelectMode.value = false
            }
        )
    }

    private fun upGroupMenu() = groupMenu?.transaction { menu ->
        menu.removeGroup(R.id.source_group)
        groups.forEach {
            menu.add(R.id.source_group, Menu.NONE, Menu.NONE, it)
        }
    }

    private fun upSourceFlow(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> {
                    appDb.rssSourceDao.flowAll()
                }

                searchKey == getString(R.string.enabled) -> {
                    appDb.rssSourceDao.flowEnabled()
                }

                searchKey == getString(R.string.disabled) -> {
                    appDb.rssSourceDao.flowDisabled()
                }

                searchKey == getString(R.string.need_login) -> {
                    appDb.rssSourceDao.flowLogin()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.rssSourceDao.flowNoGroup()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.rssSourceDao.flowGroupSearch(key)
                }

                else -> {
                    appDb.rssSourceDao.flowSearch(searchKey)
                }
            }.catch {
                AppLog.put("订阅源管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect {
                sourcesState.replaceByIndex(it)
                val currentUrls = it.mapTo(mutableSetOf()) { source -> source.sourceUrl }
                selectedUrls.value = selectedUrls.value.filter { url -> url in currentUrls }.toSet()
                isSelectMode.value = selectedUrls.value.isNotEmpty()
                upCountView()
                delay(100)
            }
        }
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
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotBlank()) {
                    if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                        cacheUrls.add(0, text)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportRssSourceDialog(text))
                }
            }
        )
    }

    private fun toggleSourceSelection(source: RssSource) {
        val current = selectedUrls.value.toMutableSet()
        if (source.sourceUrl in current) {
            current.remove(source.sourceUrl)
        } else {
            current.add(source.sourceUrl)
        }
        selectedUrls.value = current
        isSelectMode.value = current.isNotEmpty()
        upCountView()
    }

    private fun toggleSourceEnabled(source: RssSource, enabled: Boolean) {
        val updated = source.copy(enabled = enabled)
        val index = sourcesState.indexOfFirst { it.sourceUrl == source.sourceUrl }
        if (index >= 0) {
            sourcesState[index] = updated
        }
        viewModel.update(updated)
    }

    private fun updateSelectedEnabled(enabled: Boolean) {
        val urls = selectedUrls.value
        if (urls.isEmpty()) return
        sourcesState.indices.forEach { index ->
            val source = sourcesState[index]
            if (source.sourceUrl in urls && source.enabled != enabled) {
                sourcesState[index] = source.copy(enabled = enabled)
            }
        }
    }

    private fun editSource(source: RssSource) {
        startActivity<RssSourceEditActivity> {
            putExtra("sourceUrl", source.sourceUrl)
        }
    }

    private fun showSourceMenu(source: RssSource) {
        val labels = listOf(
            getString(R.string.selection_to_top),
            getString(R.string.selection_to_bottom),
            getString(R.string.delete)
        )
        showComposeActionListDialog(
            title = source.sourceName,
            labels = labels,
            dangerIndices = setOf(2),
            onSelected = { index ->
                when (index) {
                    0 -> viewModel.topSource(source)
                    1 -> viewModel.bottomSource(source)
                    2 -> {
                        showComposeConfirmDialog(
                            title = getString(R.string.draw),
                            message = getString(R.string.sure_del) + "\n" + source.sourceName,
                            positiveText = getString(R.string.yes),
                            negativeText = getString(R.string.no),
                            dangerPositive = true,
                            onPositive = { viewModel.del(source) }
                        )
                    }
                }
            }
        )
    }

    private fun getSelectedSources(): List<RssSource> {
        val urls = selectedUrls.value
        return sourcesState.filter { it.sourceUrl in urls }
    }

    private fun checkSelectedInterval() {
        val urls = selectedUrls.value
        val positions = mutableListOf<Int>()
        sourcesState.forEachIndexed { index, source ->
            if (source.sourceUrl in urls) {
                positions.add(index)
            }
        }
        if (positions.isEmpty()) return
        val minPos = positions.min()
        val maxPos = positions.max()
        val newSelected = urls.toMutableSet()
        for (i in minPos..maxPos) {
            newSelected.add(sourcesState[i].sourceUrl)
        }
        selectedUrls.value = newSelected
        upCountView()
    }

    private fun upCountView() {
        binding.selectActionBar.upCountView(
            getSelectedSources().size,
            sourcesState.size
        )
    }

}
