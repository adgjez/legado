package io.legado.app.ui.novelvideo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.entities.NovelVideoCharacterSheet
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.data.entities.NovelVideoSegment
import io.legado.app.data.entities.NovelVideoSegmentStatus
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 任务详情主屏幕。
 *
 * 结构：
 * - TopAppBar：返回 + 任务名 + overflow 菜单（重试/取消/删除/审阅）
 * - 概要卡片：状态、章节范围、创建时间、错误、进度条
 * - 输出视频卡片：当 outputPath 不空时显示「打开/分享」按钮
 * - 角色三视图列表
 * - 分镜段列表（含状态徽章、缩略、错误信息）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelVideoJobDetailScreen(
    viewModel: NovelVideoJobDetailViewModel,
    onBack: () -> Unit,
    onOpenReview: (NovelVideoJob) -> Unit
) {
    val job by viewModel.job.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val params by viewModel.params.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showOverflow by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var cancelConfirm by remember { mutableStateOf(false) }

    val currentJob = job
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentJob?.bookName ?: stringResource(R.string.novel_video_job_detail),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false }
                    ) {
                        if (currentJob?.status == NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.novel_video_screenplay_review)) },
                                onClick = {
                                    currentJob.let(onOpenReview)
                                    showOverflow = false
                                }
                            )
                        }
                        if (currentJob?.status == NovelVideoJobStatus.FAILED ||
                            currentJob?.status == NovelVideoJobStatus.PARTIAL_FAILED ||
                            currentJob?.status == NovelVideoJobStatus.CANCELLED
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.novel_video_retry_job)) },
                                onClick = {
                                    scope.launch { viewModel.retryJob(currentJob.id) }
                                    showOverflow = false
                                }
                            )
                        }
                        if (currentJob?.status in NovelVideoJobStatus.RUNNING_STATES) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.novel_video_cancel_job)) },
                                onClick = {
                                    cancelConfirm = true
                                    showOverflow = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.novel_video_delete_job)) },
                            onClick = {
                                deleteConfirm = true
                                showOverflow = false
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (currentJob == null) {
            // M11：区分"任务不存在"与"无分镜段"，原复用 novel_video_no_segments 文案误导用户
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.novel_video_job_not_found),
                    color = MaterialTheme.colorScheme.outline
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 概要卡片
            item { JobSummaryCard(currentJob, params.sceneCountPerChapter, segments.size) }

            // 输出视频
            currentJob.outputPath?.takeIf { it.isNotBlank() }?.let { path ->
                item {
                    OutputVideoCard(
                        job = currentJob,
                        onOpen = {
                            if (!viewModel.openOutputVideo(context, currentJob)) {
                                // 派发失败：路径无效或无可用应用
                            }
                        },
                        onShare = { viewModel.shareOutputVideo(context, currentJob) }
                    )
                }
            }

            // 角色三视图
            if (characters.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.novel_video_characters) + " (${characters.size})")
                }
                items(characters, key = { it.id }) { ch ->
                    CharacterSheetCard(ch)
                }
            }

            // 分镜段
            item {
                SectionHeader(stringResource(R.string.novel_video_segments) + " (${segments.size})")
            }
            if (segments.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.novel_video_no_segments),
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(segments, key = { it.id }) { seg ->
                    SegmentCard(seg)
                }
            }
        }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text(stringResource(R.string.novel_video_delete_job)) },
            text = { Text(stringResource(R.string.novel_video_confirm_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = currentJob?.id
                    deleteConfirm = false
                    id?.let {
                        scope.launch {
                            viewModel.deleteJob(it)
                            onBack()
                        }
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (cancelConfirm) {
        AlertDialog(
            onDismissRequest = { cancelConfirm = false },
            title = { Text(stringResource(R.string.novel_video_cancel_job)) },
            text = { Text(stringResource(R.string.novel_video_confirm_cancel)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = currentJob?.id
                    cancelConfirm = false
                    id?.let { scope.launch { viewModel.cancelJob(it) } }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { cancelConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun JobSummaryCard(job: NovelVideoJob, sceneCountPerChapter: Int, segmentTotal: Int) {
    Card(
        shape = novelVideoCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = job.bookName.ifBlank { job.id },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                StatusBadge(status = job.status)
            }
            Spacer(Modifier.size(6.dp))
            Text(
                text = formatChapterRange(job),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (segmentTotal > 0) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(
                        R.string.novel_video_progress_format,
                        segmentTotal,
                        sceneCountPerChapter.coerceAtLeast(segmentTotal)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (job.status == NovelVideoJobStatus.GENERATING ||
                job.status == NovelVideoJobStatus.MERGING ||
                job.status == NovelVideoJobStatus.DRAFTING
            ) {
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (job.errorMessage?.isNotBlank() == true) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = job.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                text = formatTime(job.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun OutputVideoCard(
    job: NovelVideoJob,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        shape = novelVideoCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.novel_video_completed),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            job.totalDurationMs?.let { ms ->
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.novel_video_duration_format, formatDuration(ms)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(painter = painterResource(R.drawable.ic_play_outline_24dp), contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.novel_video_open_video))
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(painter = painterResource(R.drawable.ic_share), contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.novel_video_share_video))
                }
            }
        }
    }
}

@Composable
private fun CharacterSheetCard(sheet: NovelVideoCharacterSheet) {
    Card(
        shape = novelVideoCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sheet.characterName.ifBlank { sheet.role },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(status = sheet.status)
            }
            if (sheet.description.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = sheet.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (sheet.errorMessage?.isNotBlank() == true) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = sheet.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SegmentCard(segment: NovelVideoSegment) {
    Card(
        shape = novelVideoCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.novel_video_scene_chapter_format,
                        segment.chapterIndex + 1,
                        segment.sceneId
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(status = segment.status)
            }
            if (segment.narration.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = segment.narration,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (segment.imagePrompt.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.novel_video_review_image_prompt) + ": " + segment.imagePrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (segment.status == NovelVideoSegmentStatus.FAILED && segment.errorMessage?.isNotBlank() == true) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = segment.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (segment.retryCount > 0) {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = stringResource(R.string.novel_video_segment_retry_count, segment.retryCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// ============================================================
// 工具
// ============================================================

// formatChapterRange / formatTime 复用 NovelVideoTaskCenterScreen.kt 的 internal 实现（Task 6 提升）

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d", m, s)
}
