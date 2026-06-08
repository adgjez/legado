package io.legado.app.ui.widget.compose

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.ColorUtils
import kotlin.math.roundToInt

@Stable
data class AppDialogStyle(
    val accent: Color,
    val surface: Color,
    val fieldSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val stroke: Color,
    val danger: Color,
    val panelRadius: androidx.compose.ui.unit.Dp,
    val actionRadius: androidx.compose.ui.unit.Dp,
    val bodyFontFamily: FontFamily,
    val titleFontFamily: FontFamily
)

@Composable
fun rememberAppDialogStyle(): AppDialogStyle {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    val surface = if (night) {
        0xff24262b.toInt()
    } else {
        0xfffbfcfe.toInt()
    }
    val fieldSurface = ColorUtils.blendColors(
        surface,
        accent,
        if (night) 0.08f else 0.05f
    )
    return AppDialogStyle(
        accent = Color(accent),
        surface = Color(surface),
        fieldSurface = Color(fieldSurface),
        primaryText = Color(if (night) 0xfff2f3f5.toInt() else 0xff202124.toInt()),
        secondaryText = Color(if (night) 0xffaeb4bc.toInt() else 0xff6b7178.toInt()),
        stroke = Color(if (night) 0x24ffffff else 0x14000000),
        danger = Color(ContextCompat.getColor(context, R.color.md_red_500)),
        panelRadius = context.composePanelRadius(),
        actionRadius = context.composeActionRadius(),
        bodyFontFamily = FontFamily(context.uiTypeface()),
        titleFontFamily = FontFamily(context.titleTypeface())
    )
}

@Composable
fun AppDialogFrame(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    val style = rememberAppDialogStyle()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LegadoMiuixCard(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Text(
                    text = title,
                    color = style.primaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!message.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = message,
                            color = style.secondaryText,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    content()
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions()
                }
            }
        }
    }
}

class ComposeTextInputDialog : ComposeDialogFragment() {

    private var titleText: String = ""
    private var messageText: String? = null
    private var hintText: String = ""
    private var initialText: String = ""
    private var readOnly: Boolean = false
    private var positiveText: String = ""
    private var negativeText: String = ""
    private var neutralText: String? = null
    private var validateInput: ((String) -> Boolean)? = null
    private var onPositive: ((String) -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var text by rememberSaveable { mutableStateOf(initialText) }
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = titleText,
                    message = messageText,
                    content = {
                        SelectionContainer {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { if (!readOnly) text = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = if (readOnly) 2 else 1,
                                maxLines = if (readOnly) 6 else 4,
                                readOnly = readOnly,
                                label = if (hintText.isNotBlank()) {
                                    { Text(hintText) }
                                } else {
                                    null
                                },
                                shape = RoundedCornerShape(style.actionRadius),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = style.primaryText,
                                    unfocusedTextColor = style.primaryText,
                                    disabledTextColor = style.secondaryText,
                                    focusedContainerColor = style.fieldSurface,
                                    unfocusedContainerColor = style.fieldSurface,
                                    disabledContainerColor = style.fieldSurface.copy(alpha = 0.58f),
                                    cursorColor = style.accent,
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    disabledBorderColor = Color.Transparent,
                                    focusedLabelColor = style.accent,
                                    unfocusedLabelColor = style.secondaryText
                                ),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = style.primaryText,
                                    fontFamily = style.bodyFontFamily
                                )
                            )
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        neutralText?.let { label ->
                            LegadoMiuixActionButton(
                                text = label,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    onNeutral?.invoke()
                                },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = positiveText,
                            palette = palette,
                            onClick = {
                                val current = text
                                if (validateInput?.invoke(current) != false) {
                                    dismissAllowingStateLoss()
                                    onPositive?.invoke(current)
                                }
                            },
                            primary = true,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            hint: String = "",
            initialValue: String = "",
            message: String? = null,
            readOnly: Boolean = false,
            positiveText: String,
            negativeText: String,
            neutralText: String? = null,
            validateInput: ((String) -> Boolean)? = null,
            onPositive: (String) -> Unit,
            onNeutral: (() -> Unit)? = null
        ): ComposeTextInputDialog {
            return ComposeTextInputDialog().apply {
                titleText = title
                hintText = hint
                initialText = initialValue
                messageText = message
                this.readOnly = readOnly
                this.positiveText = positiveText
                this.negativeText = negativeText
                this.neutralText = neutralText
                this.validateInput = validateInput
                this.onPositive = onPositive
                this.onNeutral = onNeutral
            }
        }
    }
}

class ComposeMultiChoiceDialog : ComposeDialogFragment() {

    private var titleText: String = ""
    private var messageText: String? = null
    private var itemLabels: List<String> = emptyList()
    private var initialChecked: BooleanArray = booleanArrayOf()
    private var positiveText: String = ""
    private var negativeText: String = ""
    private var onPositive: ((BooleanArray) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val checked = remember {
                    mutableStateListOf<Boolean>().apply {
                        addAll(
                            itemLabels.mapIndexed { index, _ ->
                                initialChecked.getOrNull(index) ?: false
                            }
                        )
                    }
                }
                AppDialogFrame(
                    title = titleText,
                    message = messageText,
                    content = {
                        val palette = style.toMiuixPalette()
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemLabels.forEachIndexed { index, label ->
                                LegadoMiuixChoiceRow(
                                    text = label,
                                    selected = checked[index],
                                    palette = palette,
                                    onClick = {
                                        checked[index] = !checked[index]
                                    },
                                    minHeight = 42.dp
                                )
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = positiveText,
                            palette = palette,
                            onClick = {
                                val result = BooleanArray(checked.size) { checked[it] }
                                dismissAllowingStateLoss()
                                onPositive?.invoke(result)
                            },
                            primary = true,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            labels: List<String>,
            checked: BooleanArray,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            onPositive: (BooleanArray) -> Unit
        ): ComposeMultiChoiceDialog {
            return ComposeMultiChoiceDialog().apply {
                titleText = title
                itemLabels = labels
                initialChecked = checked
                messageText = message
                this.positiveText = positiveText
                this.negativeText = negativeText
                this.onPositive = onPositive
            }
        }
    }
}

class ComposeConfirmDialog : ComposeDialogFragment() {

    private var titleText: String = ""
    private var messageText: String? = null
    private var positiveText: String = ""
    private var negativeText: String = ""
    private var neutralText: String? = null
    private var dangerPositive: Boolean = false
    private var onPositive: (() -> Unit)? = null
    private var onNegative: (() -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = titleText,
                    message = messageText,
                    content = {},
                    actions = {
                        val palette = style.toMiuixPalette()
                        neutralText?.let { label ->
                            LegadoMiuixActionButton(
                                text = label,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    onNeutral?.invoke()
                                },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = {
                                dismissAllowingStateLoss()
                                onNegative?.invoke()
                            },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = positiveText,
                            palette = palette,
                            onClick = {
                                dismissAllowingStateLoss()
                                onPositive?.invoke()
                            },
                            primary = !dangerPositive,
                            danger = dangerPositive,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            neutralText: String? = null,
            dangerPositive: Boolean = false,
            onPositive: () -> Unit,
            onNegative: (() -> Unit)? = null,
            onNeutral: (() -> Unit)? = null
        ): ComposeConfirmDialog {
            return ComposeConfirmDialog().apply {
                titleText = title
                messageText = message
                this.positiveText = positiveText
                this.negativeText = negativeText
                this.neutralText = neutralText
                this.dangerPositive = dangerPositive
                this.onPositive = onPositive
                this.onNegative = onNegative
                this.onNeutral = onNeutral
            }
        }
    }
}

class ComposeSingleChoiceDialog : ComposeDialogFragment() {

    private var titleText: String = ""
    private var messageText: String? = null
    private var itemLabels: List<String> = emptyList()
    private var initialSelectedIndex: Int = -1
    private var positiveText: String = ""
    private var negativeText: String = ""
    private var onPositive: ((Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                var selectedIndex by rememberSaveable {
                    mutableStateOf(initialSelectedIndex.coerceIn(-1, itemLabels.lastIndex))
                }
                AppDialogFrame(
                    title = titleText,
                    message = messageText,
                    content = {
                        val palette = style.toMiuixPalette()
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemLabels.forEachIndexed { index, label ->
                                LegadoMiuixChoiceRow(
                                    text = label,
                                    selected = selectedIndex == index,
                                    palette = palette,
                                    onClick = { selectedIndex = index },
                                    minHeight = 42.dp
                                )
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = positiveText,
                            palette = palette,
                            onClick = {
                                dismissAllowingStateLoss()
                                onPositive?.invoke(selectedIndex)
                            },
                            primary = true,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            labels: List<String>,
            selectedIndex: Int,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            onPositive: (Int) -> Unit
        ): ComposeSingleChoiceDialog {
            return ComposeSingleChoiceDialog().apply {
                titleText = title
                itemLabels = labels
                initialSelectedIndex = selectedIndex
                messageText = message
                this.positiveText = positiveText
                this.negativeText = negativeText
                this.onPositive = onPositive
            }
        }
    }
}

@Composable
fun AppDialogSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = if (checked) style.primaryText else style.secondaryText,
                fontSize = 15.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(10.dp))
            LegadoMiuixSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                palette = palette
            )
        }
    }
}

@Composable
fun AppDialogOptionGroup(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = style.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, label ->
                LegadoMiuixChoiceRow(
                    text = label,
                    selected = selectedIndex == index,
                    palette = palette,
                    onClick = { onSelected(index) },
                    minHeight = 40.dp,
                    compact = true
                )
            }
        }
    }
}

@Composable
fun AppDialogSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = style.primaryText,
                fontSize = 15.sp
            )
            Text(
                text = value.toString(),
                color = style.secondaryText,
                fontSize = 13.sp
            )
        }
        LegadoMiuixSlider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(range.first, range.last)) },
            palette = palette,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0)
        )
        }
    }
}
