package io.legado.app.ui.main.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

// Quick preset templates
data class GenPreset(
    val name: String,
    val icon: String,
    val duration: Long,
    val aspectRatio: String,
    val description: String
)

@Composable
fun AiGenDialog(
    onDismiss: () -> Unit,
    onGenerate: (prompt: String, negativePrompt: String, duration: Long, aspectRatio: String, providerId: String?) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var prompt by remember { mutableStateOf("") }
    var negativePrompt by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf(3_000L) }
    var aspectRatio by remember { mutableStateOf("16:9") }
    var showAdvanced by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(-1) }
    var selectedProviderId by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }

    val presets = remember {
        listOf(
            GenPreset("快速短片", "🎬", 3_000L, "16:9", "3秒 16:9 默认模型"),
            GenPreset("循环壁纸", "🎨", 5_000L, "9:16", "5秒 9:16 首尾帧循环"),
            GenPreset("分镜故事", "📖", 5_000L, "16:9", "5秒 16:9 角色一致性")
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(style.panelRadius),
            color = style.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Title
                Text(
                    text = "AI 视频生成",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = style.primaryText
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Quick Presets
                Text("一键模板", fontSize = 14.sp, color = style.secondaryText)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEachIndexed { index, preset ->
                        PresetButton(
                            preset = preset,
                            selected = selectedPreset == index,
                            style = style,
                            onClick = {
                                selectedPreset = index
                                duration = preset.duration
                                aspectRatio = preset.aspectRatio
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Prompt input
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("描述你想要的视频") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = style.accent,
                        unfocusedBorderColor = style.stroke
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Advanced toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("高级参数", fontSize = 14.sp, color = style.secondaryText)
                    Text(if (showAdvanced) "▼" else "▶", fontSize = 12.sp, color = style.secondaryText)
                }

                AnimatedVisibility(
                    visible = showAdvanced,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        // Negative prompt
                        OutlinedTextField(
                            value = negativePrompt,
                            onValueChange = { negativePrompt = it },
                            label = { Text("负向提示词（不想要的内容）") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = style.accent,
                                unfocusedBorderColor = style.stroke
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Duration slider
                        Text("时长: ${duration / 1000}秒", fontSize = 13.sp, color = style.secondaryText)
                        Slider(
                            value = duration.toFloat() / 1000f,
                            onValueChange = { duration = (it * 1000).toLong() },
                            valueRange = 3f..10f,
                            steps = 6,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Aspect ratio
                        Text("比例", fontSize = 13.sp, color = style.secondaryText)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("16:9", "9:16", "1:1", "4:3").forEach { ratio ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (aspectRatio == ratio) style.accent else style.fieldSurface,
                                    contentColor = if (aspectRatio == ratio) Color.White else style.primaryText,
                                    modifier = Modifier.clickable { aspectRatio = ratio }
                                ) {
                                    Text(ratio, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                val generateEnabled = prompt.isNotBlank() && !generating
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegadoMiuixActionButton(
                        text = "取消",
                        palette = palette,
                        onClick = onDismiss,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    LegadoMiuixActionButton(
                        text = if (generating) "生成中..." else "生成",
                        palette = palette,
                        onClick = {
                            if (generateEnabled) {
                                generating = true
                                onGenerate(prompt, negativePrompt, duration, aspectRatio, selectedProviderId)
                            }
                        },
                        primary = true,
                        modifier = Modifier.alpha(if (generateEnabled) 1f else 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetButton(
    preset: GenPreset,
    selected: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) style.accent.copy(alpha = 0.15f) else style.fieldSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) style.accent else style.stroke
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(preset.icon, fontSize = 24.sp)
            Text(preset.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = style.primaryText)
            Text(preset.description, fontSize = 10.sp, color = style.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
