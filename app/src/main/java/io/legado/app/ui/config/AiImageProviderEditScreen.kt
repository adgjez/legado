package io.legado.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.ai.backends.ProviderConnectionTester
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 图像供应商编辑页（按 ArcReel 模型重构）。
 *
 * 结构：类型卡片选择 → 基础区 → 连通性测试 → 高级折叠区（headers/timeout/风格提示词/参数）→ 启用 → 测试/保存。
 * 图像无 poll 端点，高级区比视频更简。
 */
@Composable
internal fun AiImageProviderEditScreen(
    name: String,
    onNameChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    providerType: String,
    onTypeChange: (String) -> Unit,
    headers: String,
    onHeadersChange: (String) -> Unit,
    timeout: String,
    onTimeoutChange: (String) -> Unit,
    stylePromptSummary: String,
    onStylePromptClick: () -> Unit,
    paramsSummary: String,
    onParamsClick: () -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    advancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    testing: Boolean,
    testResult: ProviderConnectionTester.Result?,
    onTestClick: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val meta = ImageProviderTypeRegistry.getOrNull(providerType)
    val baseUrlPlaceholder = meta?.defaultBaseUrl?.ifBlank { null } ?: stringResource(R.string.provider_base_url_hint)
    val modelPlaceholder = meta?.defaultModel?.ifBlank { null } ?: stringResource(R.string.provider_base_url_hint)
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(context.backgroundColor),
            contentColor = style.primaryText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                AiImageProviderEditTopBar(onBack = onBack)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ① 类型选择卡片
                    ProviderTypeSelector(
                        metas = ImageProviderTypeRegistry.metas,
                        selectedTypeId = providerType,
                        onSelect = onTypeChange
                    )

                    // ② 基础区
                    EditTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = stringResource(R.string.name)
                    )
                    EditTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        label = stringResource(R.string.ai_api_key),
                        isPassword = true
                    )
                    EditTextField(
                        value = baseUrl,
                        onValueChange = onBaseUrlChange,
                        label = stringResource(R.string.ai_base_url),
                        placeholder = baseUrlPlaceholder,
                        keyboardType = KeyboardType.Uri
                    )
                    EditTextField(
                        value = model,
                        onValueChange = onModelChange,
                        label = stringResource(R.string.ai_model),
                        placeholder = modelPlaceholder
                    )

                    // ③ 连通性测试结果
                    TestResultView(result = testResult)

                    // ④ 高级折叠区
                    AdvancedConfigSection(
                        expanded = advancedExpanded,
                        onToggle = onAdvancedExpandedChange
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            EditTextField(
                                value = headers,
                                onValueChange = onHeadersChange,
                                label = stringResource(R.string.ai_custom_headers),
                                minLines = 3,
                                maxLines = 5
                            )
                            EditTextField(
                                value = timeout,
                                onValueChange = onTimeoutChange,
                                label = stringResource(R.string.timeout_millisecond),
                                keyboardType = KeyboardType.Number
                            )
                            EditCodeButton(
                                label = stylePromptSummary,
                                onClick = onStylePromptClick
                            )
                            EditCodeButton(
                                label = paramsSummary,
                                onClick = onParamsClick
                            )
                        }
                    }

                    // ⑤ 启用开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEnabledChange(!enabled) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = enabled,
                            onCheckedChange = onEnabledChange,
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = palette.accent,
                                uncheckedColor = palette.secondaryText,
                                checkmarkColor = palette.surface
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.enable),
                            color = style.primaryText,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ⑥ 底部操作栏：测试 + 保存
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegadoMiuixActionButton(
                        text = if (testing) stringResource(R.string.provider_testing) else stringResource(R.string.provider_test),
                        palette = palette,
                        onClick = onTestClick,
                        modifier = Modifier.weight(1f),
                        primary = false,
                        cornerRadius = style.actionRadius,
                        minHeight = 46.dp
                    )
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.action_save),
                        palette = palette,
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                        primary = true,
                        cornerRadius = style.actionRadius,
                        minHeight = 46.dp
                    )
                }
            }
        }
    }
}

/** 连通性测试结果行内提示卡。null 时不渲染。 */
@Composable
private fun TestResultView(result: ProviderConnectionTester.Result?) {
    if (result == null) return
    val style = rememberAppDialogStyle()
    val color = if (result.success) Color(0xFF2E7D32) else style.accent
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.10f),
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = result.message,
            color = color,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun AiImageProviderEditTopBar(onBack: () -> Unit) {
    val style = rememberAppDialogStyle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(42.dp)
                .clickable(onClick = onBack),
            shape = RoundedCornerShape(style.actionRadius),
            color = Color.Transparent,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = style.primaryText,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = stringResource(R.string.ai_image_provider_manage),
            color = style.primaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = style.titleFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun EditTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
    placeholder: String? = null
) {
    val style = rememberAppDialogStyle()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = maxLines == 1,
            minLines = minLines,
            maxLines = maxLines,
            placeholder = placeholder?.let { { Text(it, color = style.secondaryText, fontSize = 13.sp) } },
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                disabledTextColor = style.secondaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                disabledContainerColor = style.fieldSurface.copy(alpha = 0.58f),
                cursorColor = style.accent,
                focusedBorderColor = style.accent.copy(alpha = 0.55f),
                unfocusedBorderColor = style.stroke,
                disabledBorderColor = style.stroke.copy(alpha = 0.38f),
                focusedPlaceholderColor = style.secondaryText,
                unfocusedPlaceholderColor = style.secondaryText
            ),
            textStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}

@Composable
private fun EditCodeButton(
    label: String,
    onClick: () -> Unit
) {
    val style = rememberAppDialogStyle()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            color = style.primaryText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
