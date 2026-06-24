package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.text.TextPaint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.annotation.RequiresApi
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ItemTextBinding
import io.legado.app.databinding.PopupActionMenuBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.getPrefString
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlin.math.ceil

@SuppressLint("RestrictedApi")
class TextActionMenu(private val context: Context, private val callBack: CallBack) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    private val binding = PopupActionMenuBinding.inflate(LayoutInflater.from(context))
    private val adapter = Adapter(context).apply {
        setHasStableIds(true)
    }
    private val allMenuItems: List<MenuItemImpl>
    private val visibleMenuItems = arrayListOf<MenuItemImpl>()
    private val moreMenuItems = arrayListOf<MenuItemImpl>()
    private var lastMaxMainWidth = 0
    private var mainMenuLayoutManager: GridLayoutManager? = null
    private var mainMenuItemOuterWidth = 70.dpToPx()
    private val itemTextPaint = TextPaint().apply {
        textSize = 12f * context.resources.displayMetrics.scaledDensity
        typeface = context.uiTypeface()
    }

    private val configuredActionIds: Set<String>
        get() = ContentSelectConfig.selectedActionIds(context)

    private val defaultOpenActionId: String
        get() = context.getPrefString(PreferKey.contentSelectDefaultOpen, "").orEmpty()

    private fun menuItemToActionId(itemId: Int): String? = when (itemId) {
        R.id.menu_web_search -> ContentSelectConfig.ACTION_WEB_SEARCH
        R.id.menu_replace -> ContentSelectConfig.ACTION_REPLACE
        R.id.menu_copy -> ContentSelectConfig.ACTION_COPY
        R.id.menu_bookmark -> ContentSelectConfig.ACTION_BOOKMARK
        R.id.menu_aloud -> ContentSelectConfig.ACTION_ALOUD
        R.id.menu_dict -> ContentSelectConfig.ACTION_DICT
        R.id.menu_ask_ai -> ContentSelectConfig.ACTION_ASK_AI
        R.id.menu_generate_image -> ContentSelectConfig.ACTION_GENERATE_IMAGE
        R.id.menu_ai_purify -> ContentSelectConfig.ACTION_AI_PURIFY
        R.id.menu_generate_video -> ContentSelectConfig.ACTION_GENERATE_VIDEO
        R.id.menu_generate_scene -> ContentSelectConfig.ACTION_GENERATE_SCENE
        else -> null
    }

    init {
        @SuppressLint("InflateParams")
        contentView = binding.root
        binding.root.applyUiBodyTypefaceDeep(context.uiTypeface())

        isTouchable = true
        isOutsideTouchable = false
        isFocusable = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = 14f.dpToPx()
        }

        val myMenu = MenuBuilder(context)
        val otherMenu = MenuBuilder(context)
        SupportMenuInflater(context).inflate(R.menu.content_select_action, myMenu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            onInitializeMenu(otherMenu)
        }
        allMenuItems = myMenu.visibleItems + otherMenu.visibleItems
        mainMenuLayoutManager = GridLayoutManager(context, 1)
        binding.recyclerView.layoutManager = mainMenuLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerViewMore.adapter = adapter
        setOnDismissListener {
            showMainMenu()
        }
        binding.ivMenuMore.setOnClickListener {
            if (binding.recyclerView.isVisible && moreMenuItems.isNotEmpty()) {
                showMoreMenu()
            } else {
                showMainMenu()
            }
        }
        upMenu()
    }

    private fun filteredMenuItems(): List<MenuItemImpl> {
        return allMenuItems.filter { item ->
            menuItemToActionId(item.itemId)?.let { configuredActionIds.contains(it) } ?: false
        }
    }

    fun upMenu() {
        upMenuForWidth(lastMaxMainWidth.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels)
    }

    private fun upMenuForWidth(maxMainWidth: Int) {
        lastMaxMainWidth = maxMainWidth
        visibleMenuItems.clear()
        moreMenuItems.clear()
        val filteredItems = filteredMenuItems()
        val maxWidth = (maxMainWidth - 16.dpToPx()).coerceAtLeast(140.dpToPx())
        val contentMaxWidth = (maxWidth - 12.dpToPx()).coerceAtLeast(120.dpToPx())
        val layout = gridMainMenu(filteredItems, contentMaxWidth)
        visibleMenuItems += layout.visible
        moreMenuItems += layout.overflow
        applyMainMenuLayout(layout)
        showMainMenu()
    }

    private fun estimateItemWidth(item: MenuItemImpl): Int {
        val textWidth = ceil(itemTextPaint.measureText(item.title?.toString().orEmpty())).toInt()
        return maxOf(52.dpToPx(), textWidth + 24.dpToPx())
            .coerceAtMost(maxMenuItemWidth()) + 6.dpToPx()
    }

    private fun maxMenuItemWidth(): Int = 132.dpToPx()

    private fun moreButtonWidth(): Int = 40.dpToPx()

    private fun mainRowHeight(): Int = 40.dpToPx()

    private fun gridMainMenu(items: List<MenuItemImpl>, contentMaxWidth: Int): MainMenuLayout {
        val noMoreColumns = mainMenuColumnCount(items.size, contentMaxWidth)
        val noMoreCapacity = noMoreColumns * 2
        val hasOverflow = items.size > noMoreCapacity
        val rowWidth = if (hasOverflow) {
            (contentMaxWidth - moreButtonWidth()).coerceAtLeast(96.dpToPx())
        } else {
            contentMaxWidth
        }
        val columns = mainMenuColumnCount(items.size.coerceAtMost(8), rowWidth)
        val capacity = columns * 2
        val visible = items.take(capacity)
        val overflow = items.drop(capacity)
        val rowCount = ceil(visible.size / columns.toFloat()).toInt().coerceIn(1, 2)
        val itemWidth = mainMenuItemWidth(visible, columns, rowWidth)
        return MainMenuLayout(
            visible = visible,
            overflow = overflow,
            rowCount = rowCount,
            columnCount = columns,
            itemWidth = itemWidth,
            width = itemWidth * columns
        )
    }

    private fun mainMenuColumnCount(itemCount: Int, rowWidth: Int): Int {
        if (itemCount <= 0) return 1
        val maxColumnsByWidth = (rowWidth / 58.dpToPx()).coerceIn(1, 4)
        val idealColumns = if (itemCount <= 4) {
            itemCount
        } else {
            ceil(itemCount / 2f).toInt()
        }
        return idealColumns.coerceIn(1, maxColumnsByWidth)
    }

    private fun mainMenuItemWidth(items: List<MenuItemImpl>, columns: Int, rowWidth: Int): Int {
        val measured = items.maxOfOrNull(::estimateItemWidth) ?: 64.dpToPx()
        val maxCellWidth = (rowWidth / columns).coerceAtLeast(1)
        return measured
            .coerceAtLeast(64.dpToPx())
            .coerceAtMost(maxCellWidth)
    }

    private fun applyMainMenuLayout(layout: MainMenuLayout) = binding.run {
        mainMenuItemOuterWidth = layout.itemWidth
        adapter.itemOuterWidth = layout.itemWidth
        mainMenuLayoutManager?.spanCount = layout.columnCount
        recyclerView.layoutParams = recyclerView.layoutParams.apply {
            width = layout.width
            height = layout.rowCount * mainRowHeight()
        }
    }

    private fun showMainMenu() = binding.run {
        ivMenuMore.setImageResource(R.drawable.ic_more_vert)
        recyclerViewMore.gone()
        adapter.itemOuterWidth = mainMenuItemOuterWidth
        adapter.setItems(visibleMenuItems)
        recyclerView.visible()
        ivMenuMore.visible(moreMenuItems.isNotEmpty())
    }

    private fun showMoreMenu() = binding.run {
        ivMenuMore.setImageResource(R.drawable.ic_arrow_back)
        val rowHeight = 42.dpToPx()
        adapter.itemOuterWidth = maxMenuItemWidth() + 6.dpToPx()
        recyclerViewMore.layoutParams = recyclerViewMore.layoutParams.apply {
            height = minOf(moreMenuItems.size * rowHeight, 240.dpToPx())
        }
        adapter.setItems(moreMenuItems)
        recyclerView.gone()
        recyclerViewMore.visible()
    }

    fun show(
        view: View,
        windowHeight: Int,
        startX: Int,
        startTopY: Int,
        startBottomY: Int,
        endX: Int,
        endBottomY: Int,
        reservedBottom: Int = 0
    ) {
        val defaultActionId = defaultOpenActionId
        if (defaultActionId.isNotEmpty() && configuredActionIds.contains(defaultActionId)) {
            val defaultItem = filteredMenuItems().firstOrNull { menuItemToActionId(it.itemId) == defaultActionId }
            if (defaultItem != null) {
                if (!callBack.onMenuItemSelected(defaultItem.itemId)) {
                    onMenuItemSelected(defaultItem)
                }
                callBack.onMenuActionFinally()
                return
            }
        }
        upMenuForWidth(view.width)
        contentView.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED,
        )
        val popupWidth = contentView.measuredWidth
        val popupHeight = contentView.measuredHeight
        val margin = 8.dpToPx()
        val safeWindowHeight = (windowHeight - reservedBottom).coerceAtLeast(margin * 2)
        val spaceAbove = startTopY
        val spaceBelow = safeWindowHeight - endBottomY
        val showAbove = spaceAbove >= popupHeight + margin || spaceAbove > spaceBelow
        val preferredX = ((startX + endX) / 2f - popupWidth / 2f).toInt()
        val maxX = (view.width - popupWidth - margin).coerceAtLeast(margin)
        val x = preferredX.coerceIn(margin, maxX)
        val y = if (showAbove) {
            (startTopY - popupHeight - margin).coerceAtLeast(margin)
        } else {
            (endBottomY + margin).coerceAtMost((safeWindowHeight - popupHeight - margin).coerceAtLeast(margin))
        }
        showAtLocation(view, Gravity.TOP or Gravity.START, x, y)
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<MenuItemImpl, ItemTextBinding>(context) {

        var itemOuterWidth: Int = 70.dpToPx()

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getViewBinding(parent: ViewGroup): ItemTextBinding {
            return ItemTextBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemTextBinding,
            item: MenuItemImpl,
            payloads: MutableList<Any>
        ) {
            with(binding) {
                val marginParams = textView.layoutParams as? ViewGroup.MarginLayoutParams
                if (marginParams != null) {
                    val horizontalMargin = marginParams.leftMargin + marginParams.rightMargin
                    marginParams.width = (itemOuterWidth - horizontalMargin).coerceAtLeast(52.dpToPx())
                    marginParams.height = 34.dpToPx()
                    textView.layoutParams = marginParams
                }
                textView.text = item.title
                textView.typeface = context.uiTypeface()
                textView.maxWidth = itemOuterWidth.coerceAtMost(maxMenuItemWidth())
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemTextBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    if (!callBack.onMenuItemSelected(it.itemId)) {
                        onMenuItemSelected(it)
                    }
                }
                callBack.onMenuActionFinally()
            }
            holder.itemView.setOnLongClickListener {
                if (AppConfig.contentSelectSpeakMod == 0) {
                    AppConfig.contentSelectSpeakMod = 1
                    context.toastOnUi(R.string.content_select_speak_from_selection)
                } else {
                    AppConfig.contentSelectSpeakMod = 0
                    context.toastOnUi(R.string.content_select_speak_selected)
                }
                true
            }
        }
    }

    private fun onMenuItemSelected(item: MenuItemImpl) {
        when (item.itemId) {
            R.id.menu_copy -> context.sendToClip(callBack.selectedText)
            R.id.menu_share_str -> context.share(callBack.selectedText)
            R.id.menu_browser -> {
                kotlin.runCatching {
                    val intent = if (callBack.selectedText.isAbsUrl()) {
                        Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse(callBack.selectedText)
                        }
                    } else {
                        Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, callBack.selectedText)
                        }
                    }
                    context.startActivity(intent)
                }.onFailure {
                    it.printOnDebug()
                    context.toastOnUi(it.localizedMessage ?: "ERROR")
                }
            }

            else -> item.intent?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    kotlin.runCatching {
                        it.putExtra(Intent.EXTRA_PROCESS_TEXT, callBack.selectedText)
                        context.startActivity(it)
                    }.onFailure { e ->
                        AppLog.put("执行文本菜单操作出错\n$e", e, true)
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createProcessTextIntent(): Intent {
        return Intent()
            .setAction(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getSupportedActivities(): List<ResolveInfo> {
        return context.packageManager
            .queryIntentActivities(createProcessTextIntent(), 0)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createProcessTextIntentForResolveInfo(info: ResolveInfo): Intent {
        return createProcessTextIntent()
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
    }

    /**
     * Start with a menu Item order value that is high enough
     * so that your "PROCESS_TEXT" menu items appear after the
     * standard selection menu items like Cut, Copy, Paste.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun onInitializeMenu(menu: Menu) {
        kotlin.runCatching {
            var menuItemOrder = 100
            for (resolveInfo in getSupportedActivities()) {
                menu.add(
                    Menu.NONE, Menu.NONE,
                    menuItemOrder++, resolveInfo.loadLabel(context.packageManager)
                ).intent = createProcessTextIntentForResolveInfo(resolveInfo)
            }
        }.onFailure {
            context.toastOnUi("获取文字操作菜单出错:${it.localizedMessage}")
        }
    }

    interface CallBack {
        val selectedText: String

        fun onMenuItemSelected(itemId: Int): Boolean

        fun onMenuActionFinally()
    }

    private data class MainMenuLayout(
        val visible: List<MenuItemImpl>,
        val overflow: List<MenuItemImpl>,
        val rowCount: Int,
        val columnCount: Int,
        val itemWidth: Int,
        val width: Int
    )
}
