package io.legado.app.ui.novelvideo

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.data.entities.NovelVideoSegmentStatus
import io.legado.app.help.ai.BookNovelVideoSummary
import io.legado.app.help.ai.ChapterStatus
import io.legado.app.ui.widget.compose.BookCoverImage

/**
 * 状态徽章：根据 job/segment 状态显示对应的色块和文案。
 */
@Composable
fun StatusBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    val (text, color) = statusBadgeContent(status)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun statusBadgeContent(status: String): Pair<String, Color> {
    val palette = MaterialTheme.colorScheme
    return when (status) {
        NovelVideoJobStatus.DRAFTING -> "草稿中" to palette.tertiary
        NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW -> "待审阅" to palette.tertiary
        NovelVideoJobStatus.SCREENPLAY_CONFIRMED -> "已确认" to palette.tertiary
        NovelVideoJobStatus.GENERATING -> "生成中" to palette.primary
        NovelVideoJobStatus.MERGING -> "合并中" to palette.primary
        NovelVideoJobStatus.COMPLETED -> "已完成" to palette.primary
        NovelVideoJobStatus.PARTIAL_FAILED -> "部分失败" to palette.error
        NovelVideoJobStatus.FAILED -> "失败" to palette.error
        NovelVideoJobStatus.CANCELLED -> "已取消" to palette.outline
        NovelVideoJobStatus.PAUSED -> "已暂停" to palette.outline
        NovelVideoSegmentStatus.PENDING -> "待处理" to palette.outline
        NovelVideoSegmentStatus.IMAGE_GENERATING -> "生图中" to palette.primary
        NovelVideoSegmentStatus.IMAGE_COMPLETED -> "图就绪" to palette.tertiary
        NovelVideoSegmentStatus.VIDEO_GENERATING -> "生视频中" to palette.primary
        NovelVideoSegmentStatus.VIDEO_COMPLETED -> "已生成" to palette.primary
        NovelVideoSegmentStatus.FAILED -> "失败" to palette.error
        else -> status to palette.outline
    }
}

/** 列表项的统一 padding，与 AiImageProviderManageScreen 一致。 */
val novelVideoItemPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)

/** 卡片背景形状。 */
val novelVideoCardShape = RoundedCornerShape(12.dp)

/** 让 Box 撑满宽度并保留底部留白的小工具。 */
@Composable
fun DividerBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {}
}

// ============================================================
// 子项目 C：书架卡片 + 章节覆盖图
// ============================================================

/**
 * 书架卡片：展示一本书的 novel-video 聚合进度（spec §6.1）。
 *
 * 结构：封面 | 书名 + X/Y 章 · 进度% + 进度条 + N 个任务 · M 部整部视频
 */
@Composable
internal fun BookShelfCard(
    summary: BookNovelVideoSummary,
    onClick: () -> Unit
) {
    val palette = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = novelVideoCardShape,
        colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面（coverPath 可能为 null，BookCoverImage 显示默认占位）
            BookCoverImage(
                path = summary.coverPath,
                name = summary.bookName.ifBlank { "未命名" },
                author = null,
                sourceOrigin = null,
                modifier = Modifier
                    .width(48.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.bookName.ifBlank { "未命名" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val progressText = if (summary.totalChapters > 0) {
                    "${summary.coveredCount}/${summary.totalChapters} 章 · 进度 ${(summary.progress * 100).toInt()}%"
                } else {
                    "${summary.coveredCount} 章 · 总数未知"
                }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { summary.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${summary.jobCount} 个任务 · ${summary.compilationCount} 部整部视频",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 章节覆盖图：色块网格一屏看完整本书进度（spec §6.3，方案 B1）。
 *
 * 每章一个色块，颜色表状态：灰=未做 / 蓝=进行中 / 绿=已完成 / 红=失败。
 * 点击色块回调；totalChapters=0 显示「章节信息未加载」占位。
 *
 * @param statusByChapter 由 [BookNovelVideoSummary.statusByChapter] 提供
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChapterCoverageGrid(
    totalChapters: Int,
    statusByChapter: Map<Int, ChapterStatus>,
    modifier: Modifier = Modifier,
    onChapterClick: (Int) -> Unit = {},
    onChapterLongClick: (Int) -> Unit = {}
) {
    val palette = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (totalChapters <= 0) {
            Text(
                text = "章节信息未加载",
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            return@Column
        }
        // 用 FlowRow 替代 LazyVerticalGrid：避免在 LazyColumn 内同方向嵌套 Lazy 组件导致滚动冲突。
        // 色块仅 28dp 小方块，非 lazy 渲染对千级章节也能接受；自然展开不限制高度，一屏看全。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            for (index in 0 until totalChapters) {
                val status = statusByChapter[index] ?: ChapterStatus.NONE
                val color = when (status) {
                    ChapterStatus.NONE -> palette.surfaceVariant
                    ChapterStatus.RUNNING -> palette.primary
                    ChapterStatus.COMPLETED -> Color(0xFF4CAF50)
                    ChapterStatus.FAILED -> palette.error
                }
                val statusLabel = when (status) {
                    ChapterStatus.NONE -> "未做"
                    ChapterStatus.RUNNING -> "进行中"
                    ChapterStatus.COMPLETED -> "已完成"
                    ChapterStatus.FAILED -> "失败"
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                        .combinedClickable(
                            onClick = { onChapterClick(index) },
                            onLongClick = { onChapterLongClick(index) },
                            onClickLabel = "第${index + 1}章 $statusLabel"
                        )
                        .semantics { contentDescription = "第${index + 1}章：$statusLabel" }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LegendDot(palette.surfaceVariant, "未做")
            LegendDot(palette.primary, "进行中")
            LegendDot(Color(0xFF4CAF50), "已完成")
            LegendDot(palette.error, "失败")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    val palette = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.onSurfaceVariant
        )
    }
}
