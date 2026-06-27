package io.legado.app.ui.main.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.help.ai.SanitizeResult
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

@Composable
fun AiSanitizeDiffDialog(
    originalText: String,
    sanitizedResult: SanitizeResult?,
    intensity: Int,
    onIntensityChange: (Int) -> Unit,
    onAccept: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(style.panelRadius),
            color = style.surface,
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Title
                Text("AI 文本净化", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = style.primaryText)
                Spacer(modifier = Modifier.height(8.dp))

                // Intensity slider
                Text("净化强度: $intensity ${intensityLabel(intensity)}", fontSize = 13.sp, color = style.secondaryText)
                Slider(
                    value = intensity.toFloat(),
                    onValueChange = { onIntensityChange(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Diff comparison
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                ) {
                    // Original (left)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("原文", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = style.secondaryText)
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = style.fieldSurface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = originalText,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Default,
                                color = style.primaryText,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // Sanitized (right)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI 净化后", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = style.accent)
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = style.fieldSurface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = sanitizedResult?.sanitizedText ?: "净化中...",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Default,
                                color = style.primaryText,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }

                // Stats
                if (sanitizedResult != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "原文 ${sanitizedResult.originalLength} 字 → 净化后 ${sanitizedResult.sanitizedLength} 字" +
                            if (sanitizedResult.cached) " (缓存)" else "",
                        fontSize = 12.sp,
                        color = style.secondaryText
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegadoMiuixActionButton(text = "取消", palette = palette, onClick = onDismiss, modifier = Modifier.padding(end = 8.dp))
                    LegadoMiuixActionButton(text = "换一批重试", palette = palette, onClick = onRetry, modifier = Modifier.padding(end = 8.dp))
                    LegadoMiuixActionButton(
                        text = "接受修改",
                        palette = palette,
                        onClick = { if (sanitizedResult != null) onAccept() },
                        primary = true,
                        modifier = Modifier.alpha(if (sanitizedResult != null) 1f else 0.5f)
                    )
                }
            }
        }
    }
}

private fun intensityLabel(intensity: Int): String = when {
    intensity <= 3 -> "(保守)"
    intensity <= 7 -> "(标准)"
    else -> "(激进)"
}
