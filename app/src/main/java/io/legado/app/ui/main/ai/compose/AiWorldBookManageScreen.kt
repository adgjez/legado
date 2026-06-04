package io.legado.app.ui.main.ai.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiWorldBookBinding
import io.legado.app.ui.main.ai.AiWorldBookConfig
import io.legado.app.ui.main.ai.AiWorldBookEntry
import io.legado.app.utils.postEvent

@Immutable
private data class WorldBookTarget(
    val label: String,
    val type: String,
    val fixedKey: String = ""
)

private data class WorldBookEditState(
    val id: String?,
    val name: String,
    val description: String,
    val maxEntries: String,
    val enabled: Boolean
)

private data class WorldBookEntryEditState(
    val worldBookId: String,
    val id: String?,
    val title: String,
    val content: String,
    val keys: String,
    val secondaryKeys: String,
    val excludeKeys: String,
    val regexEnabled: Boolean,
    val enabled: Boolean,
    val constant: Boolean,
    val priority: String,
    val scanDepth: String,
    val maxMatches: String
)

@Composable
fun AiWorldBookManageRoute(
    initialTargetType: String,
    initialTargetKey: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val style = aiComposeStyle(context)
    var books by remember { mutableStateOf(AppConfig.aiWorldBookList) }
    var tab by rememberSaveable { mutableStateOf(if (initialTargetType.isBlank()) 0 else 1) }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedBookId by rememberSaveable { mutableStateOf("") }
    var editingBook by remember { mutableStateOf<WorldBookEditState?>(null) }
    var editingEntry by remember { mutableStateOf<WorldBookEntryEditState?>(null) }
    val targets = remember(initialTargetType, initialTargetKey) {
        buildWorldBookTargets(initialTargetType, initialTargetKey)
    }
    var selectedTargetType by rememberSaveable {
        mutableStateOf(targets.firstOrNull { it.type == initialTargetType }?.type ?: AiWorldBookBinding.TARGET_GLOBAL)
    }
    var selectedTargetKey by rememberSaveable { mutableStateOf(initialTargetKey) }

    fun persist(updated: List<AiWorldBookConfig>) {
        AppConfig.aiWorldBookList = updated
        books = AppConfig.aiWorldBookList
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
    }

    fun reload() {
        books = AppConfig.aiWorldBookList
    }

    val editEntryState = editingEntry
    val editBookState = editingBook
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.colors.pageBackground)
    ) {
        when {
            editEntryState != null -> WorldBookEntryEditor(
                state = editEntryState,
                style = style,
                onBack = { editingEntry = null },
                onSave = { state ->
                    val target = books.firstOrNull { it.id == state.worldBookId } ?: return@WorldBookEntryEditor
                    val updatedEntry = state.toEntry(target)
                    persist(books.map { book ->
                        if (book.id == target.id) {
                            book.copy(entries = book.entries.filterNot { it.id == updatedEntry.id } + updatedEntry)
                        } else {
                            book
                        }
                    })
                    editingEntry = null
                    expandedBookId = target.id
                }
            )
            editBookState != null -> WorldBookEditor(
                state = editBookState,
                style = style,
                onBack = { editingBook = null },
                onSave = { state ->
                    val old = state.id?.let { id -> books.firstOrNull { it.id == id } }
                    val updated = state.toBook(old, books.size)
                    persist(books.filterNot { it.id == updated.id } + updated)
                    editingBook = null
                    expandedBookId = updated.id
                }
            )
            else -> WorldBookMainScreen(
                books = books,
                query = query,
                tab = tab,
                expandedBookId = expandedBookId,
                targets = targets,
                selectedTargetType = selectedTargetType,
                selectedTargetKey = selectedTargetKey,
                style = style,
                onBack = onBack,
                onQueryChange = { query = it },
                onTabChange = { tab = it },
                onExpandedChange = { expandedBookId = it },
                onTargetChange = { type, key ->
                    selectedTargetType = type
                    selectedTargetKey = key
                },
                onRefresh = ::reload,
                onAddBook = {
                    editingBook = WorldBookEditState(
                        id = null,
                        name = "",
                        description = "",
                        maxEntries = "12",
                        enabled = true
                    )
                },
                onEditBook = { book -> editingBook = book.toEditState() },
                onCopyBook = { book ->
                    val copy = book.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "${book.name} 副本",
                        bindings = emptyList(),
                        entries = book.entries.map { it.copy(id = java.util.UUID.randomUUID().toString()) }
                    )
                    persist(books + copy)
                    expandedBookId = copy.id
                },
                onDeleteBook = { book -> persist(books.filterNot { it.id == book.id }) },
                onToggleBook = { book ->
                    persist(books.map { if (it.id == book.id) it.copy(enabled = !it.enabled) else it })
                },
                onAddEntry = { book ->
                    editingEntry = WorldBookEntryEditState(
                        worldBookId = book.id,
                        id = null,
                        title = "",
                        content = "",
                        keys = "",
                        secondaryKeys = "",
                        excludeKeys = "",
                        regexEnabled = false,
                        enabled = true,
                        constant = false,
                        priority = "50",
                        scanDepth = "8",
                        maxMatches = "1"
                    )
                },
                onEditEntry = { book, entry -> editingEntry = entry.toEditState(book.id) },
                onDeleteEntry = { book, entry ->
                    persist(books.map {
                        if (it.id == book.id) it.copy(entries = it.entries.filterNot { item -> item.id == entry.id })
                        else it
                    })
                },
                onToggleEntry = { book, entry ->
                    persist(books.map {
                        if (it.id == book.id) {
                            it.copy(entries = it.entries.map { item ->
                                if (item.id == entry.id) item.copy(enabled = !item.enabled) else item
                            })
                        } else {
                            it
                        }
                    })
                },
                onToggleBinding = { book, targetType, targetKey ->
                    val old = book.bindings.firstOrNull { it.targetType == targetType && it.targetKey == targetKey }
                    val updatedBook = if (old == null) {
                        book.copy(
                            bindings = book.bindings + AiWorldBookBinding(
                                targetType = targetType,
                                targetKey = targetKey,
                                order = book.bindings.size
                            )
                        )
                    } else {
                        book.copy(bindings = book.bindings.filterNot { it.id == old.id })
                    }
                    persist(books.map { if (it.id == book.id) updatedBook else it })
                }
            )
        }
    }
}

@Composable
private fun WorldBookMainScreen(
    books: List<AiWorldBookConfig>,
    query: String,
    tab: Int,
    expandedBookId: String,
    targets: List<WorldBookTarget>,
    selectedTargetType: String,
    selectedTargetKey: String,
    style: AiComposeStyle,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTabChange: (Int) -> Unit,
    onExpandedChange: (String) -> Unit,
    onTargetChange: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onAddBook: () -> Unit,
    onEditBook: (AiWorldBookConfig) -> Unit,
    onCopyBook: (AiWorldBookConfig) -> Unit,
    onDeleteBook: (AiWorldBookConfig) -> Unit,
    onToggleBook: (AiWorldBookConfig) -> Unit,
    onAddEntry: (AiWorldBookConfig) -> Unit,
    onEditEntry: (AiWorldBookConfig, AiWorldBookEntry) -> Unit,
    onDeleteEntry: (AiWorldBookConfig, AiWorldBookEntry) -> Unit,
    onToggleEntry: (AiWorldBookConfig, AiWorldBookEntry) -> Unit,
    onToggleBinding: (AiWorldBookConfig, String, String) -> Unit
) {
    val filtered = remember(books, query) {
        if (query.isBlank()) {
            books
        } else {
            books.filter { book ->
                book.name.contains(query, ignoreCase = true) ||
                        book.description.contains(query, ignoreCase = true) ||
                        book.entries.any {
                            it.title.contains(query, ignoreCase = true) ||
                                    it.content.contains(query, ignoreCase = true) ||
                                    it.keys.any { key -> key.contains(query, ignoreCase = true) }
                        }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp)
    ) {
        WorldBookTopBar(
            title = "世界书",
            subtitle = "${books.count { it.enabled }} 个启用 · ${books.sumOf { it.entries.size }} 条条目",
            style = style,
            onBack = onBack,
            onAdd = onAddBook,
            onRefresh = onRefresh
        )
        WorldBookTabs(tab, style, onTabChange)
        SearchField(query, style, onQueryChange)
        Spacer(modifier = Modifier.height(10.dp))
        if (tab == 0) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { it.id }) { book ->
                    WorldBookCard(
                        book = book,
                        expanded = expandedBookId == book.id,
                        style = style,
                        onExpand = {
                            onExpandedChange(if (expandedBookId == book.id) "" else book.id)
                        },
                        onEdit = { onEditBook(book) },
                        onCopy = { onCopyBook(book) },
                        onDelete = { onDeleteBook(book) },
                        onToggle = { onToggleBook(book) },
                        onAddEntry = { onAddEntry(book) },
                        onEditEntry = { onEditEntry(book, it) },
                        onDeleteEntry = { onDeleteEntry(book, it) },
                        onToggleEntry = { onToggleEntry(book, it) }
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        } else {
            EnableWorldBookPage(
                books = filtered,
                targets = targets,
                selectedTargetType = selectedTargetType,
                selectedTargetKey = selectedTargetKey,
                style = style,
                onTargetChange = onTargetChange,
                onToggleBinding = onToggleBinding
            )
        }
    }
}

@Composable
private fun WorldBookTopBar(
    title: String,
    subtitle: String,
    style: AiComposeStyle,
    onBack: () -> Unit,
    onAdd: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = "返回",
                tint = style.colors.primaryText
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = style.colors.primaryText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = style.colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        onRefresh?.let {
            IconButton(onClick = it) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh_black_24dp),
                    contentDescription = "刷新",
                    tint = style.colors.secondaryText
                )
            }
        }
        onAdd?.let {
            IconButton(onClick = it) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = "新增",
                    tint = style.colors.accent
                )
            }
        }
    }
}

@Composable
private fun WorldBookTabs(
    tab: Int,
    style: AiComposeStyle,
    onTabChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.chipRadius))
            .background(style.colors.processSurface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("世界书库", "启用").forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(style.metrics.chipRadius))
                    .background(if (tab == index) style.colors.cardSurface else Color.Transparent)
                    .clickable { onTabChange(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (tab == index) style.colors.accent else style.colors.secondaryText,
                    fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    style: AiComposeStyle,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("搜索世界书、条目、关键词") },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = style.colors.secondaryText
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    )
}

@Composable
private fun WorldBookCard(
    book: AiWorldBookConfig,
    expanded: Boolean,
    style: AiComposeStyle,
    onExpand: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onAddEntry: () -> Unit,
    onEditEntry: (AiWorldBookEntry) -> Unit,
    onDeleteEntry: (AiWorldBookEntry) -> Unit,
    onToggleEntry: (AiWorldBookEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.cardRadius))
            .background(style.colors.cardSurface)
            .border(style.metrics.strokeWidth, style.colors.stroke, RoundedCornerShape(style.metrics.cardRadius))
            .clickable { onExpand() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.name,
                    color = style.colors.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.description.ifBlank { "无描述" },
                    color = style.colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = book.enabled, onCheckedChange = { onToggle() })
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoChip(if (book.enabled) "已启用" else "已停用", style, selected = book.enabled)
            InfoChip("${book.entries.size} 条目", style)
            InfoChip("${book.bindings.size} 启用场景", style)
            InfoChip("最多 ${book.maxEntries} 条", style)
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallAction("编辑", style, onClick = onEdit)
            SmallAction("复制", style, onClick = onCopy)
            SmallAction("新增条目", style, onClick = onAddEntry)
            SmallAction("删除", style, danger = true, onClick = onDelete)
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(10.dp))
            if (book.entries.isEmpty()) {
                Text("暂无条目", color = style.colors.secondaryText, fontSize = 13.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    book.entries.forEach { entry ->
                        EntryRow(
                            entry = entry,
                            style = style,
                            onEdit = { onEditEntry(entry) },
                            onDelete = { onDeleteEntry(entry) },
                            onToggle = { onToggleEntry(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: AiWorldBookEntry,
    style: AiComposeStyle,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.chipRadius))
            .background(style.colors.assistantBubble)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    color = style.colors.primaryText,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.content.replace(Regex("\\s+"), " ").trim(),
                    color = style.colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = entry.enabled, onCheckedChange = { onToggle() })
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (entry.constant) InfoChip("常驻", style, selected = true)
            if (entry.regexEnabled) InfoChip("正则", style)
            InfoChip("P${entry.priority}", style)
            InfoChip("扫 ${entry.scanDepth}", style)
            entry.keys.take(4).forEach { InfoChip(it, style) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            SmallAction("编辑", style, onClick = onEdit)
            SmallAction("删除", style, danger = true, onClick = onDelete)
        }
    }
}

@Composable
private fun EnableWorldBookPage(
    books: List<AiWorldBookConfig>,
    targets: List<WorldBookTarget>,
    selectedTargetType: String,
    selectedTargetKey: String,
    style: AiComposeStyle,
    onTargetChange: (String, String) -> Unit,
    onToggleBinding: (AiWorldBookConfig, String, String) -> Unit
) {
    var customKey by rememberSaveable(selectedTargetType) { mutableStateOf(selectedTargetKey) }
    val target = targets.firstOrNull { it.type == selectedTargetType }
        ?: targets.first()
    val effectiveKey = if (target.fixedKey.isNotBlank()) target.fixedKey else customKey.trim()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            targets.forEach { item ->
                InfoChip(
                    text = item.label,
                    style = style,
                    selected = selectedTargetType == item.type && selectedTargetKey == item.fixedKey,
                    modifier = Modifier.clickable {
                        customKey = item.fixedKey
                        onTargetChange(item.type, item.fixedKey)
                    }
                )
            }
        }
        if (requiresTargetKey(selectedTargetType) && target.fixedKey.isBlank()) {
            OutlinedTextField(
                value = customKey,
                onValueChange = {
                    customKey = it
                    onTargetChange(selectedTargetType, it.trim())
                },
                singleLine = true,
                label = { Text(if (selectedTargetType == AiWorldBookBinding.TARGET_BOOK) "bookKey" else "sessionId") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(books, key = { it.id }) { book ->
                val active = book.bindings.any {
                    it.enabled && it.targetType == selectedTargetType && it.targetKey == effectiveKey
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(style.metrics.cardRadius))
                        .background(style.colors.cardSurface)
                        .border(style.metrics.strokeWidth, style.colors.stroke, RoundedCornerShape(style.metrics.cardRadius))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(book.name, color = style.colors.primaryText, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${book.entries.size} 条目 · ${book.bindings.size} 场景",
                            color = style.colors.secondaryText,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = active,
                        enabled = !requiresTargetKey(selectedTargetType) || effectiveKey.isNotBlank(),
                        onCheckedChange = { onToggleBinding(book, selectedTargetType, effectiveKey) }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun WorldBookEditor(
    state: WorldBookEditState,
    style: AiComposeStyle,
    onBack: () -> Unit,
    onSave: (WorldBookEditState) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(state.name) }
    var description by rememberSaveable { mutableStateOf(state.description) }
    var maxEntries by rememberSaveable { mutableStateOf(state.maxEntries) }
    var enabled by rememberSaveable { mutableStateOf(state.enabled) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp)
    ) {
        WorldBookTopBar(
            title = if (state.id == null) "新增世界书" else "编辑世界书",
            subtitle = "资料库设置",
            style = style,
            onBack = onBack
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LabeledSwitch("启用世界书", enabled, style) { enabled = it }
                LabeledField("名称", name, style, onValueChange = { name = it })
                LabeledField("描述", description, style, minLines = 3, onValueChange = { description = it })
                LabeledField(
                    label = "每本世界书最大命中条数",
                    value = maxEntries,
                    style = style,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { maxEntries = it.filter(Char::isDigit).take(2) }
                )
                SaveBar(
                    enabled = name.trim().isNotBlank(),
                    style = style,
                    onCancel = onBack,
                    onSave = {
                        onSave(
                            state.copy(
                                name = name.trim(),
                                description = description.trim(),
                                maxEntries = maxEntries.ifBlank { "12" },
                                enabled = enabled
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun WorldBookEntryEditor(
    state: WorldBookEntryEditState,
    style: AiComposeStyle,
    onBack: () -> Unit,
    onSave: (WorldBookEntryEditState) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(state.title) }
    var content by rememberSaveable { mutableStateOf(state.content) }
    var keys by rememberSaveable { mutableStateOf(state.keys) }
    var secondaryKeys by rememberSaveable { mutableStateOf(state.secondaryKeys) }
    var excludeKeys by rememberSaveable { mutableStateOf(state.excludeKeys) }
    var regexEnabled by rememberSaveable { mutableStateOf(state.regexEnabled) }
    var enabled by rememberSaveable { mutableStateOf(state.enabled) }
    var constant by rememberSaveable { mutableStateOf(state.constant) }
    var priority by rememberSaveable { mutableStateOf(state.priority) }
    var scanDepth by rememberSaveable { mutableStateOf(state.scanDepth) }
    var maxMatches by rememberSaveable { mutableStateOf(state.maxMatches) }
    val regexOk = !regexEnabled || splitKeys(keys)
        .plus(splitKeys(secondaryKeys))
        .plus(splitKeys(excludeKeys))
        .all { runCatching { Regex(it) }.isSuccess }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp)
    ) {
        WorldBookTopBar(
            title = if (state.id == null) "新增条目" else "编辑条目",
            subtitle = "关键词命中后注入内容",
            style = style,
            onBack = onBack
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LabeledSwitch("启用条目", enabled, style) { enabled = it }
                LabeledSwitch("常驻注入", constant, style) { constant = it }
                LabeledSwitch("关键词按正则匹配", regexEnabled, style) { regexEnabled = it }
                LabeledField("标题", title, style, onValueChange = { title = it })
                LabeledField("内容", content, style, minLines = 6, onValueChange = { content = it })
                LabeledField("关键词，逗号或换行分隔", keys, style, minLines = 2, onValueChange = { keys = it })
                LabeledField("二级关键词", secondaryKeys, style, minLines = 2, onValueChange = { secondaryKeys = it })
                LabeledField("排除关键词", excludeKeys, style, minLines = 2, onValueChange = { excludeKeys = it })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField(
                        label = "优先级",
                        value = priority,
                        style = style,
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                        onValueChange = { priority = it.filter(Char::isDigit).take(3) }
                    )
                    LabeledField(
                        label = "扫描深度",
                        value = scanDepth,
                        style = style,
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                        onValueChange = { scanDepth = it.filter(Char::isDigit).take(2) }
                    )
                    LabeledField(
                        label = "最大命中",
                        value = maxMatches,
                        style = style,
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                        onValueChange = { maxMatches = it.filter(Char::isDigit).take(2) }
                    )
                }
                if (!regexOk) {
                    Text("正则表达式有错误，不能保存。", color = style.colors.danger, fontSize = 13.sp)
                }
                SaveBar(
                    enabled = title.trim().isNotBlank() && content.trim().isNotBlank() && regexOk,
                    style = style,
                    onCancel = onBack,
                    onSave = {
                        onSave(
                            state.copy(
                                title = title.trim(),
                                content = content.trim(),
                                keys = keys,
                                secondaryKeys = secondaryKeys,
                                excludeKeys = excludeKeys,
                                regexEnabled = regexEnabled,
                                enabled = enabled,
                                constant = constant,
                                priority = priority.ifBlank { "50" },
                                scanDepth = scanDepth.ifBlank { "8" },
                                maxMatches = maxMatches.ifBlank { "1" }
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    style: AiComposeStyle,
    modifier: Modifier = Modifier.fillMaxWidth(),
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
            .padding(top = 10.dp)
            .heightIn(min = if (minLines > 1) 96.dp else 56.dp)
    )
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    style: AiComposeStyle,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = style.colors.primaryText, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SaveBar(
    enabled: Boolean,
    style: AiComposeStyle,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onCancel) {
            Text("取消", color = style.colors.secondaryText)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            enabled = enabled,
            onClick = onSave,
            colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent)
        ) {
            Text("保存")
        }
    }
}

@Composable
private fun InfoChip(
    text: String,
    style: AiComposeStyle,
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(style.metrics.chipRadius))
            .background(if (selected) style.colors.toolSurface else style.colors.processSurface)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = if (selected) style.colors.accent else style.colors.secondaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SmallAction(
    text: String,
    style: AiComposeStyle,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (danger) style.colors.danger else style.colors.accent,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(style.metrics.chipRadius))
            .clickable { onClick() }
            .padding(horizontal = 2.dp, vertical = 4.dp)
    )
}

private fun buildWorldBookTargets(
    initialTargetType: String,
    initialTargetKey: String
): List<WorldBookTarget> {
    return buildList {
        add(WorldBookTarget("全局", AiWorldBookBinding.TARGET_GLOBAL))
        add(WorldBookTarget("正文问 AI", AiWorldBookBinding.TARGET_CHAT))
        add(WorldBookTarget("阅读页问 AI", AiWorldBookBinding.TARGET_READ_AI))
        add(WorldBookTarget("本书", AiWorldBookBinding.TARGET_BOOK, if (initialTargetType == AiWorldBookBinding.TARGET_BOOK) initialTargetKey else ""))
        add(WorldBookTarget("当前会话", AiWorldBookBinding.TARGET_SESSION, if (initialTargetType == AiWorldBookBinding.TARGET_SESSION) initialTargetKey else ""))
    }
}

private fun WorldBookEditState.toBook(
    old: AiWorldBookConfig?,
    order: Int
): AiWorldBookConfig {
    return (old ?: AiWorldBookConfig(name = name, order = order)).copy(
        name = name,
        description = description,
        enabled = enabled,
        bindingVersion = 1,
        maxEntries = maxEntries.toIntOrNull()?.coerceIn(1, 40) ?: 12
    )
}

private fun AiWorldBookConfig.toEditState(): WorldBookEditState {
    return WorldBookEditState(
        id = id,
        name = name,
        description = description,
        maxEntries = maxEntries.toString(),
        enabled = enabled
    )
}

private fun WorldBookEntryEditState.toEntry(book: AiWorldBookConfig): AiWorldBookEntry {
    val old = id?.let { targetId -> book.entries.firstOrNull { it.id == targetId } }
    return (old ?: AiWorldBookEntry(title = title, content = content, order = book.entries.size)).copy(
        title = title,
        content = content,
        keys = splitKeys(keys),
        secondaryKeys = splitKeys(secondaryKeys),
        excludeKeys = splitKeys(excludeKeys),
        regexEnabled = regexEnabled,
        enabled = enabled,
        constant = constant,
        priority = priority.toIntOrNull()?.coerceIn(0, 100) ?: 50,
        scanDepth = scanDepth.toIntOrNull()?.coerceIn(1, 64) ?: 8,
        maxMatches = maxMatches.toIntOrNull()?.coerceIn(1, 20) ?: 1
    )
}

private fun AiWorldBookEntry.toEditState(worldBookId: String): WorldBookEntryEditState {
    return WorldBookEntryEditState(
        worldBookId = worldBookId,
        id = id,
        title = title,
        content = content,
        keys = keys.joinToString("\n"),
        secondaryKeys = secondaryKeys.joinToString("\n"),
        excludeKeys = excludeKeys.joinToString("\n"),
        regexEnabled = regexEnabled,
        enabled = enabled,
        constant = constant,
        priority = priority.toString(),
        scanDepth = scanDepth.toString(),
        maxMatches = maxMatches.toString()
    )
}

private fun splitKeys(value: String): List<String> {
    return value.split(',', '，', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(40)
}

private fun requiresTargetKey(targetType: String): Boolean {
    return targetType == AiWorldBookBinding.TARGET_BOOK ||
            targetType == AiWorldBookBinding.TARGET_SESSION
}
