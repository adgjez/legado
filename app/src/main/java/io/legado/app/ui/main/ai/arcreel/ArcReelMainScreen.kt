package io.legado.app.ui.main.ai.arcreel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.legado.app.help.ai.*
import io.legado.app.ui.main.ai.compose.AiComposeStyle
import io.legado.app.ui.main.ai.compose.aiComposeStyle
import kotlinx.coroutines.launch

/**
 * ArcReel 主界面 — 项目管理 + 管道进度
 */
@Composable
fun ArcReelMainScreen(
    project: ArcReelProject,
    onBack: () -> Unit,
    onStartPipeline: suspend (ArcReelProject) -> ArcReelProject,
    onViewStoryboard: (ChapterStoryboard) -> Unit,
    onViewCharacters: () -> Unit,
    onViewGallery: () -> Unit
) {
    val context = LocalContext.current
    val style = aiComposeStyle(context)
    val scope = rememberCoroutineScope()
    val pipelineState by ArcReelPipeline.state.collectAsState()
    var currentProject by remember { mutableStateOf(project) }
    var isRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.colors.pageBackground)
            .statusBarsPadding()
    ) {
        // 顶部栏
        ArcReelTopBar(
            title = currentProject.name,
            onBack = onBack,
            style = style
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 项目信息卡片
            item {
                ProjectInfoCard(project = currentProject, style = style)
            }

            // 管道进度
            item {
                PipelineProgressCard(
                    state = pipelineState,
                    isRunning = isRunning,
                    style = style,
                    onStart = {
                        isRunning = true
                        scope.launch {
                            currentProject = onStartPipeline(currentProject)
                            isRunning = false
                        }
                    }
                )
            }

            // 角色列表
            if (currentProject.characters.isNotEmpty()) {
                item {
                    SectionHeader("角色设计", currentProject.characters.size, onClick = onViewCharacters, style = style)
                }
                item {
                    CharacterRow(characters = currentProject.characters, style = style)
                }
            }

            // 场景列表
            if (currentProject.scenes.isNotEmpty()) {
                item {
                    SectionHeader("场景设计", currentProject.scenes.size, style = style)
                }
                item {
                    SceneRow(scenes = currentProject.scenes, style = style)
                }
            }

            // 道具列表
            if (currentProject.props.isNotEmpty()) {
                item {
                    SectionHeader("道具设计", currentProject.props.size, style = style)
                }
                item {
                    PropRow(props = currentProject.props, style = style)
                }
            }

            // 分镜章节
            if (currentProject.storyboards.isNotEmpty()) {
                item {
                    SectionHeader("分镜剧本", currentProject.storyboards.size, style = style)
                }
                items(currentProject.storyboards) { chapter ->
                    StoryboardChapterCard(
                        chapter = chapter,
                        style = style,
                        onClick = { onViewStoryboard(chapter) }
                    )
                }
            }

            // 视频列表
            if (currentProject.videos.isNotEmpty()) {
                item {
                    SectionHeader("生成视频", currentProject.videos.size, style = style)
                }
                items(currentProject.videos) { video ->
                    VideoCard(video = video, style = style)
                }
            }

            // 图库入口
            item {
                GalleryEntryCard(onClick = onViewGallery, style = style)
            }

            // 底部间距
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ArcReelTopBar(title: String, onBack: () -> Unit, style: AiComposeStyle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Text("←", fontSize = 20.sp, color = style.colors.primaryText)
        }
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = style.colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ProjectInfoCard(project: ArcReelProject, style: AiComposeStyle) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.metrics.cardRadius),
        colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                project.bookName.ifBlank { project.name },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = style.colors.primaryText
            )
            if (project.author.isNotBlank()) {
                Text(
                    project.author,
                    fontSize = 14.sp,
                    color = style.colors.secondaryText
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatChip("角色", project.characters.size, style)
                StatChip("场景", project.scenes.size, style)
                StatChip("道具", project.props.size, style)
                StatChip("分镜", project.storyboards.size, style)
                StatChip("视频", project.videos.size, style)
            }
            if (project.worldStyle.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "世界观：${project.worldStyle}",
                    fontSize = 13.sp,
                    color = style.colors.secondaryText
                )
            }
            if (project.artStyle.isNotBlank()) {
                Text(
                    "画风：${project.artStyle}",
                    fontSize = 13.sp,
                    color = style.colors.secondaryText
                )
            }
        }
    }
}

@Composable
private fun PipelineProgressCard(
    state: PipelineState,
    isRunning: Boolean,
    style: AiComposeStyle,
    onStart: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.metrics.cardRadius),
        colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("管道进度", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = style.colors.primaryText)
                if (!isRunning) {
                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent),
                        shape = RoundedCornerShape(style.metrics.chipRadius)
                    ) {
                        Text("开始生成", color = Color.White)
                    }
                }
            }

            AnimatedVisibility(visible = isRunning || state.progress > 0f) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = style.colors.accent,
                        trackColor = style.colors.stroke
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.message.ifBlank { phaseLabel(state.phase) },
                        fontSize = 13.sp,
                        color = style.colors.secondaryText
                    )
                }
            }

            // 阶段指示器
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PhaseDot("角色", state.phase.ordinal >= ArcReelProject.PipelinePhase.EXTRACTING_CHARACTERS.ordinal, style)
                PhaseDot("场景", state.phase.ordinal >= ArcReelProject.PipelinePhase.EXTRACTING_SCENES.ordinal, style)
                PhaseDot("道具", state.phase.ordinal >= ArcReelProject.PipelinePhase.EXTRACTING_PROPS.ordinal, style)
                PhaseDot("形象", state.phase.ordinal >= ArcReelProject.PipelinePhase.GENERATING_CHARACTER_IMAGES.ordinal, style)
                PhaseDot("概念", state.phase.ordinal >= ArcReelProject.PipelinePhase.GENERATING_SCENE_IMAGES.ordinal, style)
                PhaseDot("分镜", state.phase.ordinal >= ArcReelProject.PipelinePhase.GENERATING_STORYBOARD.ordinal, style)
                PhaseDot("视频", state.phase.ordinal >= ArcReelProject.PipelinePhase.GENERATING_VIDEO.ordinal, style)
            }
        }
    }
}

@Composable
private fun PhaseDot(label: String, active: Boolean, style: AiComposeStyle) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (active) style.colors.accent else style.colors.stroke)
        )
        Text(label, fontSize = 10.sp, color = if (active) style.colors.accent else style.colors.secondaryText)
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int = 0,
    onClick: (() -> Unit)? = null,
    style: AiComposeStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$title${if (count > 0) " ($count)" else ""}",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = style.colors.primaryText
        )
        if (onClick != null) {
            Text("查看全部 →", fontSize = 13.sp, color = style.colors.accent)
        }
    }
}

@Composable
private fun CharacterRow(
    characters: List<AiCharacterDesignService.CharacterDesign>,
    style: AiComposeStyle
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(characters) { char ->
            Card(
                modifier = Modifier.width(120.dp),
                shape = RoundedCornerShape(style.metrics.cardRadius),
                colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 角色头像
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(8.dp))
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
                        } else {
                            Text(char.name.take(1), fontSize = 28.sp, color = style.colors.secondaryText)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(char.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = style.colors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(char.role, fontSize = 11.sp, color = style.colors.secondaryText)
                }
            }
        }
    }
}

@Composable
private fun SceneRow(
    scenes: List<AiCharacterDesignService.SceneDesign>,
    style: AiComposeStyle
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(scenes) { scene ->
            Card(
                modifier = Modifier.width(160.dp),
                shape = RoundedCornerShape(style.metrics.cardRadius),
                colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .clip(RoundedCornerShape(6.dp))
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
                        } else {
                            Text(scene.name.take(4), fontSize = 14.sp, color = style.colors.secondaryText)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(scene.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = style.colors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(scene.atmosphere, fontSize = 11.sp, color = style.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PropRow(
    props: List<AiCharacterDesignService.PropDesign>,
    style: AiComposeStyle
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(props) { prop ->
            Card(
                modifier = Modifier.width(140.dp),
                shape = RoundedCornerShape(style.metrics.cardRadius),
                colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(prop.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = style.colors.primaryText)
                    Text(prop.type, fontSize = 11.sp, color = style.colors.accent)
                    Text(prop.description.take(80), fontSize = 12.sp, color = style.colors.secondaryText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StoryboardChapterCard(
    chapter: ChapterStoryboard,
    style: AiComposeStyle,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.metrics.cardRadius),
        colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(chapter.chapterTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = style.colors.primaryText)
                Text("${chapter.result.scenes.size} 个场景 · ${chapter.result.summary.take(60)}", fontSize = 13.sp, color = style.colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (chapter.sceneImages.isNotEmpty()) {
                    Text("${chapter.sceneImages.size} 张插画", fontSize = 12.sp, color = style.colors.accent)
                }
            }
            Text("→", fontSize = 18.sp, color = style.colors.secondaryText)
        }
    }
}

@Composable
private fun VideoCard(video: VideoOutput, style: AiComposeStyle) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.metrics.cardRadius),
        colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(style.colors.accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("▶", fontSize = 20.sp, color = style.colors.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("场景${video.sceneId}: ${video.sceneTitle}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = style.colors.primaryText)
                Text(video.videoUrl.take(60), fontSize = 12.sp, color = style.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GalleryEntryCard(onClick: () -> Unit, style: AiComposeStyle) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.metrics.cardRadius),
        colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🖼", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("场景图库", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = style.colors.primaryText)
                Text("查看所有已生成的场景图、角色图、道具图", fontSize = 13.sp, color = style.colors.secondaryText)
            }
            Text("→", fontSize = 18.sp, color = style.colors.secondaryText)
        }
    }
}

@Composable
private fun StatChip(label: String, value: Int, style: AiComposeStyle) {
    Surface(
        shape = RoundedCornerShape(style.metrics.chipRadius),
        color = style.colors.accent.copy(alpha = 0.1f)
    ) {
        Text(
            "$label $value",
            fontSize = 12.sp,
            color = style.colors.accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun phaseLabel(phase: ArcReelProject.PipelinePhase): String = when (phase) {
    ArcReelProject.PipelinePhase.IDLE -> "就绪"
    ArcReelProject.PipelinePhase.EXTRACTING_CHARACTERS -> "正在提取角色..."
    ArcReelProject.PipelinePhase.EXTRACTING_SCENES -> "正在提取场景..."
    ArcReelProject.PipelinePhase.EXTRACTING_PROPS -> "正在提取道具..."
    ArcReelProject.PipelinePhase.GENERATING_CHARACTER_IMAGES -> "正在生成角色形象图..."
    ArcReelProject.PipelinePhase.GENERATING_SCENE_IMAGES -> "正在生成场景概念图..."
    ArcReelProject.PipelinePhase.GENERATING_STORYBOARD -> "正在生成分镜..."
    ArcReelProject.PipelinePhase.GENERATING_SCENE_SHOTS -> "正在生成场景插画..."
    ArcReelProject.PipelinePhase.GENERATING_VIDEO -> "正在生成视频..."
    ArcReelProject.PipelinePhase.COMPLETED -> "完成"
}