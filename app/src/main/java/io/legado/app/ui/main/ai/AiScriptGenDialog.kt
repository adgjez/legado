package io.legado.app.ui.main.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.help.ai.AiScriptGenerator
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun AiScriptGenDialog(
    onDismiss: () -> Unit,
    onSave: (script: String, modality: String, providerName: String) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val successColor = Color(0xFF4CAF50)

    var inputMode by remember { mutableStateOf("url") } // "url" or "doc"
    var url by remember { mutableStateOf("") }
    var docText by remember { mutableStateOf("") }
    var modality by remember { mutableStateOf("video") }
    var providerName by remember { mutableStateOf("AI 生成 Provider") }
    var generatedScript by remember { mutableStateOf("") }
    var validationResult by remember {
        mutableStateOf<AiScriptGenerator.ScriptValidationResult?>(null)
    }
    var testResult by remember { mutableStateOf<AiScriptGenerator.ScriptTestResult?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(style.panelRadius),
            color = style.surface,
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    "AI 脚本生成",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = style.primaryText
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Provider name
                OutlinedTextField(
                    value = providerName,
                    onValueChange = { providerName = it },
                    label = { Text("Provider 名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = style.accent,
                        unfocusedBorderColor = style.stroke
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Modality selector
                Text("模态", fontSize = 13.sp, color = style.secondaryText)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("image" to "图片", "video" to "视频", "audio" to "音频").forEach { (value, label) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (modality == value) style.accent else style.fieldSurface,
                            contentColor = if (modality == value) Color.White else style.primaryText,
                            modifier = Modifier.clickable { modality = value }
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Input mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (inputMode == "url") style.accent else style.fieldSurface,
                        contentColor = if (inputMode == "url") Color.White else style.primaryText,
                        modifier = Modifier.clickable { inputMode = "url" }
                    ) {
                        Text(
                            "输入网址",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (inputMode == "doc") style.accent else style.fieldSurface,
                        contentColor = if (inputMode == "doc") Color.White else style.primaryText,
                        modifier = Modifier.clickable { inputMode = "doc" }
                    ) {
                        Text(
                            "粘贴文档",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (inputMode == "url") {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("API 文档网址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = style.accent,
                            unfocusedBorderColor = style.stroke
                        )
                    )
                } else {
                    OutlinedTextField(
                        value = docText,
                        onValueChange = { docText = it },
                        label = { Text("粘贴 API 文档内容") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = style.accent,
                            unfocusedBorderColor = style.stroke
                        )
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Generate button
                LegadoMiuixActionButton(
                    text = if (busy) "生成中..." else "生成脚本",
                    palette = palette,
                    primary = true,
                    onClick = {
                        if (busy) return@LegadoMiuixActionButton
                        val hasInput = (inputMode == "url" && url.isNotBlank()) ||
                            (inputMode == "doc" && docText.isNotBlank())
                        if (!hasInput) return@LegadoMiuixActionButton
                        busy = true
                        status = "正在生成..."
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                if (inputMode == "url" && url.isNotBlank()) {
                                    AiScriptGenerator.generateFromUrl(url, modality, providerName)
                                } else if (inputMode == "doc" && docText.isNotBlank()) {
                                    AiScriptGenerator.generateFromDoc(docText, modality, providerName)
                                } else {
                                    AiScriptGenerator.GenerationResult(
                                        "", false, "请输入文档内容或网址"
                                    )
                                }
                            }
                            if (result.success) {
                                generatedScript = result.script
                                status = "生成成功"
                            } else {
                                status = "生成失败: ${result.error}"
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Status
                if (status.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(status, fontSize = 12.sp, color = style.secondaryText)
                }

                // Generated script preview
                if (generatedScript.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "生成的脚本",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = style.primaryText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = style.fieldSurface,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                    ) {
                        Text(
                            text = generatedScript,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = style.primaryText,
                            modifier = Modifier
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }

                    // Validation result
                    validationResult?.let { vr ->
                        Spacer(modifier = Modifier.height(4.dp))
                        if (vr.valid) {
                            Text("✓ 校验通过", fontSize = 12.sp, color = successColor)
                        } else {
                            Text("✗ 校验失败:", fontSize = 12.sp, color = style.danger)
                            vr.errors.forEach {
                                Text("  - $it", fontSize = 11.sp, color = style.danger)
                            }
                        }
                        vr.warnings.forEach {
                            Text("  ⚠ $it", fontSize = 11.sp, color = style.secondaryText)
                        }
                    }

                    // Test result
                    testResult?.let { tr ->
                        Spacer(modifier = Modifier.height(4.dp))
                        if (tr.success) {
                            Text(
                                "✓ 测试成功: ${tr.responseSnippet.take(100)}",
                                fontSize = 12.sp,
                                color = successColor
                            )
                        } else {
                            Text("✗ 测试失败: ${tr.error}", fontSize = 12.sp, color = style.danger)
                        }
                    }

                    // Action buttons
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LegadoMiuixActionButton(
                            text = "校验",
                            palette = palette,
                            onClick = {
                                validationResult = AiScriptGenerator.validateScript(
                                    generatedScript, modality
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        LegadoMiuixActionButton(
                            text = "测试",
                            palette = palette,
                            onClick = {
                                if (busy) return@LegadoMiuixActionButton
                                busy = true
                                scope.launch {
                                    testResult = withContext(Dispatchers.IO) {
                                        AiScriptGenerator.testScript(generatedScript, JSONObject())
                                    }
                                    busy = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        LegadoMiuixActionButton(
                            text = "保存",
                            palette = palette,
                            primary = true,
                            onClick = {
                                onSave(generatedScript, modality, providerName)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    LegadoMiuixActionButton(
                        text = "关闭",
                        palette = palette,
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}
