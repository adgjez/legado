package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.ui.widget.compose.AppDialogSliderGrid
import io.legado.app.ui.widget.compose.AppDialogSliderItem
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.LegadoMiuixSection
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.hexString
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent

class TipConfigDialog : ReaderBottomSheetComposeDialogFragment() {

    companion object {
        const val TIP_COLOR = 7897
        const val TIP_DIVIDER_COLOR = 7898
    }

    override val maxSheetHeightFraction: Float = 0.72f

    private var colorRefreshTick by mutableIntStateOf(0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (ReadBookConfig.titleMode !in 0..AdvancedTitleConfig.TITLE_MODE_ADVANCED) {
            ReadBookConfig.titleMode = 0
        }
        observeEvent<String>(EventBus.TIP_COLOR) {
            colorRefreshTick++
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    TipConfigContent(
                        style = style,
                        colorRefreshTick = colorRefreshTick,
                        onShowAdvancedTitleConfig = {
                            AdvancedTitleConfigDialog()
                                .show(parentFragmentManager, "advancedTitleConfig")
                        },
                        onShowSelector = ::showActionSelector,
                        onShowTipColorPicker = {
                            ColorPickerDialog.newBuilder()
                                .setShowAlphaSlider(false)
                                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                                .setDialogId(TIP_COLOR)
                                .show(requireActivity())
                        },
                        onShowTipDividerColorPicker = {
                            ColorPickerDialog.newBuilder()
                                .setShowAlphaSlider(false)
                                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                                .setDialogId(TIP_DIVIDER_COLOR)
                                .show(requireActivity())
                        },
                        onColorChanged = { colorRefreshTick++ }
                    )
                }
            }
        }
    }

    private fun showActionSelector(
        title: String,
        labels: List<String>,
        onSelected: (Int) -> Unit
    ) {
        ComposeActionListDialog.create(
            title = title,
            labels = labels,
            negativeText = getString(R.string.cancel),
            onSelected = onSelected
        ).show(parentFragmentManager, "tipConfigSelector")
    }
}

@Composable
private fun TipConfigContent(
    style: AppDialogStyle,
    colorRefreshTick: Int,
    onShowAdvancedTitleConfig: () -> Unit,
    onShowSelector: (String, List<String>, (Int) -> Unit) -> Unit,
    onShowTipColorPicker: () -> Unit,
    onShowTipDividerColorPicker: () -> Unit,
    onColorChanged: () -> Unit
) {
    val context = LocalContext.current
    var titleMode by rememberSaveable { mutableIntStateOf(ReadBookConfig.titleMode) }
    var titleSize by rememberSaveable { mutableIntStateOf(ReadBookConfig.titleSize) }
    var titleTopSpacing by rememberSaveable { mutableIntStateOf(ReadBookConfig.titleTopSpacing) }
    var titleBottomSpacing by rememberSaveable {
        mutableIntStateOf(ReadBookConfig.titleBottomSpacing)
    }
    var headerMode by rememberSaveable { mutableIntStateOf(ReadTipConfig.headerMode) }
    var footerMode by rememberSaveable { mutableIntStateOf(ReadTipConfig.footerMode) }
    var headerLeft by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipHeaderLeft) }
    var headerMiddle by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipHeaderMiddle) }
    var headerRight by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipHeaderRight) }
    var footerLeft by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipFooterLeft) }
    var footerMiddle by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipFooterMiddle) }
    var footerRight by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipFooterRight) }
    val headerModes = remember(context) { ReadTipConfig.getHeaderModes(context) }
    val footerModes = remember(context) { ReadTipConfig.getFooterModes(context) }
    val tipNames = ReadTipConfig.tipNames
    val tipValues = ReadTipConfig.tipValues.toList()
    val titleModeOptions = listOf(
        stringResource(R.string.title_left),
        stringResource(R.string.title_center),
        stringResource(R.string.ai_tavily_search_depth_advanced),
        stringResource(R.string.title_hide)
    )
    fun titleModeToUiIndex(mode: Int): Int {
        return when (mode) {
            AdvancedTitleConfig.TITLE_MODE_ADVANCED -> 2
            2 -> 3
            else -> mode
        }.coerceIn(0, titleModeOptions.lastIndex)
    }
    fun uiIndexToTitleMode(index: Int): Int {
        return when (index) {
            2 -> AdvancedTitleConfig.TITLE_MODE_ADVANCED
            3 -> 2
            else -> index
        }
    }
    fun tipName(value: Int): String {
        val index = tipValues.indexOf(value)
        return tipNames.getOrElse(index) { tipNames[ReadTipConfig.none] }
    }
    fun clearRepeat(value: Int) {
        if (value == ReadTipConfig.none) return
        if (headerLeft == value) { headerLeft = ReadTipConfig.none; ReadTipConfig.tipHeaderLeft = ReadTipConfig.none }
        if (headerMiddle == value) { headerMiddle = ReadTipConfig.none; ReadTipConfig.tipHeaderMiddle = ReadTipConfig.none }
        if (headerRight == value) { headerRight = ReadTipConfig.none; ReadTipConfig.tipHeaderRight = ReadTipConfig.none }
        if (footerLeft == value) { footerLeft = ReadTipConfig.none; ReadTipConfig.tipFooterLeft = ReadTipConfig.none }
        if (footerMiddle == value) { footerMiddle = ReadTipConfig.none; ReadTipConfig.tipFooterMiddle = ReadTipConfig.none }
        if (footerRight == value) { footerRight = ReadTipConfig.none; ReadTipConfig.tipFooterRight = ReadTipConfig.none }
    }
    fun chooseTip(title: String, onAssign: (Int) -> Unit) {
        onShowSelector(title, tipNames) { index ->
            val value = tipValues.getOrElse(index) { ReadTipConfig.none }
            clearRepeat(value)
            onAssign(value)
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6))
        }
    }

    ReaderBottomSheetFrame(maxHeightFraction = 0.72f) { _, palette ->
        val miuixPalette = style.toMiuixPalette()
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReaderSheetHeader(
                title = stringResource(R.string.information),
                palette = palette
            )
            // 标题设置
            ReaderSectionCard(palette = palette, style = style, title = stringResource(R.string.body_title)) {
                AppDialogSliderGrid(
                    items = listOf(
                        AppDialogSliderItem(
                            title = stringResource(R.string.title_font_size),
                            value = titleSize,
                            range = 0..20,
                            onValueChange = { titleSize = it; ReadBookConfig.titleSize = it; postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5)) }
                        ),
                        AppDialogSliderItem(
                            title = stringResource(R.string.title_margin_top),
                            value = titleTopSpacing,
                            range = 0..100,
                            onValueChange = { titleTopSpacing = it; ReadBookConfig.titleTopSpacing = it; postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5)) }
                        ),
                        AppDialogSliderItem(
                            title = stringResource(R.string.title_margin_bottom),
                            value = titleBottomSpacing,
                            range = 0..100,
                            onValueChange = { titleBottomSpacing = it; ReadBookConfig.titleBottomSpacing = it; postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5)) }
                        )
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    titleModeOptions.forEachIndexed { index, label ->
                        LegadoMiuixChoiceRow(
                            text = label,
                            selected = titleModeToUiIndex(titleMode) == index,
                            palette = miuixPalette,
                            onClick = {
                                titleMode = uiIndexToTitleMode(index)
                                ReadBookConfig.titleMode = titleMode
                                postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                            },
                            modifier = Modifier.weight(1f),
                            minHeight = 36.dp,
                            compact = true,
                            showSelectedMark = false
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                TipAdvancedActionRow(
                    style = style,
                    enabled = titleMode == AdvancedTitleConfig.TITLE_MODE_ADVANCED,
                    onClick = onShowAdvancedTitleConfig
                )
            }
            // 页眉
            TipPlacementSection(
                title = stringResource(R.string.header),
                showLabel = headerModes[headerMode].orEmpty(),
                leftLabel = tipName(headerLeft),
                middleLabel = tipName(headerMiddle),
                rightLabel = tipName(headerRight),
                palette = palette,
                style = style,
                onShowClick = {
                    val keys = headerModes.keys.toList()
                    onShowSelector(context.getString(R.string.header), headerModes.values.toList()) { index ->
                        headerMode = keys.getOrElse(index) { 0 }
                        ReadTipConfig.headerMode = headerMode
                        postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                    }
                },
                onLeftClick = { chooseTip(context.getString(R.string.left)) { headerLeft = it; ReadTipConfig.tipHeaderLeft = it } },
                onMiddleClick = { chooseTip(context.getString(R.string.middle)) { headerMiddle = it; ReadTipConfig.tipHeaderMiddle = it } },
                onRightClick = { chooseTip(context.getString(R.string.right)) { headerRight = it; ReadTipConfig.tipHeaderRight = it } }
            )
            // 页脚
            TipPlacementSection(
                title = stringResource(R.string.footer),
                showLabel = footerModes[footerMode].orEmpty(),
                leftLabel = tipName(footerLeft),
                middleLabel = tipName(footerMiddle),
                rightLabel = tipName(footerRight),
                palette = palette,
                style = style,
                onShowClick = {
                    val keys = footerModes.keys.toList()
                    onShowSelector(context.getString(R.string.footer), footerModes.values.toList()) { index ->
                        footerMode = keys.getOrElse(index) { 0 }
                        ReadTipConfig.footerMode = footerMode
                        postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                    }
                },
                onLeftClick = { chooseTip(context.getString(R.string.left)) { footerLeft = it; ReadTipConfig.tipFooterLeft = it } },
                onMiddleClick = { chooseTip(context.getString(R.string.middle)) { footerMiddle = it; ReadTipConfig.tipFooterMiddle = it } },
                onRightClick = { chooseTip(context.getString(R.string.right)) { footerRight = it; ReadTipConfig.tipFooterRight = it } }
            )
            // 颜色
            TipColorSection(
                colorRefreshTick = colorRefreshTick,
                palette = palette,
                style = style,
                onTipColorClick = {
                    onShowSelector(context.getString(R.string.text_color), ReadTipConfig.tipColorNames) { index ->
                        when (index) {
                            0 -> { ReadTipConfig.tipColor = 0; onColorChanged(); postEvent(EventBus.UP_CONFIG, arrayListOf(2)) }
                            1 -> onShowTipColorPicker()
                        }
                    }
                },
                onDividerColorClick = {
                    onShowSelector(context.getString(R.string.tip_divider_color), ReadTipConfig.tipDividerColorNames) { index ->
                        when (index) {
                            0, 1 -> { ReadTipConfig.tipDividerColor = index - 1; onColorChanged(); postEvent(EventBus.UP_CONFIG, arrayListOf(2)) }
                            2 -> onShowTipDividerColorPicker()
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun TipAdvancedActionRow(
    style: AppDialogStyle,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.55f
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        color = style.surface,
        contentColor = style.primaryText.copy(alpha = alpha),
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Text(
            text = stringResource(R.string.advanced_title_dialog_title),
            color = style.primaryText.copy(alpha = alpha),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.advanced_title_rule_label),
            color = style.secondaryText.copy(alpha = alpha),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TipPlacementSection(
    title: String,
    showLabel: String,
    leftLabel: String,
    middleLabel: String,
    rightLabel: String,
    palette: ReaderComposePalette,
    style: AppDialogStyle,
    onShowClick: () -> Unit,
    onLeftClick: () -> Unit,
    onMiddleClick: () -> Unit,
    onRightClick: () -> Unit
) {
    ReaderSectionCard(palette = palette, style = style, title = title) {
        TipValueRow(
            title = stringResource(R.string.show_hide),
            value = showLabel,
            palette = palette,
            style = style,
            onClick = onShowClick
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TipCompactValue(
                title = stringResource(R.string.left),
                value = leftLabel,
                palette = palette,
                style = style,
                modifier = Modifier.weight(1f),
                onClick = onLeftClick
            )
            TipCompactValue(
                title = stringResource(R.string.middle),
                value = middleLabel,
                palette = palette,
                style = style,
                modifier = Modifier.weight(1f),
                onClick = onMiddleClick
            )
            TipCompactValue(
                title = stringResource(R.string.right),
                value = rightLabel,
                palette = palette,
                style = style,
                modifier = Modifier.weight(1f),
                onClick = onRightClick
            )
        }
    }
}

@Composable
private fun TipColorSection(
    colorRefreshTick: Int,
    palette: ReaderComposePalette,
    style: AppDialogStyle,
    onTipColorClick: () -> Unit,
    onDividerColorClick: () -> Unit
) {
    val tipColorLabel = remember(colorRefreshTick) { tipColorText() }
    val dividerColorLabel = remember(colorRefreshTick) { tipDividerColorText() }
    ReaderSectionCard(palette = palette, style = style, title = stringResource(R.string.header_footer)) {
        TipValueRow(
            title = stringResource(R.string.text_color),
            value = tipColorLabel,
            palette = palette,
            style = style,
            onClick = onTipColorClick
        )
        TipValueRow(
            title = stringResource(R.string.tip_divider_color),
            value = dividerColorLabel,
            palette = palette,
            style = style,
            onClick = onDividerColorClick
        )
    }
}

@Composable
private fun TipValueRow(
    title: String,
    value: String,
    palette: ReaderComposePalette,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = palette.panelStrong,
        contentColor = palette.text,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = palette.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                color = palette.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TipCompactValue(
    title: String,
    value: String,
    palette: ReaderComposePalette,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = palette.panelStrong,
        contentColor = palette.text,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = palette.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = palette.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun tipColorText(): String {
    val names = ReadTipConfig.tipColorNames
    val color = ReadTipConfig.tipColor
    return if (color == 0) {
        names.first()
    } else {
        "#${color.hexString}"
    }
}

private fun tipDividerColorText(): String {
    val names = ReadTipConfig.tipDividerColorNames
    return when (val color = ReadTipConfig.tipDividerColor) {
        -1, 0 -> names[color + 1]
        else -> "#${color.hexString}"
    }
}
