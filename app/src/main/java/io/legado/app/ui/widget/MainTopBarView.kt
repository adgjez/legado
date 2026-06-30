package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.ComposeMainTopBar
import io.legado.app.ui.widget.compose.ComposeThemeImageCrop
import io.legado.app.ui.widget.compose.ComposeThemeImageLayer
import io.legado.app.ui.widget.compose.ComposeThemeImageState
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.MainTopBarAction
import io.legado.app.ui.widget.compose.MainTopBarActionState
import io.legado.app.ui.widget.compose.MainTopBarTagBarState
import io.legado.app.ui.widget.compose.MainTopBarTagItem
import io.legado.app.ui.widget.compose.MainTopBarUiState
import io.legado.app.ui.widget.compose.TopBarTagSlot
import io.legado.app.utils.ImageTypeUtils
import io.legado.app.utils.StatusBarInsetAware

class MainTopBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), StatusBarInsetAware {

    enum class Mode { BOOKSHELF, DISCOVERY, RSS, READ_RECORD }

    val titleSelect = StateAwareLinearLayout(context)
    val titleText = StateAwareTextView(context)
    val titleArrow = AppCompatImageView(context)
    val searchEntry = StateAwareLinearLayout(context)
    private val searchEntryText = StateAwareTextView(context)
    private val searchEntryIcon = AppCompatImageView(context)
    val moreButton = actionButton(R.drawable.ic_more_vert, R.string.menu)
    val searchButton = actionButton(R.drawable.ic_search, R.string.search)
    val filterButton = actionButton(R.drawable.ic_sort, R.string.sort)
    val starButton = actionButton(R.drawable.ic_star, R.string.favorite)
    val refreshButton = actionButton(R.drawable.ic_refresh_black_24dp, R.string.refresh)
    val loginButton = actionButton(R.drawable.ic_bottom_person, R.string.login)
    val primaryBar = RoundedTagBarView(context)
    val selectsBar = RoundedTagBarView(context)
    val tagsBar = RoundedTagBarView(context)

    private val filterToggleButton = actionButton(R.drawable.ic_expand_more, R.string.screen)
    private val surfaceLayout = ContentMeasuredFrameLayout(context)
    private val backgroundLayer = ComposeView(context).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }
    private val contentLayer = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }
    private val backdropGlass = StableLiquidGlassView(context).apply {
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val uiState = mutableStateOf<MainTopBarUiState?>(null)

    private var backdropGlassActive = false
    private var mode = Mode.BOOKSHELF
    private var styleSignature: String? = null
    private var primaryBarRequested = false
    private var selectsBarRequested = false
    private var tagsBarRequested = false
    private var filtersExpanded = false
    private var searchEntryRequested = true
    private var onHeightChanged: (() -> Unit)? = null
    private var onFilterExpandedChanged: ((Boolean) -> Unit)? = null
    private var statusBarInsetTop: Int = 0
    private var initialized = false

    var overlayOpaqueBackground = false
        set(value) {
            if (field == value) return
            field = value
            applyTopBarStyle(force = true, resetFilters = false)
        }

    init {
        orientation = VERTICAL
        clipChildren = true
        clipToPadding = false
        addView(surfaceLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        surfaceLayout.addView(
            backgroundLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        surfaceLayout.addView(
            backdropGlass,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        surfaceLayout.addView(
            contentLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        titleSelect.onStateChanged = ::renderContent
        searchEntry.onStateChanged = ::renderContent
        titleText.onStateChanged = ::renderContent
        searchEntryText.onStateChanged = ::renderContent
        listOf(moreButton, searchButton, filterButton, starButton, refreshButton, loginButton, filterToggleButton).forEach { proxy ->
            proxy.onStateChanged = ::renderContent
        }
        listOf(primaryBar, selectsBar, tagsBar).forEach { tagBar ->
            tagBar.setOnStateChangedListener(::renderContent)
        }
        filterToggleButton.setOnClickListener {
            filtersExpanded = !filtersExpanded
            renderContent()
            onFilterExpandedChanged?.invoke(filtersExpanded)
        }
        titleText.textSize = 24f
        titleText.includeFontPadding = false
        searchEntryText.textSize = 14f
        searchEntryText.includeFontPadding = false
        installComposeContent()
        initialized = true
        setMode(Mode.BOOKSHELF)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTopBarStyle(force = true)
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        moreButton.isVisible = mode == Mode.BOOKSHELF || mode == Mode.READ_RECORD
        searchButton.isVisible = mode == Mode.DISCOVERY || mode == Mode.RSS
        filterButton.isVisible = mode == Mode.DISCOVERY
        starButton.isVisible = mode == Mode.RSS
        refreshButton.isVisible = mode == Mode.RSS
        loginButton.isVisible = mode == Mode.DISCOVERY || mode == Mode.RSS
        titleText.textSize = if (mode == Mode.BOOKSHELF) 24f else 20f
        applyTopBarStyle(force = true)
    }

    fun setTitle(text: CharSequence) {
        titleText.text = text
        renderContent()
    }

    fun setSearchHint(text: CharSequence) {
        searchEntryText.text = text
        renderContent()
    }

    fun setSearchEntryVisible(visible: Boolean) {
        if (searchEntryRequested == visible) return
        searchEntryRequested = visible
        applyTopBarStyle(force = true)
    }

    fun setPrimaryItems(items: List<RoundedTagBarView.Item>, selectedIndex: Int) {
        primaryBarRequested = items.isNotEmpty()
        primaryBar.submitItems(items, selectedIndex)
        renderContent()
    }

    fun isRegularStyle(): Boolean {
        return TopBarConfig.currentConfig(context, AppConfig.isNightTheme).style == TopBarConfig.STYLE_REGULAR
    }

    fun isOverlayMode(): Boolean {
        return isRegularStyle()
    }

    fun supportsBackdropBlur(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun setBackdropBlur(source: ViewGroup?) {
        val active = source != null && supportsBackdropBlur()
        backdropGlassActive = active
        if (active) {
            val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
            val radius = if (isRegularStyle()) TopBarConfig.cornerRadius(context, config) else 0f
            val density = resources.displayMetrics.density
            backdropGlass.visibility = View.VISIBLE
            backdropGlass.setCornerRadius(radius)
            backdropGlass.setBlurRadius(18f * density)
            backdropGlass.setTintAlpha(0.10f)
            backdropGlass.setDispersion(0.10f)
            backdropGlass.setRefractionHeight(14f * density)
            backdropGlass.setRefractionOffset(26f * density)
            backdropGlass.bind(source)
        } else {
            backdropGlass.visibility = View.GONE
            backdropGlass.bind(null)
        }
        applyTopBarStyle(force = true, resetFilters = false)
    }

    fun refreshStyle() {
        styleSignature = null
        listOf(primaryBar, selectsBar, tagsBar).forEach { it.applyTopBarStyle(force = true) }
        applyTopBarStyle(force = true)
        requestLayout()
        invalidate()
    }

    fun setOnHeightChangedListener(listener: (() -> Unit)?) {
        onHeightChanged = listener
        notifyHeightChangedAfterLayout()
    }

    fun setOnFilterExpandedChangedListener(listener: ((Boolean) -> Unit)?) {
        onFilterExpandedChanged = listener
    }

    fun setFiltersExpanded(expanded: Boolean) {
        if (filtersExpanded == expanded) {
            renderContent()
            return
        }
        filtersExpanded = expanded
        renderContent()
    }

    fun showSelects(show: Boolean) {
        selectsBarRequested = show
        if (!show) {
            filtersExpanded = tagsBarRequested && filtersExpanded
        }
        renderContent()
    }

    fun showTags(show: Boolean) {
        tagsBarRequested = show
        if (!show) {
            filtersExpanded = selectsBarRequested && filtersExpanded
        }
        renderContent()
    }

    fun setActionsVisible(
        search: Boolean? = null,
        filter: Boolean? = null,
        star: Boolean? = null,
        refresh: Boolean? = null,
        login: Boolean? = null
    ) {
        search?.let { searchButton.isVisible = it }
        filter?.let { filterButton.isVisible = it }
        star?.let { starButton.isVisible = it }
        refresh?.let { refreshButton.isVisible = it }
        login?.let { loginButton.isVisible = it }
        renderContent()
    }

    override fun onStatusBarInsetChanged(insetTop: Int, initialPaddingTop: Int) {
        if (statusBarInsetTop == insetTop) return
        statusBarInsetTop = insetTop
        applyTopBarStyle(force = true, resetFilters = false)
        notifyHeightChangedAfterLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) {
            notifyHeightChangedAfterLayout()
        }
    }

    private fun installComposeContent() {
        contentLayer.setContent {
            LegadoComposeTheme {
                uiState.value?.let { state ->
                    ComposeMainTopBar(
                        state = state,
                        onTitleClick = { titleSelect.performClick() },
                        onTitleLongClick = { titleSelect.performLongClick() },
                        onSearchEntryClick = { searchEntry.performClick() },
                        onActionClick = ::dispatchActionClick,
                        onTagClick = ::dispatchTagClick,
                        onTagLongClick = ::dispatchTagLongClick
                    )
                }
            }
        }
    }

    private fun dispatchActionClick(action: MainTopBarAction) {
        when (action) {
            MainTopBarAction.SEARCH -> searchButton.performClick()
            MainTopBarAction.FILTER -> filterButton.performClick()
            MainTopBarAction.STAR -> starButton.performClick()
            MainTopBarAction.REFRESH -> refreshButton.performClick()
            MainTopBarAction.LOGIN -> loginButton.performClick()
            MainTopBarAction.MORE -> moreButton.performClick()
            MainTopBarAction.FILTER_TOGGLE -> filterToggleButton.performClick()
        }
    }

    private fun dispatchTagClick(slot: TopBarTagSlot, index: Int) {
        when (slot) {
            TopBarTagSlot.PRIMARY -> primaryBar.dispatchTagClick(index)
            TopBarTagSlot.SELECTS -> selectsBar.dispatchTagClick(index)
            TopBarTagSlot.TAGS -> tagsBar.dispatchTagClick(index)
        }
    }

    private fun dispatchTagLongClick(slot: TopBarTagSlot, index: Int): Boolean {
        return when (slot) {
            TopBarTagSlot.PRIMARY -> primaryBar.dispatchTagLongClick(index)
            TopBarTagSlot.SELECTS -> selectsBar.dispatchTagLongClick(index)
            TopBarTagSlot.TAGS -> tagsBar.dispatchTagLongClick(index)
        }
    }

    private fun applyTopBarStyle(force: Boolean = false, resetFilters: Boolean = force) {
        val signature = "${TopBarConfig.currentSignature(AppConfig.isNightTheme)}|$mode"
        if (!force && styleSignature == signature) return
        val signatureChanged = styleSignature != signature
        styleSignature = signature
        val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
        if (resetFilters && (force || signatureChanged)) {
            filtersExpanded = config.style == TopBarConfig.STYLE_REGULAR && config.expandFiltersByDefault
        }
        if (config.style == TopBarConfig.STYLE_REGULAR) {
            applyRegularStyle(config)
        } else {
            applyDefaultStyle()
        }
        renderContent()
    }

    private fun applyDefaultStyle() {
        background = if (overlayOpaqueBackground && !backdropGlassActive) {
            ColorDrawable(context.backgroundColor)
        } else {
            null
        }
        renderBackgroundLayer(null, 0f)
        listOf(primaryBar, selectsBar, tagsBar).forEach {
            it.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
            it.setBackgroundOverrideColor(null)
            it.setSelectedBackgroundVisible(true)
        }
    }

    private fun applyRegularStyle(config: TopBarConfig.Config) {
        background = if (overlayOpaqueBackground && !backdropGlassActive) {
            ColorDrawable(context.backgroundColor)
        } else {
            null
        }
        val hideConfigBg = overlayOpaqueBackground || backdropGlassActive
        renderBackgroundLayer(
            config.takeUnless { hideConfigBg },
            if (hideConfigBg) 0f else TopBarConfig.cornerRadius(context, config)
        )
        primaryBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        selectsBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        tagsBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        primaryBar.setBackgroundOverrideColor(null)
        selectsBar.setBackgroundOverrideColor(null)
        tagsBar.setBackgroundOverrideColor(null)
        primaryBar.setSelectedBackgroundVisible(true)
        selectsBar.setSelectedBackgroundVisible(true)
        tagsBar.setSelectedBackgroundVisible(mode == Mode.DISCOVERY)
    }

    private fun renderContent() {
        if (!initialized) return
        uiState.value = buildUiState()
        requestLayout()
        invalidate()
        notifyHeightChangedAfterLayout()
    }

    private fun buildUiState(): MainTopBarUiState {
        val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
        val isRegular = config.style == TopBarConfig.STYLE_REGULAR
        val hasFilters = selectsBarRequested || tagsBarRequested
        val filterToggleVisible = isRegular && hasFilters &&
            !(filtersExpanded && config.hideFilterToggleWhenExpanded)
        val selectsVisible = if (isRegular) {
            filtersExpanded && selectsBarRequested
        } else {
            selectsBarRequested
        }
        val tagsVisible = if (isRegular) {
            filtersExpanded && tagsBarRequested
        } else {
            tagsBarRequested
        }
        return MainTopBarUiState(
            mode = mode,
            config = config,
            statusBarInsetTopPx = statusBarInsetTop,
            title = titleText.text?.toString().orEmpty(),
            titleTextSizeSp = titleText.textSize / resources.displayMetrics.scaledDensity,
            titleMaxWidthPx = titleText.maxWidthOverride,
            titleEnabled = titleSelect.isEnabled,
            titleAlpha = titleSelect.alpha,
            searchHint = searchEntryText.text?.toString().orEmpty(),
            searchEntryRequested = searchEntryRequested,
            searchEntryEnabled = searchEntry.isEnabled,
            searchEntryAlpha = searchEntry.alpha,
            filtersExpanded = filtersExpanded,
            primaryBar = tagState(primaryBar, isRegular && primaryBarRequested),
            selectsBar = tagState(selectsBar, selectsVisible),
            tagsBar = tagState(tagsBar, tagsVisible),
            actions = listOf(
                actionState(MainTopBarAction.SEARCH, searchButton, computedSearchVisible(config)),
                actionState(MainTopBarAction.FILTER, filterButton, filterButton.isVisible),
                actionState(MainTopBarAction.STAR, starButton, starButton.isVisible),
                actionState(MainTopBarAction.REFRESH, refreshButton, refreshButton.isVisible),
                actionState(MainTopBarAction.LOGIN, loginButton, loginButton.isVisible),
                actionState(MainTopBarAction.MORE, moreButton, moreButton.isVisible),
                actionState(
                    MainTopBarAction.FILTER_TOGGLE,
                    filterToggleButton,
                    filterToggleVisible,
                    rotation = if (filtersExpanded) 180f else 0f
                )
            )
        )
    }

    private fun computedSearchVisible(config: TopBarConfig.Config): Boolean {
        if (config.style != TopBarConfig.STYLE_REGULAR && mode == Mode.BOOKSHELF) {
            return config.showSearchInDefaultStyle || isFloatingSearchHidden()
        }
        return searchButton.isVisible
    }

    private fun isFloatingSearchHidden(): Boolean {
        return AppConfig.bottomBarLayoutMode == "floating" && AppConfig.floatingBottomBarHideSearch
    }

    private fun actionState(
        action: MainTopBarAction,
        view: StateAwareImageButton,
        visible: Boolean,
        rotation: Float = 0f
    ): MainTopBarActionState {
        return MainTopBarActionState(
            action = action,
            iconRes = view.iconRes,
            contentDescription = view.contentDescription?.toString().orEmpty(),
            visible = visible,
            enabled = view.isEnabled,
            alpha = view.alpha,
            rotationDegrees = rotation
        )
    }

    private fun tagState(
        view: RoundedTagBarView,
        visible: Boolean
    ): MainTopBarTagBarState {
        val snapshot = view.snapshot()
        return MainTopBarTagBarState(
            items = snapshot.items.map {
                MainTopBarTagItem(
                    text = it.text.toString(),
                    alpha = it.alpha
                )
            },
            selectedIndex = snapshot.selectedIndex,
            visible = visible && snapshot.items.isNotEmpty(),
            selectedBackgroundVisible = snapshot.selectedBackgroundVisible,
            displayMode = snapshot.displayMode
        )
    }

    private fun renderBackgroundLayer(config: TopBarConfig.Config?, radius: Float) {
        val state = if (config == null) {
            ComposeThemeImageState(
                file = null,
                fallbackColor = Color.TRANSPARENT
            )
        } else {
            val file = TopBarConfig.currentWallpaperFile(context, AppConfig.isNightTheme)
            val alpha = config.wallpaperAlpha.coerceIn(0, 100) / 100f
            ComposeThemeImageState(
                file = file,
                animated = ImageTypeUtils.isAnimatedImage(file),
                alpha = alpha,
                crop = topBarWallpaperCrop(config),
                fallbackColor = TopBarConfig.withOpacity(
                    TopBarConfig.resolveBackgroundColor(config),
                    config.wallpaperAlpha
                )
            )
        }
        backgroundLayer.setContent {
            ComposeThemeImageLayer(
                state = state,
                cornerRadius = (radius / resources.displayMetrics.density).dp,
                stableWidthScale = true
            )
        }
    }

    private fun topBarWallpaperCrop(config: TopBarConfig.Config): ComposeThemeImageCrop? {
        val left = config.wallpaperCropLeft ?: return null
        val top = config.wallpaperCropTop ?: return null
        val right = config.wallpaperCropRight ?: return null
        val bottom = config.wallpaperCropBottom ?: return null
        if (right <= left || bottom <= top) return null
        return ComposeThemeImageCrop(left, top, right, bottom)
    }

    private fun notifyHeightChangedAfterLayout() {
        post { onHeightChanged?.invoke() }
    }

    private fun actionButton(drawableRes: Int, contentDescRes: Int): StateAwareImageButton {
        return StateAwareImageButton(context, drawableRes).apply {
            setImageResource(drawableRes)
            contentDescription = context.getString(contentDescRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
    }

    private class ContentMeasuredFrameLayout(context: Context) : FrameLayout(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val content = getChildAt(childCount - 1)
            if (content == null) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            measureChildWithMargins(content, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val contentLp = content.layoutParams as MarginLayoutParams
            val measuredWidth = resolveSize(
                paddingLeft + paddingRight + content.measuredWidth +
                    contentLp.leftMargin + contentLp.rightMargin,
                widthMeasureSpec
            )
            val contentHeight = paddingTop + paddingBottom + content.measuredHeight +
                contentLp.topMargin + contentLp.bottomMargin
            val measuredHeight = resolveSize(contentHeight, heightMeasureSpec)
            setMeasuredDimension(measuredWidth, measuredHeight)
            for (i in 0 until childCount - 1) {
                getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
                )
            }
        }
    }

    class StateAwareLinearLayout(context: Context) : LinearLayout(context) {
        var onStateChanged: (() -> Unit)? = null

        override fun setVisibility(visibility: Int) {
            if (visibility == getVisibility()) {
                super.setVisibility(visibility)
                return
            }
            super.setVisibility(visibility)
            onStateChanged?.invoke()
        }

        override fun setEnabled(enabled: Boolean) {
            if (enabled == isEnabled) {
                super.setEnabled(enabled)
                return
            }
            super.setEnabled(enabled)
            onStateChanged?.invoke()
        }

        override fun setAlpha(alpha: Float) {
            if (alpha == getAlpha()) {
                super.setAlpha(alpha)
                return
            }
            super.setAlpha(alpha)
            onStateChanged?.invoke()
        }
    }

    class StateAwareTextView(context: Context) : TextView(context) {
        var onStateChanged: (() -> Unit)? = null
        var maxWidthOverride: Int? = null
            private set

        override fun setMaxWidth(maxPixels: Int) {
            maxWidthOverride = maxPixels.takeIf { it > 0 }
            super.setMaxWidth(maxPixels)
            onStateChanged?.invoke()
        }

        override fun setTextSize(size: Float) {
            super.setTextSize(size)
            onStateChanged?.invoke()
        }

        override fun setAlpha(alpha: Float) {
            if (alpha == getAlpha()) {
                super.setAlpha(alpha)
                return
            }
            super.setAlpha(alpha)
            onStateChanged?.invoke()
        }
    }

    class StateAwareImageButton(
        context: Context,
        iconRes: Int
    ) : AppCompatImageButton(context) {
        var onStateChanged: (() -> Unit)? = null
        var iconRes: Int = iconRes
            private set

        override fun setImageResource(resId: Int) {
            iconRes = resId
            super.setImageResource(resId)
            onStateChanged?.invoke()
        }

        override fun setContentDescription(contentDescription: CharSequence?) {
            super.setContentDescription(contentDescription)
            onStateChanged?.invoke()
        }

        override fun setVisibility(visibility: Int) {
            if (visibility == getVisibility()) {
                super.setVisibility(visibility)
                return
            }
            super.setVisibility(visibility)
            onStateChanged?.invoke()
        }

        override fun setEnabled(enabled: Boolean) {
            if (enabled == isEnabled) {
                super.setEnabled(enabled)
                return
            }
            super.setEnabled(enabled)
            onStateChanged?.invoke()
        }

        override fun setAlpha(alpha: Float) {
            if (alpha == getAlpha()) {
                super.setAlpha(alpha)
                return
            }
            super.setAlpha(alpha)
            onStateChanged?.invoke()
        }
    }
}
