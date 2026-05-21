package io.legado.app.ui.main.explore

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.AutoCompleteTextView
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.widget.NestedScrollView
import com.google.android.flexbox.FlexboxLayout
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ItemFilletCompleteTextBinding
import io.legado.app.databinding.ItemFilletSelectorSingleBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemFindBookBinding
import io.legado.app.databinding.FragmentExploreBinding
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.lib.theme.UiCorner
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.explore.ExploreShowAdapter
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.explore.ExploreShowGridAdapter
import io.legado.app.ui.book.explore.ExploreShowWaterfallAdapter
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.ui.widget.SourceSelectDialog
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.gone
import io.legado.app.utils.InfoMap
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setSelectionSafely
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.ui.widget.text.AccentTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现页面
 */
class ExploreFragment() : VMBaseFragment<ExploreViewModel>(R.layout.fragment_explore),
    MainFragmentInterface,
    ExploreAdapter.CallBack,
    ExploreShowAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<ExploreViewModel>()
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val adapter by lazy { ExploreAdapter(requireContext(), this) }
    private val linearLayoutManager by lazy { LinearLayoutManager(context) }
    private var discoverBookAdapter: RecyclerAdapter<SearchBook, *>? = null
    private var discoverBookLayoutMode = 0
    private val searchView: SearchView? by lazy {
        binding.titleBar.findViewById<SearchView?>(R.id.search_view)
    }
    private val diffItemCallBack = ExploreDiffItemCallBack()
    private val groups = linkedSetOf<String>()
    private var exploreFlowJob: Job? = null
    private var groupsMenu: SubMenu? = null
    private var oldModeInitialized = false
    private var modernModeInitialized = false
    private var usingModernDiscovery = false
    private var sourceMenuPopup: PopupWindow? = null
    private var tagFilterPopup: PopupWindow? = null
    private var discoverSourceFlowJob: Job? = null
    private var discoverBookshelfFlowJob: Job? = null
    private var discoverWarmupJob: Job? = null
    private var discoverLoadJob: Job? = null
    private var discoverActionJob: Job? = null
    private val discoverSources = mutableListOf<BookSourcePart>()
    private val discoverAllTagItems = mutableListOf<DiscoverTagItem>()
    private val discoverTagItems = mutableListOf<DiscoverTagItem>()
    private val discoverSelectItems = mutableListOf<DiscoverTagItem>()
    private val discoverMajorGroups = mutableListOf<String>()
    private val discoverBookshelf = linkedSetOf<String>()
    private val discoverBooks = linkedSetOf<SearchBook>()
    private val blockedButtonActions = hashMapOf<String, MutableSet<String>>()
    private var selectedDiscoverSourcePart: BookSourcePart? = null
    private var selectedDiscoverSource: BookSource? = null
    private var discoverCurrentUrl: String? = null
    private var discoverPage = 1
    private var discoverHasMore = true
    private var discoverLoading = false
    private var selectedDiscoverMajorGroup: String? = null
    private var selectedDiscoverTagIndex = -1
    private var selectedDiscoverUrlIndex = -1
    private var discoverRequestVersion = 0L
    private var discoverSourceVersion = 0L
    private var discoveryModeLoaded = false
    private var modernTopOverlaySpace = -1

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        usingModernDiscovery = AppConfig.modernDiscoveryPage
        discoveryModeLoaded = false
        binding.swipeRefreshLayout.setColorSchemeColors(accentColor)
        binding.swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            currentDiscoverScrollTarget()?.canScrollVertically(-1) == true
        }
        binding.swipeRefreshLayout.setOnRefreshListener {
            if (usingModernDiscovery) {
                loadDiscoverBooks(reset = true)
            } else {
                upExploreData(searchView?.query?.toString())
            }
        }
        binding.topBar.setMode(io.legado.app.ui.widget.MainTopBarView.Mode.DISCOVERY)
        binding.topBar.setSearchEntryVisible(true)
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        binding.topBar.doOnLayout {
            updateModernTopBarOverlay()
        }
        binding.rvFind.clipToPadding = false
        binding.rvFind.applyMainBottomBarPadding()
        binding.rvDiscoverBooks.clipToPadding = false
        binding.rvDiscoverBooks.applyMainBottomBarPadding(withInitialPadding = true)
        applyDiscoveryMode(loadData = false)
        scheduleDiscoveryWarmup()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        super.onCompatCreateOptionsMenu(menu)
        if (usingModernDiscovery) {
            groupsMenu = null
            return
        }
        menuInflater.inflate(R.menu.main_explore, menu)
        groupsMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupsMenu()
    }

    private fun applyDiscoveryMode(loadData: Boolean = true) {
        val modern = AppConfig.modernDiscoveryPage
        usingModernDiscovery = modern
        binding.titleBar.isGone = modern
        binding.llModernDiscovery.isVisible = modern
        binding.rvFind.isGone = modern
        binding.tvEmptyMsg.isGone = modern
        searchView?.isGone = modern
        if (modern) {
            binding.topBar.post {
                updateModernTopBarOverlay()
            }
        }
        if (!loadData) {
            activity?.invalidateOptionsMenu()
            return
        }
        if (modern) {
            exploreFlowJob?.cancel()
            initModernMode()
        } else {
            stopModernMode()
            initClassicMode()
        }
        activity?.invalidateOptionsMenu()
    }

    private fun currentDiscoverScrollTarget(): View? {
        return when {
            usingModernDiscovery -> binding.rvDiscoverBooks
            else -> binding.rvFind
        }
    }

    private fun scheduleDiscoveryWarmup() {
        discoverWarmupJob?.cancel()
        if (!AppConfig.modernDiscoveryPage) return
        discoverWarmupJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(1800)
            if (!isAdded || discoveryModeLoaded || !AppConfig.modernDiscoveryPage) return@launch
            applyDiscoveryMode(loadData = true)
            discoveryModeLoaded = true
        }
    }

    private fun initClassicMode() {
        if (!oldModeInitialized) {
            oldModeInitialized = true
            initSearchView()
            initRecyclerView()
            initGroupData()
        }
        if (exploreFlowJob?.isActive != true) {
            upExploreData(searchView?.query?.toString())
        }
    }

    private fun initModernMode() {
        if (!modernModeInitialized) {
            modernModeInitialized = true
            initDiscoverRecycler()
            bindDiscoverSourceSelector()
            updateDiscoverLoginButtonState()
        }
        observeDiscoverSources()
        observeDiscoverBookshelf()
    }

    private fun stopModernMode() {
        sourceMenuPopup?.dismiss()
        sourceMenuPopup = null
        tagFilterPopup?.dismiss()
        tagFilterPopup = null
        discoverWarmupJob?.cancel()
        discoverWarmupJob = null
        discoverSourceFlowJob?.cancel()
        discoverSourceFlowJob = null
        discoverBookshelfFlowJob?.cancel()
        discoverBookshelfFlowJob = null
        discoverActionJob?.cancel()
        discoverActionJob = null
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverSourceVersion += 1
        discoverRequestVersion += 1
        discoverLoading = false
        binding.pbDiscoverLoading.gone()
        discoverAllTagItems.clear()
        discoverMajorGroups.clear()
        discoverTagItems.clear()
        selectedDiscoverMajorGroup = null
        selectedDiscoverTagIndex = -1
        selectedDiscoverUrlIndex = -1
    }

    private fun initSearchView() {
        val view = searchView ?: return
        view.applyTint(primaryTextColor)
        view.isSubmitButtonEnabled = true
        view.queryHint = getString(R.string.screen_find)
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                upExploreData(newText)
                return false
            }
        })
    }

    private fun initDiscoverRecycler() {
        binding.topBar.tagsBar.setOnTagClickListener { index ->
            val item = discoverTagItems.getOrNull(index) ?: return@setOnTagClickListener
            if (item.role == DiscoverTagItem.Role.Toggle) {
                handleDiscoverToggleTag(index, item)
                return@setOnTagClickListener
            }
            if (item.isButton) {
                handleDiscoverButtonTag(item)
                return@setOnTagClickListener
            }
            selectDiscoverTag(index, item, selectTab = true)
        }
        binding.topBar.selectsBar.setOnTagClickListener { index ->
            val item = discoverSelectItems.getOrNull(index) ?: return@setOnTagClickListener
            showDiscoverSelectDialog(item)
        }
        binding.topBar.setOnHeightChangedListener {
            updateModernTopBarOverlay()
        }
        applyDiscoverBookLayout(force = true)
        binding.rvDiscoverBooks.setEdgeEffectColor(primaryColor)
        binding.rvDiscoverBooks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                    loadDiscoverBooks(reset = false)
                }
            }
        })
        updateModernTopBarOverlay()
    }

    private fun updateModernTopBarOverlay() {
        if (!usingModernDiscovery || view == null) return
        val topSpace = binding.topBar.height
        if (modernTopOverlaySpace != topSpace) {
            modernTopOverlaySpace = topSpace
            binding.rvDiscoverBooks.clipToPadding = true
            binding.rvDiscoverBooks.setPadding(
                binding.rvDiscoverBooks.paddingLeft,
                topSpace,
                binding.rvDiscoverBooks.paddingRight,
                binding.rvDiscoverBooks.paddingBottom
            )
            binding.swipeRefreshLayout.setProgressViewOffset(
                true,
                (topSpace - 28.dpToPx()).coerceAtLeast(0),
                topSpace + 56.dpToPx()
            )
        }
        binding.topBar.bringToFront()
    }

    private fun applyDiscoverBookLayout(force: Boolean = false) {
        val layoutMode = AppConfig.discoveryPageLayout
        if (!force && discoverBookLayoutMode == layoutMode && discoverBookAdapter != null) return
        discoverBookLayoutMode = layoutMode
        binding.rvDiscoverBooks.layoutManager = when (layoutMode) {
            3 -> GridLayoutManager(requireContext(), 3)
            2 -> StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            else -> LinearLayoutManager(requireContext())
        }
        discoverBookAdapter = when (layoutMode) {
            3 -> ExploreShowGridAdapter(requireContext(), this)
            2 -> ExploreShowWaterfallAdapter(requireContext(), this, 2)
            else -> ExploreShowAdapter(requireContext(), this)
        }.also { adapter ->
            binding.rvDiscoverBooks.adapter = adapter
            if (discoverBooks.isNotEmpty()) {
                adapter.setItems(discoverBooks.toList())
            }
        }
    }

    private fun bindDiscoverSourceSelector() {
        binding.topBar.titleText.applyUiTitleTypeface(requireContext())
        val updateSourceNameWidth = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDiscoverSourceNameWidth()
        }
        binding.topBar.addOnLayoutChangeListener(updateSourceNameWidth)
        binding.topBar.post(::updateDiscoverSourceNameWidth)
        binding.topBar.titleSelect.setOnClickListener {
            showDiscoverSourceMenu()
        }
        binding.topBar.titleSelect.setOnLongClickListener {
            showDiscoverKindsDialog()
            true
        }
        binding.topBar.primaryBar.setOnTagClickListener { index ->
            discoverSources.getOrNull(index)?.let(::selectDiscoverSource)
        }
        binding.topBar.primaryBar.setOnTagLongClickListener { index ->
            discoverSources.getOrNull(index)?.let { source ->
                if (source.bookSourceUrl != selectedDiscoverSourcePart?.bookSourceUrl) {
                    selectDiscoverSource(source)
                }
                binding.topBar.post { showDiscoverKindsDialog() }
            }
            true
        }
        binding.topBar.searchEntry.setOnClickListener {
            openDiscoverSearch()
        }
        binding.topBar.loginButton.setOnClickListener {
            openSelectedSourceLogin()
        }
        binding.topBar.searchButton.setOnClickListener {
            openDiscoverSearch()
        }
        binding.topBar.filterButton.setOnClickListener {
            showDiscoverTagFilterMenu()
        }
        updateDiscoverTagFilterButtonState()
        updateDiscoverSearchButtonState()
    }

    private fun updateDiscoverSourceNameWidth() {
        val rowWidth = binding.topBar.width
        if (rowWidth <= 0) return
        val actionsWidth = listOf(
            binding.topBar.searchButton,
            binding.topBar.filterButton,
            binding.topBar.loginButton
        ).filter { it.isVisible }.sumOf { it.measuredWidth.takeIf { width -> width > 0 } ?: it.layoutParams.width }
        val spacing = 36.dpToPx()
        val maxWidth = (rowWidth - actionsWidth - spacing).coerceIn(96.dpToPx(), 190.dpToPx())
        binding.topBar.titleText.maxWidth = maxWidth
    }

    private fun openSelectedSourceLogin() {
        val source = selectedDiscoverSourcePart ?: return
        if (!source.hasLoginUrl) {
            context?.toastOnUi(R.string.source_no_login)
            return
        }
        startActivity<SourceLoginActivity> {
            putExtra("type", "bookSource")
            putExtra("key", source.bookSourceUrl)
        }
    }

    private fun updateDiscoverLoginButtonState() {
        val canLogin = selectedDiscoverSourcePart?.hasLoginUrl == true
        binding.topBar.loginButton.isEnabled = canLogin
        binding.topBar.loginButton.alpha = if (canLogin) 1f else 0.45f
    }

    private fun updateDiscoverSearchButtonState() {
        val canSearch = !selectedDiscoverSource?.searchUrl.isNullOrBlank()
        binding.topBar.searchButton.isVisible = canSearch && !binding.topBar.isRegularStyle()
        binding.topBar.searchButton.isEnabled = canSearch
        binding.topBar.searchButton.alpha = if (canSearch) 1f else 0.45f
        binding.topBar.searchEntry.isEnabled = canSearch
        binding.topBar.searchEntry.alpha = if (canSearch) 1f else 0.58f
        binding.topBar.post(::updateDiscoverSourceNameWidth)
    }

    private fun openDiscoverSearch() {
        val source = selectedDiscoverSource ?: return
        if (source.searchUrl.isNullOrBlank()) {
            context?.toastOnUi(R.string.search_book_key)
            return
        }
        startActivity<SearchActivity> {
            putExtra("searchScope", "${source.bookSourceName}::${source.bookSourceUrl}")
        }
    }

    private fun observeDiscoverSources() {
        if (discoverSourceFlowJob?.isActive == true) return
        discoverSourceFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExplore()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.STARTED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect { list ->
                    discoverSources.clear()
                    discoverSources.addAll(list)
                    if (discoverSources.isEmpty()) {
                        selectedDiscoverSourcePart = null
                        selectedDiscoverSource = null
                        AppConfig.modernDiscoverySourceUrl = null
                        discoverCurrentUrl = null
                        discoverAllTagItems.clear()
                        discoverMajorGroups.clear()
                        selectedDiscoverMajorGroup = null
                        clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
                        renderDiscoverTags(emptyList(), -1)
                        binding.topBar.setTitle(getString(R.string.explore_empty))
                        binding.topBar.setSearchHint(getString(R.string.search_book_key))
                        renderDiscoverSourceSelector()
                        updateDiscoverLoginButtonState()
                        updateDiscoverSearchButtonState()
                        updateDiscoverTagFilterButtonState()
                        binding.pbDiscoverLoading.gone()
                        return@collect
                    }
                    val keepSource = selectedDiscoverSourcePart?.bookSourceUrl
                        ?: AppConfig.modernDiscoverySourceUrl
                    val selected = discoverSources.firstOrNull { it.bookSourceUrl == keepSource }
                        ?: discoverSources.first()
                    if (selectedDiscoverSourcePart?.bookSourceUrl != selected.bookSourceUrl
                        || discoverTagItems.isEmpty()
                    ) {
                        selectDiscoverSource(selected)
                    } else {
                        updateDiscoverSourceTitle()
                        renderDiscoverSourceSelector()
                        updateDiscoverLoginButtonState()
                        updateDiscoverSearchButtonState()
                    }
                }
        }
    }

    private fun observeDiscoverBookshelf() {
        if (discoverBookshelfFlowJob?.isActive == true) return
        discoverBookshelfFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowAll()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.STARTED,
                    AppDatabase.BOOK_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect { books ->
                    discoverBookshelf.clear()
                    books.filterNot { it.isNotShelf }
                        .forEach {
                            discoverBookshelf.add("${it.name}-${it.author}")
                            discoverBookshelf.add(it.name)
                            discoverBookshelf.add(it.bookUrl)
                        }
                    val bookAdapter = discoverBookAdapter
                    if ((bookAdapter?.itemCount ?: 0) > 0) {
                        bookAdapter?.notifyItemRangeChanged(
                            0,
                            bookAdapter.itemCount,
                            bundleOf("isInBookshelf" to null)
                        )
                    }
                }
        }
    }

    private fun showDiscoverSourceMenu() {
        if (discoverSources.isEmpty()) return
        SourceSelectDialog.show(
            context = requireContext(),
            title = getString(R.string.book_source),
            items = discoverSources,
            selectedKey = selectedDiscoverSourcePart?.bookSourceUrl,
            displayName = { it.getDisPlayNameGroup() },
            searchTexts = {
                listOfNotNull(it.bookSourceName, it.bookSourceUrl, it.bookSourceGroup)
            },
            itemKey = { it.bookSourceUrl }
        ) {
            selectDiscoverSource(it)
        }
    }

    private fun showDiscoverKindsDialog() {
        val context = context ?: return
        val source = selectedDiscoverSource ?: return
        var dialog: AlertDialog? = null
        val itemBinding = ItemFindBookBinding.inflate(layoutInflater, null, false)
        val flexbox = itemBinding.flexbox
        itemBinding.apply {
            root.setPadding(0, 0, 0, 0)
            llTitle.isClickable = false
            tvName.text = source.bookSourceName
            ivStatus.gone()
            flexbox.visible()
            rotateLoading.visible()
        }
        dialog = AlertDialog.Builder(context)
            .setView(itemBinding.root)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        viewLifecycleOwner.lifecycleScope.launch {
            val kinds = withContext(IO) {
                source.exploreKinds()
            }
            if (!isAdded || dialog?.isShowing != true) return@launch
            itemBinding.rotateLoading.gone()
            if (kinds.isEmpty()) {
                context.toastOnUi(R.string.explore_empty)
                return@launch
            }
            renderDiscoverDialogKinds(itemBinding, source, kinds, dialog)
        }
    }

    private fun filterDiscoverDialogItems(items: List<DiscoverTagItem>, key: String): List<DiscoverTagItem> {
        val query = key.trim()
        if (query.isBlank()) return items
        return items.filter { item ->
            listOfNotNull(
                item.text,
                item.group,
                item.kind.title,
                item.kind.url,
                item.kind.action
            ).any { it.toString().contains(query, ignoreCase = true) }
        }
    }

    private fun renderDiscoverDialogKinds(
        itemBinding: ItemFindBookBinding,
        source: BookSource,
        kinds: List<ExploreKind>,
        dialog: AlertDialog?
    ) {
        val flexbox = itemBinding.flexbox
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val java = SourceLoginJsExtensions(
            activity as? AppCompatActivity,
            source,
            callback = object : SourceLoginJsExtensions.Callback {
                override fun upUiData(data: Map<String, Any?>?) = Unit
                override fun reUiView(deltaUp: Boolean) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val newKinds = withContext(IO) { source.exploreKinds() }
                        if (dialog?.isShowing == true) {
                            renderDiscoverDialogKinds(itemBinding, source, newKinds, dialog)
                        }
                    }
                }
            }
        )
        flexbox.removeAllViews()
        kinds.forEach { kind ->
            when (kind.type) {
                ExploreKind.Type.url -> renderDiscoverDialogUrl(flexbox, source, kind, dialog, infoMap)
                ExploreKind.Type.button -> renderDiscoverDialogButton(flexbox, source, kind, infoMap, java)
                ExploreKind.Type.toggle -> renderDiscoverDialogToggle(flexbox, source, kind, infoMap, java)
                ExploreKind.Type.select -> renderDiscoverDialogSelect(flexbox, source, kind, infoMap, java)
                ExploreKind.Type.text -> renderDiscoverDialogTextInput(flexbox, source, kind, infoMap, java)
            }
        }
    }

    private fun renderDiscoverDialogUrl(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        dialog: AlertDialog?,
        infoMap: InfoMap
    ) {
        val tv = createDiscoverDialogTextView(flexbox)
        flexbox.addView(tv)
        applyDiscoverKindTextStyle(tv, kind)
        bindDiscoverDialogKindText(tv, kind, source, infoMap)
        tv.setOnClickListener {
            val url = kind.normalizedDiscoverUrl()?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            dialog?.dismiss()
            openExplore(source.bookSourceUrl, kind.title, url)
        }
    }

    private fun renderDiscoverDialogButton(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val tv = createDiscoverDialogTextView(flexbox)
        flexbox.addView(tv)
        applyDiscoverKindTextStyle(tv, kind)
        bindDiscoverDialogKindText(tv, kind, source, infoMap)
        tv.setOnClickListener {
            val action = kind.action?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
            }
        }
    }

    private fun renderDiscoverDialogToggle(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val tv = createDiscoverDialogTextView(flexbox)
        flexbox.addView(tv)
        var left = true
        kind.style().apply {
            when (layout_justifySelf) {
                "flex_start" -> tv.gravity = Gravity.START
                "flex_end" -> tv.gravity = Gravity.END
                "right" -> left = false
                else -> tv.gravity = Gravity.CENTER
            }
            apply(tv)
        }
        val chars = kind.chars?.filterNotNull() ?: listOf("chars", "is null")
        var newName = kind.title
        var char = infoMap[kind.title] ?: (kind.default ?: chars.firstOrNull().orEmpty()).also {
            infoMap[kind.title] = it
        }
        fun updateText() {
            tv.text = if (left) char + newName else newName + char
        }
        val viewName = kind.viewName
        if (viewName != null && viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
            newName = viewName.substring(1, viewName.length - 1)
        } else if (!viewName.isNullOrBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(IO) { evalDiscoverDialogUiJs(viewName, source, infoMap) }
                if (!name.isNullOrBlank()) {
                    newName = name
                    updateText()
                }
            }
        }
        updateText()
        tv.setOnClickListener {
            val currentIndex = chars.indexOf(char)
            char = chars.getOrNull(currentIndex + 1) ?: chars.firstOrNull().orEmpty()
            infoMap[kind.title] = char
            updateText()
            val action = kind.action?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
            }
        }
    }

    private fun renderDiscoverDialogSelect(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val binding = ItemFilletSelectorSingleBinding.inflate(layoutInflater, flexbox, false)
        val root = binding.root
        flexbox.addView(root)
        kind.style().apply {
            when (layout_justifySelf) {
                "flex_start" -> root.gravity = Gravity.START
                "flex_end" -> root.gravity = Gravity.END
                else -> root.gravity = Gravity.CENTER
            }
            apply(root)
        }
        bindDiscoverDialogKindText(binding.spName, kind, source, infoMap)
        val chars = kind.chars?.filterNotNull() ?: listOf("chars", "is null")
        val adapter = ArrayAdapter(requireContext(), R.layout.item_text_common, chars)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spType.adapter = adapter
        val char = infoMap[kind.title] ?: (kind.default ?: chars.firstOrNull().orEmpty()).also {
            infoMap[kind.title] = it
        }
        binding.spType.setSelectionSafely(chars.indexOf(char))
        binding.spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initializing = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initializing) {
                    initializing = false
                    return
                }
                if (position !in chars.indices) return
                infoMap[kind.title] = chars[position]
                val action = kind.action?.takeIf { it.isNotBlank() } ?: return
                viewLifecycleOwner.lifecycleScope.launch(IO) {
                    evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun renderDiscoverDialogTextInput(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val input = ItemFilletCompleteTextBinding.inflate(layoutInflater, flexbox, false).root
        flexbox.addView(input)
        kind.style().apply {
            when (layout_justifySelf) {
                "center" -> input.gravity = Gravity.CENTER
                "flex_end" -> input.gravity = Gravity.END
                else -> input.gravity = Gravity.START
            }
            apply(input)
        }
        bindDiscoverDialogKindHint(input, kind, source, infoMap)
        input.setText(infoMap[kind.title])
        var actionJob: Job? = null
        input.addTextChangedListener(object : TextWatcher {
            var content: String? = null
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                content = s?.toString()
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString().orEmpty()
                infoMap[kind.title] = value
                val action = kind.action?.takeIf { it.isNotBlank() } ?: return
                if (value == content) return
                actionJob?.cancel()
                actionJob = viewLifecycleOwner.lifecycleScope.launch(IO) {
                    delay(600)
                    evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
                    content = value
                }
            }
        })
    }

    private fun createDiscoverDialogTextView(flexbox: FlexboxLayout): TextView {
        return ItemFilletTextBinding.inflate(layoutInflater, flexbox, false).root
    }

    private fun applyDiscoverKindTextStyle(tv: TextView, kind: ExploreKind) {
        kind.style().apply {
            when (layout_justifySelf) {
                "flex_start" -> tv.gravity = Gravity.START
                "flex_end" -> tv.gravity = Gravity.END
                else -> tv.gravity = Gravity.CENTER
            }
            apply(tv)
        }
    }

    private fun bindDiscoverDialogKindText(
        tv: TextView,
        kind: ExploreKind,
        source: BookSource,
        infoMap: InfoMap
    ) {
        val viewName = kind.viewName
        if (viewName == null) {
            tv.text = kind.title
        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
            tv.text = viewName.substring(1, viewName.length - 1)
        } else {
            tv.text = kind.title
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(IO) { evalDiscoverDialogUiJs(viewName, source, infoMap) }
                tv.text = name ?: "err"
            }
        }
    }

    private fun bindDiscoverDialogKindHint(
        input: AutoCompleteTextView,
        kind: ExploreKind,
        source: BookSource,
        infoMap: InfoMap
    ) {
        val viewName = kind.viewName
        if (viewName == null) {
            input.hint = kind.title
        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
            input.hint = viewName.substring(1, viewName.length - 1)
        } else {
            input.hint = kind.title
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(IO) { evalDiscoverDialogUiJs(viewName, source, infoMap) }
                input.hint = name ?: "err"
            }
        }
    }

    private suspend fun evalDiscoverDialogUiJs(
        jsStr: String,
        source: BookSource,
        infoMap: InfoMap
    ): String? {
        return try {
            runScriptWithContext {
                source.evalJS(jsStr) {
                    put("infoMap", infoMap)
                }.toString()
            }
        } catch (e: Throwable) {
            AppLog.put(source.getTag() + " exploreUi err:" + (e.localizedMessage ?: e.toString()), e)
            null
        }
    }

    private suspend fun evalDiscoverDialogButtonClick(
        jsStr: String,
        source: BookSource,
        infoMap: InfoMap,
        name: String,
        java: SourceLoginJsExtensions
    ) {
        try {
            runScriptWithContext {
                source.evalJS(normalizeDiscoverActionScript(jsStr)) {
                    put("java", java)
                    put("infoMap", infoMap)
                }
            }
        } catch (e: Throwable) {
            AppLog.put("ExploreUI Button $name JavaScript error", e)
        }
    }

    private fun createDiscoverDialogControl(
        item: DiscoverTagItem,
        dialog: AlertDialog?,
        refresh: () -> Unit
    ): View {
        return when (item.role) {
            DiscoverTagItem.Role.GlobalSelect -> createDiscoverDialogSelect(item)
            else -> createDiscoverDialogText(item, dialog, refresh)
        }
    }

    private fun createDiscoverDialogText(
        item: DiscoverTagItem,
        dialog: AlertDialog?,
        refresh: () -> Unit
    ): View {
        val binding = ItemFilletTextBinding.inflate(layoutInflater, null, false)
        binding.textView.text = discoverDialogLabel(item)
        binding.textView.maxLines = 1
        binding.textView.ellipsize = TextUtils.TruncateAt.END
        binding.textView.setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 4.dpToPx())
        applyDiscoverDialogFlexStyle(binding.root, item)
        binding.root.setOnClickListener {
            when (item.role) {
                DiscoverTagItem.Role.UrlTag -> {
                    dialog?.dismiss()
                    if (!item.group.isNullOrBlank()) {
                        selectedDiscoverMajorGroup = item.group
                        applyDiscoverTagFilterAndSelect(preferredUrl = item.kind.url)
                    } else {
                        val index = discoverTagItems.indexOfFirst {
                            it.role == DiscoverTagItem.Role.UrlTag && it.kind.url == item.kind.url
                        }
                        if (index >= 0) {
                            selectDiscoverTag(index, item, selectTab = true)
                        } else {
                            selectDiscoverTag(0, item, selectTab = false)
                        }
                    }
                }
                DiscoverTagItem.Role.Toggle -> handleDiscoverToggleFromDialog(item, refresh)
                DiscoverTagItem.Role.ActionButton,
                DiscoverTagItem.Role.ScriptUrl -> handleDiscoverButtonTag(item)
                DiscoverTagItem.Role.GlobalSelect -> Unit
            }
        }
        return binding.root
    }

    private fun createDiscoverDialogSelect(item: DiscoverTagItem): View {
        val binding = ItemFilletSelectorSingleBinding.inflate(layoutInflater, null, false)
        binding.spName.text = item.text
        val source = selectedDiscoverSource ?: return binding.root
        val key = item.kind.title
        val options = item.kind.chars?.filterNotNull() ?: emptyList()
        val adapter = ArrayAdapter(requireContext(), R.layout.item_text_common, options)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spType.adapter = adapter
        val current = currentDiscoverSelectValue(item)
        binding.spType.setSelection(options.indexOf(current).coerceAtLeast(0), false)
        binding.spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initializing = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initializing) {
                    initializing = false
                    return
                }
                if (position !in options.indices || key.isBlank()) return
                val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
                infoMap[key] = options[position]
                viewLifecycleOwner.lifecycleScope.launch(IO) {
                    source.clearExploreKindsCache()
                    item.kind.action?.takeIf { it.isNotBlank() }?.let { action ->
                        val script = normalizeDiscoverActionScript(action)
                        runScriptWithContext {
                            source.evalJS(script) {
                                put("java", SourceLoginJsExtensions(activity as? AppCompatActivity, source))
                                put("infoMap", infoMap)
                            }
                        }
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        loadDiscoverKindsAndDefault()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        applyDiscoverDialogFlexStyle(binding.root, item)
        return binding.root
    }

    private fun applyDiscoverDialogFlexStyle(view: View, item: DiscoverTagItem) {
        val style = item.kind.style()
        val params = FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(3.dpToPx(), 3.dpToPx(), 3.dpToPx(), 3.dpToPx())
            flexGrow = style.layout_flexGrow
            flexShrink = style.layout_flexShrink
            alignSelf = style.alignSelf()
            flexBasisPercent = style.layout_flexBasisPercent
            isWrapBefore = style.layout_wrapBefore
        }
        view.layoutParams = params
    }

    private fun discoverDialogLabel(item: DiscoverTagItem): CharSequence {
        val prefix = when (item.role) {
            DiscoverTagItem.Role.UrlTag -> ""
            DiscoverTagItem.Role.GlobalSelect -> "选择 "
            DiscoverTagItem.Role.Toggle -> "开关 "
            DiscoverTagItem.Role.ActionButton,
            DiscoverTagItem.Role.ScriptUrl -> "操作 "
        }
        val group = item.group?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val value = if (item.role == DiscoverTagItem.Role.GlobalSelect) {
            ": ${currentDiscoverSelectValue(item)}"
        } else {
            ""
        }
        return "$prefix${item.text}$value$group"
    }

    private fun handleDiscoverToggleFromDialog(item: DiscoverTagItem, refresh: () -> Unit) {
        val source = selectedDiscoverSource ?: return
        val key = item.kind.title
        if (key.isBlank()) return
        val chars = item.kind.chars?.filterNotNull() ?: return
        if (chars.isEmpty()) return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val current = infoMap[key] ?: item.kind.default ?: chars.first()
        val currentIndex = chars.indexOf(current).takeIf { it >= 0 } ?: 0
        val next = chars.getOrNull(currentIndex + 1) ?: chars.first()
        infoMap[key] = next
        refresh()
        handleDiscoverButtonTag(item.copy(text = resolveDiscoverToggleText(item.kind, next).limitDiscoverText(8)))
    }

    private fun selectDiscoverSource(source: BookSourcePart) {
        selectedDiscoverSourcePart = source
        AppConfig.modernDiscoverySourceUrl = source.bookSourceUrl
        renderDiscoverSourceSelector()
        updateDiscoverLoginButtonState()
        tagFilterPopup?.dismiss()
        tagFilterPopup = null
        discoverSourceVersion += 1
        val currentSourceVersion = discoverSourceVersion
        discoverRequestVersion += 1
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverLoading = false
        binding.pbDiscoverLoading.gone()
        discoverCurrentUrl = null
        discoverBooks.clear()
        discoverBookAdapter?.clearItems()
        binding.tvDiscoverEmpty.gone()
        discoverAllTagItems.clear()
        discoverMajorGroups.clear()
        discoverSelectItems.clear()
        selectedDiscoverMajorGroup = null
        renderDiscoverTags(emptyList(), -1)
        renderDiscoverSelects(emptyList())
        updateDiscoverTagFilterButtonState()
        viewLifecycleOwner.lifecycleScope.launch {
            val fullSource = withContext(IO) {
                appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
            }
            if (currentSourceVersion != discoverSourceVersion || !isAdded) {
                return@launch
            }
            selectedDiscoverSource = fullSource
            updateDiscoverSourceTitle()
            updateDiscoverSearchButtonState()
            loadDiscoverKindsAndDefault()
        }
    }

    private fun updateDiscoverSourceTitle() {
        val name = selectedDiscoverSourcePart?.bookSourceName
            ?: getString(R.string.discovery)
        binding.topBar.setTitle(if (binding.topBar.isRegularStyle()) getString(R.string.discovery) else name)
        binding.topBar.setSearchHint(name)
        renderDiscoverSourceSelector()
        binding.topBar.post(::updateDiscoverSourceNameWidth)
    }

    private fun renderDiscoverSourceSelector() {
        binding.topBar.setPrimaryItems(
            discoverSources.map { RoundedTagBarView.Item(it.bookSourceName) },
            discoverSources.indexOfFirst { it.bookSourceUrl == selectedDiscoverSourcePart?.bookSourceUrl }
        )
    }

    private suspend fun loadDiscoverKindsAndDefault() {
        val source = selectedDiscoverSource ?: return
        val kinds = withContext(IO) {
            source.exploreKinds()
        }
        val items = buildDiscoverTagItems(source, kinds)
        discoverAllTagItems.clear()
        discoverAllTagItems.addAll(items)
        if (items.isEmpty()) {
            discoverMajorGroups.clear()
            selectedDiscoverMajorGroup = null
            renderDiscoverTags(emptyList(), -1)
            renderDiscoverSelects(emptyList())
            updateDiscoverTagFilterButtonState()
            clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
            return
        }
        applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
    }

    private suspend fun buildDiscoverTagItems(
        source: BookSource,
        kinds: List<ExploreKind>
    ): List<DiscoverTagItem> {
        val blocked = blockedButtonActions[source.bookSourceUrl]
        var currentGroup: String? = null
        val result = mutableListOf<DiscoverTagItem>()
        kinds.forEachIndexed { index, kind ->
            if (index == 0 && isDiscoverLeadingBlankPlaceholder(kind)) {
                return@forEachIndexed
            }

            val action = kind.action?.takeIf { it.isNotBlank() }
            val url = kind.normalizedDiscoverUrl()
            val isSelect = kind.type == ExploreKind.Type.select
            val isActionControl = kind.type == ExploreKind.Type.button ||
                kind.type == ExploreKind.Type.toggle ||
                isDiscoverInputKind(kind)

            if (action.isNullOrBlank() && isDiscoverMajorGroupKind(kind)) {
                currentGroup = resolveDiscoverGroupTitle(kind)
                return@forEachIndexed
            }

            if (isSelect) {
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.select),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    role = DiscoverTagItem.Role.GlobalSelect,
                    group = null
                )
                return@forEachIndexed
            }

            if (action.isNullOrBlank() && isDiscoverDecorativeGroupKind(kind)) {
                currentGroup = resolveDiscoverGroupTitle(kind)
                return@forEachIndexed
            }

            if (!url.isNullOrBlank() && isDiscoverScriptButtonUrl(url)) {
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.button, action = url),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    role = DiscoverTagItem.Role.ScriptUrl,
                    group = null
                )
                return@forEachIndexed
            }

            if (!action.isNullOrBlank() && isActionControl) {
                if (blocked?.contains(action) == true) return@forEachIndexed
                val role = if (kind.type == ExploreKind.Type.toggle) {
                    DiscoverTagItem.Role.Toggle
                } else {
                    DiscoverTagItem.Role.ActionButton
                }
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.button),
                    text = resolveDiscoverControlText(kind).limitDiscoverText(8),
                    role = role,
                    group = currentGroup
                )
                return@forEachIndexed
            }

            if (!url.isNullOrBlank()) {
                result += DiscoverTagItem(
                    kind = kind.copy(url = url),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    role = DiscoverTagItem.Role.UrlTag,
                    group = currentGroup
                )
                return@forEachIndexed
            }
        }
        val hasMajorGroup = result.any { !it.group.isNullOrBlank() }
        val normalized = if (hasMajorGroup) {
            result
        } else {
            result.map { it.copy(group = getString(R.string.discover_group_other)) }
        }
        return normalized.distinctBy {
            "${it.group}|${it.role}|${it.kind.type}|${it.kind.title}|${it.kind.url}|${it.kind.action}|${it.kind.default}|${it.kind.viewName}|${it.kind.chars?.joinToString("\u001f")}"
        }
    }

    private fun discoverRowsWithInput(kinds: List<ExploreKind>): Set<Int> {
        val ignored = mutableSetOf<Int>()
        var rowStart = 0
        var rowWidth = 0f
        var rowHasInput = false
        kinds.forEachIndexed { index, kind ->
            if (kind.style().layout_wrapBefore && index > rowStart) {
                if (rowHasInput) {
                    for (i in rowStart until index) ignored += i
                }
                rowStart = index
                rowWidth = 0f
                rowHasInput = false
            }
            rowHasInput = rowHasInput || isDiscoverInputKind(kind)
            rowWidth += discoverKindWidth(kind)
            if (rowWidth >= 0.98f) {
                if (rowHasInput) {
                    for (i in rowStart..index) ignored += i
                }
                rowStart = index + 1
                rowWidth = 0f
                rowHasInput = false
            }
        }
        if (rowHasInput && rowStart < kinds.size) {
            for (i in rowStart until kinds.size) ignored += i
        }
        return ignored
    }

    private fun discoverKindWidth(kind: ExploreKind): Float {
        val style = kind.style()
        val width = style.layout_flexBasisPercent
        return when {
            width > 0f -> width.coerceAtMost(1f)
            style.layout_flexGrow > 0f -> 1f
            else -> 1f
        }
    }

    private fun isDiscoverMajorGroupKind(kind: ExploreKind): Boolean {
        if (!kind.normalizedDiscoverUrl().isNullOrBlank()) return false
        if (!kind.action.isNullOrBlank()) return false
        if (!isDiscoverFullWidthKind(kind)) return false
        if (kind.type == ExploreKind.Type.toggle) return true
        return kind.action.isNullOrBlank()
    }

    private fun isDiscoverInputKind(kind: ExploreKind): Boolean {
        return kind.type == ExploreKind.Type.text || kind.type == "password"
    }

    private fun isDiscoverFullWidthKind(kind: ExploreKind): Boolean {
        val style = kind.style()
        return style.layout_flexBasisPercent >= 0.95f ||
            (style.layout_flexGrow >= 1f && style.layout_flexBasisPercent < 0f)
    }

    private fun isDiscoverLeadingBlankPlaceholder(kind: ExploreKind): Boolean {
        if (!isDiscoverFullWidthKind(kind)) return false
        if (!kind.normalizedDiscoverUrl().isNullOrBlank()) return false
        if (!kind.action.isNullOrBlank()) return false
        val text = resolveDiscoverTagText(kind).trim()
        if (text.isNotBlank() && text != ExploreKind.Type.button) return false
        return kind.type == ExploreKind.Type.button || kind.action.isNullOrBlank()
    }

    private fun isDiscoverDecorativeGroupKind(kind: ExploreKind): Boolean {
        if (!kind.normalizedDiscoverUrl().isNullOrBlank()) return false
        val text = resolveDiscoverTagText(kind).trim()
        if (text.isBlank()) return false
        if (isDiscoverFullWidthKind(kind)) return true
        val compact = text.replace("\\s+".toRegex(), "")
        val hasDecoration = compact.count { it == '◎' || it == '●' || it == '○' || it == '◆' || it == '◇' || it == '=' || it == '-' } >= 2
        return hasDecoration || compact.endsWith("分类") || compact.endsWith("排行") || compact.endsWith("排行榜")
    }

    private fun isDiscoverScriptButtonUrl(url: String): Boolean {
        val value = url.trim()
        if (value.startsWith("@js:", ignoreCase = true) || value.startsWith("<js>", ignoreCase = true)) {
            return false
        }
        if (value.contains("page", ignoreCase = true) || value.contains("{{") || value.contains("{\\{")) {
            return false
        }
        return value.contains("java.startBrowser", ignoreCase = true) ||
            value.contains("java.longToast", ignoreCase = true) ||
            value.contains("java.toast", ignoreCase = true) ||
            value.contains("java.open", ignoreCase = true) ||
            value.contains("source.setVariable", ignoreCase = true)
    }

    private fun resolveDiscoverGroupTitle(kind: ExploreKind): String {
        val raw = resolveDiscoverTagText(kind).trim()
        if (raw.isBlank()) return getString(R.string.discovery)
        val normalized = raw
            .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F]+"), " ")
            .replace(Regex("[\\uFF1A:|/\\\\]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return normalized.ifBlank { raw }
    }

    private fun resolveDiscoverTagText(kind: ExploreKind): String {
        val viewName = kind.viewName
        if (!viewName.isNullOrBlank()
            && viewName.length in 3..28
            && viewName.first() == '\''
            && viewName.last() == '\''
        ) {
            return viewName.substring(1, viewName.length - 1)
        }
        return kind.title.ifBlank { kind.type }
    }

    private fun resolveDiscoverControlText(kind: ExploreKind): String {
        if (kind.type != ExploreKind.Type.toggle) {
            return resolveDiscoverTagText(kind)
        }
        val source = selectedDiscoverSource ?: return resolveDiscoverTagText(kind)
        val key = kind.title
        val value = if (key.isBlank()) {
            kind.default ?: kind.chars?.firstOrNull().orEmpty()
        } else {
            val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
            infoMap[key] ?: (kind.default ?: kind.chars?.firstOrNull().orEmpty()).also {
                infoMap[key] = it
            }
        }
        return resolveDiscoverToggleText(kind, value)
    }

    private fun ExploreKind.normalizedDiscoverUrl(): String? {
        return url?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("null", ignoreCase = true)
        }
    }

    private fun applyDiscoverTagFilterAndSelect(preferredUrl: String?) {
        val hasGroupedItems = discoverAllTagItems.any { !it.group.isNullOrBlank() }
        val groupList = discoverAllTagItems
            .mapNotNull { it.group?.takeIf { name -> name.isNotBlank() } }
            .filter { group ->
                discoverAllTagItems.any { it.group == group && isDiscoverVisibleGroupItem(it) }
            }
            .distinct()
        discoverMajorGroups.clear()
        discoverMajorGroups.addAll(groupList)

        if (discoverMajorGroups.isEmpty()) {
            selectedDiscoverMajorGroup = null
            if (hasGroupedItems) {
                renderDiscoverSelects(emptyList())
                renderDiscoverTags(emptyList(), -1)
                clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
                updateDiscoverTagFilterButtonState()
                return
            }
        } else {
            if (selectedDiscoverMajorGroup !in discoverMajorGroups) {
                selectedDiscoverMajorGroup = discoverMajorGroups.first()
            }
        }

        var filtered = if (discoverMajorGroups.isEmpty()) {
            discoverAllTagItems.toList()
        } else {
            discoverAllTagItems.filter {
                it.group == selectedDiscoverMajorGroup || isDiscoverGlobalControl(it)
            }
        }
        if (filtered.isEmpty() && discoverMajorGroups.isNotEmpty()) {
            val fallbackGroup = discoverMajorGroups.firstOrNull { group ->
                discoverAllTagItems.any { it.group == group && isDiscoverVisibleGroupItem(it) }
            }
            selectedDiscoverMajorGroup = fallbackGroup
            filtered = if (fallbackGroup.isNullOrBlank()) {
                discoverAllTagItems.toList()
            } else {
                discoverAllTagItems.filter {
                    it.group == fallbackGroup || isDiscoverGlobalControl(it)
                }
            }
        }

        val selectItems = discoverAllTagItems.filter { it.role == DiscoverTagItem.Role.GlobalSelect }
        val tagItems = filtered.filter { it.role != DiscoverTagItem.Role.GlobalSelect }
        updateDiscoverTagFilterButtonState()
        renderDiscoverSelects(selectItems)
        val targetIndexByUrl = preferredUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                tagItems.indexOfFirst { !it.isButton && it.kind.url == url }
                    .takeIf { idx -> idx >= 0 }
            }
        val targetIndex = targetIndexByUrl
            ?: tagItems.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        renderDiscoverTags(tagItems, targetIndex)
        if (targetIndex >= 0) {
            selectDiscoverTag(targetIndex, tagItems[targetIndex], selectTab = true)
        } else {
            clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
        }
    }

    private fun isDiscoverVisibleGroupItem(item: DiscoverTagItem): Boolean {
        return item.isButton || !item.kind.url.isNullOrBlank()
    }

    private fun isDiscoverGlobalControl(item: DiscoverTagItem): Boolean {
        return item.group.isNullOrBlank() && item.role != DiscoverTagItem.Role.UrlTag
    }

    private fun updateDiscoverTagFilterButtonState() {
        val enabled = discoverMajorGroups.size > 1
        binding.topBar.filterButton.isVisible = enabled
        binding.topBar.filterButton.isEnabled = enabled
        binding.topBar.filterButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun showDiscoverTagFilterMenu() {
        if (discoverMajorGroups.size <= 1) return
        val current = selectedDiscoverMajorGroup
        val actions = buildList {
            discoverMajorGroups.forEach { group ->
                add(
                    ModernActionPopup.Action(
                        (if (group == current) "> " else "") + group.limitDiscoverText(10)
                    ) {
                        selectedDiscoverMajorGroup = group
                        applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
                    }
                )
            }
        }
        tagFilterPopup = ModernActionPopup.show(
            binding.topBar.filterButton,
            actions,
            tagFilterPopup
        )
    }

    private fun renderDiscoverTags(items: List<DiscoverTagItem>, selectedIndex: Int) {
        discoverTagItems.clear()
        discoverTagItems.addAll(items)
        if (items.isEmpty()) {
            binding.topBar.showTags(false)
            selectedDiscoverTagIndex = -1
            selectedDiscoverUrlIndex = -1
            binding.topBar.tagsBar.submitItems(emptyList(), -1)
            return
        }
        binding.topBar.showTags(true)
        selectedDiscoverTagIndex = selectedIndex.coerceIn(-1, items.lastIndex)
        selectedDiscoverUrlIndex = if (selectedDiscoverTagIndex in items.indices && !items[selectedDiscoverTagIndex].isButton) {
            selectedDiscoverTagIndex
        } else {
            items.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        }
        binding.topBar.tagsBar.submitItems(
            items.map { RoundedTagBarView.Item(it.text, if (it.isButton) 0.9f else 1f) },
            selectedDiscoverTagIndex
        )
    }

    private fun renderDiscoverSelects(items: List<DiscoverTagItem>) {
        discoverSelectItems.clear()
        discoverSelectItems.addAll(items)
        if (items.isEmpty()) {
            binding.topBar.showSelects(false)
            binding.topBar.selectsBar.submitItems(emptyList(), -1)
            return
        }
        binding.topBar.showSelects(true)
        binding.topBar.selectsBar.submitItems(
            items.map {
                val value = currentDiscoverSelectValue(it)
                RoundedTagBarView.Item("${it.text}: $value", 1f)
            },
            -1
        )
    }

    private fun currentDiscoverSelectValue(item: DiscoverTagItem): String {
        val source = selectedDiscoverSource ?: return item.kind.default ?: ""
        val key = item.kind.title
        if (key.isBlank()) return item.kind.default ?: ""
        val info = getDiscoverInfoMap(source.bookSourceUrl)
        info[key]?.let { return it }
        val value = item.kind.default
            ?: item.kind.chars?.firstOrNull()
            ?: ""
        info[key] = value
        return value
    }

    private fun showDiscoverSelectDialog(item: DiscoverTagItem) {
        val source = selectedDiscoverSource ?: return
        val key = item.kind.title
        if (key.isBlank()) return
        val options = item.kind.chars?.filterNotNull() ?: emptyList()
        if (options.isEmpty()) return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        context?.selector(item.text, options) { _, value, _ ->
            infoMap[key] = value
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                source.clearExploreKindsCache()
                val action = item.kind.action?.takeIf { it.isNotBlank() }
                if (!action.isNullOrBlank()) {
                    val script = normalizeDiscoverActionScript(action)
                    runScriptWithContext {
                        source.evalJS(script) {
                            put("java", SourceLoginJsExtensions(activity as? AppCompatActivity, source))
                            put("infoMap", infoMap)
                        }
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    loadDiscoverKindsAndDefault()
                }
            }
        }
    }

    private fun selectDiscoverTabByCode(index: Int, smooth: Boolean) {
        if (index !in discoverTagItems.indices) return
        binding.topBar.tagsBar.setSelectedIndex(index, smooth)
    }

    private fun selectDiscoverTag(index: Int, item: DiscoverTagItem, selectTab: Boolean) {
        val url = item.kind.url?.takeIf { it.isNotBlank() } ?: return
        selectedDiscoverTagIndex = index
        selectedDiscoverUrlIndex = index
        if (selectTab) {
            selectDiscoverTabByCode(index, smooth = true)
        }
        if (discoverCurrentUrl == url && discoverBooks.isNotEmpty()) {
            return
        }
        discoverCurrentUrl = url
        loadDiscoverBooks(reset = true)
    }

    private fun handleDiscoverButtonTag(item: DiscoverTagItem) {
        val source = selectedDiscoverSource ?: return
        val action = item.kind.action?.takeIf { it.isNotBlank() } ?: return
        val script = normalizeDiscoverActionScript(action)
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val actionLower = script.lowercase()
        val isNavigationAction = actionLower.contains("showbrowser(")
            || actionLower.contains("open(\"explore\"")
            || actionLower.contains("open('explore'")
            || actionLower.contains("startbrowser(")
        discoverActionJob?.cancel()
        discoverActionJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.pbDiscoverLoading.visible()
            val result = withContext(IO) {
                kotlin.runCatching {
                    var handledByAction = false
                    val java = SourceLoginJsExtensions(
                        activity as? AppCompatActivity,
                        source,
                        callback = object : SourceLoginJsExtensions.Callback {
                            override fun upUiData(data: Map<String, Any?>?) = Unit
                            override fun reUiView(deltaUp: Boolean) = Unit
                            override fun showBrowser(
                                url: String,
                                html: String?,
                                preloadJs: String?,
                                config: String?
                            ): Boolean {
                                return false
                            }

                            override fun open(
                                name: String,
                                url: String?,
                                title: String?,
                                origin: String?
                            ): Boolean {
                                if (!isAdded) return false
                                if (name != "explore") return false
                                handledByAction = true
                                val targetUrl = url?.takeIf { it.isNotBlank() } ?: return true
                                val targetSourceUrl = origin
                                    ?.takeIf { it.isNotBlank() }
                                    ?: selectedDiscoverSource?.bookSourceUrl
                                    ?: source.bookSourceUrl
                                val targetTitle = title ?: item.text
                                binding.root.post {
                                    openExplore(targetSourceUrl, targetTitle, targetUrl)
                                }
                                return true
                            }
                        }
                    )
                    runScriptWithContext {
                        source.evalJS(script) {
                            put("java", java)
                            put("infoMap", infoMap)
                        }
                    }
                    when {
                        handledByAction || isNavigationAction -> null
                        else -> {
                            source.clearExploreKindsCache()
                            source.exploreKinds()
                        }
                    }
                }
            }
            binding.pbDiscoverLoading.gone()
            if (!isAdded) return@launch
            result.onSuccess { kinds ->
                if (kinds == null) {
                    return@onSuccess
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    applyDiscoverButtonResult(source, script, kinds)
                }
            }.onFailure {
                AppLog.put("发现标签按钮执行失败", it)
                context?.toastOnUi(it.localizedMessage ?: getString(R.string.unknown_error))
            }
        }
    }

    private suspend fun applyDiscoverButtonResult(
        source: BookSource,
        action: String,
        kinds: List<ExploreKind>
    ) {
        val items = buildDiscoverTagItems(source, kinds)
        val firstUrlIndex = items.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        if (firstUrlIndex >= 0) {
            discoverAllTagItems.clear()
            discoverAllTagItems.addAll(items)
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
            return
        }
        if (items.isNotEmpty()) {
            discoverAllTagItems.clear()
            discoverAllTagItems.addAll(items)
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
        }
        context?.toastOnUi(R.string.find_empty)
    }

    private fun getDiscoverInfoMap(sourceUrl: String): InfoMap {
        return ExploreAdapter.exploreInfoMapList[sourceUrl] ?: InfoMap(sourceUrl).also {
            ExploreAdapter.exploreInfoMapList.put(sourceUrl, it)
        }
    }

    private fun clearDiscoverBooksToEmpty(message: String) {
        discoverRequestVersion += 1
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverLoading = false
        binding.swipeRefreshLayout.isRefreshing = false
        binding.pbDiscoverLoading.gone()
        discoverCurrentUrl = null
        discoverHasMore = false
        discoverPage = 1
        discoverBooks.clear()
        discoverBookAdapter?.clearItems()
        binding.tvDiscoverEmpty.text = message
        binding.tvDiscoverEmpty.visible()
    }

    private fun loadDiscoverBooks(reset: Boolean) {
        if (!usingModernDiscovery) return
        val source = selectedDiscoverSource ?: return
        val url = discoverCurrentUrl?.takeIf { it.isNotBlank() } ?: return
        if (!reset && !discoverHasMore) return
        if (reset) {
            discoverLoadJob?.cancel()
        } else if (discoverLoading) {
            return
        }
        val requestVersion = if (reset) {
            discoverRequestVersion += 1
            discoverRequestVersion
        } else {
            discoverRequestVersion
        }
        discoverLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            if (reset) {
                discoverPage = 1
                discoverHasMore = true
                discoverBooks.clear()
                discoverBookAdapter?.clearItems()
                binding.tvDiscoverEmpty.gone()
            }
            discoverLoading = true
            binding.pbDiscoverLoading.visible()
            try {
                val newBooks = withContext(IO) {
                    WebBook.exploreBookAwait(
                        source,
                        url,
                        discoverPage,
                        WebViewPool.Scope.DISCOVERY
                    )
                }
                if (!isAdded || requestVersion != discoverRequestVersion || url != discoverCurrentUrl) {
                    return@launch
                }
                if (newBooks.isEmpty()) {
                    discoverHasMore = false
                    if (discoverBooks.isEmpty()) {
                        binding.tvDiscoverEmpty.text = getString(R.string.explore_empty)
                        binding.tvDiscoverEmpty.visible()
                    }
                } else {
                    withContext(IO) {
                        appDb.searchBookDao.insert(*newBooks.toTypedArray())
                    }
                    discoverPage += 1
                    discoverBooks.addAll(newBooks)
                    discoverBookAdapter?.setItems(discoverBooks.toList())
                    binding.tvDiscoverEmpty.gone()
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Throwable) {
                if (!isAdded || requestVersion != discoverRequestVersion || url != discoverCurrentUrl) {
                    return@launch
                }
                AppLog.put("发现页面加载失败", e)
                if (discoverBooks.isEmpty()) {
                    binding.tvDiscoverEmpty.text = e.localizedMessage ?: getString(R.string.unknown_error)
                    binding.tvDiscoverEmpty.visible()
                }
            } finally {
                if (isAdded && requestVersion == discoverRequestVersion && url == discoverCurrentUrl) {
                    binding.pbDiscoverLoading.gone()
                    binding.swipeRefreshLayout.isRefreshing = false
                    discoverLoading = false
                }
            }
        }
    }

    private fun initRecyclerView() {
        binding.rvFind.setEdgeEffectColor(primaryColor)
        binding.rvFind.layoutManager = linearLayoutManager
        binding.rvFind.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.rvFind.scrollToPosition(0)
                }
            }
        })
    }

    private fun initGroupData() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExploreGroups()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups.clear()
                    groups.addAll(it)
                    upGroupsMenu()
                    delay(500)
                }
        }
    }

    private fun upExploreData(searchKey: String? = null) {
        exploreFlowJob?.cancel()
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> {
                    appDb.bookSourceDao.flowExplore()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.bookSourceDao.flowGroupExplore(key)
                }

                else -> {
                    appDb.bookSourceDao.flowExplore(searchKey)
                }
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("发现页面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                binding.swipeRefreshLayout.isRefreshing = false
                binding.tvEmptyMsg.isGone = it.isNotEmpty() || (searchView?.query?.isNotEmpty() == true)
                adapter.setItems(it, diffItemCallBack)
                delay(500)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (usingModernDiscovery != AppConfig.modernDiscoveryPage || !discoveryModeLoaded) {
            applyDiscoveryMode(loadData = true)
            discoveryModeLoaded = true
        } else if (usingModernDiscovery) {
            applyDiscoverBookLayout()
        }
        if (!usingModernDiscovery) {
            adapter.upResumed(true)
        }
    }

    override fun onPause() {
        if (!usingModernDiscovery) {
            adapter.upResumed(false)
            searchView?.clearFocus()
            adapter.onPause()
        }
        if (usingModernDiscovery) {
            discoverLoadJob?.cancel()
            discoverLoadJob = null
            discoverLoading = false
            binding.pbDiscoverLoading.gone()
            binding.swipeRefreshLayout.isRefreshing = false
            discoverRequestVersion += 1
        }
        WebViewPool.scheduleDestroyScope(WebViewPool.Scope.DISCOVERY)
        super.onPause()
    }

    override fun onDestroyView() {
        stopModernMode()
        WebViewPool.destroyScope(WebViewPool.Scope.DISCOVERY)
        oldModeInitialized = false
        modernModeInitialized = false
        groupsMenu = null
        super.onDestroyView()
    }

    private fun upGroupsMenu() = groupsMenu?.transaction { subMenu ->
        subMenu.removeGroup(R.id.menu_group_text)
        groups.forEach {
            subMenu.add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
        }
    }

    override val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        if (usingModernDiscovery) return
        if (item.groupId == R.id.menu_group_text) {
            searchView?.setQuery("group:${item.title}", true) ?: upExploreData("group:${item.title}")
        }
    }

    override fun scrollTo(pos: Int) {
        (binding.rvFind.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
    }

    override fun openExplore(sourceUrl: String, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    override fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    override fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
    }

    override fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    override fun searchBook(bookSource: BookSourcePart) {
        SearchActivity.start(requireContext(), bookSource)
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return discoverBookshelf.contains(key) || discoverBookshelf.contains(book.bookUrl)
    }

    override fun showBookInfo(book: SearchBook) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(IO) {
                appDb.searchBookDao.insert(book)
            }
            val isVideo = withContext(IO) {
                SearchBookOpenHelper.isVideoResult(
                    book,
                    selectedDiscoverSourcePart?.bookSourceType ?: selectedDiscoverSource?.bookSourceType
                )
            }
            SearchBookOpenHelper.open(requireContext(), book, isVideo)
        }
    }

    private fun handleDiscoverToggleTag(index: Int, item: DiscoverTagItem) {
        val source = selectedDiscoverSource ?: return
        val key = item.kind.title
        if (key.isBlank()) return
        val chars = item.kind.chars?.filterNotNull() ?: return
        if (chars.isEmpty()) return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val current = infoMap[key] ?: item.kind.default ?: chars.first()
        val currentIndex = chars.indexOf(current).takeIf { it >= 0 } ?: 0
        val next = chars.getOrNull(currentIndex + 1) ?: chars.first()
        infoMap[key] = next
        val updated = item.copy(text = resolveDiscoverToggleText(item.kind, next).limitDiscoverText(8))
        if (index in discoverTagItems.indices) {
            discoverTagItems[index] = updated
            binding.topBar.tagsBar.submitItems(
                discoverTagItems.map { RoundedTagBarView.Item(it.text, if (it.isButton) 0.9f else 1f) },
                selectedDiscoverTagIndex
            )
        }
        handleDiscoverButtonTag(updated)
    }

    private fun resolveDiscoverToggleText(kind: ExploreKind, value: String): String {
        val label = resolveDiscoverTagText(kind)
        return when {
            label.isBlank() -> value
            value.isBlank() -> label
            else -> "$value$label"
        }
    }

    private fun normalizeDiscoverActionScript(action: String): String {
        var value = action.trim()
        if (value.startsWith("@js:", ignoreCase = true)) {
            return value.substring(4).trim()
        }
        if (value.startsWith("<js>", ignoreCase = true) && value.endsWith("</js>", ignoreCase = true)) {
            return value.substring(4, value.length - 5).trim()
        }
        value = when {
            value.startsWith("{\\{") && value.endsWith("}}") -> value.substring(3, value.length - 2)
            value.startsWith("{{") && value.endsWith("}}") -> value.substring(2, value.length - 2)
            value.startsWith("{") && value.endsWith("}") && isDiscoverExecutableObjectScript(value) ->
                value.substring(1, value.length - 1)
            else -> value
        }
        return value.trim()
    }

    private fun isDiscoverExecutableObjectScript(value: String): Boolean {
        return value.contains("java.", ignoreCase = true) ||
            value.contains("source.", ignoreCase = true) ||
            value.contains("infoMap", ignoreCase = true)
    }

    fun compressExplore() {
        if (usingModernDiscovery) {
            if (binding.rvDiscoverBooks.canScrollVertically(-1)) {
                if (AppConfig.isEInkMode) {
                    binding.rvDiscoverBooks.scrollToPosition(0)
                } else {
                    binding.rvDiscoverBooks.smoothScrollToPosition(0)
                }
            }
            return
        }
        if (!adapter.compressExplore()) {
            if (AppConfig.isEInkMode) {
                binding.rvFind.scrollToPosition(0)
            } else {
                binding.rvFind.smoothScrollToPosition(0)
            }
        }
    }

}

private fun String.limitDiscoverText(max: Int): String {
    return if (length <= max) this else "${take(max.coerceAtLeast(2) - 1)}..."
}
