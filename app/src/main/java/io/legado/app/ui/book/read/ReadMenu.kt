package io.legado.app.ui.book.read

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.databinding.ViewReadMenuBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.book.library.LibraryCloudState
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.buttonDisabledColor
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.UiCorner
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible
import splitties.views.onClick
import androidx.core.graphics.toColorInt
import io.legado.app.constant.BookType
import io.legado.app.utils.buildMainHandler
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 阅读界面菜单
 */
class ReadMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var canShowMenu: Boolean = false
    private val callBack: CallBack get() = activity as CallBack
    private val binding = ViewReadMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private var confirmSkipToChapter: Boolean = false
    private var isMenuOutAnimating = false
    private val menuTopIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_in)
    }
    private val menuTopOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_out)
    }
    private val menuBottomIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_in)
    }
    private val menuBottomOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_out)
    }
    private val immersiveMenu: Boolean
        get() = AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0
    private var bgColor: Int = if (immersiveMenu) {
        kotlin.runCatching {
            ReadBookConfig.durConfig.curBgStr().toColorInt()
        }.getOrDefault(context.bottomBackground)
    } else {
        context.bottomBackground
    }
    private var textColor: Int = if (immersiveMenu) {
        ReadBookConfig.durConfig.curTextColor()
    } else {
        context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
    }

    private var onMenuOutEnd: (() -> Unit)? = null
    private val showBrightnessView
        get() = context.getPrefBoolean(
            PreferKey.showBrightnessView,
            true
        )
    private var modernMenuPopup: PopupWindow? = null
    private var currentChapterUrl: String? = null
    private var cloudState: LibraryCloudState = LibraryCloudState.DISABLED
    private var autoPageActive: Boolean = false
    private val renderedButtons = hashMapOf<String, RenderedButton>()
    private var customButtonMetadata: Map<Long, ReadMenuCustomButton> = emptyMap()

    private data class RenderedButton(
        val ref: ReadMenuButtonConfig.ButtonRef,
        val icon: ImageView,
        val label: TextView?
    )
    private val menuInListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            binding.tvSourceAction.text =
                ReadBook.bookSource?.bookSourceName ?: context.getString(R.string.book_source)
            binding.tvSourceAction.isGone = ReadBook.isLocalBook
            binding.ivCloudLibrary.isVisible = callBack.isLibraryCloudEnabled()
            updateCloudLibraryIcon(callBack.libraryCloudState())
            ReadBook.bookSource?.let {
                if (it.customButton) {
                    binding.tvCustomBtn.visibility = VISIBLE
                }
            }
            callBack.upSystemUiVisibility()
            updateBrightnessSectionVisibility()
        }

        @SuppressLint("RtlHardcoded")
        override fun onAnimationEnd(animation: Animation) {
            binding.vwMenuBg.setOnClickListener { runMenuOut() }
            callBack.upSystemUiVisibility()
            if (!LocalConfig.readMenuHelpVersionIsLast) {
                callBack.showHelp()
            }
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }
    private val menuOutListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            isMenuOutAnimating = true
            binding.vwMenuBg.setOnClickListener(null)
        }

        override fun onAnimationEnd(animation: Animation) {
            this@ReadMenu.invisible()
            binding.titleBar.invisible()
            binding.bottomMenu.invisible()
            canShowMenu = false
            isMenuOutAnimating = false
            onMenuOutEnd?.invoke()
            callBack.upSystemUiVisibility()
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }

    init {
        binding.titleBar.applyStatusBarPadding(withInitialPadding = true)
        binding.root.applyUiBodyTypefaceDeep(context.uiTypeface())
        initView()
        upBrightnessState()
        bindEvent()
    }

    private fun createPanelDrawable(
        radiusPx: Float,
        color: Int,
        strokeColor: Int,
        topOnly: Boolean = false
    ) = GradientDrawable().apply {
        if (topOnly) {
            cornerRadii = floatArrayOf(
                radiusPx, radiusPx,
                radiusPx, radiusPx,
                0f, 0f,
                0f, 0f
            )
        } else {
            cornerRadius = radiusPx
        }
        setColor(color)
        setStroke(1.dpToPx(), strokeColor)
    }

    private fun createFillDrawable(color: Int) = GradientDrawable().apply {
        setColor(color)
    }

    private fun initView(reset: Boolean = false) = binding.run {
        initAnimation()
        val paletteBaseColor = if (immersiveMenu) bgColor else context.bottomBackground
        val palette = ReaderSheetStyle.resolve(context, paletteBaseColor)
        tvCustomBtn.setColorFilter(palette.accentColor)
        val primaryTextColor = palette.textColor
        titleBar.setTextColor(primaryTextColor)
        titleBar.setColorFilter(primaryTextColor)
        tvChapterName.setTextColor(primaryTextColor)
        tvChapterUrl.setTextColor(
            ColorUtils.withAlpha(primaryTextColor, 0.72f)
        )
        val menuOpacity = (AppConfig.readMenuAlpha / 100f).coerceIn(0.35f, 1f)
        val isBgLight = ColorUtils.isColorLight(bgColor)
        val headerBaseColor = ColorUtils.blendColors(
            palette.surface,
            palette.primaryColor,
            if (isBgLight) 0.14f else 0.24f
        )
        val sheetBaseColor = ColorUtils.blendColors(
            palette.surface,
            palette.panel,
            if (isBgLight) 0.72f else 0.82f
        )
        val actionBaseColor = ColorUtils.blendColors(
            palette.panelStrong,
            palette.primaryColor,
            if (isBgLight) 0.18f else 0.28f
        )
        val sheetColor = ColorUtils.withAlpha(sheetBaseColor, menuOpacity)
        val headerColor = ColorUtils.withAlpha(headerBaseColor, menuOpacity)
        val actionColor = ColorUtils.withAlpha(actionBaseColor, menuOpacity)
        val panelStrokeColor = palette.stroke
        vwMenuBg.setBackgroundColor(0x00000000)
        if (AppConfig.isEInkMode) {
            titleBar.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            titleBar.toolbar.background = null
            titleBarAddition.background = null
            llTitleInfo.background = null
            tvSourceAction.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            bottomMenu.background = null
            llBottomBg.setBackgroundResource(R.drawable.bg_eink_border_top)
        } else {
            titleBar.background = createFillDrawable(headerColor)
            titleBar.toolbar.background = null
            titleBarAddition.background = null
            llTitleInfo.background = null
            bottomMenu.background = null
            llBottomBg.background = createPanelDrawable(
                UiCorner.panelRadius(context),
                sheetColor,
                panelStrokeColor,
                topOnly = true
            )
            quickActionBarContainer.background = null
            llFloatingButton.background = null
            llBrightness.background = null
            llChapterPanel.background = null
            llActionPanel.background = null
            val actionRadius = UiCorner.actionRadius(context)
            tvSourceAction.background = createPanelDrawable(actionRadius, actionColor, panelStrokeColor)
            tvPre.background = createPanelDrawable(actionRadius, actionColor, panelStrokeColor)
            tvNext.background = createPanelDrawable(actionRadius, actionColor, panelStrokeColor)
        }
        tvSourceAction.setTextColor(primaryTextColor)
        tvPre.setTextColor(primaryTextColor)
        tvNext.setTextColor(primaryTextColor)
        tvBrightnessLabel.setTextColor(primaryTextColor)
        ivBrightnessAuto.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        vwBrightnessPosAdjust.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        llBrightness.setOnClickListener(null)
        updateBrightnessSectionVisibility()
        seekBrightness.post {
            seekBrightness.progress = AppConfig.readBrightness
        }
        if (AppConfig.showReadTitleBarAddition) {
            titleBarAddition.visible()
        } else {
            titleBarAddition.gone()
        }
        upBrightnessVwPos()
        /**
         * 确保视图不被导航栏遮挡
         */
        bottomMenu.applyNavigationBarPadding()
        renderReadMenuButtons(
            primaryTextColor = primaryTextColor,
            secondaryTextColor = palette.secondaryTextColor
        )
    }

    private fun renderReadMenuButtons(
        primaryTextColor: Int,
        secondaryTextColor: Int
    ) = binding.run {
        val layout = ReadMenuButtonConfig.load(context)
        customButtonMetadata = if ((layout.firstRow + layout.secondRow).any { it.type == ReadMenuButtonConfig.TYPE_CUSTOM }) {
            appDb.readMenuCustomButtonDao.all().associateBy { it.id }
        } else {
            emptyMap()
        }
        renderedButtons.clear()
        val hasFirstRow = layout.firstRow.isNotEmpty()
        val hasSecondRow = layout.secondRow.isNotEmpty()
        val hasButtons = hasFirstRow || hasSecondRow
        quickActionBarContainer.isVisible = hasButtons
        dividerActionRows.isVisible = hasFirstRow && hasSecondRow
        updateBrightnessSectionVisibility(hasButtons)
        renderButtonRow(llFloatingButton, layout.firstRow, primaryTextColor, secondaryTextColor)
        renderButtonRow(llActionPanel, layout.secondRow, primaryTextColor, primaryTextColor)
        updateAutoPageButton()
    }

    private fun renderButtonRow(
        rowContainer: LinearLayout,
        refs: List<ReadMenuButtonConfig.ButtonRef>,
        primaryTextColor: Int,
        secondaryTextColor: Int
    ) {
        rowContainer.removeAllViews()
        rowContainer.isVisible = refs.isNotEmpty()
        if (refs.isEmpty()) return
        if (refs.size <= MENU_BUTTONS_PER_PAGE) {
            val page = createButtonPage(refs, primaryTextColor, secondaryTextColor, fillEmptySlots = false).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            rowContainer.addView(page)
            return
        }
        val scrollView = MenuButtonPagerScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        rowContainer.addView(scrollView)
        rowContainer.doOnLayout {
            val pageWidth = rowContainer.width.takeIf { it > 0 } ?: return@doOnLayout
            if (scrollView.parent !== rowContainer || scrollView.childCount > 0) return@doOnLayout
            val pageRefs = refs.chunked(MENU_BUTTONS_PER_PAGE)
            val pagesContainer = LinearLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    pageWidth * pageRefs.size,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
            }
            pageRefs.forEach { refsInPage ->
                pagesContainer.addView(
                    createButtonPage(refsInPage, primaryTextColor, secondaryTextColor, fillEmptySlots = true).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            pageWidth,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                )
            }
            scrollView.addView(pagesContainer)
            scrollView.setPageCount(pageRefs.size)
        }
    }

    private fun createButtonPage(
        refs: List<ReadMenuButtonConfig.ButtonRef>,
        primaryTextColor: Int,
        secondaryTextColor: Int,
        fillEmptySlots: Boolean
    ) = LinearLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        isBaselineAligned = false
        orientation = LinearLayout.HORIZONTAL
        refs.forEach { ref ->
            addView(createMenuButton(ref, primaryTextColor, secondaryTextColor))
        }
        if (fillEmptySlots) {
            repeat((MENU_BUTTONS_PER_PAGE - refs.size).coerceAtLeast(0)) {
                addView(Space(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f
                    )
                })
            }
        }
    }

    private fun createMenuButton(
        ref: ReadMenuButtonConfig.ButtonRef,
        primaryTextColor: Int,
        labelColor: Int
    ): View {
        val title = buttonTitle(ref)
        val container = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            minimumHeight = 66.dpToPx()
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            isClickable = true
            isFocusable = true
            setOnClickListener { handleMenuButtonClick(ref) }
            setOnLongClickListener { handleMenuButtonLongClick(ref) }
        }
        val icon = AppCompatImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                24.dpToPx(),
                24.dpToPx()
            )
            contentDescription = title
            setButtonIcon(this, ref, primaryTextColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val label = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6.dpToPx()
            }
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            maxLines = 1
            text = title
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        container.addView(icon)
        container.addView(label)
        renderedButtons[buttonKey(ref)] = RenderedButton(ref, icon, label)
        return container
    }

    private fun handleMenuButtonClick(ref: ReadMenuButtonConfig.ButtonRef) {
        when (ref.type) {
            ReadMenuButtonConfig.TYPE_BUILTIN -> handleBuiltinMenuButtonClick(ref.id)
            ReadMenuButtonConfig.TYPE_CUSTOM -> ref.id.toLongOrNull()?.let {
                callBack.runCustomReadMenuButton(it)
            }
        }
    }

    private fun handleBuiltinMenuButtonClick(id: String) {
        when (id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> runMenuOut {
                callBack.openSearchActivity(null)
            }
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> runMenuOut {
                callBack.autoPage()
            }
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> callBack.openReplaceRule()
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> {
                AppConfig.isNightTheme = !AppConfig.isNightTheme
                ThemeConfig.applyDayNight(context)
                callBack.onNightThemeChanged()
            }
            ReadMenuButtonConfig.Builtin.CATALOG -> runMenuOut {
                callBack.openChapterList()
            }
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> runMenuOut {
                callBack.showReadAloudDialog()
            }
            ReadMenuButtonConfig.Builtin.READ_STYLE -> runMenuOut {
                callBack.showReadStyle()
            }
            ReadMenuButtonConfig.Builtin.SETTING -> runMenuOut {
                callBack.showMoreSetting()
            }
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> runMenuOut {
                callBack.openReadAssistant()
            }
            ReadMenuButtonConfig.Builtin.AI_SUMMARY -> runMenuOut {
                callBack.openReadAiSummary()
            }
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> runMenuOut {
                callBack.showParagraphRuleQuickDialog()
            }
            ReadMenuButtonConfig.Builtin.CHARACTERS -> runMenuOut {
                callBack.openBookCharacters()
            }
        }
    }

    private fun handleMenuButtonLongClick(ref: ReadMenuButtonConfig.ButtonRef): Boolean {
        return when {
            ref.type == ReadMenuButtonConfig.TYPE_BUILTIN &&
                    ref.id == ReadMenuButtonConfig.Builtin.READ_ALOUD -> {
                runMenuOut {
                    callBack.showReadAloudDialog()
                }
                true
            }
            ref.type == ReadMenuButtonConfig.TYPE_CUSTOM -> {
                ref.id.toLongOrNull()?.let { id ->
                    runMenuOut { callBack.loginCustomReadMenuButton(id) }
                }
                true
            }
            else -> false
        }
    }

    private fun buttonTitle(ref: ReadMenuButtonConfig.ButtonRef): String {
        ref.titleOverride.trim().takeIf { it.isNotBlank() }?.let { return it }
        return when (ref.type) {
            ReadMenuButtonConfig.TYPE_CUSTOM -> {
                ref.id.toLongOrNull()
                    ?.let { customButtonMetadata[it]?.displayName() }
                    ?: ref.id
            }
            else -> when (ref.id) {
                ReadMenuButtonConfig.Builtin.SEARCH -> context.getString(R.string.search_content)
                ReadMenuButtonConfig.Builtin.AUTO_PAGE -> context.getString(R.string.auto_next_page)
                ReadMenuButtonConfig.Builtin.REPLACE_RULE -> context.getString(R.string.replace_rule_title)
                ReadMenuButtonConfig.Builtin.NIGHT_THEME -> context.getString(R.string.dark_theme)
                ReadMenuButtonConfig.Builtin.CATALOG -> context.getString(R.string.chapter_list)
                ReadMenuButtonConfig.Builtin.READ_ALOUD -> context.getString(R.string.read_aloud)
                ReadMenuButtonConfig.Builtin.READ_STYLE -> context.getString(R.string.interface_setting)
                ReadMenuButtonConfig.Builtin.SETTING -> context.getString(R.string.setting)
                ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> context.getString(R.string.ai_assistant)
                ReadMenuButtonConfig.Builtin.AI_SUMMARY -> "AI总结"
                ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> context.getString(R.string.paragraph_rule)
                ReadMenuButtonConfig.Builtin.CHARACTERS -> "角色"
                else -> ref.id
            }
        }
    }

    private fun buttonIconRes(ref: ReadMenuButtonConfig.ButtonRef): Int {
        if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) return R.drawable.ic_custom
        return when (ref.id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> R.drawable.ic_search
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> {
                if (autoPageActive) R.drawable.ic_auto_page_stop else R.drawable.ic_auto_page
            }
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> R.drawable.ic_find_replace
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> {
                if (AppConfig.isNightTheme) R.drawable.ic_daytime else R.drawable.ic_brightness
            }
            ReadMenuButtonConfig.Builtin.CATALOG -> R.drawable.ic_toc
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> R.drawable.ic_read_aloud
            ReadMenuButtonConfig.Builtin.READ_STYLE -> R.drawable.ic_interface_setting
            ReadMenuButtonConfig.Builtin.SETTING -> R.drawable.ic_settings
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> R.drawable.ic_bottom_ai_assistant
            ReadMenuButtonConfig.Builtin.AI_SUMMARY -> R.drawable.ic_bottom_ai_assistant
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> R.drawable.ic_code
            ReadMenuButtonConfig.Builtin.CHARACTERS -> R.drawable.ic_bottom_person
            else -> R.drawable.ic_custom
        }
    }

    private fun setButtonIcon(
        imageView: ImageView,
        ref: ReadMenuButtonConfig.ButtonRef,
        color: Int
    ) {
        val customIconPath = if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) {
            ref.id.toLongOrNull()?.let { customButtonMetadata[it]?.iconPath }
        } else {
            null
        }
        imageView.setImageDrawable(
            ReadMenuButtonIconHelper.drawable(
                context,
                ref,
                buttonIconRes(ref),
                customIconPath
            )
        )
        imageView.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun buttonKey(ref: ReadMenuButtonConfig.ButtonRef): String {
        return "${ref.type}:${ref.id}"
    }

    private fun builtinButtonKey(id: String): String {
        return "${ReadMenuButtonConfig.TYPE_BUILTIN}:$id"
    }

    private fun updateAutoPageButton() {
        val rendered = renderedButtons[builtinButtonKey(ReadMenuButtonConfig.Builtin.AUTO_PAGE)]
        rendered?.icon?.apply {
            setButtonIcon(this, rendered.ref, textColor)
            contentDescription = context.getString(
                if (autoPageActive) R.string.auto_next_page_stop else R.string.auto_next_page
            )
        }
    }

    fun reset() {
        upColorConfig()
        initView(true)
    }

    fun refreshMenuColorFilter() {
        if (immersiveMenu) {
            binding.titleBar.setColorFilter(textColor)
        }
    }

    private fun upColorConfig() {
        bgColor = if (immersiveMenu) {
            kotlin.runCatching {
                ReadBookConfig.durConfig.curBgStr().toColorInt()
            }.getOrDefault(context.bottomBackground)
        } else {
            context.bottomBackground
        }
        textColor = if (immersiveMenu) {
            ReadBookConfig.durConfig.curTextColor()
        } else {
            context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        }
    }

    fun upBrightnessState() {
        updateBrightnessSectionVisibility()
        if (brightnessAuto()) {
            binding.ivBrightnessAuto.setColorFilter(ReaderSheetStyle.resolve(context).accentColor)
            binding.seekBrightness.isEnabled = false
        } else {
            binding.ivBrightnessAuto.setColorFilter(context.buttonDisabledColor)
            binding.seekBrightness.isEnabled = true
        }
        setScreenBrightness(AppConfig.readBrightness.toFloat())
    }

    private fun updateBrightnessSectionVisibility(hasMenuButtons: Boolean = true) = binding.run {
        val visible = showBrightnessView && hasMenuButtons
        llBrightness.isVisible = visible
        dividerBrightnessTop.isVisible = visible
        dividerBrightnessBottom.isVisible = visible
    }

    /**
     * 系统亮度监听，在高阳光亮度时启用
     */
    private var contentObserver: ContentObserver? = null
    /**
     * 设置屏幕亮度
     */
    fun setScreenBrightness(value: Float) {
        activity?.run {
            fun setBrightness(value: Float) {
                val params = window.attributes
                params.screenBrightness = value
                window.attributes = params
            }
            val autoBrightness = BRIGHTNESS_OVERRIDE_NONE
            if (brightnessAuto() || value == autoBrightness) {
                setBrightness(autoBrightness)
                return
            }
            val brightness = if (value < 1f) 0.004f else value / 255f
            var isSunMax = false
            if (brightness == 1f) {
                val sysBrightness = getCurrentBrightness(context)
                if (sysBrightness == 255) {
                    isSunMax = true
                }
            }
            if (isSunMax) {
                contentObserver = object : ContentObserver(buildMainHandler()) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        if (contentObserver == null) return
                        if (uri == Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)) {
                            val sysBrightness = getCurrentBrightness(context)
                            if (sysBrightness < 200) {
                                setBrightness(brightness)
                                contentObserver?.let {
                                    context.contentResolver.unregisterContentObserver(it)
                                }
                                contentObserver = null
                            } else if (sysBrightness < 255) {
                                setBrightness(brightness)
                            } else {
                                setBrightness(autoBrightness)
                            }
                        }
                    }
                }
                val brightnessUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                context.contentResolver.registerContentObserver(
                    brightnessUri,
                    false,
                    contentObserver!!
                )
                setBrightness(autoBrightness)
            } else {
                setBrightness(brightness)
            }
        }
    }

    /**
     * 获取系统亮度值
     */
    private fun getCurrentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (_: Settings.SettingNotFoundException) {
            -1
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        callBack.onMenuShow()
        this.visible()
        binding.titleBar.visible()
        binding.bottomMenu.visible()
        if (anim) {
            binding.titleBar.startAnimation(menuTopIn)
            binding.bottomMenu.startAnimation(menuBottomIn)
        } else {
            menuInListener.onAnimationStart(menuBottomIn)
            menuInListener.onAnimationEnd(menuBottomIn)
        }
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode, onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) {
            return
        }
        callBack.onMenuHide()
        this.onMenuOutEnd = onMenuOutEnd
        if (this.isVisible) {
            if (anim) {
                binding.titleBar.startAnimation(menuTopOut)
                binding.bottomMenu.startAnimation(menuBottomOut)
            } else {
                menuOutListener.onAnimationStart(menuBottomOut)
                menuOutListener.onAnimationEnd(menuBottomOut)
            }
        }
    }

    fun bottomMenuBoundsIn(target: View): RectF? = binding.run {
        if (!isVisible || !bottomMenu.isVisible) return null
        if (bottomMenu.width <= 0 || bottomMenu.height <= 0 || target.width <= 0 || target.height <= 0) {
            return null
        }
        val menuLocation = IntArray(2)
        val targetLocation = IntArray(2)
        bottomMenu.getLocationInWindow(menuLocation)
        target.getLocationInWindow(targetLocation)
        val left = (menuLocation[0] - targetLocation[0]).toFloat()
        val top = (menuLocation[1] - targetLocation[1]).toFloat()
        return RectF(left, top, left + bottomMenu.width, top + bottomMenu.height)
    }

    private fun brightnessAuto(): Boolean {
        return context.getPrefBoolean("brightnessAuto", true) || !showBrightnessView
    }

    private fun bindEvent() = binding.run {
        vwMenuBg.setOnClickListener { runMenuOut() }
        titleBar.toolbar.setOnClickListener {
            callBack.openBookInfoActivity()
        }
        val chapterViewClickListener = OnClickListener {
            if (ReadBook.isLocalBook) {
                return@OnClickListener
            }
            val chapterUrl = getChapterUrlForOpen() ?: return@OnClickListener
            Coroutine.async {
                context.startActivity<WebViewActivity> {
                    val bookSource = ReadBook.bookSource
                    putExtra("title", tvChapterName.text)
                    putExtra("url", chapterUrl)
                    putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                    putExtra("sourceName", bookSource?.bookSourceName)
                    putExtra("sourceType", bookSource?.getSourceType())
                }
            }
        }
        val chapterViewLongClickListener = OnLongClickListener {
            if (!ReadBook.isLocalBook) {
                getChapterUrlForOpen()?.let { chapterUrl ->
                    context.alert(R.string.open_fun) {
                        setMessage(R.string.use_browser_open)
                        okButton {
                            context.openUrl(chapterUrl)
                        }
                        noButton()
                    }
                }
            }
            true
        }
        tvChapterName.setOnClickListener(chapterViewClickListener)
        tvChapterName.setOnLongClickListener(chapterViewLongClickListener)
        tvChapterUrl.setOnClickListener(chapterViewClickListener)
        tvChapterUrl.setOnLongClickListener(chapterViewLongClickListener)
        tvCustomBtn.setOnClickListener {
            val book = ReadBook.book ?: return@setOnClickListener
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
        }
        tvCustomBtn.setOnLongClickListener {
            val book = ReadBook.book ?: return@setOnLongClickListener true
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.LONG_CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
            true
        }
        ivCloudLibrary.setOnClickListener {
            callBack.showLibraryCloudChapters(refresh = false)
        }
        ivCloudLibrary.setOnLongClickListener {
            if (io.legado.app.BuildConfig.DEBUG) {
                callBack.showLibraryCloudDebug()
            } else {
                callBack.showLibraryCloudChapters(refresh = true)
            }
            true
        }
        //书源操作
        tvSourceAction.onClick {
            modernMenuPopup = ModernActionPopup.showFromMenu(
                tvSourceAction,
                R.menu.book_read_source,
                modernMenuPopup,
                prepare = {
                    findItem(R.id.menu_login).isVisible =
                        !ReadBook.bookSource?.loginUrl.isNullOrEmpty()
                    findItem(R.id.menu_chapter_pay).isVisible =
                        !ReadBook.bookSource?.loginUrl.isNullOrEmpty()
                                && ReadBook.curTextChapter?.isVip == true
                                && ReadBook.curTextChapter?.isPay != true
                }
            ) {
                when (it.itemId) {
                    R.id.menu_login -> callBack.showLogin()
                    R.id.menu_chapter_pay -> callBack.payAction()
                    R.id.menu_edit_source -> callBack.openSourceEditActivity()
                    R.id.menu_disable_source -> callBack.disableSource()
                }
                true
            }
        }
        //亮度跟随
        ivBrightnessAuto.setOnClickListener {
            context.putPrefBoolean("brightnessAuto", !brightnessAuto())
            upBrightnessState()
        }
        //亮度调节
        seekBrightness.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setScreenBrightness(progress.toFloat())
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppConfig.readBrightness = seekBar.progress
            }

        })
        vwBrightnessPosAdjust.setOnClickListener {
            AppConfig.brightnessVwPos = !AppConfig.brightnessVwPos
            upBrightnessVwPos()
        }
        //阅读进度
        seekReadPage.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
                when (AppConfig.progressBarBehavior) {
                    "page" -> {
                        if (!callBack.skipToEpubCorePage(seekBar.progress)) {
                            ReadBook.skipToPage(seekBar.progress)
                        }
                    }
                    "chapter" -> {
                        if (confirmSkipToChapter) {
                            callBack.skipToChapter(seekBar.progress)
                        } else {
                            context.alert("章节跳转确认", "确定要跳转章节吗？") {
                                yesButton {
                                    confirmSkipToChapter = true
                                    callBack.skipToChapter(seekBar.progress)
                                }
                                noButton {
                                    upSeekBar()
                                }
                                onCancelled {
                                    upSeekBar()
                                }
                            }
                        }
                    }
                }
            }

        })

        //上一章
        tvPre.setOnClickListener {
            if (callBack.isEpubCoreBook()) {
                callBack.openPreviousEpubCoreChapter()
            } else {
                ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            }
        }

        //下一章
        tvNext.setOnClickListener {
            if (callBack.isEpubCoreBook()) {
                callBack.openNextEpubCoreChapter()
            } else {
                ReadBook.moveToNextChapter(true)
            }
        }
    }

    private fun initAnimation() {
        menuTopIn.setAnimationListener(menuInListener)
        menuTopOut.setAnimationListener(menuOutListener)
    }

    fun upBookView() {
        binding.titleBar.title = ReadBook.book?.name
        if (callBack.isEpubCoreBook()) {
            currentChapterUrl = callBack.epubCoreChapterUrl()
            binding.tvChapterName.text = callBack.epubCoreChapterTitle().orEmpty()
            binding.tvChapterName.visible()
            binding.tvChapterUrl.gone()
            upSeekBar()
            binding.tvPre.isEnabled = ReadBook.durChapterIndex != 0
            binding.tvNext.isEnabled = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
            return
        }
        ReadBook.curTextChapter?.let {
            binding.tvChapterName.text = it.title
            binding.tvChapterName.visible()
            if (!ReadBook.isLocalBook) {
                currentChapterUrl = resolveChapterUrl(it.chapter)
                binding.tvChapterUrl.gone()
            } else {
                currentChapterUrl = null
                binding.tvChapterUrl.gone()
            }
            upSeekBar()
            binding.tvPre.isEnabled = ReadBook.durChapterIndex != 0
            binding.tvNext.isEnabled = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
        } ?: let {
            currentChapterUrl = null
            binding.tvChapterName.gone()
            binding.tvChapterUrl.gone()
        }
    }

    private fun getChapterUrlForOpen(): String? {
        val url = currentChapterUrl?.trim().orEmpty()
        return url.takeIf { it.isNotBlank() }
    }

    private fun resolveChapterUrl(chapter: io.legado.app.data.entities.BookChapter): String? {
        val candidates = listOf(
            chapter.url,
            runCatching { chapter.getAbsoluteURL() }.getOrNull(),
            chapter.baseUrl,
            ReadBook.book?.bookUrl
        )
        return candidates.asSequence()
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    fun upSeekBar() {
        binding.seekReadPage.apply {
            when (AppConfig.progressBarBehavior) {
                "page" -> {
                    val epubPageCount = callBack.epubCorePageCount()
                    if (epubPageCount > 0) {
                        max = epubPageCount - 1
                        progress = callBack.epubCorePageIndex().coerceIn(0, max)
                    } else {
                        ReadBook.curTextChapter?.let {
                            max = it.pageSize.minus(1)
                            progress = ReadBook.durPageIndex
                        }
                    }
                }

                "chapter" -> {
                    max = ReadBook.simulatedChapterSize - 1
                    progress = ReadBook.durChapterIndex
                }
            }
        }
    }

    fun setSeekPage(seek: Int) {
        binding.seekReadPage.progress = seek
    }

    fun setAutoPage(autoPage: Boolean) {
        autoPageActive = autoPage
        updateAutoPageButton()
    }

    fun updateCloudLibraryState(state: LibraryCloudState) {
        cloudState = state
        binding.ivCloudLibrary.isVisible = callBack.isLibraryCloudEnabled()
        updateCloudLibraryIcon(state)
    }

    private fun updateCloudLibraryIcon(state: LibraryCloudState) = binding.run {
        val alpha = when (state) {
            LibraryCloudState.READY -> 255
            LibraryCloudState.ERROR -> 230
            LibraryCloudState.DISABLED -> 90
            else -> 150
        }
        ivCloudLibrary.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivCloudLibrary.imageAlpha = alpha
    }

    private fun upBrightnessVwPos() {
        binding.vwBrightnessPosAdjust.gone()
    }

    interface CallBack {
        fun autoPage()
        fun openReplaceRule()
        fun openChapterList()
        fun openSearchActivity(searchWord: String?)
        fun openSourceEditActivity()
        fun openBookInfoActivity()
        fun showReadStyle()
        fun showMoreSetting()
        fun openReadAiSummary()
        fun showReadAloudDialog()
        fun upSystemUiVisibility()
        fun onClickReadAloud()
        fun showHelp()
        fun showLogin()
        fun payAction()
        fun disableSource()
        fun skipToChapter(index: Int)
        fun onMenuShow()
        fun onMenuHide()
        fun epubCorePageCount(): Int = 0
        fun epubCorePageIndex(): Int = 0
        fun skipToEpubCorePage(index: Int): Boolean = false
        fun isEpubCoreBook(): Boolean = false
        fun epubCoreChapterTitle(): String? = null
        fun epubCoreChapterUrl(): String? = null
        fun openPreviousEpubCoreChapter() = Unit
        fun openNextEpubCoreChapter() = Unit
        fun onNightThemeChanged() = Unit
        fun isLibraryCloudEnabled(): Boolean = false
        fun libraryCloudState(): LibraryCloudState = LibraryCloudState.DISABLED
        fun showLibraryCloudChapters(refresh: Boolean) = Unit
        fun showLibraryCloudDebug() = Unit
        fun openReadAssistant() = Unit
        fun showParagraphRuleQuickDialog() = Unit
        fun openBookCharacters() = Unit
        fun runCustomReadMenuButton(id: Long) = Unit
        fun editCustomReadMenuButton(id: Long) = Unit
        fun loginCustomReadMenuButton(id: Long) = Unit
    }

    private class MenuButtonPagerScrollView(context: Context) : HorizontalScrollView(context) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
        private var pageCount = 1
        private var downX = 0f
        private var downY = 0f
        private var downScrollX = 0
        private var downTime = 0L
        private var dragging = false
        private var snapAnimator: ValueAnimator? = null

        fun setPageCount(count: Int) {
            pageCount = count.coerceAtLeast(1)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            return when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(ev)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (shouldStartDrag(ev)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endGesture()
                    false
                }
                else -> false
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            return when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(ev)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && shouldStartDrag(ev)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        dragTo(ev.x)
                        true
                    } else {
                        super.onTouchEvent(ev)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val handled = dragging
                    settle(ev.x, ev.eventTime, ev.actionMasked == MotionEvent.ACTION_CANCEL)
                    endGesture()
                    handled || super.onTouchEvent(ev)
                }
                else -> super.onTouchEvent(ev)
            }
        }

        override fun fling(velocityX: Int) {
            val pageWidth = width.takeIf { it > 0 } ?: return
            val lastPage = lastPage()
            val nearestPage = nearestPage(pageWidth, lastPage)
            val targetPage = when {
                velocityX > minFlingVelocity -> nearestPage + 1
                velocityX < -minFlingVelocity -> nearestPage - 1
                else -> nearestPage
            }.coerceIn(0, lastPage)
            animateToPage(targetPage, pageWidth)
        }

        private fun beginGesture(event: MotionEvent) {
            snapAnimator?.cancel()
            downX = event.x
            downY = event.y
            downScrollX = scrollX
            downTime = event.eventTime
            dragging = false
        }

        private fun endGesture() {
            dragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        private fun shouldStartDrag(event: MotionEvent): Boolean {
            val dx = event.x - downX
            val dy = event.y - downY
            return abs(dx) > touchSlop && abs(dx) > abs(dy)
        }

        private fun dragTo(x: Float) {
            val pageWidth = width.takeIf { it > 0 } ?: return
            val maxScroll = pageWidth * lastPage()
            val targetScroll = (downScrollX - (x - downX)).roundToInt()
                .coerceIn(0, maxScroll)
            scrollTo(targetScroll, 0)
        }

        private fun settle(releaseX: Float, releaseTime: Long, isCancel: Boolean) {
            val pageWidth = width.takeIf { it > 0 } ?: return
            val lastPage = lastPage()
            val startPage = ((downScrollX + pageWidth / 2) / pageWidth)
                .coerceIn(0, lastPage)
            val nearestPage = nearestPage(pageWidth, lastPage)
            val dragDistance = downX - releaseX
            val threshold = pageWidth * MENU_PAGE_SWITCH_THRESHOLD
            val duration = releaseTime - downTime
            val isFastSwipe = !isCancel &&
                    duration in 1..MENU_PAGE_FAST_SWIPE_MAX_MS &&
                    abs(dragDistance) >= MENU_PAGE_FAST_SWIPE_MIN_DISTANCE_DP.dpToPx()
            val targetPage = when {
                isCancel -> nearestPage
                isFastSwipe && dragDistance > 0f -> startPage + 1
                isFastSwipe && dragDistance < 0f -> startPage - 1
                dragDistance > threshold -> startPage + 1
                dragDistance < -threshold -> startPage - 1
                else -> startPage
            }.coerceIn(0, lastPage)
            animateToPage(targetPage, pageWidth)
        }

        private fun animateToPage(page: Int, pageWidth: Int) {
            val targetScroll = page * pageWidth
            snapAnimator?.cancel()
            if (scrollX == targetScroll) return
            snapAnimator = ValueAnimator.ofInt(scrollX, targetScroll).apply {
                duration = MENU_PAGE_SNAP_ANIM_MS
                addUpdateListener { animator ->
                    scrollTo(animator.animatedValue as Int, 0)
                }
                start()
            }
        }

        private fun nearestPage(pageWidth: Int, lastPage: Int): Int {
            return ((scrollX + pageWidth / 2) / pageWidth).coerceIn(0, lastPage)
        }

        private fun lastPage(): Int {
            return (pageCount - 1).coerceAtLeast(0)
        }
    }

    private companion object {
        const val MENU_BUTTONS_PER_PAGE = 4
        const val MENU_PAGE_SWITCH_THRESHOLD = 0.02f
        const val MENU_PAGE_FAST_SWIPE_MAX_MS = 220L
        const val MENU_PAGE_FAST_SWIPE_MIN_DISTANCE_DP = 8
        const val MENU_PAGE_SNAP_ANIM_MS = 120L
    }

}
