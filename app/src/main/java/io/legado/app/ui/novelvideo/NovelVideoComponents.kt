package io.legado.app.ui.novelvideo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.data.entities.NovelVideoSegmentStatus

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
