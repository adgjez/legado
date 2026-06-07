package io.legado.app.ui.widget.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.setLayout
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
    val actionRadius: androidx.compose.ui.unit.Dp
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
        actionRadius = context.composeActionRadius()
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        shape = RoundedCornerShape(style.panelRadius),
        color = style.surface,
        tonalElevation = 0.dp,
        shadowElevation = if (AppConfig.isEInkMode) 0.dp else 10.dp,
        border = BorderStroke(1.dp, style.stroke)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
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

class ComposeTextInputDialog : BaseDialogFragment(0) {

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

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

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
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = style.primaryText
                                )
                            )
                        }
                    },
                    actions = {
                        neutralText?.let { label ->
                            TextButton(
                                onClick = {
                                    dismissAllowingStateLoss()
                                    onNeutral?.invoke()
                                },
                                shape = RoundedCornerShape(style.actionRadius)
                            ) {
                                Text(label, color = style.accent)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(
                            onClick = { dismissAllowingStateLoss() },
                            shape = RoundedCornerShape(style.actionRadius)
                        ) {
                            Text(negativeText, color = style.secondaryText)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                        onClick = {
                            val current = text
                            if (validateInput?.invoke(current) == false) {
                                return@TextButton
                            }
                            dismissAllowingStateLoss()
                            onPositive?.invoke(current)
                        },
                            shape = RoundedCornerShape(style.actionRadius)
                        ) {
                            Text(positiveText, color = style.accent)
                        }
                    }
                )
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
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

class ComposeMultiChoiceDialog : BaseDialogFragment(0) {

    private var titleText: String = ""
    private var messageText: String? = null
    private var itemLabels: List<String> = emptyList()
    private var initialChecked: BooleanArray = booleanArrayOf()
    private var positiveText: String = ""
    private var negativeText: String = ""
    private var onPositive: ((BooleanArray) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                        itemLabels.forEachIndexed { index, label ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { checked[index] = !checked[index] }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked[index],
                                    onCheckedChange = { checked[index] = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    color = style.primaryText,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp
                                )
                            }
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { dismissAllowingStateLoss() },
                            shape = RoundedCornerShape(style.actionRadius)
                        ) {
                            Text(negativeText, color = style.secondaryText)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val result = BooleanArray(checked.size) { checked[it] }
                                dismissAllowingStateLoss()
                                onPositive?.invoke(result)
                            },
                            shape = RoundedCornerShape(style.actionRadius)
                        ) {
                            Text(positiveText, color = style.accent)
                        }
                    }
                )
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
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

@Composable
fun AppDialogSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val style = rememberAppDialogStyle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = style.primaryText,
            fontSize = 15.sp
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = style.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        options.forEachIndexed { index, label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelected(index) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedIndex == index,
                    onClick = { onSelected(index) }
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    color = style.primaryText,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
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
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0)
        )
    }
}
