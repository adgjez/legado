package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.utils.postEvent
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private data class PaddingItem(
    val label: String,
    val value: Int,
    val range: IntRange,
    val onValueChange: (Int) -> Unit
)

private data class PaddingFloatingSlider(
    val value: Int,
    val range: IntRange,
    val sourceBounds: Rect?,
    val onValueChange: (Int) -> Unit
)

class PaddingConfigDialog : ComposeDialogFragment() {

    override val widthFraction: Float = 0.91f
    override val maxWidthDp: Int? = 400

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = window.attributes
            attr.dimAmount = 0f
            window.attributes = attr
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    PaddingConfigContent(style = style)
                }
            }
        }
    }

    @Composable
    private fun PaddingConfigContent(style: AppDialogStyle) {
        val configuration = LocalConfiguration.current
        var floatingSlider by remember { mutableStateOf<PaddingFloatingSlider?>(null) }
        val floatingWidth = (configuration.screenWidthDp.dp - 48.dp).coerceIn(240.dp, 340.dp)
        val floatingHeight = 72.dp
        val floatingThumbSize = 42.dp
        val floatingEndpointWidth = 48.dp

        Box(modifier = Modifier.fillMaxWidth()) {
            LegadoMiuixCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                color = style.surface,
                contentColor = style.primaryText,
                cornerRadius = style.panelRadius,
                insidePadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeaderSection(
                        style = style,
                        floatingWidth = floatingWidth,
                        onFloatingSliderChange = { floatingSlider = it }
                    )
                    BodySection(
                        style = style,
                        floatingWidth = floatingWidth,
                        onFloatingSliderChange = { floatingSlider = it }
                    )
                    FooterSection(
                        style = style,
                        floatingWidth = floatingWidth,
                        onFloatingSliderChange = { floatingSlider = it }
                    )
                }
            }

            floatingSlider?.let { slider ->
                Popup(
                    alignment = Alignment.TopStart,
                    properties = PopupProperties(focusable = true, clippingEnabled = false)
                ) {
                    PaddingFloatingSliderOverlay(
                        value = slider.value,
                        range = slider.range,
                        sourceBounds = slider.sourceBounds,
                        style = style,
                        screenWidth = configuration.screenWidthDp.dp,
                        screenHeight = configuration.screenHeightDp.dp,
                        sliderWidth = floatingWidth,
                        sliderHeight = floatingHeight,
                        endpointWidth = floatingEndpointWidth,
                        thumbSize = floatingThumbSize,
                        onValueChange = { value ->
                            val next = value.coerceIn(slider.range)
                            if (next != slider.value) {
                                floatingSlider = slider.copy(value = next)
                                slider.onValueChange(next)
                            }
                        },
                        onDismiss = {
                            floatingSlider = null
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun HeaderSection(
        style: AppDialogStyle,
        floatingWidth: Dp,
        onFloatingSliderChange: (PaddingFloatingSlider?) -> Unit
    ) {
        var showLine by rememberSaveable { mutableStateOf(ReadBookConfig.showHeaderLine) }
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingRight) }

        PaddingSection(
            title = stringResource(R.string.header),
            style = style,
            showLine = showLine,
            onShowLineChange = {
                showLine = it
                ReadBookConfig.showHeaderLine = it
                postHeaderFooterChanged()
            }
        ) {
            PaddingSliderRows(
                style = style,
                floatingWidth = floatingWidth,
                top = top,
                bottom = bottom,
                left = left,
                right = right,
                topRange = 0..100,
                bottomRange = 0..100,
                sideRange = 0..100,
                onTopChange = {
                    top = it
                    ReadBookConfig.headerPaddingTop = it
                    postHeaderFooterChanged()
                },
                onBottomChange = {
                    bottom = it
                    ReadBookConfig.headerPaddingBottom = it
                    postHeaderFooterChanged()
                },
                onLeftChange = {
                    left = it
                    ReadBookConfig.headerPaddingLeft = it
                    postHeaderFooterChanged()
                },
                onRightChange = {
                    right = it
                    ReadBookConfig.headerPaddingRight = it
                    postHeaderFooterChanged()
                },
                onFloatingSliderChange = onFloatingSliderChange
            )
        }
    }

    @Composable
    private fun BodySection(
        style: AppDialogStyle,
        floatingWidth: Dp,
        onFloatingSliderChange: (PaddingFloatingSlider?) -> Unit
    ) {
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingRight) }

        PaddingSection(
            title = stringResource(R.string.main_body),
            style = style
        ) {
            PaddingSliderRows(
                style = style,
                floatingWidth = floatingWidth,
                top = top,
                bottom = bottom,
                left = left,
                right = right,
                topRange = 0..200,
                bottomRange = 0..100,
                sideRange = 0..100,
                onTopChange = {
                    top = it
                    ReadBookConfig.paddingTop = it
                    postBodyChanged()
                },
                onBottomChange = {
                    bottom = it
                    ReadBookConfig.paddingBottom = it
                    postBodyChanged()
                },
                onLeftChange = {
                    left = it
                    ReadBookConfig.paddingLeft = it
                    postBodyChanged()
                },
                onRightChange = {
                    right = it
                    ReadBookConfig.paddingRight = it
                    postBodyChanged()
                },
                onFloatingSliderChange = onFloatingSliderChange
            )
        }
    }

    @Composable
    private fun FooterSection(
        style: AppDialogStyle,
        floatingWidth: Dp,
        onFloatingSliderChange: (PaddingFloatingSlider?) -> Unit
    ) {
        var showLine by rememberSaveable { mutableStateOf(ReadBookConfig.showFooterLine) }
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingRight) }

        PaddingSection(
            title = stringResource(R.string.footer),
            style = style,
            showLine = showLine,
            onShowLineChange = {
                showLine = it
                ReadBookConfig.showFooterLine = it
                postHeaderFooterChanged()
            }
        ) {
            PaddingSliderRows(
                style = style,
                floatingWidth = floatingWidth,
                top = top,
                bottom = bottom,
                left = left,
                right = right,
                topRange = 0..100,
                bottomRange = 0..100,
                sideRange = 0..100,
                onTopChange = {
                    top = it
                    ReadBookConfig.footerPaddingTop = it
                    postHeaderFooterChanged()
                },
                onBottomChange = {
                    bottom = it
                    ReadBookConfig.footerPaddingBottom = it
                    postHeaderFooterChanged()
                },
                onLeftChange = {
                    left = it
                    ReadBookConfig.footerPaddingLeft = it
                    postHeaderFooterChanged()
                },
                onRightChange = {
                    right = it
                    ReadBookConfig.footerPaddingRight = it
                    postHeaderFooterChanged()
                },
                onFloatingSliderChange = onFloatingSliderChange
            )
        }
    }

    @Composable
    private fun PaddingSection(
        title: String,
        style: AppDialogStyle,
        showLine: Boolean? = null,
        onShowLineChange: ((Boolean) -> Unit)? = null,
        content: @Composable () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = style.accent,
                        fontSize = 14.sp,
                        fontFamily = style.titleFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (showLine != null && onShowLineChange != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.showLine),
                            modifier = Modifier.widthIn(max = 72.dp),
                            color = style.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        PaddingLineSwitch(
                            checked = showLine,
                            onCheckedChange = onShowLineChange,
                            style = style
                        )
                    }
                }
                content()
            }
        }
    }

    @Composable
    private fun PaddingLineSwitch(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        style: AppDialogStyle
    ) {
        val thumbSize = 24.dp
        val innerPadding = 2.dp
        Surface(
            modifier = Modifier
                .width(48.dp)
                .height(28.dp)
                .clickable { onCheckedChange(!checked) },
            shape = CircleShape,
            color = if (checked) style.accent.copy(alpha = 0.86f) else style.surface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.CenterStart
            ) {
                val targetOffset = if (checked) {
                    maxWidth - thumbSize
                } else {
                    0.dp
                }
                val offset by animateDpAsState(
                    targetValue = targetOffset,
                    label = "paddingLineSwitchThumb"
                )
                Surface(
                    modifier = Modifier
                        .offset(x = offset)
                        .size(thumbSize),
                    shape = CircleShape,
                    color = style.surface,
                    contentColor = style.primaryText,
                    tonalElevation = 0.dp,
                    shadowElevation = 5.dp
                ) {}
            }
        }
    }

    @Composable
    private fun PaddingSliderRows(
        style: AppDialogStyle,
        floatingWidth: Dp,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        topRange: IntRange,
        bottomRange: IntRange,
        sideRange: IntRange,
        onTopChange: (Int) -> Unit,
        onBottomChange: (Int) -> Unit,
        onLeftChange: (Int) -> Unit,
        onRightChange: (Int) -> Unit,
        onFloatingSliderChange: (PaddingFloatingSlider?) -> Unit
    ) {
        val items = listOf(
            PaddingItem(stringResource(R.string.top), top, topRange) { if (it != top) onTopChange(it) },
            PaddingItem(stringResource(R.string.bottom), bottom, bottomRange) { if (it != bottom) onBottomChange(it) },
            PaddingItem(stringResource(R.string.left), left, sideRange) { if (it != left) onLeftChange(it) },
            PaddingItem(stringResource(R.string.right), right, sideRange) { if (it != right) onRightChange(it) }
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    rowItems.forEach { item ->
                        PaddingSliderTile(
                            item = item,
                            style = style,
                            floatingWidth = floatingWidth,
                            onFloatingSliderChange = onFloatingSliderChange,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PaddingSliderTile(
        item: PaddingItem,
        style: AppDialogStyle,
        floatingWidth: Dp,
        onFloatingSliderChange: (PaddingFloatingSlider?) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Surface(
            modifier = modifier.heightIn(min = 58.dp),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.surface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.label,
                        modifier = Modifier.weight(1f),
                        color = style.primaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.value.toString(),
                        color = style.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                PaddingStepperSlider(
                    item = item,
                    style = style,
                    floatingWidth = floatingWidth,
                    onFloatingSliderChange = onFloatingSliderChange
                )
            }
        }
    }

    @Composable
    private fun PaddingStepperSlider(
        item: PaddingItem,
        style: AppDialogStyle,
        floatingWidth: Dp,
        onFloatingSliderChange: (PaddingFloatingSlider?) -> Unit
    ) {
        val density = LocalDensity.current
        val latestItem by rememberUpdatedState(item)
        var sourceBounds by remember { mutableStateOf<Rect?>(null) }
        val latestSourceBounds by rememberUpdatedState(sourceBounds)
        val thumbSize = 26.dp
        val endpointWidth = 30.dp
        val floatingThumbSize = 42.dp
        val endpointWidthPx = with(density) { endpointWidth.toPx() }
        val thumbSizePx = with(density) { thumbSize.toPx() }
        val floatingUsablePx = with(density) {
            (floatingWidth - floatingThumbSize).toPx().coerceAtLeast(1f)
        }
        val rangeSize = (item.range.last - item.range.first).coerceAtLeast(1)
        val fraction = ((item.value - item.range.first).toFloat() / rangeSize).coerceIn(0f, 1f)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val usablePx = (widthPx - thumbSizePx).coerceAtLeast(0f)
            val thumbOffsetPx = (usablePx * fraction).roundToInt()
            val enabledMinus = item.value > item.range.first
            val enabledPlus = item.value < item.range.last

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        sourceBounds = it.boundsInWindow()
                    }
                    .pointerInput(item.range) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val width = size.width.toFloat()
                            val touchSlop = viewConfiguration.touchSlop
                            val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
                            var totalX = 0f
                            var totalY = 0f
                            var dragging = false
                            var released = false
                            var longPressed = false

                            fun valueForPosition(x: Float): Int {
                                val usable = (width - thumbSizePx).coerceAtLeast(1f)
                                val clamped = (x - thumbSizePx / 2f).coerceIn(0f, usable)
                                return (latestItem.range.first + (clamped / usable) *
                                    (latestItem.range.last - latestItem.range.first))
                                    .roundToInt()
                                    .coerceIn(latestItem.range)
                            }

                            fun applyPosition(x: Float) {
                                val next = valueForPosition(x)
                                if (next != latestItem.value) {
                                    latestItem.onValueChange(next)
                                }
                            }

                            fun expandedValueForDrag(x: Float, startX: Float, startValue: Int): Int {
                                val delta = ((x - startX) / floatingUsablePx * rangeSize).roundToInt()
                                return (startValue + delta).coerceIn(latestItem.range)
                            }

                            fun showExpanded(value: Int) {
                                onFloatingSliderChange(
                                    PaddingFloatingSlider(
                                        value = value,
                                        range = latestItem.range,
                                        sourceBounds = latestSourceBounds,
                                        onValueChange = latestItem.onValueChange
                                    )
                                )
                            }

                            try {
                                val startedBeforeLongPress = withTimeoutOrNull(longPressTimeoutMillis) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            released = true
                                            break
                                        }
                                        val delta = change.positionChange()
                                        totalX += delta.x
                                        totalY += delta.y
                                        if (abs(totalX) > touchSlop || abs(totalY) > touchSlop) {
                                            if (abs(totalX) > abs(totalY)) {
                                                dragging = true
                                                applyPosition(change.position.x)
                                                change.consume()
                                            }
                                            break
                                        }
                                    }
                                    true
                                } ?: false

                                longPressed = !released && !dragging && !startedBeforeLongPress

                                if (longPressed) {
                                    var expandedValue = latestItem.value
                                    val startValue = expandedValue
                                    showExpanded(expandedValue)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        val next = expandedValueForDrag(
                                            x = change.position.x,
                                            startX = down.position.x,
                                            startValue = startValue
                                        )
                                        if (next != expandedValue) {
                                            expandedValue = next
                                            showExpanded(next)
                                            latestItem.onValueChange(next)
                                        }
                                        change.consume()
                                    }
                                } else if (dragging) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        applyPosition(change.position.x)
                                        change.consume()
                                    }
                                }

                                if (!released && !dragging && !longPressed) {
                                    when {
                                        down.position.x <= endpointWidthPx -> {
                                            val next = (latestItem.value - 1).coerceIn(latestItem.range)
                                            if (next != latestItem.value) latestItem.onValueChange(next)
                                        }
                                        down.position.x >= size.width - endpointWidthPx -> {
                                            val next = (latestItem.value + 1).coerceIn(latestItem.range)
                                            if (next != latestItem.value) latestItem.onValueChange(next)
                                        }
                                        else -> applyPosition(down.position.x)
                                    }
                                }
                            } finally {
                                if (!longPressed) {
                                    onFloatingSliderChange(null)
                                }
                            }
                        }
                },
                shape = CircleShape,
                color = style.fieldSurface,
                contentColor = style.primaryText,
                tonalElevation = 0.dp,
                shadowElevation = 1.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PaddingEndpointText(
                            text = "-",
                            enabled = enabledMinus,
                            style = style,
                            modifier = Modifier.width(endpointWidth)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        PaddingEndpointText(
                            text = "+",
                            enabled = enabledPlus,
                            style = style,
                            modifier = Modifier.width(endpointWidth)
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(thumbOffsetPx, 0) }
                            .size(thumbSize),
                        shape = CircleShape,
                        color = style.surface,
                        contentColor = style.primaryText,
                        tonalElevation = 0.dp,
                        shadowElevation = 3.dp
                    ) {}
                }
            }
        }
    }

    @Composable
    private fun PaddingFloatingSliderOverlay(
        value: Int,
        range: IntRange,
        sourceBounds: Rect?,
        style: AppDialogStyle,
        screenWidth: Dp,
        screenHeight: Dp,
        sliderWidth: Dp,
        sliderHeight: Dp,
        endpointWidth: Dp,
        thumbSize: Dp,
        onValueChange: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        val density = LocalDensity.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val progress = remember(sourceBounds) { Animatable(if (sourceBounds == null) 1f else 0f) }
        var editingValue by remember { mutableStateOf(false) }
        val valueHeight = 34.dp
        val valueGap = 8.dp
        val contentHeight = valueHeight + valueGap + sliderHeight
        val targetWidthPx = with(density) { sliderWidth.toPx() }
        val targetHeightPx = with(density) { contentHeight.toPx() }
        val screenWidthPx = with(density) { screenWidth.toPx() }
        val screenHeightPx = with(density) { screenHeight.toPx() }
        val targetLeft = ((screenWidthPx - targetWidthPx) / 2f).coerceAtLeast(0f)
        val targetTop = ((screenHeightPx - targetHeightPx) / 2f).coerceAtLeast(0f)
        val targetRect = Rect(
            left = targetLeft,
            top = targetTop,
            right = targetLeft + targetWidthPx,
            bottom = targetTop + targetHeightPx
        )
        val startRect = sourceBounds ?: targetRect

        LaunchedEffect(sourceBounds, screenWidth, screenHeight, sliderWidth, sliderHeight) {
            progress.snapTo(if (sourceBounds == null) 1f else 0f)
            progress.animateTo(1f, animationSpec = tween(durationMillis = 180))
        }

        fun lerp(start: Float, end: Float): Float {
            return start + (end - start) * progress.value
        }

        val currentLeft = lerp(startRect.left, targetRect.left)
        val currentTop = lerp(startRect.top, targetRect.top)
        val currentWidth = lerp(startRect.width, targetRect.width).coerceAtLeast(1f)
        val currentHeight = lerp(startRect.height, targetRect.height).coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .width(screenWidth)
                .height(screenHeight)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.44f * progress.value))
                    .clickable {
                        if (editingValue) {
                            keyboardController?.hide()
                            editingValue = false
                        } else {
                            onDismiss()
                        }
                    }
            )
            Column(
                modifier = Modifier
                    .offset { IntOffset(currentLeft.roundToInt(), currentTop.roundToInt()) }
                    .width(sliderWidth)
                    .height(contentHeight)
                    .graphicsLayer {
                        scaleX = currentWidth / targetWidthPx
                        scaleY = currentHeight / targetHeightPx
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(valueGap)
            ) {
                PaddingFloatingValueControl(
                    value = value,
                    range = range,
                    editing = editingValue,
                    style = style,
                    modifier = Modifier.height(valueHeight),
                    onEditingChange = { editingValue = it },
                    onValueChange = onValueChange
                )
                PaddingFloatingStepperSlider(
                    value = value,
                    range = range,
                    style = style,
                    modifier = Modifier
                        .width(sliderWidth)
                        .height(sliderHeight),
                    endpointWidth = endpointWidth,
                    thumbSize = thumbSize,
                    onValueChange = onValueChange
                )
            }
        }
    }

    @Composable
    private fun PaddingFloatingValueControl(
        value: Int,
        range: IntRange,
        editing: Boolean,
        style: AppDialogStyle,
        modifier: Modifier = Modifier,
        onEditingChange: (Boolean) -> Unit,
        onValueChange: (Int) -> Unit
    ) {
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusRequester = remember { FocusRequester() }
        var inputText by remember { mutableStateOf(value.toString()) }

        fun commitInput() {
            val parsed = inputText.toIntOrNull()
            if (parsed != null) {
                onValueChange(parsed.coerceIn(range))
            }
            keyboardController?.hide()
            onEditingChange(false)
        }

        LaunchedEffect(editing) {
            if (editing) {
                inputText = value.toString()
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        }

        if (editing) {
            Surface(
                modifier = modifier.width(226.dp),
                shape = CircleShape,
                color = style.surface,
                contentColor = style.primaryText,
                tonalElevation = 0.dp,
                shadowElevation = 10.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = style.secondaryText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.clickable {
                            keyboardController?.hide()
                            onEditingChange(false)
                        }
                    )
                    BasicTextField(
                        value = inputText,
                        onValueChange = { text ->
                            inputText = text.filter { it.isDigit() }.take(4)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = style.accent,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { commitInput() })
                    )
                    Text(
                        text = stringResource(R.string.ok),
                        color = style.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.clickable { commitInput() }
                    )
                }
            }
        } else {
            Surface(
                modifier = modifier
                    .width(96.dp)
                    .clickable { onEditingChange(true) },
                shape = CircleShape,
                color = style.surface,
                contentColor = style.accent,
                tonalElevation = 0.dp,
                shadowElevation = 10.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, style.accent.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = value.toString(),
                        color = style.accent,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    private fun PaddingFloatingStepperSlider(
        value: Int,
        range: IntRange,
        style: AppDialogStyle,
        modifier: Modifier = Modifier,
        endpointWidth: Dp,
        thumbSize: Dp,
        onValueChange: (Int) -> Unit
    ) {
        val density = LocalDensity.current
        val latestValue by rememberUpdatedState(value)
        val rangeSize = (range.last - range.first).coerceAtLeast(1)
        val fraction = ((value - range.first).toFloat() / rangeSize).coerceIn(0f, 1f)
        Surface(
            modifier = modifier.pointerInput(range) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    val endpointWidthPx = with(density) { endpointWidth.toPx() }
                    val thumbSizePx = with(density) { thumbSize.toPx() }

                    fun valueForPosition(x: Float): Int {
                        val usable = (width - thumbSizePx).coerceAtLeast(1f)
                        val clamped = (x - thumbSizePx / 2f).coerceIn(0f, usable)
                        return (range.first + (clamped / usable) * (range.last - range.first))
                            .roundToInt()
                            .coerceIn(range)
                    }

                    when {
                        down.position.x <= endpointWidthPx -> {
                            onValueChange((latestValue - 1).coerceIn(range))
                        }
                        down.position.x >= width - endpointWidthPx -> {
                            onValueChange((latestValue + 1).coerceIn(range))
                        }
                        else -> {
                            onValueChange(valueForPosition(down.position.x))
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                onValueChange(valueForPosition(change.position.x))
                                change.consume()
                            }
                        }
                    }
                }
            },
            shape = CircleShape,
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 14.dp
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                val widthPx = with(density) { maxWidth.toPx() }
                val thumbSizePx = with(density) { thumbSize.toPx() }
                val usablePx = (widthPx - thumbSizePx).coerceAtLeast(0f)
                val thumbOffsetPx = (usablePx * fraction).roundToInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PaddingFloatingEndpointText(
                        text = "-",
                        enabled = value > range.first,
                        style = style,
                        modifier = Modifier.width(endpointWidth)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    PaddingFloatingEndpointText(
                        text = "+",
                        enabled = value < range.last,
                        style = style,
                        modifier = Modifier.width(endpointWidth)
                    )
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(thumbOffsetPx, 0) }
                        .size(thumbSize),
                    shape = CircleShape,
                    color = style.surface,
                    contentColor = style.primaryText,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp
                ) {}
            }
        }
    }

    @Composable
    private fun PaddingFloatingEndpointText(
        text: String,
        enabled: Boolean,
        style: AppDialogStyle,
        modifier: Modifier = Modifier
    ) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) style.accent else style.secondaryText.copy(alpha = 0.34f),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }

    @Composable
    private fun PaddingEndpointText(
        text: String,
        enabled: Boolean,
        style: AppDialogStyle,
        modifier: Modifier = Modifier
    ) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) style.accent else style.secondaryText.copy(alpha = 0.36f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }

    private fun postBodyChanged() {
        postEvent(EventBus.UP_CONFIG, arrayListOf(10, 5))
    }

    private fun postHeaderFooterChanged() {
        postEvent(EventBus.UP_CONFIG, arrayListOf(2))
    }
}
