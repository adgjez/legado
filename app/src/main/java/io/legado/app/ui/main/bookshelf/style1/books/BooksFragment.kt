package io.legado.app.ui.main.bookshelf.style1.books

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.isGone
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBooksBinding
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.bookshelf.compose.BookshelfBookItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItem
import io.legado.app.ui.main.bookshelf.compose.buildBookshelfItems
import io.legado.app.ui.main.bookshelf.compose.updateBookshelfItemUpdating
import io.legado.app.utils.cnCompare
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BooksFragment() : BaseFragment(R.layout.fragment_books),
    BaseBooksAdapter.CallBack {

    constructor(position: Int, group: BookGroup) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        bundle.putLong("groupId", group.groupId)
        bundle.putInt("bookSort", group.getRealBookSort())
        bundle.putBoolean("enableRefresh", group.enableRefresh)
        bundle.putBoolean("onlyUpdateRead", group.onlyUpdateRead)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBooksBinding::bind)
    private val activityViewModel by activityViewModels<MainViewModel>()
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        when (bookshelfLayout) {
            0 -> {
                BooksAdapterList(requireContext(), this, this, viewLifecycleOwner.lifecycle)
            }
            1 -> {
                BooksAdapterList2(requireContext(), this, this, viewLifecycleOwner.lifecycle)
            }
            else -> {
                BooksAdapterGrid(requireContext(), this)
            }
        }
    }
    private var booksFlowJob: Job? = null
    var position = 0
        private set
    var groupId = -1L
        private set
    var bookSort = 0
        private set
    private var upLastUpdateTimeJob: Job? = null
    private var enableRefresh = true
    private var onlyUpdateRead = false
    private var bookTagFilter = ""
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }
    private var itemCount = 0
    private var totalRows = 0
    private var topOverlaySpace by mutableStateOf(0)
    private var topOverlayEnabled by mutableStateOf(false)
    private val useComposeList get() = bookshelfLayout < 2
    private data class ComposeListScrollPosition(val index: Int, val offset: Int)
    private var composeItems by mutableStateOf<List<BookshelfItemUi>>(emptyList())
    private var composeCanScrollBackward by mutableStateOf(false)
    private var composeScrollToTopTick by mutableStateOf(0)
    private var composeScrollPosition: ComposeListScrollPosition? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            position = it.getInt("position", 0)
            groupId = it.getLong("groupId", -1)
            bookSort = it.getInt("bookSort", 0)
            enableRefresh = it.getBoolean("enableRefresh", true)
            onlyUpdateRead = it.getBoolean("onlyUpdateRead", false)
            binding.refreshLayout.isEnabled = enableRefresh
        }
        initRecyclerView()
        upRecyclerData()
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        binding.rvBookshelf.clipToPadding = false
        binding.rvBookshelf.applyMainBottomBarPadding()
        upFastScrollerBar()
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnChildScrollUpCallback { _, _ ->
            if (useComposeList) composeCanScrollBackward else binding.rvBookshelf.canScrollVertically(-1)
        }
        applyTopOverlaySpace()
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(getBooks(), onlyUpdateRead)
        }
        binding.rvBookshelf.isGone = useComposeList
        binding.composeBookshelf.isGone = !useComposeList
        if (useComposeList) {
            initComposeBookshelf()
            startLastUpdateTimeJob()
            return
        }
        if (bookshelfLayout >= 2) {
            binding.rvBookshelf.layoutManager = GridLayoutManager(context, bookshelfLayout)
            binding.rvBookshelf.setRecycledViewPool(activityViewModel.booksGridRecycledViewPool)
        } else {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
            binding.rvBookshelf.setRecycledViewPool(activityViewModel.booksListRecycledViewPool)
        }
        booksAdapter.stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.rvBookshelf.adapter = booksAdapter
        /**
         * 应该是当初没有使用override val keepScrollPosition = true 加的代码
         * 最近阅读插入顶部时会造成滚动
         * 但是采用keepScrollPosition = true复原滚动后,代码就多余了
         * 采用下面代码反而会向上多滚动一个行
         * 再加上2025/12/19代码,因为下面的代码会出现很奇怪的自动滚动到顶部现象,没理出原因,注释掉下面代码
         * **/
//        booksAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
//            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
//                val layoutManager = binding.rvBookshelf.layoutManager
//                if (positionStart == 0 && itemCount == 1 && layoutManager is LinearLayoutManager) {
//                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
//                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
//                }
//            }
//
//            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
//                val layoutManager = binding.rvBookshelf.layoutManager
//                if (toPosition == 0 && itemCount == 1 && layoutManager is LinearLayoutManager) {
//                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
//                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
//                }
//            }
//        })
        binding.rvBookshelf.addItemDecoration( object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val position = parent.getChildAdapterPosition(view)
                val topExtra = if (topOverlayEnabled) {
                    0
                } else {
                    resources.getDimensionPixelSize(R.dimen.bookshelf_content_margin_top)
                }
                if (bookshelfLayout >= 2) {
                    val spanCount = bookshelfLayout
                    val rowIndex = position / spanCount
                    when (rowIndex) {
                        0 -> { //第一行加额外上边距
                            outRect.set(bookshelfMargin, bookshelfMargin + topExtra, bookshelfMargin, bookshelfMargin)
                        }
                        totalRows - 1 -> { //最后一行加额外下边距
                            outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, bookshelfMargin)
                        }
                        else -> {
                            outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, bookshelfMargin)
                        }
                    }
                } else {
                    when (position) {
                        0 -> {
                            outRect.set(0, bookshelfMargin + topExtra, 0, bookshelfMargin)
                        }
                        itemCount - 1 -> {
                            outRect.set(0, bookshelfMargin, 0, bookshelfMargin)
                        }
                        else -> {
                            outRect.set(0, bookshelfMargin, 0, bookshelfMargin)
                        }
                    }
                }
            }
        })
        startLastUpdateTimeJob()
    }

    private fun initComposeBookshelf() {
        binding.composeBookshelf.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeBookshelf.setContent {
            BookshelfListContent()
        }
    }

    @Composable
    private fun BookshelfListContent() {
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = composeScrollPosition?.index ?: 0,
            initialFirstVisibleItemScrollOffset = composeScrollPosition?.offset ?: 0
        )
        val canScrollBackward by remember {
            derivedStateOf { listState.canScrollBackward }
        }
        val marginDp = with(LocalDensity.current) { bookshelfMargin.toDp() }
        val topExtraDp = with(LocalDensity.current) {
            val topExtra = if (topOverlayEnabled) {
                topOverlaySpace + resources.getDimensionPixelSize(R.dimen.bookshelf_top_overlay_gap)
            } else {
                resources.getDimensionPixelSize(R.dimen.bookshelf_content_margin_top)
            }
            topExtra.toDp()
        }
        val bottomBarPadding = with(LocalDensity.current) {
            resources.getDimensionPixelSize(R.dimen.main_content_bottom_bar_padding).toDp()
        }
        DisposableEffect(Unit) {
            onDispose {
                composeScrollPosition = ComposeListScrollPosition(
                    index = listState.firstVisibleItemIndex,
                    offset = listState.firstVisibleItemScrollOffset
                )
            }
        }
        LaunchedEffect(canScrollBackward) {
            composeCanScrollBackward = canScrollBackward
        }
        LaunchedEffect(composeScrollToTopTick) {
            if (composeScrollToTopTick > 0) {
                if (AppConfig.isEInkMode) {
                    listState.scrollToItem(0)
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = topExtraDp + marginDp,
                end = 8.dp,
                bottom = marginDp + bottomBarPadding + 12.dp
            )
        ) {
            items(
                items = composeItems,
                key = { it.key },
                contentType = { it.contentType }
            ) { item ->
                BookshelfListItem(
                    item = item,
                    listLayout = bookshelfLayout,
                    cardStyle = AppConfig.bookshelfListItemStyle,
                    modifier = Modifier.padding(vertical = marginDp.coerceAtLeast(2.dp)),
                    fragment = this@BooksFragment,
                    lifecycle = viewLifecycleOwner.lifecycle,
                    onClick = ::onComposeItemClick,
                    onLongClick = ::onComposeItemLongClick
                )
            }
        }
    }

    fun setTopOverlaySpace(space: Int, overlay: Boolean) {
        topOverlaySpace = space
        topOverlayEnabled = overlay
        view?.post {
            applyTopOverlaySpace()
        }
    }

    private fun applyTopOverlaySpace() {
        if (view == null) return
        if (useComposeList) {
            binding.rvBookshelf.clipToPadding = true
            binding.rvBookshelf.setPadding(
                binding.rvBookshelf.paddingLeft,
                0,
                binding.rvBookshelf.paddingRight,
                binding.rvBookshelf.paddingBottom
            )
            if (topOverlayEnabled) {
                binding.refreshLayout.setProgressViewOffset(
                    true,
                    (topOverlaySpace - 28.dpToPx()).coerceAtLeast(0),
                    topOverlaySpace + 56.dpToPx()
                )
            } else {
                binding.refreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
            }
            return
        }
        val topGap = if (topOverlayEnabled) {
            resources.getDimensionPixelSize(R.dimen.bookshelf_top_overlay_gap)
        } else {
            0
        }
        binding.rvBookshelf.clipToPadding = true
        binding.rvBookshelf.setPadding(
            binding.rvBookshelf.paddingLeft,
            if (topOverlayEnabled) topOverlaySpace + topGap else 0,
            binding.rvBookshelf.paddingRight,
            binding.rvBookshelf.paddingBottom
        )
        if (topOverlayEnabled) {
            binding.refreshLayout.setProgressViewOffset(
                true,
                (topOverlaySpace - 28.dpToPx()).coerceAtLeast(0),
                topOverlaySpace + 56.dpToPx()
            )
        } else {
            binding.refreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        }
        binding.rvBookshelf.invalidateItemDecorations()
    }

    private fun upFastScrollerBar() {
        if (useComposeList) {
            binding.rvBookshelf.setFastScrollEnabled(false)
            return
        }
        val showBookshelfFastScroller = AppConfig.showBookshelfFastScroller
        binding.rvBookshelf.setFastScrollEnabled(showBookshelfFastScroller)
        if (showBookshelfFastScroller) {
            binding.rvBookshelf.scrollBarSize = 0
        } else {
            binding.rvBookshelf.scrollBarSize =
                ViewConfiguration.get(requireContext()).scaledScrollBarSize
        }
    }

    fun upBookSort(sort: Int) {
        binding.root.post {
            arguments?.putInt("bookSort", sort)
            bookSort = sort
            upRecyclerData()
        }
    }

    fun setEnableRefresh(enable: Boolean) {
        enableRefresh = enable
        binding.refreshLayout.isEnabled = enable
    }

    fun setBookTagFilter(tag: String) {
        val normalized = tag.trim()
        if (bookTagFilter == normalized) return
        bookTagFilter = normalized
        upRecyclerData()
    }

    /**
     * 更新书籍列表信息
     */
    private fun upRecyclerData() {
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { list ->
                //排序
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy { it.order }

                    // 综合排序 issue #3192
                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }
                    // 按作者排序
                    5 -> list.sortedWith { o1, o2 ->
                        o1.author.cnCompare(o2.author)
                    }

                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }.map { list ->
                val filteredList = if (bookTagFilter.isBlank()) {
                    list
                } else {
                    list.filter { it.hasCustomTag(bookTagFilter) }
                }
                list to filteredList
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { (allBooks, list) ->
                (parentFragment as? io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1)
                    ?.onBooksChanged(groupId, allBooks)
                itemCount = list.size
                val spanCount = bookshelfLayout
                if (spanCount >= 2) {
                    totalRows = if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
                }
                binding.tvEmptyMsg.isGone = itemCount > 0
                binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
                if (useComposeList) {
                    updateComposeItems(list)
                } else {
                    booksAdapter.setItems(list)
                }
                delay(100)
            }
        }
    }

    private fun startLastUpdateTimeJob() {
        upLastUpdateTimeJob?.cancel()
        if (!AppConfig.showLastUpdateTime || (!useComposeList && bookshelfLayout >= 2)) {
            return
        }
        upLastUpdateTimeJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    if (useComposeList) {
                        updateComposeItems(getBooks())
                    } else {
                        booksAdapter.upLastUpdateTime()
                    }
                    delay(30 * 1000)
                }
            }
        }
    }

    fun getBooks(): List<Book> {
        if (useComposeList) {
            return composeItems.mapNotNull { (it as? BookshelfBookItemUi)?.book }
        }
        return booksAdapter.getItems()
    }

    private fun Book.hasCustomTag(tag: String): Boolean {
        return BookTagHelper.has(customTag, tag)
    }

    fun gotoTop() {
        if (useComposeList) {
            composeScrollToTopTick++
            return
        }
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    fun getBooksCount(): Int {
        if (useComposeList) {
            return composeItems.size
        }
        return booksAdapter.itemCount
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (useComposeList) {
            composeItems = emptyList()
            composeCanScrollBackward = false
            return
        }
        /**
         * 将 RecyclerView 中的视图全部回收到 RecycledViewPool 中
         */
        binding.rvBookshelf.setItemViewCacheSize(0)
        binding.rvBookshelf.adapter = null
    }

    override fun open(book: Book) {
        startActivityForBook(book)
    }

    override fun openBookInfo(book: Book) {
        BookInfoNavigator.open(requireContext(), book)
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    private fun updateComposeItems(list: List<Book>) {
        composeItems = buildBookshelfItems(
            groups = emptyList(),
            books = list,
            isRootGroup = false,
            isUpdating = ::isUpdate
        )
    }

    private fun onComposeItemClick(item: BookshelfItemUi) {
        (item as? BookshelfBookItemUi)?.book?.let(::open)
    }

    private fun onComposeItemLongClick(item: BookshelfItemUi) {
        (item as? BookshelfBookItemUi)?.book?.let(::openBookInfo)
    }

    private fun updateComposeItemUpdating(bookUrl: String) {
        composeItems = updateBookshelfItemUpdating(
            items = composeItems,
            bookUrl = bookUrl,
            isUpdating = ::isUpdate
        )
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            if (useComposeList) {
                updateComposeItemUpdating(it)
            } else {
                booksAdapter.notification(it)
            }
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            if (useComposeList) {
                updateComposeItems(getBooks())
            } else {
                booksAdapter.notifyDataSetChanged()
            }
            startLastUpdateTimeJob()
            upFastScrollerBar()
        }
    }
}
