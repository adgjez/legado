package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.ACache
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

/**
 * TTS 引擎管理。
 */
class SpeakEngineDialog : BaseDialogFragment(0), SpeakEngineDialogActions {

    private val viewModel: SpeakEngineViewModel by viewModels()
    private val ttsUrlKey = "ttsUrlKey"
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private var ttsEngine by mutableStateOf(ReadAloud.ttsEngine)
    private var httpTtsList by mutableStateOf<List<HttpTTS>>(emptyList())
    private var selectedGroupKey by mutableStateOf<String?>(null)

    private val importDocResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> showDialogFragment(ImportHttpTtsDialog(uri.toString())) }
    }

    private val exportDirResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton { requireContext().sendToClip(uri.toString()) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SpeakEngineScreen(
                    ttsEngine = ttsEngine,
                    httpTtsList = httpTtsList,
                    selectedGroupKey = selectedGroupKey,
                    actions = this@SpeakEngineDialog
                )
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            appDb.httpTTSDao.flowAll()
                .catch { AppLog.put("朗读引擎界面获取数据失败\n${it.localizedMessage}", it) }
                .flowOn(IO)
                .conflate()
                .collect {
                    httpTtsList = it
                    if (selectedGroupKey == null) {
                        selectedGroupKey = currentGroupKey(it)
                    }
                }
        }
    }

    override fun selectGroup(group: SpeechVoiceEngineGroup) {
        selectedGroupKey = group.key
        ttsEngine = group.engineValue
        val httpTts = group.loginKey.toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
        if (httpTts != null && !httpTts.loginUrl.isNullOrBlank() && httpTts.getLoginInfo().isNullOrBlank()) {
            login(group)
        }
    }

    override fun setForBook() {
        ReadBook.book?.setTtsEngine(ttsEngine)
        callBack?.upSpeakEngineSummary()
        ReadAloud.upReadAloudClass()
        dismissAllowingStateLoss()
    }

    override fun setForGlobal() {
        ReadBook.book?.setTtsEngine(null)
        AppConfig.ttsEngine = ttsEngine
        callBack?.upSpeakEngineSummary()
        ReadAloud.upReadAloudClass()
        dismissAllowingStateLoss()
    }

    override fun addHttpTts() {
        showDialogFragment<HttpTtsEditDialog>()
    }

    override fun editHttpTts(id: Long) {
        showDialogFragment(HttpTtsEditDialog(id))
    }

    override fun deleteHttpTts(httpTTS: HttpTTS) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + httpTTS.name)
            noButton()
            yesButton { appDb.httpTTSDao.delete(httpTTS) }
        }
    }

    override fun login(group: SpeechVoiceEngineGroup) {
        group.loginKey.takeIf { it.isNotBlank() }?.let { key ->
            startActivity<SourceLoginActivity> {
                putExtra("type", "httpTts")
                putExtra("key", key)
            }
        }
    }

    override fun importDefault() {
        viewModel.importDefault()
    }

    override fun importLocal() {
        importDocResult.launch {
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("txt", "json")
        }
    }

    override fun importOnline() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls = aCache.getAsString(ttsUrlKey)
            ?.splitNotBlank(",")
            ?.toMutableList()
            ?: mutableListOf()
        alert(R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { url ->
                    if (url.isAbsUrl() && !cacheUrls.contains(url)) {
                        cacheUrls.add(0, url)
                        aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportHttpTtsDialog(url))
                }
            }
        }
    }

    override fun exportAll() {
        exportDirResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "httpTts.json",
                GSON.toJson(httpTtsList).toByteArray(),
                "application/json"
            )
        }
    }

    override fun exportSelected() {
        val id = ttsEngine?.toLongOrNull()
        val tts = id?.let { appDb.httpTTSDao.get(it) }
        if (tts == null) {
            toastOnUi(R.string.is_system_tts_no_export)
            return
        }
        exportDirResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "httpTts_${tts.name}.json",
                GSON.toJson(tts).toByteArray(),
                "application/json"
            )
        }
    }

    override fun clearCache() {
        execute {
            ReadAloud.upReadAloudClass()
            val ttsFolderPath = "${requireContext().cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            toastOnUi(R.string.clear_cache_success)
        }
    }

    override fun close() {
        dismissAllowingStateLoss()
    }

    private fun currentGroupKey(httpTtsList: List<HttpTTS>): String {
        val current = ttsEngine ?: return "system:"
        if (current.isJsonObject()) {
            val value = GSON.fromJsonObject<SelectItem<String>>(current).getOrNull()?.value.orEmpty()
            return "system:$value"
        }
        return "http:$current".takeIf {
            httpTtsList.any { item -> item.id.toString() == current }
        } ?: "system:"
    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }
}

private interface SpeakEngineDialogActions {
    fun selectGroup(group: SpeechVoiceEngineGroup)
    fun setForBook()
    fun setForGlobal()
    fun addHttpTts()
    fun editHttpTts(id: Long)
    fun deleteHttpTts(httpTTS: HttpTTS)
    fun login(group: SpeechVoiceEngineGroup)
    fun importDefault()
    fun importLocal()
    fun importOnline()
    fun exportAll()
    fun exportSelected()
    fun clearCache()
    fun close()
}

@Composable
private fun SpeakEngineScreen(
    ttsEngine: String?,
    httpTtsList: List<HttpTTS>,
    selectedGroupKey: String?,
    actions: SpeakEngineDialogActions
) {
    val context = LocalContext.current
    val colors = rememberSpeakEngineColors()
    val groups = rememberSpeechGroups(httpTtsList)
    val selectedKey = selectedGroupKey ?: selectedKeyFromEngine(ttsEngine, httpTtsList)
    val selectedGroup = groups.firstOrNull { it.key == selectedKey } ?: groups.firstOrNull()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.page,
        shape = RoundedCornerShape(context.composeActionRadius().coerceAtLeast(18.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "朗读引擎",
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = actions::clearCache) { Text("清缓存", color = colors.accent) }
                TextButton(onClick = actions::close) { Text("关闭", color = colors.subText) }
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallEngineAction("新增", actions::addHttpTts, colors)
                SmallEngineAction("默认规则", actions::importDefault, colors)
                SmallEngineAction("本地导入", actions::importLocal, colors)
                SmallEngineAction("在线导入", actions::importOnline, colors)
                SmallEngineAction("导出全部", actions::exportAll, colors)
                SmallEngineAction("导出当前", actions::exportSelected, colors)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(0.42f).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groups, key = { it.key }) { group ->
                        EngineGroupRow(
                            group = group,
                            selected = group.key == selectedGroup?.key,
                            colors = colors,
                            onClick = { actions.selectGroup(group) },
                            onEdit = group.loginKey.toLongOrNull()?.let { { actions.editHttpTts(it) } },
                            onDelete = httpTtsList.firstOrNull { it.id.toString() == group.loginKey }
                                ?.let { httpTts -> { actions.deleteHttpTts(httpTts) } }
                        )
                    }
                }
                Surface(
                    modifier = Modifier.weight(0.58f).fillMaxSize(),
                    color = colors.card,
                    shape = RoundedCornerShape(context.composeActionRadius().coerceAtLeast(16.dp)),
                    border = BorderStroke(1.dp, colors.stroke)
                ) {
                    EngineDetail(group = selectedGroup, colors = colors, actions = actions)
                }
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BottomEngineAction("设为本书", colors, modifier = Modifier.weight(1f), onClick = actions::setForBook)
                BottomEngineAction("设为通用", colors, modifier = Modifier.weight(1f), onClick = actions::setForGlobal)
            }
        }
    }
}

@Composable
private fun rememberSpeechGroups(httpTtsList: List<HttpTTS>): List<SpeechVoiceEngineGroup> {
    val context = LocalContext.current
    return SpeechVoiceCatalogRepository.allGroups(context, httpTtsList)
}

private fun selectedKeyFromEngine(ttsEngine: String?, httpTtsList: List<HttpTTS>): String {
    val current = ttsEngine ?: return "system:"
    if (current.isJsonObject()) {
        val value = GSON.fromJsonObject<SelectItem<String>>(current).getOrNull()?.value.orEmpty()
        return "system:$value"
    }
    return "http:$current".takeIf { httpTtsList.any { item -> item.id.toString() == current } } ?: "system:"
}

@Composable
private fun EngineGroupRow(
    group: SpeechVoiceEngineGroup,
    selected: Boolean,
    colors: SpeakEngineColors,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) colors.accent.copy(alpha = 0.15f) else colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius().coerceAtLeast(14.dp)),
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.stroke)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(group.title, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(group.subtitle, color = colors.subText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onEdit != null || onDelete != null) {
                Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    onEdit?.let { TextButton(onClick = it) { Text("编辑", color = colors.accent, fontSize = 12.sp) } }
                    onDelete?.let { TextButton(onClick = it) { Text("删除", color = colors.danger, fontSize = 12.sp) } }
                }
            }
        }
    }
}

@Composable
private fun EngineDetail(
    group: SpeechVoiceEngineGroup?,
    colors: SpeakEngineColors,
    actions: SpeakEngineDialogActions
) {
    if (group == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无朗读引擎", color = colors.subText, fontSize = 14.sp)
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(group.title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(group.subtitle, color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (!group.loginUrl.isNullOrBlank()) {
                TextButton(onClick = { actions.login(group) }) {
                    Text("登录", color = colors.accent)
                }
            }
        }
        Text("发言人", color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(group.options, key = { it.key }) { option ->
                Surface(
                    color = colors.page,
                    shape = RoundedCornerShape(LocalContext.current.composeActionRadius().coerceAtLeast(12.dp)),
                    border = BorderStroke(1.dp, colors.stroke)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(11.dp)) {
                        Text(option.speakerName, color = colors.text, fontSize = 14.sp)
                        Text(
                            option.groupName.ifBlank { option.toneID.ifBlank { option.engineName } },
                            color = colors.subText,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (group.emotions.isNotEmpty()) {
            Text("情绪", color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.emotions.forEach { emotion ->
                    Surface(
                        color = colors.page,
                        shape = RoundedCornerShape(LocalContext.current.composeActionRadius().coerceAtLeast(12.dp)),
                        border = BorderStroke(1.dp, colors.stroke)
                    ) {
                        Text(emotion.emotionName, color = colors.text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "选择此引擎后，普通朗读会使用该引擎；角色配音可在角色编辑页按发言人单独指定。",
            color = colors.subText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun SmallEngineAction(text: String, onClick: () -> Unit, colors: SpeakEngineColors) {
    Surface(
        modifier = Modifier.height(34.dp).clickable(onClick = onClick),
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius().coerceAtLeast(12.dp)),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, color = colors.text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BottomEngineAction(
    text: String,
    colors: SpeakEngineColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(44.dp).clickable(onClick = onClick),
        color = colors.accent,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius().coerceAtLeast(14.dp))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class SpeakEngineColors(
    val page: Color,
    val card: Color,
    val text: Color,
    val subText: Color,
    val stroke: Color,
    val accent: Color,
    val danger: Color
)

@Composable
private fun rememberSpeakEngineColors(): SpeakEngineColors {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    val page = Color(if (night) 0xff15171b.toInt() else 0xffffffff.toInt())
    val card = Color(if (night) 0xff20242a.toInt() else 0xfff6f7fa.toInt())
    val text = Color(if (night) 0xfff2f3f5.toInt() else 0xff202124.toInt())
    val subText = Color(if (night) 0xffaeb4bc.toInt() else 0xff6b7178.toInt())
    val stroke = Color(if (night) 0x26ffffff else 0x18000000)
    return SpeakEngineColors(
        page = page,
        card = card,
        text = text,
        subText = subText,
        stroke = stroke,
        accent = Color(accent),
        danger = Color(ColorUtils.blendColors(0xffff4444.toInt(), accent, 0.08f))
    )
}
