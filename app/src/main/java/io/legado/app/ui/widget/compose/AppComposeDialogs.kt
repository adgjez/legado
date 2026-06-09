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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
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

private const val MAX_SAVEABLE_MULTI_CHOICE_ITEMS = 128
private const val MAX_ACTION_LIST_ITEMS = 64

@Composable
private fun DismissWhenCallbackMissing(
    missing: Boolean,
    dismiss: () -> Unit
) {
    LaunchedEffect(missing) {
        if (missing) {
            dismiss()
        }
    }
}

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
    scrollContent: Boolean = true,
    messageInContent: Boolean = false,
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
                if (!message.isNullOrBlank() && !messageInContent) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AppDialogMessageText(message = message, style = style)
                }
                Spacer(modifier = Modifier.height(16.dp))
                val contentModifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                val shouldScrollContent = scrollContent || messageInContent
                if (shouldScrollContent) {
                    Column(
                        modifier = contentModifier.verticalScroll(rememberScrollState())
                    ) {
                        AppDialogContent(
                            message = message,
                            messageInContent = messageInContent,
                            style = style,
                            content = content
                        )
                    }
                } else {
                    Column(modifier = contentModifier) {
                        AppDialogContent(
                            message = message,
                            messageInContent = messageInContent,
                            style = style,
                            content = content
                        )
                    }
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

@Composable
private fun AppDialogContent(
    message: String?,
    messageInContent: Boolean,
    style: AppDialogStyle,
    content: @Composable () -> Unit
) {
    if (!message.isNullOrBlank() && messageInContent) {
        AppDialogMessageText(message = message, style = style)
    }
    content()
}

@Composable
private fun AppDialogMessageText(
    message: String,
    style: AppDialogStyle
) {
    SelectionContainer {
        Text(
            text = message,
            color = style.secondaryText,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

class ComposeTextInputDialog : ComposeDialogFragment() {

    private var validateInput: ((String) -> Boolean)? = null
    private var onPositive: ((String) -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onPositive == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val readOnly = args.getBoolean(ARG_READ_ONLY)
                val hintText = args.getString(ARG_HINT).orEmpty()
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val neutralText = args.getString(ARG_NEUTRAL_TEXT)?.takeIf { it.isNotBlank() }
                var text by rememberSaveable {
                    mutableStateOf(args.getString(ARG_INITIAL_TEXT).orEmpty())
                }
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
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
                        val neutralLabel = neutralText
                        val neutralCallback = onNeutral
                        if (neutralLabel != null && neutralCallback != null) {
                            LegadoMiuixActionButton(
                                text = neutralLabel,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    neutralCallback.invoke()
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
                        onPositive?.let { positiveCallback ->
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    val current = text
                                    if (validateInput?.invoke(current) != false) {
                                        dismissAllowingStateLoss()
                                        positiveCallback.invoke(current)
                                    }
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
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
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_HINT, hint)
                    putString(ARG_INITIAL_TEXT, initialValue)
                    putString(ARG_MESSAGE, message)
                    putBoolean(ARG_READ_ONLY, readOnly)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_NEUTRAL_TEXT, neutralText)
                }
                this.validateInput = validateInput
                this.onPositive = onPositive
                this.onNeutral = onNeutral
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_HINT = "hint"
        private const val ARG_INITIAL_TEXT = "initialText"
        private const val ARG_MESSAGE = "message"
        private const val ARG_READ_ONLY = "readOnly"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_NEUTRAL_TEXT = "neutralText"
    }
}

class ComposeMultiChoiceDialog : ComposeDialogFragment() {

    private var onPositive: ((BooleanArray) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onPositive == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val style = rememberAppDialogStyle()
                val itemLabels = remember {
                    args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                }
                val initialCheckedValues = remember(itemLabels) {
                    val initialChecked = args.getBooleanArray(ARG_CHECKED) ?: booleanArrayOf()
                    List(itemLabels.size) { index -> initialChecked.getOrNull(index) ?: false }
                }
                val saveCheckedState = itemLabels.size <= MAX_SAVEABLE_MULTI_CHOICE_ITEMS
                val saveableChecked = if (saveCheckedState) {
                    rememberSaveable(itemLabels) { mutableStateOf(initialCheckedValues) }
                } else {
                    null
                }
                val localChecked = if (saveCheckedState) {
                    null
                } else {
                    remember(itemLabels) {
                        mutableStateListOf<Boolean>().apply { addAll(initialCheckedValues) }
                    }
                }
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val canSubmit = onPositive != null
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    scrollContent = false,
                    content = {
                        val palette = style.toMiuixPalette()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(itemLabels) { index, label ->
                                LegadoMiuixChoiceRow(
                                    text = label,
                                    selected = saveableChecked?.value?.getOrNull(index)
                                        ?: localChecked?.getOrNull(index)
                                        ?: false,
                                    palette = palette,
                                    onClick = {
                                        if (index in itemLabels.indices) {
                                            val state = saveableChecked
                                            if (state != null) {
                                                state.value = state.value.toggleAt(index, itemLabels.size)
                                            } else {
                                                localChecked?.let { values ->
                                                    if (index in values.indices) {
                                                        values[index] = !values[index]
                                                    }
                                                }
                                            }
                                        }
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
                        if (canSubmit) {
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    val result = BooleanArray(itemLabels.size) { index ->
                                        saveableChecked?.value?.getOrNull(index)
                                            ?: localChecked?.getOrNull(index)
                                            ?: false
                                    }
                                    dismissAllowingStateLoss()
                                    onPositive?.invoke(result)
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
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
            val safeLabels = labels.toList()
            return ComposeMultiChoiceDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putBooleanArray(ARG_CHECKED, checked.copyOf())
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.onPositive = onPositive
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_CHECKED = "checked"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
    }
}

private fun List<Boolean>.toggleAt(index: Int, size: Int): List<Boolean> {
    if (index !in 0 until size) return this
    return MutableList(size) { i -> getOrNull(i) ?: false }.apply {
        this[index] = !this[index]
    }
}

class ComposeConfirmDialog : ComposeDialogFragment() {

    private var onPositive: (() -> Unit)? = null
    private var onNegative: (() -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val neutralText = args.getString(ARG_NEUTRAL_TEXT)?.takeIf { it.isNotBlank() }
                val dangerPositive = args.getBoolean(ARG_DANGER_POSITIVE)
                val positiveRequiresCallback = args.getBoolean(ARG_POSITIVE_REQUIRES_CALLBACK, true)
                val negativeRequiresCallback = args.getBoolean(ARG_NEGATIVE_REQUIRES_CALLBACK, false)
                val messageInContent = args.getBoolean(ARG_MESSAGE_IN_CONTENT)
                DismissWhenCallbackMissing(
                    missing = positiveRequiresCallback && onPositive == null ||
                        negativeRequiresCallback && onNegative == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    messageInContent = messageInContent,
                    content = {},
                    actions = {
                        val palette = style.toMiuixPalette()
                        val neutralCallback = onNeutral
                        if (neutralText != null && neutralCallback != null) {
                            LegadoMiuixActionButton(
                                text = neutralText,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    neutralCallback.invoke()
                                },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        val negativeCallback = onNegative
                        if (!negativeRequiresCallback || negativeCallback != null) {
                            LegadoMiuixActionButton(
                                text = negativeText,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    negativeCallback?.invoke()
                                },
                                cornerRadius = style.actionRadius
                            )
                        }
                        val positiveCallback = onPositive
                        if (!positiveRequiresCallback || positiveCallback != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    positiveCallback?.invoke()
                                },
                                primary = !dangerPositive,
                                danger = dangerPositive,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        /**
         * Lambda callbacks are intentionally transient. If Android recreates the dialog,
         * action buttons with lost callbacks are hidden instead of running stale work.
         */
        fun create(
            title: String,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            neutralText: String? = null,
            dangerPositive: Boolean = false,
            positiveRequiresCallback: Boolean = true,
            negativeRequiresCallback: Boolean = false,
            messageInContent: Boolean = false,
            onPositive: () -> Unit,
            onNegative: (() -> Unit)? = null,
            onNeutral: (() -> Unit)? = null
        ): ComposeConfirmDialog {
            return ComposeConfirmDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_NEUTRAL_TEXT, neutralText)
                    putBoolean(ARG_DANGER_POSITIVE, dangerPositive)
                    putBoolean(ARG_POSITIVE_REQUIRES_CALLBACK, positiveRequiresCallback)
                    putBoolean(ARG_NEGATIVE_REQUIRES_CALLBACK, negativeRequiresCallback)
                    putBoolean(ARG_MESSAGE_IN_CONTENT, messageInContent)
                }
                this.onPositive = onPositive
                this.onNegative = onNegative
                this.onNeutral = onNeutral
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_NEUTRAL_TEXT = "neutralText"
        private const val ARG_DANGER_POSITIVE = "dangerPositive"
        private const val ARG_POSITIVE_REQUIRES_CALLBACK = "positiveRequiresCallback"
        private const val ARG_NEGATIVE_REQUIRES_CALLBACK = "negativeRequiresCallback"
        private const val ARG_MESSAGE_IN_CONTENT = "messageInContent"
    }
}

class ComposeSingleChoiceDialog : ComposeDialogFragment() {

    private var onPositive: ((Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onPositive == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val style = rememberAppDialogStyle()
                val itemLabels = remember {
                    args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                }
                val allowNoSelection = args.getBoolean(ARG_ALLOW_NO_SELECTION)
                var selectedIndex by rememberSaveable {
                    mutableStateOf(args.getInt(ARG_SELECTED_INDEX).coerceIn(-1, itemLabels.lastIndex))
                }
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val canSubmit = onPositive != null &&
                    (allowNoSelection || selectedIndex in itemLabels.indices)
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    scrollContent = false,
                    content = {
                        val palette = style.toMiuixPalette()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(itemLabels) { index, label ->
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
                        if (canSubmit) {
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
                    }
                )
            }
        }
    }

    companion object {
        /**
         * Lambda callbacks are intentionally transient. If Android recreates the dialog,
         * the confirm action is hidden unless a callback is still attached.
         */
        fun create(
            title: String,
            labels: List<String>,
            selectedIndex: Int,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            allowNoSelection: Boolean = false,
            onPositive: (Int) -> Unit
        ): ComposeSingleChoiceDialog {
            val safeLabels = labels.toList()
            return ComposeSingleChoiceDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putInt(ARG_SELECTED_INDEX, selectedIndex)
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putBoolean(ARG_ALLOW_NO_SELECTION, allowNoSelection)
                }
                this.onPositive = onPositive
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_SELECTED_INDEX = "selectedIndex"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_ALLOW_NO_SELECTION = "allowNoSelection"
    }
}

class ComposeActionListDialog : ComposeDialogFragment() {

    private var onSelected: ((Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onSelected == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val style = rememberAppDialogStyle()
                val itemLabels = remember {
                    args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                }
                val itemDescriptions = remember {
                    args.getStringArrayList(ARG_DESCRIPTIONS)?.toList().orEmpty()
                }
                val dangerIndices = remember {
                    args.getIntegerArrayList(ARG_DANGER_INDICES)?.toSet().orEmpty()
                }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val canSelect = onSelected != null
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    scrollContent = false,
                    content = {
                        val palette = style.toMiuixPalette()
                        if (canSelect) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(itemLabels) { index, label ->
                                    LegadoMiuixActionRow(
                                        text = label,
                                        palette = palette,
                                        onClick = {
                                            dismissAllowingStateLoss()
                                            onSelected?.invoke(index)
                                        },
                                        description = itemDescriptions.getOrNull(index),
                                        danger = index in dangerIndices,
                                        cornerRadius = style.actionRadius
                                    )
                                }
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
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            labels: List<String>,
            message: String? = null,
            descriptions: List<String> = emptyList(),
            dangerIndices: Set<Int> = emptySet(),
            negativeText: String,
            onSelected: (Int) -> Unit
        ): ComposeActionListDialog {
            require(labels.size <= MAX_ACTION_LIST_ITEMS) {
                "ComposeActionListDialog is for small action menus only."
            }
            val safeLabels = labels.toList()
            val safeDescriptions = List(safeLabels.size) { index ->
                descriptions.getOrNull(index).orEmpty()
            }
            val safeDangerIndices = dangerIndices.filterTo(linkedSetOf()) {
                it in safeLabels.indices
            }
            return ComposeActionListDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putString(ARG_MESSAGE, message)
                    putStringArrayList(ARG_DESCRIPTIONS, ArrayList(safeDescriptions))
                    putIntegerArrayList(ARG_DANGER_INDICES, ArrayList(safeDangerIndices))
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.onSelected = onSelected
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_MESSAGE = "message"
        private const val ARG_DESCRIPTIONS = "descriptions"
        private const val ARG_DANGER_INDICES = "dangerIndices"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
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
