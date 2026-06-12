package io.legado.app.ui.book.source.manage

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ActivityBookSourceBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.config.CheckSourceConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 书源管理界面
 */
class BookSourceActivity : VMBaseActivity<ActivityBookSourceBinding, BookSourceViewModel>(),
    MenuItem.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    SearchView.OnQueryTextListener {
    override val binding by viewBinding(ActivityBookSourceBinding::inflate)
    override val viewModel by viewModels<BookSourceViewModel>()
    private val importRecordKey = "bookSourceRecordKey"
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var sourceFlowJob: Job? = null
    private var checkMessageRefreshJob: Job? = null
    private val groups = linkedSetOf<String>()
    private var groupMenu: SubMenu? = null
    private var sort = BookSourceSort.Default
        private set
    private var sortAscending = true
        private set
    private var snackBar: Snackbar? = null
    private var groupSourcesByDomain = false
    private val hostMap = hashMapOf<String, String>()
    private val finalMessageRegex = Regex("成功|失败")
    private val sourcesState = mutableStateOf<List<BookSourcePart>>(emptyList())
    private val selectedUrls = mutableStateOf<Set<String>>(emptySet())
    private val isSelectMode = mutableStateOf(false)
    private val showSourceHostState = mutableStateOf(false)
    private val sourceHostHeaders = mutableStateMapOf<String, String?>()
    private val debugMessagesState = mutableStateMapOf<String, String>()
    private val isCheckingState = mutableStateOf(Debug.isChecking)
    private val qrResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportBookSourceDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportBookSourceDialog(uri.toString()))
        }
    }
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(uri.toString())
                }
            }
        }
    }
    private val groupMenuLifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun onMenuOpened() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun onMenuClosed() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }

    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        initSearchView()
        upBookSource()
        initLiveDataGroup()
        initSelectActionBar()
        resumeCheckSource()
        if (!LocalConfig.bookSourcesHelpVersionIsLast) {
            showHelp("SourceMBookHelp")
        }
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
                BookSourceScreen(
                    sources = sourcesState.value,
                    selectedUrls = selectedUrls.value,
                    isSelectMode = isSelectMode.value,
                    showSourceHost = showSourceHostState.value,
                    sourceHostHeaders = sourceHostHeaders,
                    debugMessages = debugMessagesState,
                    isChecking = isCheckingState.value,
                    onToggleSelect = ::toggleSourceSelection,
                    onToggleEnabled = ::toggleSourceEnabled,
                    onEdit = ::edit,
                    onShowMenu = ::showSourceMenu
                )
            }
        }
        container.addView(cv, index)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_source, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_group).subMenu
        val sortSubMenu = menu.findItem(R.id.action_sort).subMenu!!
        sortSubMenu.findItem(R.id.menu_sort_desc).isChecked = !sortAscending
        sortSubMenu.setGroupCheckable(R.id.menu_group_sort, true, true)
        upGroupMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_book_source -> startActivity<BookSourceEditActivity>()
            R.id.menu_import_qr -> qrResult.launch()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> showImportDialog()

            R.id.menu_sort_desc -> {
                sortAscending = !sortAscending
                item.isChecked = !sortAscending
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_manual -> {
                item.isChecked = true
                sort = BookSourceSort.Default
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_auto -> {
                item.isChecked = true
                sort = BookSourceSort.Weight
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_name -> {
                item.isChecked = true
                sort = BookSourceSort.Name
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_url -> {
                item.isChecked = true
                sort = BookSourceSort.Url
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_time -> {
                item.isChecked = true
                sort = BookSourceSort.Update
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_respondTime -> {
                item.isChecked = true
                sort = BookSourceSort.Respond
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_enable -> {
                item.isChecked = true
                sort = BookSourceSort.Enable
                upBookSource(searchView.query?.toString())
            }

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

            R.id.menu_enabled_explore_group -> {
                searchView.setQuery(getString(R.string.enabled_explore), true)
            }

            R.id.menu_disabled_explore_group -> {
                searchView.setQuery(getString(R.string.disabled_explore), true)
            }

            R.id.menu_group_sources_by_domain -> {
                item.isChecked = !item.isChecked
                groupSourcesByDomain = item.isChecked
                showSourceHostState.value = item.isChecked
                updateSourceHostHeaders(sourcesState.value)
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_help -> showHelp("SourceMBookHelp")
        }
        if (item.groupId == R.id.source_group) {
            searchView.setQuery("group:${item.title}", true)
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.search_book_source)
        searchView.setOnQueryTextListener(this)
    }


    private fun upBookSource(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.bookSourceDao.flowAll()
                }

                searchKey == getString(R.string.enabled) -> {
                    appDb.bookSourceDao.flowEnabled()
                }

                searchKey == getString(R.string.disabled) -> {
                    appDb.bookSourceDao.flowDisabled()
                }

                searchKey == getString(R.string.need_login) -> {
                    appDb.bookSourceDao.flowLogin()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.bookSourceDao.flowNoGroup()
                }

                searchKey == getString(R.string.enabled_explore) -> {
                    appDb.bookSourceDao.flowEnabledExplore()
                }

                searchKey == getString(R.string.disabled_explore) -> {
                    appDb.bookSourceDao.flowDisabledExplore()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.bookSourceDao.flowGroupSearch(key)
                }

                else -> {
                    appDb.bookSourceDao.flowSearch(searchKey)
                }
            }.map { data ->
                hostMap.clear()
                if (groupSourcesByDomain) {
                    data.sortedWith(
                        compareBy<BookSourcePart> { getSourceHost(it.bookSourceUrl) == "#" }
                            .thenBy { getSourceHost(it.bookSourceUrl) }
                            .thenByDescending { it.lastUpdateTime })
                } else if (sortAscending) {
                    when (sort) {
                        BookSourceSort.Weight -> data.sortedBy { it.weight }
                        BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                            o1.bookSourceName.cnCompare(o2.bookSourceName)
                        }

                        BookSourceSort.Url -> data.sortedBy { it.bookSourceUrl }
                        BookSourceSort.Update -> data.sortedByDescending { it.lastUpdateTime }
                        BookSourceSort.Respond -> data.sortedBy { it.respondTime }
                        BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                            var sort = -o1.enabled.compareTo(o2.enabled)
                            if (sort == 0) {
                                sort = o1.bookSourceName.cnCompare(o2.bookSourceName)
                            }
                            sort
                        }

                        else -> data
                    }
                } else {
                    when (sort) {
                        BookSourceSort.Weight -> data.sortedByDescending { it.weight }
                        BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                            o2.bookSourceName.cnCompare(o1.bookSourceName)
                        }

                        BookSourceSort.Url -> data.sortedByDescending { it.bookSourceUrl }
                        BookSourceSort.Update -> data.sortedBy { it.lastUpdateTime }
                        BookSourceSort.Respond -> data.sortedByDescending { it.respondTime }
                        BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                            var sort = o1.enabled.compareTo(o2.enabled)
                            if (sort == 0) {
                                sort = o1.bookSourceName.cnCompare(o2.bookSourceName)
                            }
                            sort
                        }

                        else -> data.reversed()
                    }
                }
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle,
                table = AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("书源界面更新书源出错", it)
            }.flowOn(IO).conflate().collect { data ->
                sourcesState.value = data
                val currentUrls = data.mapTo(mutableSetOf()) { it.bookSourceUrl }
                selectedUrls.value = selectedUrls.value.filter { it in currentUrls }.toSet()
                isSelectMode.value = selectedUrls.value.isNotEmpty()
                updateSourceHostHeaders(data)
                refreshDebugMessages()
                upCountView()
                delay(500)
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }


    private fun initLiveDataGroup() {
        lifecycleScope.launch {
            appDb.bookSourceDao.flowGroups()
                .flowWithLifecycleAndDatabaseChange(
                    lifecycle,
                    table = AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .flowWithLifecycleAndDatabaseChangeFirst(
                    groupMenuLifecycleOwner.lifecycle,
                    table = AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups.clear()
                    groups.addAll(it)
                    upGroupMenu()
                    delay(500)
                }
        }
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedUrls.value = sourcesState.value.map { it.bookSourceUrl }.toSet()
        } else {
            selectedUrls.value = emptySet()
        }
        isSelectMode.value = selectedUrls.value.isNotEmpty()
        upCountView()
    }

    override fun revertSelection() {
        val allUrls = sourcesState.value.map { it.bookSourceUrl }.toSet()
        selectedUrls.value = allUrls - selectedUrls.value
        isSelectMode.value = selectedUrls.value.isNotEmpty()
        upCountView()
    }

    override fun onClickSelectBarMainAction() {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                viewModel.del(getSelectedSources())
                selectedUrls.value = emptySet()
                isSelectMode.value = false
                upCountView()
            }
        )
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        if (menu === groupMenu) {
            groupMenuLifecycleOwner.onMenuOpened()
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onPanelClosed(featureId: Int, menu: Menu) {
        super.onPanelClosed(featureId, menu)
        if (menu === groupMenu) {
            groupMenuLifecycleOwner.onMenuClosed()
        }
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.book_source_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_selection -> {
                val selection = getSelectedSources()
                updateSelectedEnabled(true)
                viewModel.enableSelection(selection)
            }
            R.id.menu_disable_selection -> {
                val selection = getSelectedSources()
                updateSelectedEnabled(false)
                viewModel.disableSelection(selection)
            }
            R.id.menu_enable_explore -> {
                val selection = getSelectedSources()
                updateSelectedExplore(true)
                viewModel.enableSelectExplore(selection)
            }
            R.id.menu_disable_explore -> {
                val selection = getSelectedSources()
                updateSelectedExplore(false)
                viewModel.disableSelectExplore(selection)
            }
            R.id.menu_check_source -> checkSource()
            R.id.menu_top_sel -> viewModel.topSource(*getSelectedSources().toTypedArray())
            R.id.menu_bottom_sel -> viewModel.bottomSource(*getSelectedSources().toTypedArray())
            R.id.menu_add_group -> selectionAddToGroups()
            R.id.menu_remove_group -> selectionRemoveFromGroups()
            R.id.menu_export_selection -> viewModel.saveToFile(
                getSelectedSources(),
                sourcesState.value.size,
                searchView.query?.toString(),
                sortAscending,
                sort
            ) { file, name ->
                exportDir.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        name,
                        file,
                        "application/json"
                    )
                }
            }

            R.id.menu_share_source -> viewModel.saveToFile(
                getSelectedSources(),
                sourcesState.value.size,
                searchView.query?.toString(),
                sortAscending,
                sort
            ) { file, name ->
                share(file)
            }

            R.id.menu_check_selected_interval -> checkSelectedInterval()
        }
        return true
    }

    @SuppressLint("InflateParams")
    private fun checkSource() {
        val dialog = alert(titleResource = R.string.search_book_key) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "search word"
                editView.setText(CheckSource.keyword)
            }
            customView { alertBinding.root }
            okButton {
                keepScreenOn(true)
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        CheckSource.keyword = it
                    }
                }
                val selectItems = getSelectedSources()
                CheckSource.start(this@BookSourceActivity, selectItems)
                val currentItems = sourcesState.value
                val firstItem = currentItems.indexOf(selectItems.firstOrNull())
                val lastItem = currentItems.indexOf(selectItems.lastOrNull())
                Debug.isChecking = firstItem >= 0 && lastItem >= 0
                updateCheckingState(Debug.isChecking)
                refreshDebugMessages(force = true)
                startCheckMessageRefreshJob(firstItem, lastItem)
            }
            neutralButton(R.string.check_source_config)
            cancelButton()
        }
        //手动设置监听 避免点击打开校验设置后对话框关闭
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            showDialogFragment<CheckSourceConfig>()
        }
    }

    private fun resumeCheckSource() {
        if (!Debug.isChecking) {
            return
        }
        keepScreenOn(true)
        CheckSource.resume(this)
        updateCheckingState(Debug.isChecking)
        refreshDebugMessages(force = true)
        startCheckMessageRefreshJob(0, 0)
    }

    @SuppressLint("InflateParams")
    private fun selectionAddToGroups() {
        alert(titleResource = R.string.add_group) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.setHint(R.string.group_name)
                editView.setFilterValues(groups.toList())
                editView.dropDownHeight = 180.dpToPx()
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        viewModel.selectionAddToGroups(getSelectedSources(), it)
                    }
                }
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    private fun selectionRemoveFromGroups() {
        alert(titleResource = R.string.remove_group) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.setHint(R.string.group_name)
                editView.setFilterValues(groups.toList())
                editView.dropDownHeight = 180.dpToPx()
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        viewModel.selectionRemoveFromGroups(getSelectedSources(), it)
                    }
                }
            }
            cancelButton()
        }
    }

    private fun upGroupMenu() = groupMenu?.transaction { menu ->
        menu.removeGroup(R.id.source_group)
        groups.forEach {
            menu.add(R.id.source_group, Menu.NONE, Menu.NONE, it)
        }
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportBookSourceDialog(it))
                }
            }
            cancelButton()
        }
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.CHECK_SOURCE) { msg ->
            snackBar?.setText(msg) ?: let {
                snackBar = Snackbar
                    .make(binding.root, msg, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.cancel) {
                        CheckSource.stop(this)
                        Debug.finishChecking()
                        updateCheckingState(false)
                        refreshDebugMessages(force = true)
                    }.apply { show() }
            }
        }
        observeEvent<Int>(EventBus.CHECK_SOURCE_DONE) {
            keepScreenOn(false)
            snackBar?.dismiss()
            snackBar = null
            updateCheckingState(false)
            refreshDebugMessages(force = true)
            groups.forEach { group ->
                if (group.contains("失效") && searchView.query.isEmpty()) {
                    searchView.setQuery("失效", true)
                    toastOnUi("发现有失效书源，已为您自动筛选！")
                }
            }
        }
    }

    private fun startCheckMessageRefreshJob(firstItem: Int, lastItem: Int) {
        checkMessageRefreshJob?.cancel()
        checkMessageRefreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshDebugMessages()
                    if (!Debug.isChecking) {
                        updateCheckingState(false)
                        refreshDebugMessages(force = true)
                        checkMessageRefreshJob?.cancel()
                    }
                    delay(500L)
                }
            }
        }
    }

    /**
     * 保持亮屏
     */
    private fun keepScreenOn(on: Boolean) {
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun upCountView() {
        binding.selectActionBar
            .upCountView(getSelectedSources().size, sourcesState.value.size)
    }

    private fun getSelectedSources(): List<BookSourcePart> {
        val urls = selectedUrls.value
        return sourcesState.value.filter { it.bookSourceUrl in urls }
    }

    private fun toggleSourceSelection(source: BookSourcePart) {
        val current = selectedUrls.value.toMutableSet()
        if (source.bookSourceUrl in current) {
            current.remove(source.bookSourceUrl)
        } else {
            current.add(source.bookSourceUrl)
        }
        selectedUrls.value = current
        isSelectMode.value = current.isNotEmpty()
        upCountView()
    }

    private fun toggleSourceEnabled(source: BookSourcePart, enabled: Boolean) {
        val updated = source.copy(enabled = enabled)
        sourcesState.value = sourcesState.value.map {
            if (it.bookSourceUrl == source.bookSourceUrl) updated else it
        }
        viewModel.enable(enabled, listOf(updated))
    }

    private fun updateSelectedEnabled(enabled: Boolean) {
        val urls = selectedUrls.value
        if (urls.isEmpty()) return
        sourcesState.value = sourcesState.value.map {
            if (it.bookSourceUrl in urls) it.copy(enabled = enabled) else it
        }
    }

    private fun updateSelectedExplore(enabled: Boolean) {
        val urls = selectedUrls.value
        if (urls.isEmpty()) return
        sourcesState.value = sourcesState.value.map {
            if (it.bookSourceUrl in urls) it.copy(enabledExplore = enabled) else it
        }
    }

    private fun checkSelectedInterval() {
        val urls = selectedUrls.value
        val positions = sourcesState.value.mapIndexedNotNull { index, source ->
            if (source.bookSourceUrl in urls) index else null
        }
        if (positions.isEmpty()) return
        val newSelected = urls.toMutableSet()
        for (index in positions.min()..positions.max()) {
            newSelected.add(sourcesState.value[index].bookSourceUrl)
        }
        selectedUrls.value = newSelected
        isSelectMode.value = newSelected.isNotEmpty()
        upCountView()
    }

    private fun buildSourceHostHeaders(sources: List<BookSourcePart>): Map<String, String?> {
        if (!groupSourcesByDomain) return emptyMap()
        val headers = linkedMapOf<String, String?>()
        var lastHost: String? = null
        sources.forEachIndexed { index, source ->
            val host = getSourceHost(source.bookSourceUrl)
            headers[source.bookSourceUrl] = if (index == 0 || host != lastHost) host else null
            lastHost = host
        }
        return headers
    }

    private fun updateSourceHostHeaders(sources: List<BookSourcePart>) {
        val next = buildSourceHostHeaders(sources)
        sourceHostHeaders.keys
            .filter { it !in next }
            .toList()
            .forEach { sourceHostHeaders.remove(it) }
        next.forEach { (url, header) ->
            if (sourceHostHeaders[url] != header) {
                sourceHostHeaders[url] = header
            }
        }
    }

    private fun refreshDebugMessages(force: Boolean = false) {
        val next = buildDebugMessagesSnapshot()
        debugMessagesState.keys
            .filter { it !in next }
            .toList()
            .forEach { debugMessagesState.remove(it) }
        next.forEach { (url, message) ->
            if (force || debugMessagesState[url] != message) {
                debugMessagesState[url] = message
            }
        }
    }

    private fun updateCheckingState(checking: Boolean) {
        if (isCheckingState.value != checking) {
            isCheckingState.value = checking
        }
    }

    private fun buildDebugMessagesSnapshot(): Map<String, String> {
        return sourcesState.value.mapNotNull { source ->
            val initial = Debug.debugMessageMap[source.bookSourceUrl].orEmpty()
            if (initial.isBlank()) {
                null
            } else {
                if (!Debug.isChecking && !initial.contains(finalMessageRegex)) {
                    Debug.updateFinalMessage(source.bookSourceUrl, "校验失败")
                }
                val latest = Debug.debugMessageMap[source.bookSourceUrl].orEmpty()
                source.bookSourceUrl to latest
            }
        }.toMap()
    }

    private fun getSourceHost(origin: String): String {
        return hostMap.getOrPut(origin) {
            NetworkUtils.getSubDomainOrNull(origin) ?: "#"
        }
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        newText?.let {
            upBookSource(it)
        }
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

    private fun showSourceMenu(bookSource: BookSourcePart) {
        val labels = buildList {
            if (sort == BookSourceSort.Default) {
                add(getString(R.string.to_top))
                add(getString(R.string.to_bottom))
            }
            if (bookSource.hasLoginUrl) {
                add(getString(R.string.login))
            }
            add(getString(R.string.search))
            add(getString(R.string.debug))
            add(getString(R.string.delete))
            if (bookSource.hasExploreUrl) {
                add(
                    if (bookSource.enabledExplore) {
                        getString(R.string.disable_explore)
                    } else {
                        getString(R.string.enable_explore)
                    }
                )
            }
        }
        val dangerIndex = labels.indexOf(getString(R.string.delete)).takeIf { it >= 0 }
        showComposeActionListDialog(
            title = bookSource.bookSourceName,
            labels = labels,
            dangerIndices = dangerIndex?.let { setOf(it) } ?: emptySet()
        ) { index ->
            when (labels[index]) {
                getString(R.string.to_top) -> toTop(bookSource)
                getString(R.string.to_bottom) -> toBottom(bookSource)
                getString(R.string.login) -> startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", bookSource.bookSourceUrl)
                }
                getString(R.string.search) -> searchBook(bookSource)
                getString(R.string.debug) -> debug(bookSource)
                getString(R.string.delete) -> del(bookSource)
                getString(R.string.enable_explore) -> enableExplore(true, bookSource)
                getString(R.string.disable_explore) -> enableExplore(false, bookSource)
            }
        }
    }

    private fun del(bookSource: BookSourcePart) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + bookSource.bookSourceName,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = { viewModel.del(listOf(bookSource)) }
        )
    }

    private fun edit(bookSource: BookSourcePart) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", bookSource.bookSourceUrl)
        }
    }

    private fun upOrder(items: List<BookSourcePart>) {
        viewModel.upOrder(items)
    }

    private fun enable(enable: Boolean, bookSource: BookSourcePart) {
        viewModel.enable(enable, listOf(bookSource))
    }

    private fun enableExplore(enable: Boolean, bookSource: BookSourcePart) {
        val updated = bookSource.copy(enabledExplore = enable)
        sourcesState.value = sourcesState.value.map {
            if (it.bookSourceUrl == bookSource.bookSourceUrl) updated else it
        }
        viewModel.enableExplore(enable, listOf(updated))
    }

    private fun toTop(bookSource: BookSourcePart) {
        if (sortAscending) {
            viewModel.topSource(bookSource)
        } else {
            viewModel.bottomSource(bookSource)
        }
    }

    private fun toBottom(bookSource: BookSourcePart) {
        if (sortAscending) {
            viewModel.bottomSource(bookSource)
        } else {
            viewModel.topSource(bookSource)
        }
    }

    private fun searchBook(bookSource: BookSourcePart) {
        SearchActivity.start(this, bookSource)
    }

    private fun debug(bookSource: BookSourcePart) {
        startActivity<BookSourceDebugActivity> {
            putExtra("key", bookSource.bookSourceUrl)
        }
    }

    override fun finish() {
        if (searchView.query.isNullOrEmpty()) {
            super.finish()
        } else {
            searchView.setQuery("", true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!Debug.isChecking) {
            Debug.debugMessageMap.clear()
        }
    }

}
