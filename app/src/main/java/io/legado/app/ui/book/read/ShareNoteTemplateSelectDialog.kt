package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.ui.config.ShareNoteTemplatePreview
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ShareNoteTemplateSelectDialog : ComposeDialogFragment() {

    override val widthFraction: Float? = 0.94f
    override val maxWidthDp: Int? = 640

    private var onSelected: ((ShareNoteTemplateManager.Entry) -> Unit)? = null
    private var onManage: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = rememberAppManagementPalette()
                var entries by remember { mutableStateOf<List<ShareNoteTemplateManager.Entry>>(emptyList()) }
                var previews by remember { mutableStateOf<Map<String, File>>(emptyMap()) }
                LaunchedEffect(Unit) {
                    val loaded = withContext(Dispatchers.IO) { ShareNoteTemplateManager.loadEntries() }
                    val last = ShareNoteTemplateManager.lastDirName()
                    entries = loaded.sortedBy { if (it.dirName == last) 0 else 1 }
                    loaded.forEach { entry ->
                        ShareNoteImageRenderer.renderPreview(requireContext(), entry)?.let { file ->
                            previews = previews + (entry.dirName to file)
                        }
                    }
                }
                AppDialogFrame(
                    title = "选择分享模板",
                    message = "预览只显示模板头部，选择后会生成完整分享图片。",
                    scrollContent = false,
                    content = {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 460.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(entries, key = { it.dirName }) { entry ->
                                AppManagementCard(
                                    palette = palette,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            dismissAllowingStateLoss()
                                            ShareNoteTemplateManager.rememberLast(entry)
                                            onSelected?.invoke(entry)
                                        },
                                    insidePadding = PaddingValues(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ShareNoteTemplatePreview(previewFile = previews[entry.dirName])
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.meta.name,
                                                color = palette.settings.primaryText,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${entry.meta.canvasLabel()} · ${entry.meta.sizeLabel()}",
                                                color = palette.settings.secondaryText,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        val dialogPalette = rememberAppManagementPalette().miuix
                        LegadoMiuixActionButton(
                            text = "管理模板",
                            palette = dialogPalette,
                            onClick = {
                                dismissAllowingStateLoss()
                                onManage?.invoke()
                            }
                        )
                        LegadoMiuixActionButton(
                            text = getString(R.string.cancel),
                            palette = dialogPalette,
                            onClick = { dismissAllowingStateLoss() }
                        )
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            onSelected: (ShareNoteTemplateManager.Entry) -> Unit,
            onManage: () -> Unit
        ): ShareNoteTemplateSelectDialog {
            return ShareNoteTemplateSelectDialog().apply {
                this.onSelected = onSelected
                this.onManage = onManage
            }
        }
    }
}
