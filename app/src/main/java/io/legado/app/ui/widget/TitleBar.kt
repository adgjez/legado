package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.widget.Toolbar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.alpha
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.google.android.material.appbar.AppBarLayout
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.widget.compose.ComposeTitleBarTitle
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.TitleBarTitleState
import io.legado.app.utils.activity
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import splitties.views.bottomPadding
import splitties.views.topPadding

@Suppress("unused", "MemberVisibilityCanBePrivate")
class TitleBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppBarLayout(context, attrs) {

    val toolbar: Toolbar
    val menu: Menu
        get() = toolbar.menu

    var title: CharSequence?
        get() = titleTextValue
        set(title) {
            if (titleTextValue != title) {
                titleTextValue = title
                toolbar.title = null
                updateComposeTitle()
                post { applyTitleTypeface() }
            }
        }

    var subtitle: CharSequence?
        get() = subtitleTextValue
        set(subtitle) {
            if (subtitleTextValue != subtitle) {
                subtitleTextValue = subtitle
                toolbar.subtitle = null
                updateComposeTitle()
                post { applyTitleTypeface() }
            }
        }

    private val composeTitleState = mutableStateOf(
        TitleBarTitleState("", "", Color.BLACK, Color.DKGRAY, 20f, 14f)
    )
    private var composeTitleView: ComposeView? = null
    private var composeTitleEnabled = false
    private var titleTextValue: CharSequence? = null
    private var subtitleTextValue: CharSequence? = null
    private var titleTextColorOverride: Int? = null
    private var subtitleTextColorOverride: Int? = null
    private var titleTextSizeSp = 20f
    private var subtitleTextSizeSp = 14f
    private var titleThemeMode = 0

    private val displayHomeAsUp: Boolean
    private val navigationIconTint: ColorStateList?
    private val navigationIconTintMode: Int
    private val fitStatusBar: Boolean
    private val fitNavigationBar: Boolean
    private val attachToActivity: Boolean
    private val opaque: Boolean

    init {
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.TitleBar,
            R.attr.titleBarStyle, 0
        )
        navigationIconTint = a.getColorStateList(R.styleable.TitleBar_navigationIconTint)
        navigationIconTintMode = a.getInt(R.styleable.TitleBar_navigationIconTintMode, 9)
        attachToActivity = a.getBoolean(R.styleable.TitleBar_attachToActivity, true)
        displayHomeAsUp = a.getBoolean(R.styleable.TitleBar_displayHomeAsUp, true)
        fitStatusBar = a.getBoolean(R.styleable.TitleBar_fitStatusBar, true)
        fitNavigationBar = a.getBoolean(R.styleable.TitleBar_fitNavigationBar, false)
        opaque = a.getBoolean(R.styleable.TitleBar_opaque, false)
        titleThemeMode = a.getInt(R.styleable.TitleBar_themeMode, 0)

        val navigationIcon = a.getDrawable(R.styleable.TitleBar_navigationIcon)
        val navigationContentDescription =
            a.getText(R.styleable.TitleBar_navigationContentDescription)
        val titleText = a.getString(R.styleable.TitleBar_title)
        val subtitleText = a.getString(R.styleable.TitleBar_subtitle)
        val hasContentLayout = a.hasValue(R.styleable.TitleBar_contentLayout)

        when (titleThemeMode) {
            1 -> inflate(context, R.layout.view_title_bar_dark, this)
            else -> inflate(context, R.layout.view_title_bar, this)
        }
        toolbar = findViewById(R.id.toolbar)
        installComposeTitle(!hasContentLayout)

        toolbar.apply {
            navigationIcon?.let {
                this.navigationIcon = it
                this.navigationContentDescription = navigationContentDescription
            }

            if (a.hasValue(R.styleable.TitleBar_titleTextAppearance)) {
                this@TitleBar.setTitleTextAppearance(
                    a.getResourceId(R.styleable.TitleBar_titleTextAppearance, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_titleTextColor)) {
                this@TitleBar.setTitleTextColor(a.getColor(R.styleable.TitleBar_titleTextColor, -0x1))
            }

            if (a.hasValue(R.styleable.TitleBar_subtitleTextAppearance)) {
                this@TitleBar.setSubTitleTextAppearance(
                    a.getResourceId(R.styleable.TitleBar_subtitleTextAppearance, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_subtitleTextColor)) {
                this@TitleBar.setSubTitleTextColor(
                    a.getColor(R.styleable.TitleBar_subtitleTextColor, -0x1)
                )
            }


            if (a.hasValue(R.styleable.TitleBar_contentInsetLeft)
                || a.hasValue(R.styleable.TitleBar_contentInsetRight)
            ) {
                this.setContentInsetsAbsolute(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetLeft, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetRight, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStart)
                || a.hasValue(R.styleable.TitleBar_contentInsetEnd)
            ) {
                this.setContentInsetsRelative(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetStart, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetEnd, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStartWithNavigation)) {
                this.contentInsetStartWithNavigation = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetStartWithNavigation, 0
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetEndWithActions)) {
                this.contentInsetEndWithActions = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetEndWithActions, 0
                )
            }

            if (!titleText.isNullOrBlank()) {
                this@TitleBar.title = titleText
            }

            if (!subtitleText.isNullOrBlank()) {
                this@TitleBar.subtitle = subtitleText
            }

            if (hasContentLayout) {
                inflate(context, a.getResourceId(R.styleable.TitleBar_contentLayout, 0), this)
            }
        }
        toolbar.title = null
        toolbar.subtitle = null
        updateComposeTitle()
        applyTitleTypeface()

        if (!isInEditMode) {
//            if (fitStatusBar) {
//                setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)
//            }
//
//            if (fitNavigationBar) {
//                setPadding(paddingLeft, paddingTop, paddingRight, context.navigationBarHeight)
//            }

            if (fitStatusBar || fitNavigationBar) {
                setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    if (fitStatusBar) {
                        topPadding = insets.top
                    }
                    if (fitNavigationBar) {
                        bottomPadding = insets.bottom
                    }
                    windowInsets
                }
            }

            if (AppConfig.isEInkMode) {
                setBackgroundResource(R.drawable.bg_eink_border_bottom)
            } else if (!opaque && context.transparentNavBar) {
                setBackgroundColor(Color.TRANSPARENT)
            } else {
                setBackgroundColor(context.primaryColor)
                elevation = context.elevation
            }

            stateListAnimator = null
        }
        a.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachToActivity()
        post { applyTitleTypeface() }
    }

    fun setNavigationOnClickListener(clickListener: ((View) -> Unit)) {
        toolbar.setNavigationOnClickListener(clickListener)
    }

    fun setTitle(titleId: Int) {
        title = context.getText(titleId)
        post { applyTitleTypeface() }
    }

    fun setSubTitle(subtitleId: Int) {
        subtitle = context.getText(subtitleId)
        post { applyTitleTypeface() }
    }

    private fun installComposeTitle(enabled: Boolean) {
        composeTitleEnabled = enabled
        if (!enabled) return
        composeTitleView = ComposeView(context).apply {
            isClickable = false
            isFocusable = false
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LegadoComposeTheme {
                    ComposeTitleBarTitle(state = composeTitleState.value)
                }
            }
        }
        toolbar.addView(
            composeTitleView,
            Toolbar.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = toolbar.titleMarginStart
                marginEnd = toolbar.titleMarginEnd
            }
        )
    }

    private fun updateComposeTitle() {
        val titleText = titleTextValue?.toString().orEmpty()
        val subtitleText = subtitleTextValue?.toString().orEmpty()
        composeTitleState.value = TitleBarTitleState(
            title = titleText,
            subtitle = subtitleText,
            titleColor = titleTextColorOverride ?: defaultTitleColor(),
            subtitleColor = subtitleTextColorOverride ?: defaultSubtitleColor(),
            titleSizeSp = titleTextSizeSp,
            subtitleSizeSp = subtitleTextSizeSp
        )
        composeTitleView?.isVisible = composeTitleEnabled &&
            (titleText.isNotBlank() || subtitleText.isNotBlank())
    }

    private fun defaultTitleColor(): Int {
        return if (titleThemeMode == 1) {
            Color.WHITE
        } else {
            context.primaryTextColor
        }
    }

    private fun defaultSubtitleColor(): Int {
        return if (titleThemeMode == 1) {
            ColorUtils.setAlphaComponent(Color.WHITE, 0xB3)
        } else {
            context.secondaryTextColor
        }
    }

    private fun applyTitleTypeface() {
        if (isInEditMode) return
        val typeface = context.titleTypeface()
        toolbar.children.filterIsInstance<TextView>().forEach {
            it.typeface = typeface
        }
    }

    fun setTitleTextColor(@ColorInt color: Int) {
        titleTextColorOverride = color
        toolbar.setTitleTextColor(color)
        updateComposeTitle()
    }

    fun setTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setTitleTextAppearance(context, resId)
        updateTextAppearance(resId, subtitle = false)
    }

    fun setSubTitleTextColor(@ColorInt color: Int) {
        subtitleTextColorOverride = color
        toolbar.setSubtitleTextColor(color)
        updateComposeTitle()
    }

    fun setSubTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setSubtitleTextAppearance(context, resId)
        updateTextAppearance(resId, subtitle = true)
    }

    fun setTextColor(@ColorInt color: Int) {
        setTitleTextColor(color)
        setSubTitleTextColor(color)
    }

    fun setColorFilter(@ColorInt color: Int) {
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.children.firstOrNull { it is ImageView }?.background?.colorFilter = colorFilter
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
        toolbar.menu.children.forEach {
            it.icon?.colorFilter = colorFilter
        }
    }

    private fun updateTextAppearance(@StyleRes resId: Int, subtitle: Boolean) {
        if (resId == 0) return
        val attrs = intArrayOf(android.R.attr.textSize, android.R.attr.textColor)
        val typedArray = context.obtainStyledAttributes(resId, attrs)
        try {
            val textSizePx = typedArray.getDimensionPixelSize(0, 0)
            if (textSizePx > 0) {
                val textSizeSp = textSizePx / resources.displayMetrics.scaledDensity
                if (subtitle) {
                    subtitleTextSizeSp = textSizeSp
                } else {
                    titleTextSizeSp = textSizeSp
                }
            }
            typedArray.getColorStateList(1)?.defaultColor?.let { color ->
                if (subtitle) {
                    subtitleTextColorOverride = color
                } else {
                    titleTextColorOverride = color
                }
            }
        } finally {
            typedArray.recycle()
        }
        updateComposeTitle()
    }

    override fun setBackgroundColor(color: Int) {
        if (color.alpha < 255) {
            //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
            elevation = 0.1f
        }
        super.setBackgroundColor(color)
    }

    override fun setBackground(background: Drawable?) {
        if (background is ColorDrawable) {
            if (background.alpha < 255) {
                //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
                elevation = 0.1f
            }
        }
        super.setBackground(background)
    }

    fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, fullScreen: Boolean) {
//        if (fitStatusBar) {
//            val topPadding = if (!isInMultiWindowMode && fullScreen) context.statusBarHeight else 0
//            setPadding(paddingLeft, topPadding, paddingRight, paddingBottom)
//        }
    }

    private fun attachToActivity() {
        if (attachToActivity) {
            activity?.let {
                it.setSupportActionBar(toolbar)
                it.supportActionBar?.setDisplayHomeAsUpEnabled(displayHomeAsUp)
                toolbar.title = null
                toolbar.subtitle = null
                updateComposeTitle()
            }
        }
    }

}
