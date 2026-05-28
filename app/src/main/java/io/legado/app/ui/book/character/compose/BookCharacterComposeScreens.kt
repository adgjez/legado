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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
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
        ),
        radius = context.composePanelRadius(),
        smallRadius = context.composeActionRadius()
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
            TextButton(onClick = { expanded = true }) {
                Text("更多", color = style.colors.subText)
            }
            if (expanded) {
                CharacterMoreDialog(
                    character = character,
                    onDismiss = { expanded = false },
                    onEdit = {
                        expanded = false
                        onEdit()
                    },
                    onDelete = {
                        expanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun CharacterMoreDialog(
    character: BookCharacter,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val style = rememberCharacterStyle()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(style.radius),
        containerColor = style.colors.card,
        title = { Text(character.displayName(), color = style.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MoreActionRow("编辑角色", "修改头像、身份、技能和生平", onEdit)
                MoreActionRow("删除角色", "同时删除与此角色相关的关系", onDelete, danger = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = style.colors.accent) } }
    )
}

@Composable
private fun MoreActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    val style = rememberCharacterStyle()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (danger) style.colors.danger.copy(alpha = 0.09f) else style.colors.page,
        shape = RoundedCornerShape(style.smallRadius)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = if (danger) style.colors.danger else style.colors.text, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = style.colors.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.radius))
            .background(
                Brush.linearGradient(
                    listOf(
                        style.colors.cardAlt,
                        style.colors.card,
                        style.colors.page
                    )
                )
            )
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = style.colors.page.copy(alpha = 0.72f),
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
                ) {
                    CharacterAvatar(character.avatar, character.displayName(), 96)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                ) {
                    Text(
                        text = character.displayName(),
                        color = style.colors.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RolePill(character.roleLabel(), compact = false)
                        character.identity.takeIf { it.isNotBlank() }?.let {
                            InfoPill(it, maxWidth = 150.dp)
                        }
                    }
                }
            }
            val highlights = listOf(
                "技能" to character.skills,
                "属性" to character.attributes
            ).filter { it.second.isNotBlank() }
            if (highlights.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    highlights.forEach { (label, value) ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = style.colors.page.copy(alpha = 0.58f),
                            shape = RoundedCornerShape(style.smallRadius),
                            border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(label, color = style.colors.subText, fontSize = 12.sp)
                                Text(
                                    value,
                                    color = style.colors.text,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, maxWidth: androidx.compose.ui.unit.Dp) {
    val style = rememberCharacterStyle()
    Surface(
        modifier = Modifier.widthIn(max = maxWidth),
        color = style.colors.page.copy(alpha = 0.60f),
        shape = RoundedCornerShape(style.smallRadius),
        border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
    ) {
        Text(
            text = text,
            color = style.colors.subText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
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
    val style = rememberCharacterStyle()
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
                color = if (selected) style.colors.accent.copy(alpha = 0.14f) else style.colors.card,
                shape = RoundedCornerShape(style.smallRadius),
                modifier = Modifier.clickable { onSelect(character.id) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CharacterAvatar(character.avatar, character.displayName(), 32)
                    Text(
                        text = character.displayName(),
                        color = if (selected) style.colors.accent else style.colors.text,
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
    LaunchedEffect(visibleCharacters.map { it.id }, selectedCenterId) {
        scale = 1f
        pan = Offset.Zero
    }
    Surface(
        modifier = modifier,
        color = style.colors.card,
        shape = RoundedCornerShape(style.radius)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(visibleCharacters, selectedCenterId) {
                    detectTransformGestures { _, p, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 2.2f)
                        scale = nextScale
                        pan = constrainGraphPan(pan + p, size.width.toFloat(), size.height.toFloat(), nextScale)
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
                    drawLine(
                        color = style.colors.accent.copy(alpha = 0.22f + relation.strength.coerceIn(0, 100) / 360f),
                        start = start,
                        end = end,
                        strokeWidth = 2.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            visibleCharacters.forEach { character ->
                val point = positions[character.id] ?: return@forEach
                val p = transformGraphPoint(point, scale, pan, widthPx, heightPx)
                val avatarSize = when (character.id) {
                    selectedCenterId -> 82
                    else -> if (character.roleLevel == BookCharacter.ROLE_IMPORTANT) 64 else 56
                }
                val nodeWidth = 112.dp
                val nodeWidthPx = with(density) { nodeWidth.toPx() }
                val avatarSizePx = with(density) { avatarSize.dp.toPx() }
                Column(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (p.x - nodeWidthPx / 2f).roundToInt(),
                                (p.y - avatarSizePx / 2f).roundToInt()
                            )
                        }
                        .width(nodeWidth)
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
                        CharacterAvatar(character.avatar, character.displayName(), avatarSize)
                    }
                    Text(
                        text = character.displayName(),
                        color = style.colors.text,
                        fontSize = 11.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
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
    val byId = characters.associateBy { it.id }
    val directIds = relations
        .filter { it.fromCharacterId == center.id || it.toCharacterId == center.id }
        .sortedWith(compareByDescending<BookCharacterRelation> { it.strength }.thenBy { it.sortOrder }.thenBy { it.id })
        .mapNotNull {
            when (center.id) {
                it.fromCharacterId -> it.toCharacterId
                it.toCharacterId -> it.fromCharacterId
                else -> null
            }
        }
        .distinct()
        .take(8)
    return (listOf(center.id) + directIds)
        .mapNotNull { byId[it] }
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
    val centerPoint = Offset(width * 0.5f, height * 0.46f)
    result[center.id] = centerPoint
    val radiusX = (width * 0.38f).coerceAtMost(width / 2f - 72f).coerceAtLeast(92f)
    val radiusY = (height * 0.34f).coerceAtMost(height / 2f - 88f).coerceAtLeast(76f)
    others.forEachIndexed { index, character ->
        val angle = graphAngle(index, others.size)
        result[character.id] = Offset(
            x = centerPoint.x + cos(angle).toFloat() * radiusX,
            y = centerPoint.y + sin(angle).toFloat() * radiusY
        )
    }
    return result
}

private fun graphAngle(index: Int, count: Int): Double {
    return when (count) {
        1 -> -PI / 2.0
        2 -> if (index == 0) PI else 0.0
        3 -> -PI / 2.0 + index * 2.0 * PI / 3.0
        4 -> listOf(-PI / 2.0, 0.0, PI / 2.0, PI)[index]
        else -> -PI / 2.0 + index * 2.0 * PI / count
    }
}

private fun transformGraphPoint(point: Offset, scale: Float, pan: Offset, width: Float, height: Float): Offset {
    val center = Offset(width / 2f, height / 2f)
    return center + (point - center) * scale + pan
}

private fun constrainGraphPan(pan: Offset, width: Float, height: Float, scale: Float): Offset {
    val maxX = (width * (scale - 1f) / 2f + 80f).coerceAtLeast(0f)
    val maxY = (height * (scale - 1f) / 2f + 80f).coerceAtLeast(0f)
    return Offset(
        pan.x.coerceIn(-maxX, maxX),
        pan.y.coerceIn(-maxY, maxY)
    )
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
            .background(style.colors.page.copy(alpha = 0.78f), RoundedCornerShape(style.radius))
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
                        Surface(color = style.colors.card, shape = RoundedCornerShape(style.smallRadius)) {
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
    val style = rememberCharacterStyle()
    val from = characters.firstOrNull { it.id == relation.fromCharacterId }?.displayName().orEmpty()
    val to = characters.firstOrNull { it.id == relation.toCharacterId }?.displayName().orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(style.radius),
        containerColor = style.colors.card,
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
    var selectingTarget by remember { mutableStateOf<RelationSelectTarget?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val style = rememberCharacterStyle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = style.radius, topEnd = style.radius),
        containerColor = style.colors.page
    ) {
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
                CharacterSelectField("角色 A", characters.firstOrNull { it.id == editing.fromCharacterId }) {
                    selectingTarget = RelationSelectTarget.FROM
                }
            }
            item {
                CharacterSelectField("角色 B", characters.firstOrNull { it.id == editing.toCharacterId }) {
                    selectingTarget = RelationSelectTarget.TO
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
    selectingTarget?.let { target ->
        CharacterSelectDialog(
            title = if (target == RelationSelectTarget.FROM) "选择角色 A" else "选择角色 B",
            characters = characters,
            selectedId = if (target == RelationSelectTarget.FROM) editing.fromCharacterId else editing.toCharacterId,
            onDismiss = { selectingTarget = null },
            onSelect = { id ->
                editing = if (target == RelationSelectTarget.FROM) {
                    editing.copy(fromCharacterId = id)
                } else {
                    editing.copy(toCharacterId = id)
                }
                selectingTarget = null
            }
        )
    }
}

@Composable
private fun CharacterSelectField(
    label: String,
    selected: BookCharacter?,
    onClick: () -> Unit
) {
    val style = rememberCharacterStyle()
    Column {
        Text(label, color = style.colors.subText, fontSize = 13.sp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clickable(onClick = onClick),
            color = style.colors.card,
            shape = RoundedCornerShape(style.smallRadius),
            border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selected != null) {
                    CharacterAvatar(selected.avatar, selected.displayName(), 38)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (selected != null) 10.dp else 0.dp)
                ) {
                    Text(
                        text = selected?.displayName() ?: "请选择角色",
                        color = style.colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    selected?.identity?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = style.colors.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("更换", color = style.colors.accent, fontSize = 13.sp)
            }
        }
    }
}

private enum class RelationSelectTarget {
    FROM, TO
}

@Composable
private fun CharacterSelectDialog(
    title: String,
    characters: List<BookCharacter>,
    selectedId: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit
) {
    val style = rememberCharacterStyle()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(style.radius),
        containerColor = style.colors.card,
        title = { Text(title, color = style.colors.text) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(characters, key = { it.id }) { character ->
                    val selected = character.id == selectedId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(character.id) },
                        color = if (selected) style.colors.accent.copy(alpha = 0.14f) else style.colors.page,
                        shape = RoundedCornerShape(style.smallRadius),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) style.colors.accent else style.colors.stroke
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CharacterAvatar(character.avatar, character.displayName(), 44)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            ) {
                                Text(
                                    character.displayName(),
                                    color = style.colors.text,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    character.identity.ifBlank { character.roleLabel() },
                                    color = style.colors.subText,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (selected) {
                                Text("已选", color = style.colors.accent, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = style.colors.accent) } }
    )
}

@Composable
private fun RolePill(text: String, compact: Boolean, modifier: Modifier = Modifier) {
    val style = rememberCharacterStyle()
    Surface(
        modifier = modifier,
        color = style.colors.accent.copy(alpha = 0.13f),
        shape = RoundedCornerShape(style.smallRadius)
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
        shape = RoundedCornerShape(style.smallRadius)
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
