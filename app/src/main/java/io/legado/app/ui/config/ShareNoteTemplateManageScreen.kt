package io.legado.app.ui.config

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val noteTemplateDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
internal fun ShareNoteTemplateManageScreen(
    entries: List<ShareNoteTemplateManager.Entry>,
    activeDirName: String,
    previewFiles: Map<String, File>,
    onApply: (ShareNoteTemplateManager.Entry) -> Unit,
    onEdit: (ShareNoteTemplateManager.Entry) -> Unit,
    onMoreActions: (ShareNoteTemplateManager.Entry) -> List<AppManagementMenuAction>,
    onAddClick: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.settings.page)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Text(
            text = "管理正文长按摘录分享图片使用的 HTML 模板。预览只显示模板头部，分享时会生成完整图片。",
            color = palette.settings.secondaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
        AppManagementLazyColumn(
            palette = palette,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 0.dp)
        ) {
            items(entries, key = { it.dirName }) { entry ->
                ShareNoteTemplateItemRow(
                    entry = entry,
                    active = activeDirName == entry.dirName,
                    previewFile = previewFiles[entry.dirName],
                    onApply = { onApply(entry) },
                    onEdit = { onEdit(entry) },
                    moreActions = onMoreActions(entry)
                )
            }
        }
        LegadoMiuixActionButton(
            text = "添加模板",
            palette = palette.miuix,
            onClick = onAddClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            minHeight = 48.dp
        )
    }
}

@Composable
private fun ShareNoteTemplateItemRow(
    entry: ShareNoteTemplateManager.Entry,
    active: Boolean,
    previewFile: File?,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    moreActions: List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShareNoteTemplatePreview(previewFile = previewFile)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.meta.name,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildInfoText(entry),
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShareNoteTemplateActionButton(
                        text = if (active) "已应用" else "应用",
                        accent = true,
                        onClick = onApply
                    )
                    if (entry.source == ShareNoteTemplateManager.Source.LOCAL) {
                        ShareNoteTemplateActionButton(text = "编辑", onClick = onEdit)
                    }
                    AppManagementMoreActionButton(
                        actionsProvider = { moreActions },
                        palette = palette,
                        contentDescription = "更多"
                    )
                }
            }
        }
    }
}

@Composable
internal fun ShareNoteTemplatePreview(
    previewFile: File?,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    Box(
        modifier = modifier
            .size(width = 78.dp, height = 110.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.miuix.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (previewFile != null && previewFile.exists()) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { imageView ->
                    Glide.with(imageView.context.applicationContext ?: imageView.context)
                        .load(previewFile)
                        .centerCrop()
                        .into(imageView)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "预览",
                color = palette.settings.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ShareNoteTemplateActionButton(
    text: String,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    androidx.compose.material3.Surface(
        modifier = Modifier
            .height(34.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp),
        color = if (accent) palette.settings.accent.copy(alpha = 0.14f) else palette.miuix.surfaceVariant,
        contentColor = if (accent) palette.settings.accent else palette.settings.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun buildInfoText(entry: ShareNoteTemplateManager.Entry): String {
    val source = when (entry.source) {
        ShareNoteTemplateManager.Source.BUILTIN -> "内置"
        ShareNoteTemplateManager.Source.LOCAL -> "本地"
    }
    val time = entry.meta.updatedAt.takeIf { it > 0L }?.let {
        noteTemplateDateFormat.format(Date(it))
    }
    return listOfNotNull(
        entry.meta.canvasLabel(),
        entry.meta.sizeLabel(),
        source,
        time
    ).joinToString(" · ")
}
