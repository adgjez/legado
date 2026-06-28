package io.legado.app.ui.main.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityAiVideoGalleryBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiVideoProviderEditActivity : BaseActivity<ActivityAiVideoGalleryBinding>() {

    override val binding by viewBinding(ActivityAiVideoGalleryBinding::inflate)

    private var providerId: String? = null

    private var nameText by mutableStateOf("")
    private var providerType by mutableStateOf(AiVideoProviderConfig.TYPE_OPENAI)
    private var baseUrlText by mutableStateOf("")
    private var apiKeyText by mutableStateOf("")
    private var modelText by mutableStateOf("")
    private var templateText by mutableStateOf("")
    private var stylePromptText by mutableStateOf("")
    private var negativePromptText by mutableStateOf("")
    private var submitEndpointText by mutableStateOf("/videos/generations")
    private var statusEndpointText by mutableStateOf("/videos/generations/{id}")
    private var pollIntervalText by mutableStateOf("5000")
    private var timeoutText by mutableStateOf("600000")
    private var supportsTailFrame by mutableStateOf(false)
    private var supportsCameraControl by mutableStateOf(false)
    private var supportsLivePreview by mutableStateOf(false)
    private var supportsReferenceImage by mutableStateOf(false)
    private var costExpressionText by mutableStateOf("")
    private var costPerSecondText by mutableStateOf("0")
    private var scriptText by mutableStateOf("")
    private var enabledState by mutableStateOf(true)

    private var showAdvanced by mutableStateOf(false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        val provider = currentProvider()
        bind(provider)
        binding.titleBar.title = if (providerId == null) {
            "新建视频模型"
        } else {
            "编辑视频模型"
        }
        binding.composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                EditScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditScreen() {
        val context = LocalContext.current
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val isOpenAi = providerType == AiVideoProviderConfig.TYPE_OPENAI
        val isEditing = providerId != null

        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(context.backgroundColor),
                contentColor = style.primaryText
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    if (isEditing) "编辑视频模型" else "新建视频模型",
                                    fontFamily = style.bodyFontFamily,
                                    fontSize = 18.sp
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Text("←", fontSize = 20.sp)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color(context.backgroundColor),
                                titleContentColor = style.primaryText
                            )
                        )
                    },
                    containerColor = Color(context.backgroundColor)
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // ── 类型选择 ──
                            CardSection("类型", palette) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    TypeChip(
                                        label = "OpenAI",
                                        subtitle = "标准 API",
                                        selected = isOpenAi,
                                        onClick = { providerType = AiVideoProviderConfig.TYPE_OPENAI },
                                        palette = palette,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TypeChip(
                                        label = "JS 脚本",
                                        subtitle = "自定义脚本",
                                        selected = !isOpenAi,
                                        onClick = { providerType = AiVideoProviderConfig.TYPE_JS },
                                        palette = palette,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // ── 模型预设 ──
                            CardSection("模型预设", palette) {
                                ModelPresetSelector(
                                    currentModel = modelText,
                                    onSelect = { preset ->
                                        modelText = preset.model
                                        templateText = preset.template
                                        submitEndpointText = preset.submitEndpoint
                                        statusEndpointText = preset.statusEndpoint
                                        if (preset.baseUrl.isNotBlank()) baseUrlText = preset.baseUrl
                                    },
                                    palette = palette
                                )
                            }

                            // ── 基本信息 ──
                            CardSection("基本信息", palette) {
                                EditTextField(
                                    value = nameText,
                                    onValueChange = { nameText = it },
                                    label = "名称",
                                    placeholder = "例：Agnes AI 视频"
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                EditTextField(
                                    value = baseUrlText,
                                    onValueChange = { baseUrlText = it },
                                    label = "Base URL",
                                    placeholder = "https://apihub.agnes-ai.com/v1",
                                    keyboardType = KeyboardType.Uri
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                EditTextField(
                                    value = apiKeyText,
                                    onValueChange = { apiKeyText = it },
                                    label = "API Key",
                                    placeholder = "sk-...",
                                    isPassword = true
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedDropdownField(
                                    value = modelText,
                                    onValueChange = { modelText = it },
                                    label = "模型名称",
                                    placeholder = "agnes-video-v2.0",
                                    suggestions = listOf(
                                        "agnes-video-v2.0",
                                        "agnes-video-2.0",
                                        "cogvideox",
                                        "stable-video-diffusion"
                                    ),
                                    palette = palette
                                )
                            }

                            // ── API 端点 ──
                            CardSection("API 端点", palette) {
                                EditTextField(
                                    value = submitEndpointText,
                                    onValueChange = { submitEndpointText = it },
                                    label = "提交端点",
                                    placeholder = "/videos/generations"
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                EditTextField(
                                    value = statusEndpointText,
                                    onValueChange = { statusEndpointText = it },
                                    label = "状态端点",
                                    placeholder = "/videos/generations/{id}"
                                )
                            }

                            // ── 生成参数 ──
                            CardSection("生成参数", palette) {
                                EditTextField(
                                    value = stylePromptText,
                                    onValueChange = { stylePromptText = it },
                                    label = "风格提示词",
                                    placeholder = "电影级画质，4K",
                                    minLines = 2,
                                    maxLines = 4
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                EditTextField(
                                    value = negativePromptText,
                                    onValueChange = { negativePromptText = it },
                                    label = "负面提示词",
                                    placeholder = "低质量，模糊",
                                    minLines = 2,
                                    maxLines = 4
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    EditTextField(
                                        value = pollIntervalText,
                                        onValueChange = { pollIntervalText = it },
                                        label = "轮询间隔",
                                        placeholder = "5000",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                    EditTextField(
                                        value = timeoutText,
                                        onValueChange = { timeoutText = it },
                                        label = "超时时间",
                                        placeholder = "600000",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "单位：毫秒，轮询间隔建议 ≥ 3000ms",
                                    color = palette.secondaryText,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // ── 高级设置（可折叠） ──
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAdvanced = !showAdvanced },
                                shape = RoundedCornerShape(style.actionRadius),
                                colors = CardDefaults.cardColors(
                                    containerColor = palette.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "高级设置",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.primaryText
                                    )
                                    Text(
                                        if (showAdvanced) "▲" else "▼",
                                        color = palette.secondaryText
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = showAdvanced,
                                enter = expandVertically(),
                                exit = shrinkVertically()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // 能力支持
                                    CardSection("能力支持", palette) {
                                        CheckboxRow(
                                            "支持尾帧", supportsTailFrame,
                                            { supportsTailFrame = it }, palette
                                        )
                                        CheckboxRow(
                                            "支持运镜", supportsCameraControl,
                                            { supportsCameraControl = it }, palette
                                        )
                                        CheckboxRow(
                                            "实时预览", supportsLivePreview,
                                            { supportsLivePreview = it }, palette
                                        )
                                        CheckboxRow(
                                            "支持参考图", supportsReferenceImage,
                                            { supportsReferenceImage = it }, palette
                                        )
                                    }

                                    // 费用
                                    CardSection("费用", palette) {
                                        EditTextField(
                                            value = costExpressionText,
                                            onValueChange = { costExpressionText = it },
                                            label = "费用表达式",
                                            placeholder = "duration * rate"
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        EditTextField(
                                            value = costPerSecondText,
                                            onValueChange = { costPerSecondText = it },
                                            label = "每秒费用",
                                            placeholder = "0",
                                            keyboardType = KeyboardType.Decimal
                                        )
                                    }

                                    // JS 脚本
                                    if (!isOpenAi) {
                                        CardSection("JS 脚本", palette) {
                                            EditTextField(
                                                value = scriptText,
                                                onValueChange = { scriptText = it },
                                                label = "脚本内容",
                                                placeholder = "// 自定义视频生成脚本...",
                                                minLines = 6,
                                                maxLines = 14
                                            )
                                        }
                                    }

                                    // 状态
                                    CardSection("状态", palette) {
                                        CheckboxRow(
                                            "启用此供应商", enabledState,
                                            { enabledState = it }, palette
                                        )
                                    }

                                    if (isEditing) {
                                        LegadoMiuixActionButton(
                                            text = "删除视频模型",
                                            palette = palette,
                                            onClick = { delete() },
                                            modifier = Modifier.fillMaxWidth(),
                                            danger = true,
                                            cornerRadius = style.actionRadius,
                                            minHeight = 46.dp
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // 底部保存按钮
                        Surface(
                            color = Color(context.backgroundColor),
                            shadowElevation = 4.dp
                        ) {
                            LegadoMiuixActionButton(
                                text = "保存",
                                palette = palette,
                                onClick = { save() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                primary = true,
                                cornerRadius = style.actionRadius,
                                minHeight = 48.dp
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CardSection(
        title: String,
        palette: LegadoMiuixPalette,
        content: @Composable () -> Unit
    ) {
        val style = rememberAppDialogStyle()
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(style.actionRadius),
            colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = title,
                    color = palette.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                content()
            }
        }
    }

    @Composable
    private fun TypeChip(
        label: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit,
        palette: LegadoMiuixPalette,
        modifier: Modifier = Modifier
    ) {
        val style = rememberAppDialogStyle()
        val borderColor = if (selected) palette.accent else palette.surfaceVariant
        val bgColor = if (selected) palette.accent.copy(alpha = 0.08f) else Color.Transparent

        Surface(
            modifier = modifier
                .clip(RoundedCornerShape(style.actionRadius))
                .border(1.5.dp, borderColor, RoundedCornerShape(style.actionRadius))
                .clickable { onClick() },
            color = bgColor,
            shape = RoundedCornerShape(style.actionRadius)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    color = if (selected) palette.accent else palette.primaryText,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    color = palette.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ModelPresetSelector(
        currentModel: String,
        onSelect: (VideoModelPreset) -> Unit,
        palette: LegadoMiuixPalette
    ) {
        val style = rememberAppDialogStyle()
        var expanded by remember { mutableStateOf(false) }
        val presets = remember {
            listOf(
                VideoModelPreset(
                    name = "Agnes AI Video 2.0",
                    model = "agnes-video-v2.0",
                    template = "agnes_video_2.0",
                    submitEndpoint = "/videos",
                    statusEndpoint = "/videos/generations/{id}",
                    baseUrl = "https://apihub.agnes-ai.com/v1",
                    description = "免费视频模型，1080P 音画同出"
                ),
                VideoModelPreset(
                    name = "自定义模型",
                    model = "",
                    template = "",
                    submitEndpoint = "/videos/generations",
                    statusEndpoint = "/videos/generations/{id}",
                    baseUrl = "",
                    description = "手动配置所有参数"
                )
            )
        }
        val selectedName = remember(currentModel) {
            presets.firstOrNull { it.model == currentModel }?.name
                ?: if (currentModel.isNotBlank()) "自定义 ($currentModel)" else "未选择"
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                label = { Text("预设", fontFamily = style.bodyFontFamily) },
                trailingIcon = { Text(if (expanded) "▲" else "▼") },
                shape = RoundedCornerShape(style.actionRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = style.primaryText,
                    unfocusedTextColor = style.primaryText,
                    focusedContainerColor = style.fieldSurface,
                    unfocusedContainerColor = style.fieldSurface,
                    focusedBorderColor = style.accent.copy(alpha = 0.55f),
                    unfocusedBorderColor = style.stroke,
                    cursorColor = style.accent
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = style.primaryText,
                    fontFamily = style.bodyFontFamily
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    preset.name,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Text(
                                    preset.description,
                                    fontSize = 11.sp,
                                    color = palette.secondaryText,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        onClick = {
                            onSelect(preset)
                            expanded = false
                        },
                        leadingIcon = if (preset.model == currentModel) {
                            { Text("✓", color = palette.accent, fontSize = 16.sp) }
                        } else null
                    )
                }
            }
        }
    }

    @Composable
    private fun OutlinedDropdownField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        placeholder: String,
        suggestions: List<String>,
        palette: LegadoMiuixPalette
    ) {
        val style = rememberAppDialogStyle()
        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded && suggestions.isNotEmpty(),
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                label = {
                    Text(label, fontFamily = style.bodyFontFamily)
                },
                placeholder = {
                    Text(placeholder, fontFamily = style.bodyFontFamily, color = style.secondaryText)
                },
                singleLine = true,
                shape = RoundedCornerShape(style.actionRadius),
                trailingIcon = if (suggestions.isNotEmpty()) {
                    {
                        IconButton(onClick = { expanded = !expanded }) {
                            Text(
                                if (expanded) "▲" else "▼",
                                contentDescription = null
                            )
                        }
                    }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = style.primaryText,
                    unfocusedTextColor = style.primaryText,
                    focusedContainerColor = style.fieldSurface,
                    unfocusedContainerColor = style.fieldSurface,
                    focusedBorderColor = style.accent.copy(alpha = 0.55f),
                    unfocusedBorderColor = style.stroke,
                    cursorColor = style.accent
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = style.primaryText,
                    fontFamily = style.bodyFontFamily
                )
            )
            if (suggestions.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    suggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    suggestion,
                                    fontFamily = style.bodyFontFamily,
                                    fontSize = 14.sp
                                )
                            },
                            onClick = {
                                onValueChange(suggestion)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    private fun bind(provider: AiVideoProviderConfig?) {
        nameText = provider?.name.orEmpty()
        providerType = provider?.type ?: AiVideoProviderConfig.TYPE_OPENAI
        baseUrlText = provider?.baseUrl.orEmpty()
        apiKeyText = provider?.apiKey.orEmpty()
        modelText = provider?.model.orEmpty()
        templateText = provider?.template.orEmpty()
        stylePromptText = provider?.stylePrompt.orEmpty()
        negativePromptText = provider?.negativePrompt.orEmpty()
        submitEndpointText = provider?.submitEndpoint ?: "/videos/generations"
        statusEndpointText = provider?.statusEndpoint ?: "/videos/generations/{id}"
        pollIntervalText = (provider?.pollIntervalMillisecond ?: 5_000L).toString()
        timeoutText = (provider?.timeoutMillisecond ?: 600_000L).toString()
        supportsTailFrame = provider?.supportsTailFrame ?: false
        supportsCameraControl = provider?.supportsCameraControl ?: false
        supportsLivePreview = provider?.supportsLivePreview ?: false
        supportsReferenceImage = provider?.supportsReferenceImage ?: false
        costExpressionText = provider?.costExpression.orEmpty()
        costPerSecondText = (provider?.costPerSecond ?: 0.0).toString()
        scriptText = provider?.script.orEmpty()
        enabledState = provider?.enabled ?: true
    }

    private fun save() {
        if (nameText.isBlank()) {
            toastOnUi("请输入名称")
            return
        }
        if (providerType == AiVideoProviderConfig.TYPE_OPENAI && baseUrlText.isBlank()) {
            toastOnUi("请输入 Base URL")
            return
        }
        if (providerType == AiVideoProviderConfig.TYPE_OPENAI &&
            baseUrlText.isNotBlank() &&
            !baseUrlText.startsWith("http://") &&
            !baseUrlText.startsWith("https://")
        ) {
            toastOnUi("Base URL 需以 http:// 或 https:// 开头")
            return
        }
        if (providerType == AiVideoProviderConfig.TYPE_OPENAI && apiKeyText.isBlank()) {
            toastOnUi("请输入 API Key")
            return
        }
        val old = currentProvider()
        val updated = (old ?: AiVideoProviderConfig(name = nameText, type = providerType)).copy(
            name = nameText,
            type = providerType,
            template = templateText,
            baseUrl = baseUrlText,
            apiKey = apiKeyText,
            model = modelText,
            stylePrompt = stylePromptText.trim(),
            negativePrompt = negativePromptText.trim(),
            submitEndpoint = submitEndpointText,
            statusEndpoint = statusEndpointText,
            pollIntervalMillisecond = pollIntervalText.toLongOrNull() ?: 5_000L,
            timeoutMillisecond = timeoutText.toLongOrNull() ?: 600_000L,
            supportsTailFrame = supportsTailFrame,
            supportsCameraControl = supportsCameraControl,
            supportsLivePreview = supportsLivePreview,
            supportsReferenceImage = supportsReferenceImage,
            costExpression = costExpressionText,
            costPerSecond = costPerSecondText.toDoubleOrNull() ?: 0.0,
            script = scriptText,
            enabled = enabledState
        )
        AppConfig.aiVideoProviderList = AppConfig.aiVideoProviderList
            .filterNot { it.id == updated.id } + updated
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
        finish()
    }

    private fun delete() {
        val id = providerId ?: return
        alert(title = "删除视频模型", message = "确定要删除此视频模型配置吗？") {
            okButton {
                AppConfig.aiVideoProviderList = AppConfig.aiVideoProviderList
                    .filterNot { it.id == id }
                postEvent(EventBus.AI_CONFIG_CHANGED, true)
                finish()
            }
            cancelButton()
        }
    }

    private fun currentProvider(): AiVideoProviderConfig? {
        val id = providerId ?: return null
        return AppConfig.aiVideoProviderList.firstOrNull { it.id == id }
    }

    companion object {
        private const val EXTRA_PROVIDER_ID = "providerId"

        fun start(context: Context, providerId: String? = null) {
            context.startActivity(
                Intent(context, AiVideoProviderEditActivity::class.java).apply {
                    providerId?.let { putExtra(EXTRA_PROVIDER_ID, it) }
                }
            )
        }
    }
}

data class VideoModelPreset(
    val name: String,
    val model: String,
    val template: String = "",       // matches AiVideoProviderConfig.template
    val submitEndpoint: String,
    val statusEndpoint: String,
    val baseUrl: String,
    val description: String
)

@Composable
private fun EditTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
    modifier: Modifier = Modifier
) {
    val style = rememberAppDialogStyle()
    Column(
        modifier = modifier
    ) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = maxLines == 1,
            minLines = minLines,
            maxLines = maxLines,
            placeholder = {
                if (placeholder.isNotBlank()) {
                    Text(placeholder, color = style.secondaryText.copy(alpha = 0.5f))
                }
            },
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
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    palette: LegadoMiuixPalette
) {
    val style = rememberAppDialogStyle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = palette.accent,
                uncheckedColor = palette.secondaryText,
                checkmarkColor = palette.surface
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = style.primaryText,
            fontSize = 14.sp
        )
    }
}