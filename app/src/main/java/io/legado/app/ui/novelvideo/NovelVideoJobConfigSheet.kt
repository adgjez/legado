package io.legado.app.ui.novelvideo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.ai.NovelVideoParams
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.res.stringResource
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * 新建任务的配置 ModalBottomSheet。
 *
 * - 若 [presetBookUrl] 非空，书籍选择器被禁用并预填该 book
 * - 提交时调用 [viewModel].[createJob]，成功后调 [onCreated] 关闭 sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelVideoJobConfigSheet(
    viewModel: NovelVideoTaskCenterViewModel,
    presetBookUrl: String?,
    presetBookName: String?,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val allBooks by viewModel.allBooks.collectAsStateWithLifecycle()
    val imageProviders = remember { AppConfig.aiImageProviderList.filter { it.enabled } }
    val videoProviders = remember { AppConfig.aiVideoProviderList.filter { it.enabled } }
    val llmModels = remember { AppConfig.aiModelConfigList }

    // 表单状态
    var selectedBook by remember {
        mutableStateOf<Book?>(allBooks.firstOrNull { it.bookUrl == presetBookUrl }
            ?: allBooks.firstOrNull())
    }
    var chapters by remember { mutableStateOf<List<BookChapter>>(emptyList()) }
    var chapterStart by remember { mutableStateOf(0) }
    var chapterEnd by remember { mutableStateOf(0) }
    var params by remember { mutableStateOf(NovelVideoParams()) }
    var submitting by remember { mutableStateOf(false) }

    // 拉取章节
    LaunchedEffect(selectedBook?.bookUrl) {
        val book = selectedBook
        if (book == null) {
            chapters = emptyList()
        } else {
            val list = viewModel.loadChapters(book.bookUrl)
            chapters = list
            if (list.isNotEmpty()) {
                chapterStart = chapterStart.coerceIn(0, list.lastIndex)
                chapterEnd = chapterEnd.coerceIn(chapterStart, list.lastIndex)
                if (chapterEnd == 0) chapterEnd = list.lastIndex
            }
        }
    }

    // 第一次拉到 books 后回填 preset
    LaunchedEffect(allBooks) {
        if (selectedBook == null && allBooks.isNotEmpty()) {
            selectedBook = allBooks.firstOrNull { it.bookUrl == presetBookUrl } ?: allBooks.first()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.novel_video_new_task),
                style = MaterialTheme.typography.titleMedium
            )

            // 书籍选择
            BookSelector(
                allBooks = allBooks,
                selected = selectedBook,
                enabled = presetBookUrl.isNullOrEmpty(),
                onSelect = { selectedBook = it }
            )

            // 章节范围
            ChapterRangeSelector(
                chapters = chapters,
                startIndex = chapterStart,
                endIndex = chapterEnd,
                onStartSelected = { chapterStart = it.coerceIn(0, chapterEnd) },
                onEndSelected = { chapterEnd = it.coerceIn(chapterStart, chapters.lastIndex.coerceAtLeast(0)) }
            )

            // 数值类参数
            IntSliderRow(
                label = stringResource(R.string.novel_video_config_scene_count),
                value = params.sceneCountPerChapter,
                range = 3..12,
                onValueChange = { params = params.copy(sceneCountPerChapter = it) }
            )
            IntSliderRow(
                label = stringResource(R.string.novel_video_config_duration),
                value = params.sceneDurationSeconds,
                range = 3..10,
                onValueChange = { params = params.copy(sceneDurationSeconds = it) }
            )
            IntSliderRow(
                label = stringResource(R.string.novel_video_config_max_characters),
                value = params.maxCharacters,
                range = 1..3,
                onValueChange = { params = params.copy(maxCharacters = it) }
            )
            IntSliderRow(
                label = stringResource(R.string.novel_video_config_concurrency),
                value = params.concurrency,
                range = 1..4,
                onValueChange = { params = params.copy(concurrency = it) }
            )

            // 文本参数
            OutlinedTextField(
                value = params.resolution,
                onValueChange = { params = params.copy(resolution = it) },
                label = { Text(stringResource(R.string.novel_video_config_resolution)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = params.stylePrompt,
                onValueChange = { params = params.copy(stylePrompt = it) },
                label = { Text(stringResource(R.string.novel_video_config_style)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            )

            // Provider 下拉
            ProviderDropdown(
                label = stringResource(R.string.novel_video_config_image_provider),
                items = imageProviders,
                itemId = { it.id },
                itemLabel = { it.name },
                selectedId = params.imageProviderId,
                onSelect = { params = params.copy(imageProviderId = it) }
            )
            ProviderDropdown(
                label = stringResource(R.string.novel_video_config_video_provider),
                items = videoProviders,
                itemId = { it.id },
                itemLabel = { it.name },
                selectedId = params.videoProviderId,
                onSelect = { params = params.copy(videoProviderId = it) }
            )
            ProviderDropdown(
                label = stringResource(R.string.novel_video_config_llm_model),
                items = llmModels,
                itemId = { it.id },
                itemLabel = { it.modelId },
                selectedId = params.llmModelId,
                onSelect = { params = params.copy(llmModelId = it) }
            )

            // 开关
            SwitchRow(
                label = stringResource(R.string.novel_video_config_enable_review),
                checked = params.enableReview,
                onCheckedChange = { params = params.copy(enableReview = it) }
            )
            SwitchRow(
                label = stringResource(R.string.novel_video_config_attach_chapter),
                checked = params.attachToBookChapter,
                onCheckedChange = { params = params.copy(attachToBookChapter = it) }
            )
            SwitchRow(
                label = stringResource(R.string.novel_video_config_save_gallery),
                checked = params.saveToGallery,
                onCheckedChange = { params = params.copy(saveToGallery = it) }
            )

            Spacer(modifier = Modifier.size(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        val book = selectedBook
                        if (book == null) {
                            return@Button
                        }
                        if (chapters.isEmpty()) {
                            return@Button
                        }
                        submitting = true
                        scope.launch {
                            val newId = viewModel.createJob(
                                book = book,
                                chapterStartIndex = chapterStart,
                                chapterEndIndex = chapterEnd,
                                chapters = chapters,
                                params = params
                            )
                            submitting = false
                            onCreated(newId)
                        }
                    },
                    enabled = !submitting && selectedBook != null && chapters.isNotEmpty()
                ) {
                    Text(stringResource(R.string.novel_video_config_submit))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSelector(
    allBooks: List<Book>,
    selected: Book?,
    enabled: Boolean,
    onSelect: (Book) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.name ?: stringResource(R.string.novel_video_no_book_selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.novel_video_select_book)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            allBooks.forEach { book ->
                DropdownMenuItem(
                    text = { Text(book.name) },
                    onClick = {
                        onSelect(book)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterRangeSelector(
    chapters: List<BookChapter>,
    startIndex: Int,
    endIndex: Int,
    onStartSelected: (Int) -> Unit,
    onEndSelected: (Int) -> Unit
) {
    var startExpanded by remember { mutableStateOf(false) }
    var endExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = startExpanded,
            onExpandedChange = { startExpanded = it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = chapters.getOrNull(startIndex)?.title ?: "-",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.novel_video_config_chapter_start)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = startExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(expanded = startExpanded, onDismissRequest = { startExpanded = false }) {
                chapters.forEachIndexed { idx, ch ->
                    DropdownMenuItem(
                        text = { Text("${idx + 1}. ${ch.title}") },
                        onClick = {
                            onStartSelected(idx)
                            startExpanded = false
                        }
                    )
                }
            }
        }
        ExposedDropdownMenuBox(
            expanded = endExpanded,
            onExpandedChange = { endExpanded = it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = chapters.getOrNull(endIndex)?.title ?: "-",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.novel_video_config_chapter_end)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = endExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(expanded = endExpanded, onDismissRequest = { endExpanded = false }) {
                chapters.forEachIndexed { idx, ch ->
                    if (idx >= startIndex) {
                        DropdownMenuItem(
                            text = { Text("${idx + 1}. ${ch.title}") },
                            onClick = {
                                onEndSelected(idx)
                                endExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ProviderDropdown(
    label: String,
    items: List<T>,
    itemId: (T) -> String,
    itemLabel: (T) -> String,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = items.firstOrNull { itemId(it) == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let(itemLabel) ?: stringResource(R.string.novel_video_config_use_default),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.novel_video_config_use_default)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        onSelect(itemId(item))
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun IntSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$value",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0)
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
