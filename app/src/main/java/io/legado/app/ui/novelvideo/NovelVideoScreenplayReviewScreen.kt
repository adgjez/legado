package io.legado.app.ui.novelvideo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.help.ai.Scene

/**
 * 剧本审阅主屏幕。
 *
 * 结构：
 * - TopAppBar：返回 + 标题 + 状态徽章
 * - 顶部提示文案
 * - 标题输入框
 * - 场景列表（每个 Scene 一张卡片，可编辑 narration/imagePrompt/videoPrompt/characterDescription）
 * - 底部固定操作栏：新增场景 / 取消 / 确认生成
 *
 * 仅当 job.status == [NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW] 时可编辑；
 * 其他状态（已确认/已完成）只读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelVideoScreenplayReviewScreen(
    viewModel: NovelVideoScreenplayReviewViewModel,
    onBack: () -> Unit
) {
    val job by viewModel.job.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()

    val editable = job?.status == NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW
    var cancelConfirm by remember { mutableStateOf(false) }
    var addSceneDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.novel_video_screenplay_review)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = null)
                    }
                }
            )
        },
        bottomBar = {
            if (editable && draft != null) {
                ReviewBottomBar(
                    submitting = submitting,
                    onAddScene = { addSceneDialog = true },
                    onCancel = { cancelConfirm = true },
                    onConfirm = { viewModel.confirm(onBack) }
                )
            }
        }
    ) { padding ->
        val curDraft = draft
        if (curDraft == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.novel_video_review_no_draft),
                    color = MaterialTheme.colorScheme.outline
                )
            }
            return@Scaffold
        }

        val statusText = job?.status?.let { describeReviewStatus(it) }
        if (statusText != null && !editable) {
            // 顶部只读提示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.novel_video_review_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = curDraft.displayTitle,
                    onValueChange = { viewModel.updateTitle(it) },
                    label = { Text(stringResource(R.string.novel_video_review_title_label)) },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    text = stringResource(R.string.novel_video_review_total_scenes, curDraft.scenes.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }
            items(curDraft.scenes, key = { it.sceneId }) { scene ->
                SceneEditCard(
                    scene = scene,
                    editable = editable,
                    onUpdate = { newScene -> viewModel.updateScene(scene.sceneId) { newScene } },
                    onDelete = { viewModel.deleteScene(scene.sceneId) }
                )
            }
        }
    }

    if (cancelConfirm) {
        AlertDialog(
            onDismissRequest = { cancelConfirm = false },
            title = { Text(stringResource(R.string.novel_video_cancel_job)) },
            text = { Text(stringResource(R.string.novel_video_confirm_cancel)) },
            confirmButton = {
                TextButton(onClick = {
                    cancelConfirm = false
                    viewModel.cancel(onBack)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { cancelConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (addSceneDialog) {
        // 直接添加一个空白场景，不开弹窗（简化交互）
        addSceneDialog = false
        viewModel.addScene()
    }
}

@Composable
private fun ReviewBottomBar(
    submitting: Boolean,
    onAddScene: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onAddScene, enabled = !submitting) {
                Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.novel_video_review_add_scene))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel, enabled = !submitting) {
                Text(stringResource(R.string.novel_video_review_cancel_edit))
            }
            Button(
                onClick = onConfirm,
                enabled = !submitting
            ) {
                Text(
                    if (submitting) stringResource(R.string.novel_video_review_confirming)
                    else stringResource(R.string.novel_video_review_confirm)
                )
            }
        }
    }
}

@Composable
private fun SceneEditCard(
    scene: Scene,
    editable: Boolean,
    onUpdate: (Scene) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.novel_video_scene_n, scene.sceneId),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (editable) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            painter = painterResource(R.drawable.ic_outline_delete),
                            contentDescription = stringResource(R.string.novel_video_review_delete_scene),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            LabeledTextField(
                label = stringResource(R.string.novel_video_review_narration),
                value = scene.narration,
                enabled = editable,
                minLines = 2,
                onValueChange = { v -> onUpdate(scene.copy(narration = v)) }
            )
            Spacer(Modifier.size(6.dp))
            LabeledTextField(
                label = stringResource(R.string.novel_video_review_image_prompt),
                value = scene.imagePrompt,
                enabled = editable,
                minLines = 2,
                onValueChange = { v -> onUpdate(scene.copy(imagePrompt = v)) }
            )
            Spacer(Modifier.size(6.dp))
            LabeledTextField(
                label = stringResource(R.string.novel_video_review_video_prompt),
                value = scene.videoPrompt,
                enabled = editable,
                minLines = 2,
                onValueChange = { v -> onUpdate(scene.copy(videoPrompt = v)) }
            )
            Spacer(Modifier.size(6.dp))
            LabeledTextField(
                label = stringResource(R.string.novel_video_review_character_desc),
                value = scene.effectiveCharacterDescription,
                enabled = editable,
                minLines = 1,
                onValueChange = { v -> onUpdate(scene.copy(characterDescription = v)) }
            )
        }
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    enabled: Boolean,
    minLines: Int = 1,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth().heightIn(min = (minLines * 48).dp)
    )
}

@Composable
private fun describeReviewStatus(status: String): String? = when (status) {
    NovelVideoJobStatus.SCREENPLAY_CONFIRMED -> stringResource(R.string.novel_video_review_status_blocked, "已确认")
    NovelVideoJobStatus.GENERATING -> stringResource(R.string.novel_video_review_status_blocked, "生成中")
    NovelVideoJobStatus.COMPLETED -> stringResource(R.string.novel_video_review_status_blocked, "已完成")
    NovelVideoJobStatus.FAILED -> stringResource(R.string.novel_video_review_status_blocked, "失败")
    NovelVideoJobStatus.PARTIAL_FAILED -> stringResource(R.string.novel_video_review_status_blocked, "部分失败")
    NovelVideoJobStatus.CANCELLED -> stringResource(R.string.novel_video_review_status_blocked, "已取消")
    else -> null
}
