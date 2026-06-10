package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSlider
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.postEvent
import kotlin.math.roundToInt

private enum class PaddingEdge {
    Top,
    Bottom,
    Left,
    Right
}

private data class PaddingItem(
    val edge: PaddingEdge,
    val label: String,
    val value: Int,
    val range: IntRange
)

class PaddingConfigDialog : ComposeDialogFragment() {

    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int? = 430

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
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeaderSection(style = style)
                BodySection(style = style)
                FooterSection(style = style)
            }
        }
    }

    @Composable
    private fun HeaderSection(style: AppDialogStyle) {
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
            PaddingDirectionEditor(
                style = style,
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
                }
            )
        }
    }

    @Composable
    private fun BodySection(style: AppDialogStyle) {
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingRight) }

        PaddingSection(
            title = stringResource(R.string.main_body),
            style = style
        ) {
            PaddingDirectionEditor(
                style = style,
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
                }
            )
        }
    }

    @Composable
    private fun FooterSection(style: AppDialogStyle) {
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
            PaddingDirectionEditor(
                style = style,
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
                }
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
            color = style.fieldSurface.copy(alpha = 0.72f),
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
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (showLine != null && onShowLineChange != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.showLine),
                            color = style.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        LegadoMiuixSwitch(
                            checked = showLine,
                            onCheckedChange = onShowLineChange,
                            palette = style.toMiuixPalette()
                        )
                    }
                }
                content()
            }
        }
    }

    @Composable
    private fun PaddingDirectionEditor(
        style: AppDialogStyle,
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
        onRightChange: (Int) -> Unit
    ) {
        var activeName by rememberSaveable { mutableStateOf(PaddingEdge.Top.name) }
        var editingName by rememberSaveable { mutableStateOf<String?>(null) }
        var editingText by rememberSaveable { mutableStateOf("") }
        val active = activeName.toPaddingEdge()
        val values = listOf(
            PaddingItem(PaddingEdge.Top, stringResource(R.string.top), top, topRange),
            PaddingItem(PaddingEdge.Bottom, stringResource(R.string.bottom), bottom, bottomRange),
            PaddingItem(PaddingEdge.Left, stringResource(R.string.left), left, sideRange),
            PaddingItem(PaddingEdge.Right, stringResource(R.string.right), right, sideRange)
        )
        val activeItem = values.first { it.edge == active }
        val editingItem = editingName
            ?.toPaddingEdge()
            ?.let { edge -> values.firstOrNull { it.edge == edge } }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                values.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowItems.forEach { item ->
                            PaddingValueChip(
                                item = item,
                                selected = item.edge == active,
                                style = style,
                                modifier = Modifier.weight(1f),
                                onClick = { activeName = item.edge.name }
                            )
                        }
                    }
                }
            }
            ActivePaddingControl(
                item = activeItem,
                style = style,
                onExactInputRequest = {
                    editingName = activeItem.edge.name
                    editingText = activeItem.value.toString()
                },
                onValueChange = { value ->
                    applyPaddingChange(
                        edge = activeItem.edge,
                        value = value,
                        onTopChange = onTopChange,
                        onBottomChange = onBottomChange,
                        onLeftChange = onLeftChange,
                        onRightChange = onRightChange
                    )
                }
            )
            if (editingItem != null) {
                PaddingExactInputPanel(
                    item = editingItem,
                    text = editingText,
                    style = style,
                    onTextChange = { text ->
                        editingText = text.filter { it.isDigit() }.take(3)
                    },
                    onCancel = {
                        editingName = null
                        editingText = ""
                    },
                    onConfirm = {
                        editingText.toIntOrNull()
                            ?.coerceIn(editingItem.range)
                            ?.let { value ->
                                applyPaddingChange(
                                    edge = editingItem.edge,
                                    value = value,
                                    onTopChange = onTopChange,
                                    onBottomChange = onBottomChange,
                                    onLeftChange = onLeftChange,
                                    onRightChange = onRightChange
                                )
                            }
                        editingName = null
                        editingText = ""
                    }
                )
            }
        }
    }

    @Composable
    private fun PaddingValueChip(
        item: PaddingItem,
        selected: Boolean,
        style: AppDialogStyle,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = modifier
                .heightIn(min = 42.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(style.actionRadius),
            color = if (selected) style.accent.copy(alpha = 0.14f) else style.surface.copy(alpha = 0.72f),
            contentColor = if (selected) style.accent else style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.label,
                    modifier = Modifier.weight(1f),
                    color = if (selected) style.accent else style.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.value.toString(),
                    color = if (selected) style.accent else style.secondaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 1
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ActivePaddingControl(
        item: PaddingItem,
        style: AppDialogStyle,
        onExactInputRequest: () -> Unit,
        onValueChange: (Int) -> Unit
    ) {
        val palette = style.toMiuixPalette()
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.label,
                    modifier = Modifier.weight(1f),
                    color = style.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                PaddingStepButton(
                    text = "-",
                    enabled = item.value > item.range.first,
                    style = style,
                    onClick = { onValueChange((item.value - 1).coerceIn(item.range)) }
                )
                Text(
                    text = item.value.toString(),
                    modifier = Modifier
                        .width(38.dp)
                        .combinedClickable(
                            onClick = onExactInputRequest,
                            onLongClick = onExactInputRequest
                        ),
                    color = style.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                PaddingStepButton(
                    text = "+",
                    enabled = item.value < item.range.last,
                    style = style,
                    onClick = { onValueChange((item.value + 1).coerceIn(item.range)) }
                )
            }
            LegadoMiuixSlider(
                value = item.value.toFloat(),
                onValueChange = { value ->
                    val newValue = value.roundToInt().coerceIn(item.range)
                    if (newValue != item.value) {
                        onValueChange(newValue)
                    }
                },
                palette = palette,
                valueRange = item.range.first.toFloat()..item.range.last.toFloat(),
                steps = (item.range.last - item.range.first - 1).coerceAtLeast(0)
            )
        }
    }

    @Composable
    private fun PaddingExactInputPanel(
        item: PaddingItem,
        text: String,
        style: AppDialogStyle,
        onTextChange: (String) -> Unit,
        onCancel: () -> Unit,
        onConfirm: () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.surface.copy(alpha = 0.78f),
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.label,
                    modifier = Modifier.weight(1f),
                    color = style.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(
                        color = style.primaryText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.width(64.dp),
                    decorationBox = { innerTextField ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(style.actionRadius),
                            color = style.fieldSurface,
                            contentColor = style.primaryText,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (text.isEmpty()) {
                                    Text(
                                        text = item.value.toString(),
                                        color = style.secondaryText.copy(alpha = 0.56f),
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                                innerTextField()
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.width(6.dp))
                PaddingInlineButton(
                    text = stringResource(android.R.string.cancel),
                    primary = false,
                    style = style,
                    onClick = onCancel
                )
                Spacer(modifier = Modifier.width(5.dp))
                PaddingInlineButton(
                    text = stringResource(android.R.string.ok),
                    primary = true,
                    style = style,
                    onClick = onConfirm
                )
            }
        }
    }

    @Composable
    private fun PaddingInlineButton(
        text: String,
        primary: Boolean,
        style: AppDialogStyle,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .heightIn(min = 34.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(style.actionRadius),
            color = if (primary) style.accent.copy(alpha = 0.14f) else style.fieldSurface,
            contentColor = if (primary) style.accent else style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = if (primary) style.accent else style.primaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun PaddingStepButton(
        text: String,
        enabled: Boolean,
        style: AppDialogStyle,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .size(34.dp)
                .clickable(enabled = enabled, onClick = onClick),
            shape = RoundedCornerShape(style.actionRadius),
            color = if (enabled) style.accent.copy(alpha = 0.12f) else style.surface.copy(alpha = 0.56f),
            contentColor = if (enabled) style.accent else style.secondaryText.copy(alpha = 0.46f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = if (enabled) style.accent else style.secondaryText.copy(alpha = 0.46f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }

    private fun applyPaddingChange(
        edge: PaddingEdge,
        value: Int,
        onTopChange: (Int) -> Unit,
        onBottomChange: (Int) -> Unit,
        onLeftChange: (Int) -> Unit,
        onRightChange: (Int) -> Unit
    ) {
        when (edge) {
            PaddingEdge.Top -> onTopChange(value)
            PaddingEdge.Bottom -> onBottomChange(value)
            PaddingEdge.Left -> onLeftChange(value)
            PaddingEdge.Right -> onRightChange(value)
        }
    }

    private fun String.toPaddingEdge(): PaddingEdge {
        return runCatching { PaddingEdge.valueOf(this) }.getOrDefault(PaddingEdge.Top)
    }

    private fun postBodyChanged() {
        postEvent(EventBus.UP_CONFIG, arrayListOf(10, 5))
    }

    private fun postHeaderFooterChanged() {
        postEvent(EventBus.UP_CONFIG, arrayListOf(2))
    }
}
