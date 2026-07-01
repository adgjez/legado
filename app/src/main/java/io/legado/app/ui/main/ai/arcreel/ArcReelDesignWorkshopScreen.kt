package io.legado.app.ui.main.ai.arcreel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.legado.app.help.ai.*
import io.legado.app.ui.main.ai.compose.AiComposeStyle
import io.legado.app.ui.main.ai.compose.aiComposeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ArcReel 角色设计工坊 — 查看和编辑AI生成的角色、场景、道具设计
 */
@Composable
fun ArcReelDesignWorkshopScreen(
    project: ArcReelProject,
    onBack: () -> Unit,
    onGenerateCharacterImage: (suspend (String) -> String?)? = null,
    onGenerateSceneImage: (suspend (String) -> String?)? = null
) {
    val context = LocalContext.current
    val style = aiComposeStyle(context)
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("角色", "场景", "道具")
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf(project.characters) }
    var scenes by remember { mutableStateOf(project.scenes) }
    var generatingName by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.colors.pageBackground)
            .statusBarsPadding()
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 20.sp, color = style.colors.primaryText)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("设计工坊", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = style.colors.primaryText)
                if (project.artStyle.isNotBlank()) {
                    Text("画风：${project.artStyle}", fontSize = 13.sp, color = style.colors.secondaryText)
                }
            }
        }

        // Tab 切换
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = style.colors.pageBackground,
            contentColor = style.colors.accent
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            "$title (${when (index) {
                                0 -> characters.size
                                1 -> scenes.size
                                else -> project.props.size
                            }})",
                            fontSize = 14.sp
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> CharacterDesignList(
                characters = characters,
                generatingName = generatingName,
                style = style,
                onGenerateImage = { name ->
                    if (onGenerateCharacterImage != null) {
                        generatingName = name
                        scope.launch {
                            val url = withContext(Dispatchers.IO) {
                                onGenerateCharacterImage(name)
                            }
                            if (url != null) {
                                characters = characters.map {
                                    if (it.name == name) it.copy(generatedImageUrl = url) else it
                                }
                            }
                            generatingName = null
                        }
                    }
                }
            )
            1 -> SceneDesignList(
                scenes = scenes,
                generatingName = generatingName,
                style = style,
                onGenerateImage = { name ->
                    if (onGenerateSceneImage != null) {
                        generatingName = name
                        scope.launch {
                            val url = withContext(Dispatchers.IO) {
                                onGenerateSceneImage(name)
                            }
                            if (url != null) {
                                scenes = scenes.map {
                                    if (it.name == name) it.copy(generatedImageUrl = url) else it
                                }
                            }
                            generatingName = null
                        }
                    }
                }
            )
            2 -> PropDesignList(props = project.props, style = style)
        }
    }
}

@Composable
private fun CharacterDesignList(
    characters: List<AiCharacterDesignService.CharacterDesign>,
    generatingName: String?,
    style: AiComposeStyle,
    onGenerateImage: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(characters) { char ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(style.metrics.cardRadius),
                colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    // 角色头像
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(style.colors.stroke),
                        contentAlignment = Alignment.Center
                    ) {
                        if (char.generatedImageUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(char.generatedImageUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = char.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else if (generatingName == char.name) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = style.colors.accent,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("生成中...", fontSize = 10.sp, color = style.colors.secondaryText)
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(char.name.take(2), fontSize = 24.sp, color = style.colors.secondaryText)
                                Spacer(Modifier.height(4.dp))
                                TextButton(
                                    onClick = { onGenerateImage(char.name) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("生成形象", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    // 角色信息
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(char.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = style.colors.primaryText)
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = style.colors.accent.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    char.role,
                                    fontSize = 11.sp,
                                    color = style.colors.accent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text("${char.gender} · ${char.age}", fontSize = 12.sp, color = style.colors.secondaryText)
                        if (char.identity.isNotBlank()) {
                            Text(char.identity, fontSize = 12.sp, color = style.colors.secondaryText)
                        }
                        if (char.personality.isNotBlank()) {
                            Text(char.personality.take(60), fontSize = 12.sp, color = style.colors.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (char.relationships.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                char.relationships.joinToString(" · ") { "${it.targetName}(${it.relation})" },
                                fontSize = 11.sp,
                                color = style.colors.accent,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 展开详情
                var expanded by remember { mutableStateOf(false) }
                if (expanded) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                        if (char.appearance.isNotBlank()) {
                            DetailSection("外貌", char.appearance, style)
                        }
                        if (char.skills.isNotBlank()) {
                            DetailSection("能力", char.skills, style)
                        }
                        if (char.biography.isNotBlank()) {
                            DetailSection("背景", char.biography, style)
                        }
                        if (char.imagePrompt.isNotBlank()) {
                            DetailSection("绘画提示词", char.imagePrompt, style)
                        }
                        if (char.consistencyTags.isNotEmpty()) {
                            DetailSection("一致性标签", char.consistencyTags.joinToString(", "), style)
                        }
                    }
                }

                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(if (expanded) "收起" else "展开详情", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SceneDesignList(
    scenes: List<AiCharacterDesignService.SceneDesign>,
    generatingName: String?,
    style: AiComposeStyle,
    onGenerateImage: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(scenes) { scene ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(style.metrics.cardRadius),
                colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // 场景概念图
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(style.colors.stroke),
                        contentAlignment = Alignment.Center
                    ) {
                        if (scene.generatedImageUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(scene.generatedImageUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = scene.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else if (generatingName == scene.name) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = style.colors.accent,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("生成中...", fontSize = 12.sp, color = style.colors.secondaryText)
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🏞", fontSize = 28.sp)
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = { onGenerateImage(scene.name) }) {
                                    Text("生成概念图")
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(scene.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = style.colors.primaryText)
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = style.colors.accent.copy(alpha = 0.15f)
                        ) {
                            Text(
                                scene.type,
                                fontSize = 11.sp,
                                color = style.colors.accent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(scene.description, fontSize = 13.sp, color = style.colors.primaryText)
                    Text("氛围：${scene.atmosphere}", fontSize = 12.sp, color = style.colors.secondaryText)

                    if (scene.keyElements.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(scene.keyElements) { element ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = style.colors.stroke
                                ) {
                                    Text(
                                        element,
                                        fontSize = 11.sp,
                                        color = style.colors.secondaryText,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropDesignList(
    props: List<AiCharacterDesignService.PropDesign>,
    style: AiComposeStyle
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(props) { prop ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(style.metrics.cardRadius),
                colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
            ) {
                Row(modifier = Modifier.padding(14.dp)) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(style.colors.accent.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (prop.generatedImageUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(prop.generatedImageUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = prop.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text("🗡", fontSize = 24.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(prop.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = style.colors.primaryText)
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = style.colors.accent.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    prop.type,
                                    fontSize = 10.sp,
                                    color = style.colors.accent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(prop.description, fontSize = 13.sp, color = style.colors.primaryText)
                        if (prop.significance.isNotBlank()) {
                            Text("重要性：${prop.significance}", fontSize = 12.sp, color = style.colors.secondaryText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: String, style: AiComposeStyle) {
    Spacer(Modifier.height(8.dp))
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = style.colors.accent)
    Text(content, fontSize = 13.sp, color = style.colors.primaryText)
}