package io.legado.app.ui.novelvideo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelVideoTaskCenterScreen(
    viewModel: NovelVideoTaskCenterViewModel,
    presetBookUrl: String?,
    presetBookName: String?,
    onBack: () -> Unit,
    onOpenJobDetail: (NovelVideoJob) -> Unit,
    onOpenReview: (NovelVideoJob) -> Unit
) {
    val runningJobs by viewModel.runningJobs.collectAsStateWithLifecycle()
    val completedJobs by viewModel.completedJobs.collectAsStateWithLifecycle()
    val failedJobs by viewModel.failedJobs.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.serviceRunning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showConfigSheet by rememberSaveable { mutableStateOf(false) }
    var menuJob by remember { mutableStateOf<NovelVideoJob?>(null) }
    var deleteConfirmJob by remember { mutableStateOf<NovelVideoJob?>(null) }
    var cancelConfirmJob by remember { mutableStateOf<NovelVideoJob?>(null) }
    var showOverflow by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.novel_video_task_center)) },
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
                        if (serviceRunning) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.novel_video_stop_service)) },
                                onClick = {
                                    viewModel.stopService()
                                    showOverflow = false
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.novel_video_start_service)) },
                                onClick = {
                                    viewModel.startService()
                                    showOverflow = false
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showConfigSheet = true }) {
                Icon(painter = painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.novel_video_new_task))
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(stringResource(R.string.novel_video_tab_running) + " (${runningJobs.size})")
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(stringResource(R.string.novel_video_tab_completed) + " (${completedJobs.size})")
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(stringResource(R.string.novel_video_tab_failed) + " (${failedJobs.size})")
                    }
                )
            }

            val list = when (selectedTab) {
                0 -> runningJobs
                1 -> completedJobs
                else -> failedJobs
            }
            val emptyText = when (selectedTab) {
                0 -> R.string.novel_video_empty_running
                1 -> R.string.novel_video_empty_completed
                else -> R.string.novel_video_empty_failed
            }

            if (list.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(emptyText),
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(list, key = { it.id }) { job ->
                        JobCard(
                            job = job,
                            onClick = { onOpenJobDetail(job) },
                            onLongClick = { menuJob = job }
                        )
                    }
                }
            }
        }

        // 新建任务 bottom sheet
        if (showConfigSheet) {
            NovelVideoJobConfigSheet(
                viewModel = viewModel,
                presetBookUrl = presetBookUrl,
                presetBookName = presetBookName,
                onDismiss = { showConfigSheet = false },
                onCreated = { showConfigSheet = false }
            )
        }

        // 长按操作菜单
        menuJob?.let { job ->
            JobActionSheet(
                job = job,
                serviceRunning = serviceRunning,
                onDismiss = { menuJob = null },
                onOpenDetail = { onOpenJobDetail(job); menuJob = null },
                onOpenReview = { onOpenReview(job); menuJob = null },
                onRetry = { scope.launch { viewModel.retryJob(job.id) }; menuJob = null },
                onCancel = { cancelConfirmJob = job; menuJob = null },
                onDelete = { deleteConfirmJob = job; menuJob = null }
            )
        }

        deleteConfirmJob?.let { job ->
            AlertDialog(
                onDismissRequest = { deleteConfirmJob = null },
                title = { Text(stringResource(R.string.novel_video_delete_job)) },
                text = { Text(stringResource(R.string.novel_video_confirm_delete)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch { viewModel.deleteJob(job.id) }
                        deleteConfirmJob = null
                    }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { deleteConfirmJob = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        cancelConfirmJob?.let { job ->
            AlertDialog(
                onDismissRequest = { cancelConfirmJob = null },
                title = { Text(stringResource(R.string.novel_video_cancel_job)) },
                text = { Text(stringResource(R.string.novel_video_confirm_cancel)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch { viewModel.cancelJob(job.id) }
                        cancelConfirmJob = null
                    }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { cancelConfirmJob = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun JobCard(
    job: NovelVideoJob,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clickable(onClick = onLongClick),  // 简化：单击触发长按菜单；正式版可改 combinedClickable
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
            Spacer(Modifier.size(4.dp))
            Text(
                text = formatChapterRange(job),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (job.status == NovelVideoJobStatus.GENERATING
                || job.status == NovelVideoJobStatus.MERGING
                || job.status == NovelVideoJobStatus.DRAFTING
            ) {
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (job.errorMessage?.isNotBlank() == true) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = job.errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = formatTime(job.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobActionSheet(
    job: NovelVideoJob,
    serviceRunning: Boolean,
    onDismiss: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenReview: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            ActionItem(stringResource(R.string.novel_video_job_detail), onClick = onOpenDetail)
            if (job.status == NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW) {
                ActionItem(stringResource(R.string.novel_video_screenplay_review), onClick = onOpenReview)
            }
            if (job.status == NovelVideoJobStatus.FAILED
                || job.status == NovelVideoJobStatus.PARTIAL_FAILED
                || job.status == NovelVideoJobStatus.CANCELLED
            ) {
                ActionItem(stringResource(R.string.novel_video_retry_job), onClick = onRetry)
            }
            if (job.status in NovelVideoJobStatus.RUNNING_STATES) {
                ActionItem(stringResource(R.string.novel_video_cancel_job), onClick = onCancel)
            }
            ActionItem(
                text = stringResource(R.string.novel_video_delete_job),
                danger = true,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun ActionItem(
    text: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

// ============================================================
// 工具
// ============================================================

private fun formatChapterRange(job: NovelVideoJob): String {
    if (job.chapterStartIndex < 0 || job.chapterEndIndex < 0) return job.id
    return runCatching {
        val titles = GSON.fromJsonArray<String>(job.chapterTitlesJson).getOrDefault(emptyList())
        val start = titles.firstOrNull() ?: "第${job.chapterStartIndex + 1}章"
        val end = titles.lastOrNull() ?: "第${job.chapterEndIndex + 1}章"
        if (start == end) start else "$start → $end"
    }.getOrDefault("第${job.chapterStartIndex + 1}章 → 第${job.chapterEndIndex + 1}章")
}

private fun formatTime(ts: Long): String {
    if (ts <= 0) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
