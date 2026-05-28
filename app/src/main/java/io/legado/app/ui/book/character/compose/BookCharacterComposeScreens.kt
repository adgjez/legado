package io.legado.app.ui.book.character.compose

import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Immutable
data class CharacterColors(
    val accent: Color,
    val page: Color,
    val card: Color,
    val cardAlt: Color,
    val text: Color,
    val subText: Color,
    val stroke: Color,
    val danger: Color
)

@Immutable
data class CharacterStyle(
    val colors: CharacterColors,
    val radius: androidx.compose.ui.unit.Dp = 22.dp,
    val smallRadius: androidx.compose.ui.unit.Dp = 14.dp
)

@Stable
@Composable
fun rememberCharacterStyle(): CharacterStyle {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    val page = ContextCompat.getColor(context, if (night) R.color.md_grey_900 else R.color.white)
    val card = if (night) 0xff202329.toInt() else 0xfff7f8fb.toInt()
    val cardAlt = ColorUtils.blendColors(card, accent, if (night) 0.18f else 0.08f)
    val text = if (night) 0xfff2f3f5.toInt() else 0xff202124.toInt()
    val subText = if (night) 0xffaeb4bc.toInt() else 0xff6b7178.toInt()
    val stroke = if (night) 0x26ffffff else 0x17000000
    return CharacterStyle(
        colors = CharacterColors(
            accent = Color(accent),
            page = Color(page),
            card = Color(card),
            cardAlt = Color(cardAlt),
            text = Color(text),
            subText = Color(subText),
            stroke = Color(stroke),
            danger = Color(0xffe34f4f.toInt())
        )
    )
}

data class CharacterEditDraft(
    val name: String = "",
    val avatar: String = "",
    val roleLevel: Int = BookCharacter.ROLE_NORMAL,
    val identity: String = "",
    val skills: String = "",
    val attributes: String = "",
    val appearance: String = "",
    val personality: String = "",
    val biography: String = ""
)

data class RelationEditDraft(
    val id: Long = 0L,
    val fromCharacterId: Long = 0L,
    val toCharacterId: Long = 0L,
    val relationName: String = "",
    val relationType: String = "",
    val description: String = "",
    val strength: Int = 50,
    val sortOrder: Int = 0
)

fun BookCharacter.toDraft(): CharacterEditDraft = CharacterEditDraft(
    name = name,
    avatar = avatar,
    roleLevel = roleLevel,
    identity = identity,
    skills = skills,
    attributes = attributes,
    appearance = appearance,
    personality = personality,
    biography = biography
)

fun BookCharacter.summaryText(): String = listOf(identity, skills, attributes)
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .joinToString(" · ")
    .ifBlank { "点击查看角色卡片" }

@Composable
fun CharacterScaffold(
    title: String,
    subtitle: String = "",
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    val style = rememberCharacterStyle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.colors.page)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("返回", color = style.colors.text)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = style.colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = style.colors.subText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = actions)
        }
        content()
    }
}

@Composable
fun CharacterManageScreen(
    bookName: String,
    characters: List<BookCharacter>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenCard: (BookCharacter) -> Unit,
    onEdit: (BookCharacter) -> Unit,
    onDelete: (BookCharacter) -> Unit,
    onOpenRelations: () -> Unit
) {
    val style = rememberCharacterStyle()
    CharacterScaffold(
        title = "角色资料",
        subtitle = bookName,
        onBack = onBack,
        actions = {
            TextButton(onClick = onOpenRelations) { Text("关系网", color = style.colors.accent) }
        }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp, 10.dp, 18.dp, 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                CharacterSummaryHeader(
                    count = characters.size,
                    onAdd = onAdd
                )
            }
            if (characters.isEmpty()) {
                item { EmptyCharacterCard("还没有角色，先添加主角或重要角色。") }
            } else {
                items(characters, key = { it.id }) { character ->
                    CharacterListCard(
                        character = character,
                        onClick = { onOpenCard(character) },
                        onEdit = { onEdit(character) },
                        onDelete = { onDelete(character) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterSummaryHeader(count: Int, onAdd: () -> Unit) {
    val style = rememberCharacterStyle()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(style.radius), clip = false),
        color = style.colors.cardAlt,
        shape = RoundedCornerShape(style.radius)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("本书角色库", color = style.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (count == 0) "角色卡会只绑定当前书籍" else "已记录 $count 个角色",
                    color = style.colors.subText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent)
            ) {
                Text("添加")
            }
        }
    }
}

@Composable
fun CharacterListCard(
    character: BookCharacter,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val style = rememberCharacterStyle()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.radius))
            .clickable(onClick = onClick),
        color = style.colors.card,
        shape = RoundedCornerShape(style.radius),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CharacterAvatar(character.avatar, character.displayName(), 64)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = character.displayName(),
                        color = style.colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    RolePill(character.roleLabel(), compact = true)
                }
                Text(
                    text = character.summaryText(),
                    color = style.colors.subText,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp)
                )
            }
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text("更多", color = style.colors.subText)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = {
                        expanded = false
                        onEdit()
                    })
                    DropdownMenuItem(text = { Text("删除") }, onClick = {
                        expanded = false
                        onDelete()
                    })
                }
            }
        }
    }
}

@Composable
fun CharacterCardScreen(
    character: BookCharacter?,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    val style = rememberCharacterStyle()
    CharacterScaffold(
        title = "角色卡片",
        subtitle = character?.displayName().orEmpty(),
        onBack = onBack,
        actions = {
            TextButton(onClick = onEdit, enabled = character != null) {
                Text("编辑", color = if (character == null) style.colors.subText else style.colors.accent)
            }
        }
    ) {
        if (character == null) {
            EmptyCharacterCard("角色不存在")
            return@CharacterScaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp, 10.dp, 18.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { CharacterHeroCard(character) }
            item { CharacterSection("身份", character.identity) }
            item { CharacterSection("技能", character.skills) }
            item { CharacterSection("属性", character.attributes) }
            item { CharacterSection("形象描述", character.appearance) }
            item { CharacterSection("性格描述", character.personality) }
            item { CharacterSection("角色生平", character.biography) }
        }
    }
}

@Composable
private fun CharacterHeroCard(character: BookCharacter) {
    val style = rememberCharacterStyle()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = style.colors.cardAlt,
        shape = RoundedCornerShape(28.dp)
    ) {
        Box {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawCircle(
                    color = style.colors.accent.copy(alpha = 0.18f),
                    radius = size.minDimension * 0.42f,
                    center = Offset(size.width * 0.86f, size.height * 0.12f)
                )
                drawCircle(
                    color = style.colors.accent.copy(alpha = 0.10f),
                    radius = size.minDimension * 0.30f,
                    center = Offset(size.width * 0.08f, size.height * 0.92f)
                )
            }
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CharacterAvatar(character.avatar, character.displayName(), 112)
                Text(
                    text = character.displayName(),
                    color = style.colors.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
                RolePill(character.roleLabel(), compact = false, modifier = Modifier.padding(top = 10.dp))
                Text(
                    text = character.identity.ifBlank { character.summaryText() },
                    color = style.colors.subText,
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun CharacterSection(label: String, value: String) {
    val style = rememberCharacterStyle()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = style.colors.card,
        shape = RoundedCornerShape(style.radius)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, color = style.colors.subText, fontSize = 13.sp)
            Text(
                text = value.ifBlank { "未填写" },
                color = style.colors.text,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun CharacterEditScreen(
    title: String,
    draft: CharacterEditDraft,
    onDraftChange: (CharacterEditDraft) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onPickLocalAvatar: () -> Unit,
    onPickOnlineAvatar: () -> Unit,
    onClearAvatar: () -> Unit
) {
    val style = rememberCharacterStyle()
    CharacterScaffold(
        title = title,
        onBack = onBack,
        actions = {
            TextButton(onClick = onSave) { Text("保存", color = style.colors.accent) }
        }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp, 10.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Surface(color = style.colors.card, shape = RoundedCornerShape(style.radius)) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CharacterAvatar(draft.avatar, draft.name, 82)
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text("角色头像", color = style.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallAction("本地", onPickLocalAvatar)
                                SmallAction("在线", onPickOnlineAvatar)
                                SmallAction("清除", onClearAvatar, danger = true)
                            }
                        }
                    }
                }
            }
            item { CharacterTextField("角色名称", draft.name, { onDraftChange(draft.copy(name = it)) }, singleLine = true) }
            item { RoleSelector(draft.roleLevel) { onDraftChange(draft.copy(roleLevel = it)) } }
            item { CharacterTextField("角色身份", draft.identity, { onDraftChange(draft.copy(identity = it)) }, singleLine = true) }
            item { CharacterTextField("角色技能", draft.skills, { onDraftChange(draft.copy(skills = it)) }) }
            item { CharacterTextField("角色属性", draft.attributes, { onDraftChange(draft.copy(attributes = it)) }) }
            item { CharacterTextField("角色形象描述", draft.appearance, { onDraftChange(draft.copy(appearance = it)) }) }
            item { CharacterTextField("角色性格描述", draft.personality, { onDraftChange(draft.copy(personality = it)) }) }
            item { CharacterTextField("角色生平", draft.biography, { onDraftChange(draft.copy(biography = it)) }, minLines = 5) }
        }
    }
}

@Composable
private fun RoleSelector(role: Int, onRoleChange: (Int) -> Unit) {
    val style = rememberCharacterStyle()
    Surface(color = style.colors.card, shape = RoundedCornerShape(style.radius)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("角色类型", color = style.colors.subText, fontSize = 13.sp)
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    BookCharacter.ROLE_NORMAL to "普通角色",
                    BookCharacter.ROLE_IMPORTANT to "重要角色",
                    BookCharacter.ROLE_MAIN to "主角"
                ).forEach { (value, label) ->
                    val selected = role == value
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(style.smallRadius))
                            .clickable { onRoleChange(value) },
                        color = if (selected) style.colors.accent.copy(alpha = 0.16f) else style.colors.page,
                        shape = RoundedCornerShape(style.smallRadius),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) style.colors.accent else style.colors.stroke)
                    ) {
                        Text(
                            text = label,
                            color = if (selected) style.colors.accent else style.colors.text,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 10.dp),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = false,
    minLines: Int = 2
) {
    val style = rememberCharacterStyle()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.smallRadius)
    )
}

@Composable
fun CharacterRelationScreen(
    characters: List<BookCharacter>,
    relations: List<BookCharacterRelation>,
    selectedCenterId: Long,
    selectedRelation: BookCharacterRelation?,
    editingRelation: RelationEditDraft?,
    onBack: () -> Unit,
    onAddRelation: () -> Unit,
    onEditRelation: (BookCharacterRelation) -> Unit,
    onDeleteRelation: (BookCharacterRelation) -> Unit,
    onSaveRelation: (RelationEditDraft) -> Unit,
    onDismissEdit: () -> Unit,
    onSelectCenter: (Long) -> Unit,
    onOpenCard: (BookCharacter) -> Unit,
    onSelectRelation: (BookCharacterRelation?) -> Unit
) {
    val style = rememberCharacterStyle()
    CharacterScaffold(
        title = "角色关系网",
        subtitle = "主角视角 · ${characters.size} 个角色 · ${relations.size} 条关系",
        onBack = onBack,
        actions = {
            TextButton(onClick = onAddRelation) { Text("添加关系", color = style.colors.accent) }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            CharacterCenterSelector(characters, selectedCenterId, onSelectCenter)
            CharacterGraph(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                characters = characters,
                relations = relations,
                selectedCenterId = selectedCenterId,
                onCharacterClick = onOpenCard,
                onRelationClick = onSelectRelation
            )
            RelationListPanel(
                relations = relations,
                characters = characters,
                onEdit = onEditRelation,
                onDelete = onDeleteRelation
            )
        }
    }
    selectedRelation?.let {
        RelationDetailDialog(
            relation = it,
            characters = characters,
            onDismiss = { onSelectRelation(null) },
            onEdit = {
                onSelectRelation(null)
                onEditRelation(it)
            }
        )
    }
    editingRelation?.let {
        RelationEditSheet(
            draft = it,
            characters = characters,
            onChange = onSaveRelation,
            onDismiss = onDismissEdit
        )
    }
}

@Composable
private fun CharacterCenterSelector(
    characters: List<BookCharacter>,
    selectedCenterId: Long,
    onSelect: (Long) -> Unit
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        characters.forEach { character ->
            val selected = character.id == selectedCenterId
            Surface(
                color = if (selected) rememberCharacterStyle().colors.accent.copy(alpha = 0.14f) else rememberCharacterStyle().colors.card,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.clickable { onSelect(character.id) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CharacterAvatar(character.avatar, character.displayName(), 32)
                    Text(
                        text = character.displayName(),
                        color = if (selected) rememberCharacterStyle().colors.accent else rememberCharacterStyle().colors.text,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterGraph(
    modifier: Modifier,
    characters: List<BookCharacter>,
    relations: List<BookCharacterRelation>,
    selectedCenterId: Long,
    onCharacterClick: (BookCharacter) -> Unit,
    onRelationClick: (BookCharacterRelation) -> Unit
) {
    val style = rememberCharacterStyle()
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val visibleCharacters = remember(characters, relations, selectedCenterId) {
        buildVisibleCharacters(characters, relations, selectedCenterId)
    }
    val visibleIds = visibleCharacters.map { it.id }.toSet()
    val visibleRelations = remember(relations, visibleIds) {
        relations.filter { it.fromCharacterId in visibleIds && it.toCharacterId in visibleIds }
    }
    Surface(
        modifier = modifier,
        color = style.colors.card,
        shape = RoundedCornerShape(28.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, p, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.62f, 2.2f)
                        pan += p
                    }
                }
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val positions = remember(visibleCharacters, selectedCenterId, widthPx, heightPx) {
                buildGraphPositions(visibleCharacters, selectedCenterId, widthPx, heightPx)
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(style.colors.accent.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.42f),
                        radius = size.minDimension * 0.65f
                    )
                )
                visibleRelations.forEach { relation ->
                    val from = positions[relation.fromCharacterId] ?: return@forEach
                    val to = positions[relation.toCharacterId] ?: return@forEach
                    val start = transformGraphPoint(from, scale, pan, size.width, size.height)
                    val end = transformGraphPoint(to, scale, pan, size.width, size.height)
                    val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                    val normal = Offset(-(end.y - start.y), end.x - start.x)
                    val len = kotlin.math.sqrt(normal.x * normal.x + normal.y * normal.y).coerceAtLeast(1f)
                    val control = mid + normal / len * 34f
                    val path = Path().apply {
                        moveTo(start.x, start.y)
                        quadraticBezierTo(control.x, control.y, end.x, end.y)
                    }
                    drawPath(
                        path = path,
                        color = style.colors.accent.copy(alpha = 0.22f + relation.strength.coerceIn(0, 100) / 360f),
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            visibleRelations.forEach { relation ->
                val from = positions[relation.fromCharacterId] ?: return@forEach
                val to = positions[relation.toCharacterId] ?: return@forEach
                val start = transformGraphPoint(from, scale, pan, widthPx, heightPx)
                val end = transformGraphPoint(to, scale, pan, widthPx, heightPx)
                val label = relation.displayName()
                Text(
                    text = label,
                    color = style.colors.accent,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                ((start.x + end.x) / 2f).roundToInt() - 28,
                                ((start.y + end.y) / 2f).roundToInt() - 10
                            )
                        }
                        .clickable { onRelationClick(relation) }
                        .background(style.colors.page.copy(alpha = 0.58f), RoundedCornerShape(9.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            visibleCharacters.forEach { character ->
                val point = positions[character.id] ?: return@forEach
                val p = transformGraphPoint(point, scale, pan, widthPx, heightPx)
                val size = when (character.id) {
                    selectedCenterId -> 82
                    else -> if (character.roleLevel == BookCharacter.ROLE_IMPORTANT) 64 else 56
                }
                Column(
                    modifier = Modifier
                        .offset { IntOffset(p.x.roundToInt() - size / 2, p.y.roundToInt() - size / 2) }
                        .widthIn(min = size.dp + 18.dp, max = 112.dp)
                        .pointerInput(character.id) {
                            detectTapGestures(onTap = { onCharacterClick(character) })
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (character.id == selectedCenterId) style.colors.accent.copy(alpha = 0.18f) else style.colors.page,
                        border = androidx.compose.foundation.BorderStroke(
                            if (character.id == selectedCenterId) 2.dp else 1.dp,
                            if (character.id == selectedCenterId) style.colors.accent else style.colors.stroke
                        )
                    ) {
                        CharacterAvatar(character.avatar, character.displayName(), size)
                    }
                    Text(
                        text = character.displayName(),
                        color = style.colors.text,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .background(style.colors.card.copy(alpha = 0.76f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            if (characters.size > visibleCharacters.size) {
                UnlinkedCharactersStrip(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                    characters = characters.filter { it.id !in visibleIds },
                    onCharacterClick = onCharacterClick
                )
            }
        }
    }
}

private fun buildVisibleCharacters(
    characters: List<BookCharacter>,
    relations: List<BookCharacterRelation>,
    centerId: Long
): List<BookCharacter> {
    if (characters.isEmpty()) return emptyList()
    val center = characters.firstOrNull { it.id == centerId } ?: characters.first()
    val directIds = relations.filter { it.fromCharacterId == center.id || it.toCharacterId == center.id }
        .flatMap { listOf(it.fromCharacterId, it.toCharacterId) }
        .toMutableSet()
    val secondIds = relations.filter { it.fromCharacterId in directIds || it.toCharacterId in directIds }
        .flatMap { listOf(it.fromCharacterId, it.toCharacterId) }
        .take(10)
    directIds.add(center.id)
    directIds.addAll(secondIds)
    return characters.filter { it.id in directIds }
        .ifEmpty { listOf(center) }
}

private fun buildGraphPositions(
    characters: List<BookCharacter>,
    centerId: Long,
    width: Float,
    height: Float
): Map<Long, Offset> {
    if (characters.isEmpty()) return emptyMap()
    val center = characters.firstOrNull { it.id == centerId } ?: characters.first()
    val others = characters.filter { it.id != center.id }
    val result = linkedMapOf<Long, Offset>()
    result[center.id] = Offset(width * 0.5f, height * 0.46f)
    val radius = minOf(width, height) * 0.31f
    others.forEachIndexed { index, character ->
        val angle = -PI / 2.0 + index * 2.0 * PI / others.size.coerceAtLeast(1)
        val levelOffset = if (character.roleLevel == BookCharacter.ROLE_IMPORTANT) 0.88f else 1.06f
        result[character.id] = Offset(
            x = width * 0.5f + cos(angle).toFloat() * radius * levelOffset,
            y = height * 0.46f + sin(angle).toFloat() * radius * levelOffset
        )
    }
    return result
}

private fun transformGraphPoint(point: Offset, scale: Float, pan: Offset, width: Float, height: Float): Offset {
    val center = Offset(width / 2f, height / 2f)
    return center + (point - center) * scale + pan
}

@Composable
private fun UnlinkedCharactersStrip(
    modifier: Modifier,
    characters: List<BookCharacter>,
    onCharacterClick: (BookCharacter) -> Unit
) {
    val style = rememberCharacterStyle()
    Row(
        modifier = modifier
            .background(style.colors.page.copy(alpha = 0.78f), RoundedCornerShape(20.dp))
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        characters.forEach { character ->
            CharacterAvatar(
                path = character.avatar,
                contentDescription = character.displayName(),
                sizeDp = 38,
                modifier = Modifier.clickable { onCharacterClick(character) }
            )
        }
    }
}

@Composable
private fun RelationListPanel(
    relations: List<BookCharacterRelation>,
    characters: List<BookCharacter>,
    onEdit: (BookCharacterRelation) -> Unit,
    onDelete: (BookCharacterRelation) -> Unit
) {
    val style = rememberCharacterStyle()
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = style.colors.page,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("关系列表", color = style.colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", color = style.colors.accent, fontSize = 13.sp)
            }
            AnimatedVisibility(expanded) {
                Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    relations.take(8).forEach { relation ->
                        val from = characters.firstOrNull { it.id == relation.fromCharacterId }?.displayName().orEmpty()
                        val to = characters.firstOrNull { it.id == relation.toCharacterId }?.displayName().orEmpty()
                        Surface(color = style.colors.card, shape = RoundedCornerShape(16.dp)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("$from → $to", color = style.colors.text, fontSize = 14.sp, maxLines = 1)
                                    Text(relation.displayName(), color = style.colors.subText, fontSize = 12.sp, maxLines = 1)
                                }
                                TextButton(onClick = { onEdit(relation) }) { Text("编辑", color = style.colors.accent) }
                                TextButton(onClick = { onDelete(relation) }) { Text("删除", color = style.colors.danger) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationDetailDialog(
    relation: BookCharacterRelation,
    characters: List<BookCharacter>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit
) {
    val from = characters.firstOrNull { it.id == relation.fromCharacterId }?.displayName().orEmpty()
    val to = characters.firstOrNull { it.id == relation.toCharacterId }?.displayName().orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(relation.displayName()) },
        text = {
            Column {
                Text("$from → $to")
                Text("属性：${relation.relationType.ifBlank { "未填写" }}")
                Text("强度：${relation.strength}")
                if (relation.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(relation.description)
                }
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("编辑") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelationEditSheet(
    draft: RelationEditDraft,
    characters: List<BookCharacter>,
    onChange: (RelationEditDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember(draft) { mutableStateOf(draft) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val style = rememberCharacterStyle()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 8.dp, 20.dp, 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    if (draft.id > 0) "编辑关系" else "添加关系",
                    color = style.colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            item {
                CharacterDropdown("角色 A", characters, editing.fromCharacterId) {
                    editing = editing.copy(fromCharacterId = it)
                }
            }
            item {
                CharacterDropdown("角色 B", characters, editing.toCharacterId) {
                    editing = editing.copy(toCharacterId = it)
                }
            }
            item { CharacterTextField("关系名称", editing.relationName, { editing = editing.copy(relationName = it) }, singleLine = true) }
            item { CharacterTextField("关系属性", editing.relationType, { editing = editing.copy(relationType = it) }, singleLine = true) }
            item {
                Column {
                    Text("关系强度 ${editing.strength}", color = style.colors.subText, fontSize = 13.sp)
                    Slider(
                        value = editing.strength.toFloat(),
                        onValueChange = { editing = editing.copy(strength = it.roundToInt().coerceIn(0, 100)) },
                        valueRange = 0f..100f
                    )
                }
            }
            item { CharacterTextField("关系说明", editing.description, { editing = editing.copy(description = it) }, minLines = 3) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(
                        onClick = { onChange(editing) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent)
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun CharacterDropdown(
    label: String,
    characters: List<BookCharacter>,
    selectedId: Long,
    onSelect: (Long) -> Unit
) {
    val style = rememberCharacterStyle()
    var expanded by remember { mutableStateOf(false) }
    val selected = characters.firstOrNull { it.id == selectedId } ?: characters.firstOrNull()
    Column {
        Text(label, color = style.colors.subText, fontSize = 13.sp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clickable { expanded = true },
            color = style.colors.card,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
        ) {
            Text(
                text = selected?.displayName() ?: "请选择角色",
                color = style.colors.text,
                modifier = Modifier.padding(14.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            characters.forEach {
                DropdownMenuItem(text = { Text(it.displayName()) }, onClick = {
                    expanded = false
                    onSelect(it.id)
                })
            }
        }
    }
}

@Composable
private fun RolePill(text: String, compact: Boolean, modifier: Modifier = Modifier) {
    val style = rememberCharacterStyle()
    Surface(
        modifier = modifier,
        color = style.colors.accent.copy(alpha = 0.13f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            color = style.colors.accent,
            fontSize = if (compact) 11.sp else 13.sp,
            modifier = Modifier.padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 4.dp else 6.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit, danger: Boolean = false) {
    val style = rememberCharacterStyle()
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (danger) style.colors.danger.copy(alpha = 0.10f) else style.colors.accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            color = if (danger) style.colors.danger else style.colors.accent,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun EmptyCharacterCard(text: String) {
    val style = rememberCharacterStyle()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        color = style.colors.card,
        shape = RoundedCornerShape(style.radius)
    ) {
        Text(
            text = text,
            color = style.colors.subText,
            fontSize = 14.sp,
            modifier = Modifier.padding(22.dp)
        )
    }
}

@Composable
fun CharacterAvatar(
    path: String,
    contentDescription: String,
    sizeDp: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(rememberCharacterStyle().colors.card),
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                this.contentDescription = contentDescription
            }
        },
        update = { imageView ->
            ImageLoader.load(context, path.ifBlank { null })
                .placeholder(R.drawable.ic_bottom_person)
                .error(R.drawable.ic_bottom_person)
                .into(imageView)
        }
    )
}
