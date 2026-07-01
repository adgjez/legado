package io.legado.app.ui.main.ai.arcreel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.ai.ArcReelProject
import io.legado.app.help.ai.ArcReelProjectRepository
import io.legado.app.ui.main.ai.compose.aiComposeStyle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ArcReelProjectListScreen(
    onBack: () -> Unit,
    onOpenProject: (ArcReelProject) -> Unit,
    onNewProject: () -> Unit
) {
    val context = LocalContext.current
    val style = aiComposeStyle(context)
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ArcReelProject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf<ArcReelProject?>(null) }

    LaunchedEffect(Unit) {
        ArcReelProjectRepository.listProjects().onSuccess {
            projects = it
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.colors.pageBackground)
            .statusBarsPadding()
    ) {
        // Top bar
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
                "我的项目",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = style.colors.primaryText,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onNewProject) {
                Text("新建", color = style.colors.accent)
            }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = style.colors.accent)
            }
        } else if (projects.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无项目", fontSize = 16.sp, color = style.colors.secondaryText)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onNewProject,
                        colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent),
                        shape = RoundedCornerShape(style.metrics.chipRadius)
                    ) {
                        Text("新建项目", color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects) { project ->
                    ProjectCard(
                        project = project,
                        style = style,
                        onClick = { onOpenProject(project) },
                        onDelete = { showDeleteDialog = project }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { project ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除项目") },
            text = { Text("确认删除「${project.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        ArcReelProjectRepository.deleteProject(project.id)
                        ArcReelProjectRepository.listProjects().onSuccess { projects = it }
                    }
                    showDeleteDialog = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ProjectCard(
    project: ArcReelProject,
    style: io.legado.app.ui.main.ai.compose.AiComposeStyle,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = style.colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (project.bookName.isNotBlank()) {
                    Text(
                        project.bookName,
                        fontSize = 13.sp,
                        color = style.colors.secondaryText
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${project.characters.size} 角色",
                        fontSize = 12.sp,
                        color = style.colors.accent
                    )
                    Text(
                        "${project.storyboards.size} 分镜",
                        fontSize = 12.sp,
                        color = style.colors.accent
                    )
                    Text(
                        statusLabel(project.status),
                        fontSize = 12.sp,
                        color = style.colors.secondaryText
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    dateFormat.format(Date(project.updatedAt)),
                    fontSize = 11.sp,
                    color = style.colors.secondaryText
                )
            }
            IconButton(onClick = onDelete) {
                Text("🗑", fontSize = 16.sp)
            }
        }
    }
}

private fun statusLabel(status: ArcReelProject.ProjectStatus): String = when (status) {
    ArcReelProject.ProjectStatus.DRAFT -> "草稿"
    ArcReelProject.ProjectStatus.DESIGNING -> "设计中"
    ArcReelProject.ProjectStatus.STORYBOARDING -> "分镜中"
    ArcReelProject.ProjectStatus.GENERATING -> "生成中"
    ArcReelProject.ProjectStatus.REVIEWING -> "审核中"
    ArcReelProject.ProjectStatus.COMPLETED -> "已完成"
}