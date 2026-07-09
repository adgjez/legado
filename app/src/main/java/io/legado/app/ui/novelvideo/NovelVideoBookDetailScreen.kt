package io.legado.app.ui.novelvideo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.entities.NovelVideoCompilation
import io.legado.app.data.entities.NovelVideoJob

/**
 * 书详情页（子项目 C）：聚合展示一本书的章节覆盖 + 任务 + 整部视频。
 *
 * @param onBack 返回
 * @param onOpenJobDetail 点击任务卡 → 打开任务详情
 * @param onNewTask FAB 新建任务（在书的语境里，预填 bookUrl）
 * @param onOpenCompilation 打开整部视频
 * @param onShareCompilation 分享整部视频
 * @param onDeleteCompilation 删除整部视频（已含二次确认）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelVideoBookDetailScreen(
    viewModel: NovelVideoBookDetailViewModel,
    bookName: String,
    onBack: () -> Unit,
    onOpenJobDetail: (NovelVideoJob) -> Unit,
    onNewTask: () -> Unit,
    onOpenCompilation: (NovelVideoCompilation) -> Unit,
    onShareCompilation: (NovelVideoCompilation) -> Unit,
    onDeleteCompilation: (NovelVideoCompilation) -> Unit
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val compilations by viewModel.compilations.collectAsStateWithLifecycle()
    val chapterTitles by viewModel.chapterTitles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDeleteCompilation by remember { mutableStateOf<NovelVideoCompilation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bookName, maxLines = 1) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(io.legado.app.R.drawable.ic_arrow_back),
                            contentDescription = null
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewTask,
                icon = { Icon(painter = androidx.compose.ui.res.painterResource(io.legado.app.R.drawable.ic_add), contentDescription = null) },
                text = { Text("新建任务") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 概要卡
            item {
                SummaryCard(summary)
            }

            // 章节覆盖图
            item {
                Text(
                    text = "章节覆盖",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                )
                ChapterCoverageGrid(
                    totalChapters = summary?.totalChapters ?: 0,
                    statusByChapter = summary?.statusByChapter() ?: emptyMap(),
                    onChapterClick = { idx ->
                        val jobAtChapter = jobs.firstOrNull { idx in it.chapterStartIndex..it.chapterEndIndex }
                        if (jobAtChapter != null) {
                            onOpenJobDetail(jobAtChapter)
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "第${idx + 1}章尚未建任务",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onChapterLongClick = { idx ->
                        viewModel.loadChapterTitle(summary?.bookUrl ?: "", idx)
                        val title = chapterTitles[idx]
                        if (title != null) {
                            android.widget.Toast.makeText(
                                context,
                                "第${idx + 1}章：$title",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

            // 任务 Section
            item {
                Text(
                    text = "任务 (${jobs.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
                HorizontalDivider()
            }
            if (jobs.isEmpty()) {
                item {
                    Text(
                        text = "这本书还没有任务，点右下角新建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(jobs, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        onClick = { onOpenJobDetail(job) },
                        onLongClick = { /* 书详情页不做多选 */ }
                    )
                }
            }

            // 整部视频 Section
            item {
                Text(
                    text = "整部视频 (${compilations.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
                HorizontalDivider()
            }
            if (compilations.isNotEmpty()) {
                items(compilations, key = { it.id }) { c ->
                    CompilationCard(
                        c = c,
                        onOpen = { onOpenCompilation(c) },
                        onShare = { onShareCompilation(c) },
                        onDelete = { pendingDeleteCompilation = c }
                    )
                }
            }
        }
    }

    // 删除整部视频二次确认
    pendingDeleteCompilation?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDeleteCompilation = null },
            title = { Text("删除整部视频") },
            text = { Text("确定删除「${c.title.ifBlank { c.bookName }}」？文件和记录都会清除，不可恢复。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingDeleteCompilation = null
                    onDeleteCompilation(c)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDeleteCompilation = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SummaryCard(summary: io.legado.app.help.ai.BookNovelVideoSummary?) {
    if (summary == null) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = novelVideoCardShape,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = summary.bookName.ifBlank { "未命名" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            val progressText = if (summary.totalChapters > 0) {
                "${summary.coveredCount}/${summary.totalChapters} 章 · 进度 ${(summary.progress * 100).toInt()}%"
            } else {
                "${summary.coveredCount} 章 · 总数未知"
            }
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { summary.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${summary.jobCount} 个任务 · ${summary.compilationCount} 部整部视频",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
