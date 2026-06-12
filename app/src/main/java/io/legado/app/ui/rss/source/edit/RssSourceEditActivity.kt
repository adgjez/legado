package io.legado.app.ui.rss.source.edit

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ActivityRssSourceEditBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.source.debug.RssSourceDebugActivity
import io.legado.app.ui.widget.compose.AppRuleFieldSpacer
import io.legado.app.ui.widget.compose.AppRuleSwitchRow
import io.legado.app.ui.widget.compose.AppRuleTabRow
import io.legado.app.ui.widget.compose.AppRuleTextField
import io.legado.app.ui.widget.compose.ComposeConfirmDialog
import io.legado.app.ui.widget.compose.LegadoMiuixSelectField
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.share
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RssSourceEditActivity :
    VMBaseActivity<ActivityRssSourceEditBinding, RssSourceEditViewModel>(),
    KeyboardToolPop.CallBack,
    VariableDialog.Callback {

    override val binding by viewBinding(ActivityRssSourceEditBinding::inflate)
    override val viewModel by viewModels<RssSourceEditViewModel>()

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }
    private val textValues = mutableStateMapOf<String, TextFieldValue>()
    private val boolValues = mutableStateMapOf<String, Boolean>()
    private val undoStacks = mutableMapOf<String, ArrayDeque<TextFieldValue>>()
    private val redoStacks = mutableMapOf<String, ArrayDeque<TextFieldValue>>()
    private var focusedKey: String? = null
    private var updatingHistory = false
    private var enabled by mutableStateOf(true)
    private var singleUrl by mutableStateOf(false)
    private var enabledCookieJar by mutableStateOf(true)
    private var preload by mutableStateOf(false)
    private var sourceType by mutableIntStateOf(0)
    private var articleStyle by mutableIntStateOf(0)
    private var selectedTab by mutableIntStateOf(0)

    private val selectDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                sendText(uri.toString())
            } else {
                sendText(uri.path.toString())
            }
        }
    }
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it?.let {
            viewModel.importSource(it) { source: RssSource ->
                upSourceView(source)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            upSourceView(viewModel.rssSource)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!LocalConfig.ruleHelpVersionIsLast) {
            showHelp("rssRuleHelp")
        }
    }

    override fun finish() {
        val source = getRssSource()
        if (!source.equal(viewModel.rssSource ?: RssSource())) {
            ComposeConfirmDialog.create(
                title = getString(R.string.exit),
                message = getString(R.string.exit_no_save),
                positiveText = getString(R.string.yes),
                negativeText = getString(R.string.no),
                onPositive = {},
                onNegative = ::finishWithoutConfirm
            ).show(supportFragmentManager, "exitConfirm")
        } else {
            super.finish()
        }
    }

    private fun finishWithoutConfirm() {
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.source_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !getRssSource().loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_auto_complete)?.isChecked = viewModel.autoComplete
        return super.onMenuOpened(featureId, menu)
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val key = focusedKey
            if (key == null) {
                toastOnUi(R.string.focus_lost_on_textbox)
                return@registerForActivityResult
            }
            result.data?.getStringExtra("text")?.let {
                val cursor = result.data?.getIntExtra("cursorPosition", -1)
                    ?.takeIf { position -> position in 0..it.length }
                    ?: it.length
                updateTextValue(key, TextFieldValue(it, TextRange(cursor)))
            }
        }
    }

    private fun onFullEditClicked() {
        val key = focusedKey
        if (key != null) {
            val value = getTextValue(key)
            val intent = Intent(this, CodeEditActivity::class.java).apply {
                putExtra("text", value.text)
                putExtra("title", fieldTitle(key))
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

            R.id.menu_save -> viewModel.save(getRssSource()) {
                setResult(RESULT_OK)
                finish()
            }

            R.id.menu_debug_source -> viewModel.save(getRssSource()) { source ->
                startActivity<RssSourceDebugActivity> {
                    putExtra("key", source.sourceUrl)
                }
            }

            R.id.menu_login -> viewModel.save(getRssSource()) {
                startActivity<SourceLoginActivity> {
                    putExtra("type", "rssSource")
                    putExtra("key", it.sourceUrl)
                }
            }

            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_clear_cookie -> viewModel.clearCookie(getRssSource().sourceUrl)
            R.id.menu_auto_complete -> viewModel.autoComplete = !viewModel.autoComplete
            R.id.menu_copy_source -> sendToClip(GSON.toJson(getRssSource()))
            R.id.menu_qr_code_camera -> qrCodeResult.launch()
            R.id.menu_paste_source -> viewModel.pasteSource { upSourceView(it) }
            R.id.menu_share_str -> share(GSON.toJson(getRssSource()))
            R.id.menu_share_qr -> shareWithQr(
                GSON.toJson(getRssSource()),
                getString(R.string.share_rss_source),
                ErrorCorrectionLevel.L
            )

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("rssRuleHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        val root = binding.root as ViewGroup
        while (root.childCount > 1) {
            root.removeViewAt(1)
        }
        root.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    RssSourceEditContent(
                        textValues = textValues,
                        boolValues = boolValues,
                        enabled = enabled,
                        onEnabledChange = { enabled = it },
                        singleUrl = singleUrl,
                        onSingleUrlChange = { singleUrl = it },
                        enabledCookieJar = enabledCookieJar,
                        onEnabledCookieJarChange = { enabledCookieJar = it },
                        preload = preload,
                        onPreloadChange = { preload = it },
                        sourceType = sourceType,
                        onSourceTypeChange = { sourceType = it },
                        articleStyle = articleStyle,
                        onArticleStyleChange = { articleStyle = it },
                        selectedTab = selectedTab,
                        onSelectedTabChange = {
                            selectedTab = it
                            focusedKey = null
                        },
                        onTextChange = ::updateTextValue,
                        onBooleanChange = { key, value -> boolValues[key] = value },
                        onFieldFocused = { focusedKey = it }
                    )
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    private fun upSourceView(rssSource: RssSource?) {
        val rs = rssSource ?: RssSource()
        clearEditHistory()
        textValues.clear()
        boolValues.clear()
        enabled = rs.enabled
        singleUrl = rs.singleUrl
        enabledCookieJar = rs.enabledCookieJar == true
        preload = rs.preload
        sourceType = rs.type.takeIf { it in resources.getStringArray(R.array.rss_type).indices } ?: 0
        articleStyle = rs.articleStyle.takeIf { it in resources.getStringArray(R.array.layout_type).indices } ?: 0

        putText("sourceName", rs.sourceName)
        putText("sourceUrl", rs.sourceUrl)
        putText("sourceIcon", rs.sourceIcon)
        putText("sourceGroup", rs.sourceGroup)
        putText("sourceComment", rs.sourceComment)
        putText("searchUrl", rs.searchUrl)
        putText("sortUrl", rs.sortUrl)
        putText("loginUrl", rs.loginUrl)
        putText("loginUi", rs.loginUi)
        putText("loginCheckJs", rs.loginCheckJs)
        putText("coverDecodeJs", rs.coverDecodeJs)
        putText("header", rs.header)
        putText("variableComment", rs.variableComment)
        putText("concurrentRate", rs.concurrentRate)
        putText("jsLib", rs.jsLib)

        putText("startHtml", rs.startHtml)
        putText("startStyle", rs.startStyle)
        putText("startJs", rs.startJs)
        putText("preloadJs", rs.preloadJs)

        putText("ruleArticles", rs.ruleArticles)
        putText("ruleNextPage", rs.ruleNextPage)
        putText("ruleTitle", rs.ruleTitle)
        putText("rulePubDate", rs.rulePubDate)
        putText("ruleDescription", rs.ruleDescription)
        putText("ruleImage", rs.ruleImage)
        putText("ruleLink", rs.ruleLink)

        boolValues["enableJs"] = rs.enableJs
        boolValues["loadWithBaseUrl"] = rs.loadWithBaseUrl
        boolValues["showWebLog"] = rs.showWebLog
        boolValues["cacheFirst"] = rs.cacheFirst
        putText("ruleContent", rs.ruleContent)
        putText("style", rs.style)
        putText("injectJs", rs.injectJs)
        putText("contentWhitelist", rs.contentWhitelist)
        putText("contentBlacklist", rs.contentBlacklist)
        putText("shouldOverrideUrlLoading", rs.shouldOverrideUrlLoading)

        selectedTab = 0
        focusedKey = null
    }

    private fun getRssSource(): RssSource {
        val source = viewModel.rssSource?.copy() ?: RssSource()
        source.enabled = enabled
        source.singleUrl = singleUrl
        source.enabledCookieJar = enabledCookieJar
        source.preload = preload
        source.type = sourceType
        source.articleStyle = articleStyle

        source.sourceName = textOrEmpty("sourceName")
        source.sourceUrl = textOrEmpty("sourceUrl")
        source.sourceIcon = textOrEmpty("sourceIcon")
        source.sourceGroup = textOrNull("sourceGroup")
        source.sourceComment = textOrNull("sourceComment")
        source.loginUrl = textOrNull("loginUrl")
        source.loginUi = textOrNull("loginUi")
        source.loginCheckJs = textOrNull("loginCheckJs")
        source.coverDecodeJs = textOrNull("coverDecodeJs")
        source.header = textOrNull("header")
        source.variableComment = textOrNull("variableComment")
        source.concurrentRate = textOrNull("concurrentRate")
        source.searchUrl = textOrNull("searchUrl")
        source.sortUrl = textOrNull("sortUrl")
        source.jsLib = textOrNull("jsLib")

        source.startHtml = textOrNull("startHtml")
        source.startStyle = textOrNull("startStyle")
        source.startJs = textOrNull("startJs")
        source.preloadJs = textOrNull("preloadJs")

        source.ruleArticles = textOrNull("ruleArticles")
        source.ruleNextPage = viewModel.ruleComplete(textOrNull("ruleNextPage"), source.ruleArticles, 2)
        source.ruleTitle = viewModel.ruleComplete(textOrNull("ruleTitle"), source.ruleArticles)
        source.rulePubDate = viewModel.ruleComplete(textOrNull("rulePubDate"), source.ruleArticles)
        source.ruleDescription = viewModel.ruleComplete(textOrNull("ruleDescription"), source.ruleArticles)
        source.ruleImage = viewModel.ruleComplete(textOrNull("ruleImage"), source.ruleArticles, 3)
        source.ruleLink = viewModel.ruleComplete(textOrNull("ruleLink"), source.ruleArticles)

        source.enableJs = boolValues["enableJs"] == true
        source.loadWithBaseUrl = boolValues["loadWithBaseUrl"] == true
        source.showWebLog = boolValues["showWebLog"] == true
        source.cacheFirst = boolValues["cacheFirst"] == true
        source.ruleContent = viewModel.ruleComplete(textOrNull("ruleContent"), source.ruleArticles)
        source.style = textOrNull("style")
        source.injectJs = textOrNull("injectJs")
        source.contentWhitelist = textOrNull("contentWhitelist")
        source.contentBlacklist = textOrNull("contentBlacklist")
        source.shouldOverrideUrlLoading = textOrNull("shouldOverrideUrlLoading")
        return source
    }

    private fun setSourceVariable() {
        viewModel.save(getRssSource()) { source ->
            lifecycleScope.launch {
                val comment = source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
                val variable = withContext(Dispatchers.IO) { source.getVariable() }
                showDialogFragment(
                    VariableDialog(
                        getString(R.string.set_source_variable),
                        source.getKey(),
                        variable,
                        comment
                    )
                )
            }
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.rssSource?.setVariable(variable)
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("插入URL参数", "urlOption"),
            SelectItem("订阅源教程", "ruleHelp"),
            SelectItem("js教程", "jsHelp"),
            SelectItem("正则教程", "regexHelp"),
            SelectItem("选择文件", "selectFile"),
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "urlOption" -> UrlOptionDialog(this) {
                sendText(it)
            }.show()

            "ruleHelp" -> showHelp("rssRuleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
            "selectFile" -> selectDoc.launch {
                mode = HandleFileContract.FILE
            }
        }
    }

    override fun sendText(text: String) {
        val key = focusedKey ?: return
        if (text.isEmpty()) return
        val value = getTextValue(key)
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val newText = buildString {
            append(value.text.substring(0, start))
            append(text)
            append(value.text.substring(end))
        }
        updateTextValue(key, TextFieldValue(newText, TextRange(start + text.length)))
    }

    override fun onUndoClicked() {
        focusedKey?.let(::undoField)
    }

    override fun onRedoClicked() {
        focusedKey?.let(::redoField)
    }

    private fun putText(key: String, value: String?) {
        textValues[key] = TextFieldValue(value.orEmpty())
    }

    private fun getTextValue(key: String): TextFieldValue {
        return textValues[key] ?: TextFieldValue("")
    }

    private fun updateTextValue(key: String, value: TextFieldValue) {
        val oldValue = getTextValue(key)
        if (!updatingHistory && oldValue != value) {
            val undoStack = undoStacks.getOrPut(key) { ArrayDeque() }
            undoStack.addLast(oldValue)
            while (undoStack.size > MAX_FIELD_HISTORY) {
                undoStack.removeFirst()
            }
            redoStacks[key]?.clear()
        }
        textValues[key] = value
    }

    private fun undoField(key: String) {
        val undoStack = undoStacks[key]
        if (undoStack.isNullOrEmpty()) return
        val current = getTextValue(key)
        val previous = undoStack.removeLast()
        redoStacks.getOrPut(key) { ArrayDeque() }.addLast(current)
        updatingHistory = true
        textValues[key] = previous
        updatingHistory = false
    }

    private fun redoField(key: String) {
        val redoStack = redoStacks[key]
        if (redoStack.isNullOrEmpty()) return
        val current = getTextValue(key)
        val next = redoStack.removeLast()
        undoStacks.getOrPut(key) { ArrayDeque() }.addLast(current)
        updatingHistory = true
        textValues[key] = next
        updatingHistory = false
    }

    private fun clearEditHistory() {
        undoStacks.clear()
        redoStacks.clear()
    }

    private fun textOrEmpty(key: String): String {
        return textValues[key]?.text.orEmpty()
    }

    private fun textOrNull(key: String): String? {
        return textValues[key]?.text?.takeIf { it.isNotBlank() }
    }

    private fun fieldTitle(key: String): String {
        return when (key) {
            "sourceName" -> getString(R.string.source_name)
            "sourceUrl" -> getString(R.string.source_url)
            "sourceIcon" -> getString(R.string.source_icon)
            "sourceGroup" -> getString(R.string.source_group)
            "sourceComment" -> getString(R.string.comment)
            "searchUrl" -> getString(R.string.r_search_url)
            "sortUrl" -> getString(R.string.sort_url)
            "loginUrl" -> getString(R.string.login_url)
            "loginUi" -> getString(R.string.login_ui)
            "loginCheckJs" -> getString(R.string.login_check_js)
            "coverDecodeJs" -> getString(R.string.cover_decode_js)
            "header" -> getString(R.string.source_http_header)
            "variableComment" -> getString(R.string.variable_comment)
            "concurrentRate" -> getString(R.string.concurrent_rate)
            "startHtml" -> getString(R.string.r_startHtml)
            "startStyle" -> getString(R.string.r_startStyle)
            "startJs" -> getString(R.string.r_startJs)
            "preloadJs" -> getString(R.string.r_preloadJs)
            "ruleArticles" -> getString(R.string.r_articles)
            "ruleNextPage" -> getString(R.string.r_next)
            "ruleTitle" -> getString(R.string.r_title)
            "rulePubDate" -> getString(R.string.r_date)
            "ruleDescription" -> getString(R.string.r_description)
            "ruleImage" -> getString(R.string.r_image)
            "ruleLink" -> getString(R.string.r_link)
            "ruleContent" -> getString(R.string.r_content)
            "style" -> getString(R.string.r_style)
            "injectJs" -> getString(R.string.r_inject_js)
            "contentWhitelist" -> getString(R.string.c_whitelist)
            "contentBlacklist" -> getString(R.string.c_blacklist)
            "shouldOverrideUrlLoading" -> URL_OVERRIDE_LABEL
            else -> key
        }
    }

    companion object {
        const val MAX_FIELD_HISTORY = 120
        const val URL_OVERRIDE_LABEL =
            "url跳转拦截(js, 返回true拦截,js变量url,可以通过js打开url,比如调用阅读搜索,添加书架等,简化规则写法,不用webView js注入)"
    }
}

private data class RssFieldSpec(
    val key: String,
    val label: String,
    val switchField: Boolean = false,
    val minLines: Int = 1,
    val maxLines: Int = AppConfig.sourceEditMaxLine.coerceAtLeast(1)
)

@Composable
private fun RssSourceEditContent(
    textValues: Map<String, TextFieldValue>,
    boolValues: Map<String, Boolean>,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    singleUrl: Boolean,
    onSingleUrlChange: (Boolean) -> Unit,
    enabledCookieJar: Boolean,
    onEnabledCookieJarChange: (Boolean) -> Unit,
    preload: Boolean,
    onPreloadChange: (Boolean) -> Unit,
    sourceType: Int,
    onSourceTypeChange: (Int) -> Unit,
    articleStyle: Int,
    onArticleStyleChange: (Int) -> Unit,
    selectedTab: Int,
    onSelectedTabChange: (Int) -> Unit,
    onTextChange: (String, TextFieldValue) -> Unit,
    onBooleanChange: (String, Boolean) -> Unit,
    onFieldFocused: (String) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val typeOptions = stringArrayResource(R.array.rss_type).toList()
    val layoutOptions = stringArrayResource(R.array.layout_type).toList()
    val safeType = sourceType.coerceIn(typeOptions.indices)
    val safeArticleStyle = articleStyle.coerceIn(layoutOptions.indices)
    val tabs = listOf(
        stringResource(R.string.source_tab_base),
        stringResource(R.string.source_tab_start),
        stringResource(R.string.source_tab_list),
        "WEB_VIEW"
    )
    val fieldSpecs = rssFieldSpecs(selectedTab)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppRuleSwitchRow(
                    text = stringResource(R.string.is_enable),
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.weight(1f),
                    style = style
                )
                AppRuleSwitchRow(
                    text = stringResource(R.string.single_url),
                    checked = singleUrl,
                    onCheckedChange = onSingleUrlChange,
                    modifier = Modifier.weight(1f),
                    style = style
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppRuleSwitchRow(
                    text = stringResource(R.string.auto_save_cookie),
                    checked = enabledCookieJar,
                    onCheckedChange = onEnabledCookieJarChange,
                    modifier = Modifier.weight(1f),
                    style = style
                )
                AppRuleSwitchRow(
                    text = stringResource(R.string.enable_preload),
                    checked = preload,
                    onCheckedChange = onPreloadChange,
                    modifier = Modifier.weight(1f),
                    style = style
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegadoMiuixSelectField(
                    label = stringResource(R.string.book_type),
                    options = typeOptions.indices.toList(),
                    selected = safeType,
                    optionLabel = { typeOptions[it] },
                    onSelected = onSourceTypeChange,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    cornerRadius = style.actionRadius,
                    popupWidth = 320.dp
                )
                LegadoMiuixSelectField(
                    label = stringResource(R.string.layout_type),
                    options = layoutOptions.indices.toList(),
                    selected = safeArticleStyle,
                    optionLabel = { layoutOptions[it] },
                    onSelected = onArticleStyleChange,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    cornerRadius = style.actionRadius,
                    popupWidth = 320.dp
                )
            }
            AppRuleTabRow(
                tabs = tabs,
                selectedIndex = selectedTab.coerceIn(tabs.indices),
                onSelected = onSelectedTabChange,
                style = style
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(fieldSpecs, key = { it.key }) { spec ->
                if (spec.switchField) {
                    AppRuleSwitchRow(
                        text = spec.label,
                        checked = boolValues[spec.key] == true,
                        onCheckedChange = { onBooleanChange(spec.key, it) },
                        style = style
                    )
                } else {
                    AppRuleTextField(
                        value = textValues[spec.key] ?: TextFieldValue(""),
                        onValueChange = { onTextChange(spec.key, it) },
                        label = spec.label,
                        minLines = spec.minLines,
                        maxLines = spec.maxLines,
                        style = style,
                        onFocused = { onFieldFocused(spec.key) }
                    )
                }
            }
            item {
                AppRuleFieldSpacer()
            }
        }
    }
}

@Composable
private fun rssFieldSpecs(tab: Int): List<RssFieldSpec> {
    return when (tab) {
        1 -> listOf(
            RssFieldSpec("startHtml", stringResource(R.string.r_startHtml), minLines = 3),
            RssFieldSpec("startStyle", stringResource(R.string.r_startStyle), minLines = 3),
            RssFieldSpec("startJs", stringResource(R.string.r_startJs), minLines = 3),
            RssFieldSpec("preloadJs", stringResource(R.string.r_preloadJs), minLines = 3)
        )

        2 -> listOf(
            RssFieldSpec("ruleArticles", stringResource(R.string.r_articles)),
            RssFieldSpec("ruleNextPage", stringResource(R.string.r_next)),
            RssFieldSpec("ruleTitle", stringResource(R.string.r_title)),
            RssFieldSpec("rulePubDate", stringResource(R.string.r_date)),
            RssFieldSpec("ruleDescription", stringResource(R.string.r_description)),
            RssFieldSpec("ruleImage", stringResource(R.string.r_image)),
            RssFieldSpec("ruleLink", stringResource(R.string.r_link))
        )

        3 -> listOf(
            RssFieldSpec("enableJs", stringResource(R.string.enable_js), switchField = true),
            RssFieldSpec("loadWithBaseUrl", stringResource(R.string.load_with_base_url), switchField = true),
            RssFieldSpec("showWebLog", stringResource(R.string.load_with_web_log), switchField = true),
            RssFieldSpec("cacheFirst", stringResource(R.string.cache_first), switchField = true),
            RssFieldSpec("ruleContent", stringResource(R.string.r_content)),
            RssFieldSpec("style", stringResource(R.string.r_style), minLines = 3),
            RssFieldSpec("injectJs", stringResource(R.string.r_inject_js), minLines = 3),
            RssFieldSpec("contentWhitelist", stringResource(R.string.c_whitelist)),
            RssFieldSpec("contentBlacklist", stringResource(R.string.c_blacklist)),
            RssFieldSpec("shouldOverrideUrlLoading", RssSourceEditActivity.URL_OVERRIDE_LABEL, minLines = 3)
        )

        else -> listOf(
            RssFieldSpec("sourceName", stringResource(R.string.source_name)),
            RssFieldSpec("sourceUrl", stringResource(R.string.source_url)),
            RssFieldSpec("sourceIcon", stringResource(R.string.source_icon)),
            RssFieldSpec("sourceGroup", stringResource(R.string.source_group)),
            RssFieldSpec("sourceComment", stringResource(R.string.comment), minLines = 2),
            RssFieldSpec("searchUrl", stringResource(R.string.r_search_url)),
            RssFieldSpec("sortUrl", stringResource(R.string.sort_url)),
            RssFieldSpec("loginUrl", stringResource(R.string.login_url)),
            RssFieldSpec("loginUi", stringResource(R.string.login_ui), minLines = 3),
            RssFieldSpec("loginCheckJs", stringResource(R.string.login_check_js), minLines = 3),
            RssFieldSpec("coverDecodeJs", stringResource(R.string.cover_decode_js), minLines = 3),
            RssFieldSpec("header", stringResource(R.string.source_http_header), minLines = 3),
            RssFieldSpec("variableComment", stringResource(R.string.variable_comment), minLines = 2),
            RssFieldSpec("concurrentRate", stringResource(R.string.concurrent_rate)),
            RssFieldSpec("jsLib", "jsLib")
        )
    }
}
