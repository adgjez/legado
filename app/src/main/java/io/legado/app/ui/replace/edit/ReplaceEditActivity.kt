package io.legado.app.ui.replace.edit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.ActivityReplaceEditBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppRuleFieldSpacer
import io.legado.app.ui.widget.compose.AppRuleSwitchRow
import io.legado.app.ui.widget.compose.AppRuleTextField
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class ReplaceEditActivity :
    VMBaseActivity<ActivityReplaceEditBinding, ReplaceEditViewModel>(),
    KeyboardToolPop.CallBack {

    companion object {

        private const val MAX_FIELD_HISTORY = 120

        fun startIntent(
            context: Context,
            id: Long = -1,
            pattern: String? = null,
            isRegex: Boolean = false,
            scope: String? = null
        ): Intent {
            val intent = Intent(context, ReplaceEditActivity::class.java)
            intent.putExtra("id", id)
            intent.putExtra("pattern", pattern)
            intent.putExtra("isRegex", isRegex)
            intent.putExtra("scope", scope)
            return intent
        }
    }

    enum class ReplaceField {
        Name, Group, Pattern, Replacement, Scope, ExcludeScope, Timeout
    }

    override val binding by viewBinding(ActivityReplaceEditBinding::inflate)
    override val viewModel by viewModels<ReplaceEditViewModel>()

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    private var nameValue by mutableStateOf(TextFieldValue(""))
    private var groupValue by mutableStateOf(TextFieldValue(""))
    private var patternValue by mutableStateOf(TextFieldValue(""))
    private var replacementValue by mutableStateOf(TextFieldValue(""))
    private var scopeValue by mutableStateOf(TextFieldValue(""))
    private var excludeScopeValue by mutableStateOf(TextFieldValue(""))
    private var timeoutValue by mutableStateOf(TextFieldValue(""))
    private var isRegex by mutableStateOf(false)
    private var scopeTitle by mutableStateOf(false)
    private var scopeContent by mutableStateOf(true)
    private var focusedField: ReplaceField? = null
    private var updatingHistory = false
    private val undoStacks = mutableMapOf<ReplaceField, ArrayDeque<TextFieldValue>>()
    private val redoStacks = mutableMapOf<ReplaceField, ArrayDeque<TextFieldValue>>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            upReplaceView(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.replace_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val field = focusedField
            if (field == null) {
                toastOnUi(R.string.focus_lost_on_textbox)
                return@registerForActivityResult
            }
            result.data?.getStringExtra("text")?.let {
                val cursor = result.data?.getIntExtra("cursorPosition", -1)
                    ?.takeIf { position -> position in 0..it.length }
                    ?: it.length
                updateFieldValue(field, TextFieldValue(it, TextRange(cursor)))
            }
        }
    }

    private fun onFullEditClicked() {
        val field = focusedField
        if (field != null) {
            val value = getFieldValue(field)
            val intent = Intent(this, CodeEditActivity::class.java).apply {
                putExtra("text", value.text)
                putExtra("title", fieldTitle(field))
                putExtra("cursorPosition", value.selection.start)
            }
            textEditLauncher.launch(intent)
        } else {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_fullscreen_edit -> onFullEditClicked()
            R.id.menu_save -> viewModel.save(getReplaceRule()) {
                setResult(RESULT_OK)
                finish()
            }

            R.id.menu_copy_rule -> sendToClip(GSON.toJson(getReplaceRule()))
            R.id.menu_paste_rule -> viewModel.pasteRule {
                upReplaceView(it)
            }
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    private fun initView() {
        binding.llContent.removeAllViews()
        binding.llContent.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    ReplaceEditContent(
                        name = nameValue,
                        onNameChange = { updateFieldValue(ReplaceField.Name, it) },
                        group = groupValue,
                        onGroupChange = { updateFieldValue(ReplaceField.Group, it) },
                        pattern = patternValue,
                        onPatternChange = { updateFieldValue(ReplaceField.Pattern, it) },
                        isRegex = isRegex,
                        onRegexChange = { isRegex = it },
                        replacement = replacementValue,
                        onReplacementChange = { updateFieldValue(ReplaceField.Replacement, it) },
                        scopeTitle = scopeTitle,
                        onScopeTitleChange = { scopeTitle = it },
                        scopeContent = scopeContent,
                        onScopeContentChange = { scopeContent = it },
                        scope = scopeValue,
                        onScopeChange = { updateFieldValue(ReplaceField.Scope, it) },
                        excludeScope = excludeScopeValue,
                        onExcludeScopeChange = { updateFieldValue(ReplaceField.ExcludeScope, it) },
                        timeout = timeoutValue,
                        onTimeoutChange = { updateFieldValue(ReplaceField.Timeout, it) },
                        onFieldFocused = { focusedField = it },
                        onRegexHelp = { showHelp("regexHelp") }
                    )
                }
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    private fun upReplaceView(replaceRule: ReplaceRule) {
        clearEditHistory()
        setFieldValue(ReplaceField.Name, TextFieldValue(replaceRule.name))
        setFieldValue(ReplaceField.Group, TextFieldValue(replaceRule.group.orEmpty()))
        setFieldValue(ReplaceField.Pattern, TextFieldValue(replaceRule.pattern.orEmpty()))
        isRegex = replaceRule.isRegex
        setFieldValue(ReplaceField.Replacement, TextFieldValue(replaceRule.replacement.orEmpty()))
        scopeTitle = replaceRule.scopeTitle
        scopeContent = replaceRule.scopeContent
        setFieldValue(ReplaceField.Scope, TextFieldValue(replaceRule.scope.orEmpty()))
        setFieldValue(ReplaceField.ExcludeScope, TextFieldValue(replaceRule.excludeScope.orEmpty()))
        setFieldValue(ReplaceField.Timeout, TextFieldValue(replaceRule.timeoutMillisecond.toString()))
    }

    private fun getReplaceRule(): ReplaceRule {
        val replaceRule: ReplaceRule = viewModel.replaceRule ?: ReplaceRule()
        replaceRule.name = nameValue.text
        replaceRule.group = groupValue.text
        replaceRule.pattern = patternValue.text
        replaceRule.isRegex = isRegex
        replaceRule.replacement = replacementValue.text
        replaceRule.scopeTitle = scopeTitle
        replaceRule.scopeContent = scopeContent
        replaceRule.scope = scopeValue.text
        replaceRule.excludeScope = excludeScopeValue.text
        replaceRule.timeoutMillisecond = timeoutValue.text.ifEmpty { "3000" }.toLongOrNull() ?: 3000L
        return replaceRule
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("正则教程", "regexHelp")
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "regexHelp" -> showHelp("regexHelp")
        }
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val field = focusedField ?: return
        val value = getFieldValue(field)
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val newText = buildString {
            append(value.text.substring(0, start))
            append(text)
            append(value.text.substring(end))
        }
        updateFieldValue(field, TextFieldValue(newText, TextRange(start + text.length)))
    }

    override fun onUndoClicked() {
        focusedField?.let(::undoField)
    }

    override fun onRedoClicked() {
        focusedField?.let(::redoField)
    }

    private fun getFieldValue(field: ReplaceField): TextFieldValue {
        return when (field) {
            ReplaceField.Name -> nameValue
            ReplaceField.Group -> groupValue
            ReplaceField.Pattern -> patternValue
            ReplaceField.Replacement -> replacementValue
            ReplaceField.Scope -> scopeValue
            ReplaceField.ExcludeScope -> excludeScopeValue
            ReplaceField.Timeout -> timeoutValue
        }
    }

    private fun updateFieldValue(field: ReplaceField, value: TextFieldValue) {
        val oldValue = getFieldValue(field)
        if (!updatingHistory && oldValue != value) {
            val undoStack = undoStacks.getOrPut(field) { ArrayDeque() }
            undoStack.addLast(oldValue)
            while (undoStack.size > MAX_FIELD_HISTORY) {
                undoStack.removeFirst()
            }
            redoStacks[field]?.clear()
        }
        setFieldValue(field, value)
    }

    private fun setFieldValue(field: ReplaceField, value: TextFieldValue) {
        when (field) {
            ReplaceField.Name -> nameValue = value
            ReplaceField.Group -> groupValue = value
            ReplaceField.Pattern -> patternValue = value
            ReplaceField.Replacement -> replacementValue = value
            ReplaceField.Scope -> scopeValue = value
            ReplaceField.ExcludeScope -> excludeScopeValue = value
            ReplaceField.Timeout -> timeoutValue = value
        }
    }

    private fun undoField(field: ReplaceField) {
        val undoStack = undoStacks[field]
        if (undoStack.isNullOrEmpty()) return
        val current = getFieldValue(field)
        val previous = undoStack.removeLast()
        redoStacks.getOrPut(field) { ArrayDeque() }.addLast(current)
        updatingHistory = true
        setFieldValue(field, previous)
        updatingHistory = false
    }

    private fun redoField(field: ReplaceField) {
        val redoStack = redoStacks[field]
        if (redoStack.isNullOrEmpty()) return
        val current = getFieldValue(field)
        val next = redoStack.removeLast()
        undoStacks.getOrPut(field) { ArrayDeque() }.addLast(current)
        updatingHistory = true
        setFieldValue(field, next)
        updatingHistory = false
    }

    private fun clearEditHistory() {
        undoStacks.clear()
        redoStacks.clear()
    }

    private fun fieldTitle(field: ReplaceField): String {
        return when (field) {
            ReplaceField.Name -> getString(R.string.replace_rule_summary)
            ReplaceField.Group -> getString(R.string.group)
            ReplaceField.Pattern -> getString(R.string.replace_rule)
            ReplaceField.Replacement -> getString(R.string.replace_to)
            ReplaceField.Scope -> getString(R.string.replace_scope)
            ReplaceField.ExcludeScope -> getString(R.string.replace_exclude_scope)
            ReplaceField.Timeout -> getString(R.string.timeout_millisecond)
        }
    }
}

@Composable
private fun ReplaceEditContent(
    name: TextFieldValue,
    onNameChange: (TextFieldValue) -> Unit,
    group: TextFieldValue,
    onGroupChange: (TextFieldValue) -> Unit,
    pattern: TextFieldValue,
    onPatternChange: (TextFieldValue) -> Unit,
    isRegex: Boolean,
    onRegexChange: (Boolean) -> Unit,
    replacement: TextFieldValue,
    onReplacementChange: (TextFieldValue) -> Unit,
    scopeTitle: Boolean,
    onScopeTitleChange: (Boolean) -> Unit,
    scopeContent: Boolean,
    onScopeContentChange: (Boolean) -> Unit,
    scope: TextFieldValue,
    onScopeChange: (TextFieldValue) -> Unit,
    excludeScope: TextFieldValue,
    onExcludeScopeChange: (TextFieldValue) -> Unit,
    timeout: TextFieldValue,
    onTimeoutChange: (TextFieldValue) -> Unit,
    onFieldFocused: (ReplaceEditActivity.ReplaceField) -> Unit,
    onRegexHelp: () -> Unit
) {
    val style = rememberAppDialogStyle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        AppRuleTextField(
            value = name,
            onValueChange = onNameChange,
            label = stringResource(R.string.replace_rule_summary),
            singleLine = true,
            style = style,
            onFocused = { onFieldFocused(ReplaceEditActivity.ReplaceField.Name) }
        )
        AppRuleFieldSpacer()
        AppRuleTextField(
            value = group,
            onValueChange = onGroupChange,
            label = stringResource(R.string.group),
            singleLine = true,
            style = style,
            onFocused = { onFieldFocused(ReplaceEditActivity.ReplaceField.Group) }
        )
        AppRuleFieldSpacer()
        AppRuleTextField(
            value = pattern,
            onValueChange = onPatternChange,
            label = stringResource(R.string.replace_rule),
            minLines = 2,
            maxLines = 6,
            style = style,
            onFocused = { onFieldFocused(ReplaceEditActivity.ReplaceField.Pattern) }
        )
        AppRuleFieldSpacer()
        RegexSwitchRow(
            isRegex = isRegex,
            onRegexChange = onRegexChange,
            onRegexHelp = onRegexHelp,
            style = style
        )
        AppRuleFieldSpacer()
        AppRuleTextField(
            value = replacement,
            onValueChange = onReplacementChange,
            label = stringResource(R.string.replace_to),
            minLines = 2,
            maxLines = 6,
            style = style,
            onFocused = { onFieldFocused(ReplaceEditActivity.ReplaceField.Replacement) }
        )
        AppRuleFieldSpacer()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppRuleSwitchRow(
                text = stringResource(R.string.scope_title),
                checked = scopeTitle,
                onCheckedChange = onScopeTitleChange,
                modifier = Modifier.weight(1f),
                style = style
            )
            AppRuleSwitchRow(
                text = stringResource(R.string.scope_content),
                checked = scopeContent,
                onCheckedChange = onScopeContentChange,
                modifier = Modifier.weight(1f),
                style = style
            )
        }
        AppRuleFieldSpacer()
        AppRuleTextField(
            value = scope,
            onValueChange = onScopeChange,
            label = stringResource(R.string.replace_scope),
            singleLine = true,
            style = style,
            onFocused = { onFieldFocused(ReplaceEditActivity.ReplaceField.Scope) }
        )
        AppRuleFieldSpacer()
        AppRuleTextField(
            value = excludeScope,
            onValueChange = onExcludeScopeChange,
            label = stringResource(R.string.replace_exclude_scope),
            singleLine = true,
            style = style,
            onFocused = { onFieldFocused(ReplaceEditActivity.ReplaceField.ExcludeScope) }
        )
        AppRuleFieldSpacer()
        AppRuleTextField(
            value = timeout,
            onValueChange = onTimeoutChange,
            label = stringResource(R.string.timeout_millisecond),
            singleLine = true,
            keyboardType = KeyboardType.Number,
            style = style,
            onFocused = { onFieldFocused(ReplaceEditActivity.ReplaceField.Timeout) }
        )
    }
}

@Composable
private fun RegexSwitchRow(
    isRegex: Boolean,
    onRegexChange: (Boolean) -> Unit,
    onRegexHelp: () -> Unit,
    style: AppDialogStyle
) {
    val palette = style.toMiuixPalette()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppRuleSwitchRow(
            text = stringResource(R.string.use_regex),
            checked = isRegex,
            onCheckedChange = onRegexChange,
            modifier = Modifier.weight(1f),
            style = style
        )
        LegadoMiuixActionButton(
            text = stringResource(R.string.help),
            palette = palette,
            onClick = onRegexHelp,
            cornerRadius = style.actionRadius,
            minWidth = 64.dp
        )
    }
}
