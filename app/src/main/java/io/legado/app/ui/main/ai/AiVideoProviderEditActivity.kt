package io.legado.app.ui.main.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.legado.app.ui.widget.compose.LegadoMiuixSelectField
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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        val provider = currentProvider()
        bind(provider)
        binding.titleBar.title = if (providerId == null) {
            "新建视频服务商"
        } else {
            "编辑视频服务商"
        }
        binding.composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                EditScreen()
            }
        }
    }

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
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LegadoMiuixSelectField(
                            label = "类型",
                            options = listOf(
                                AiVideoProviderConfig.TYPE_OPENAI,
                                AiVideoProviderConfig.TYPE_JS
                            ),
                            selected = providerType,
                            optionLabel = {
                                if (it == AiVideoProviderConfig.TYPE_OPENAI) "OpenAI" else "JS 脚本"
                            },
                            onSelected = { providerType = it },
                            palette = palette
                        )

                        EditTextField(
                            value = nameText,
                            onValueChange = { nameText = it },
                            label = "名称"
                        )

                        if (isOpenAi) {
                            EditTextField(
                                value = baseUrlText,
                                onValueChange = { baseUrlText = it },
                                label = "Base URL",
                                keyboardType = KeyboardType.Uri
                            )
                            EditTextField(
                                value = apiKeyText,
                                onValueChange = { apiKeyText = it },
                                label = "API Key",
                                isPassword = true
                            )
                            EditTextField(
                                value = modelText,
                                onValueChange = { modelText = it },
                                label = "模型"
                            )
                        }

                        EditTextField(
                            value = stylePromptText,
                            onValueChange = { stylePromptText = it },
                            label = "风格提示词",
                            minLines = 2,
                            maxLines = 5
                        )

                        EditTextField(
                            value = negativePromptText,
                            onValueChange = { negativePromptText = it },
                            label = "负面提示词",
                            minLines = 2,
                            maxLines = 5
                        )

                        EditTextField(
                            value = submitEndpointText,
                            onValueChange = { submitEndpointText = it },
                            label = "提交端点 (Submit Endpoint)"
                        )

                        EditTextField(
                            value = statusEndpointText,
                            onValueChange = { statusEndpointText = it },
                            label = "状态端点 (Status Endpoint)"
                        )

                        EditTextField(
                            value = pollIntervalText,
                            onValueChange = { pollIntervalText = it },
                            label = "轮询间隔 (毫秒)",
                            keyboardType = KeyboardType.Number
                        )

                        EditTextField(
                            value = timeoutText,
                            onValueChange = { timeoutText = it },
                            label = "超时时间 (毫秒)",
                            keyboardType = KeyboardType.Number
                        )

                        SectionLabel("能力支持", palette)
                        CheckboxRow(
                            label = "支持尾帧 (Tail Frame)",
                            checked = supportsTailFrame,
                            onCheckedChange = { supportsTailFrame = it },
                            palette = palette
                        )
                        CheckboxRow(
                            label = "支持运镜控制 (Camera Control)",
                            checked = supportsCameraControl,
                            onCheckedChange = { supportsCameraControl = it },
                            palette = palette
                        )
                        CheckboxRow(
                            label = "支持实时预览 (Live Preview)",
                            checked = supportsLivePreview,
                            onCheckedChange = { supportsLivePreview = it },
                            palette = palette
                        )
                        CheckboxRow(
                            label = "支持参考图 (Reference Image)",
                            checked = supportsReferenceImage,
                            onCheckedChange = { supportsReferenceImage = it },
                            palette = palette
                        )

                        SectionLabel("费用", palette)
                        EditTextField(
                            value = costExpressionText,
                            onValueChange = { costExpressionText = it },
                            label = "费用表达式 (Cost Expression)"
                        )
                        EditTextField(
                            value = costPerSecondText,
                            onValueChange = { costPerSecondText = it },
                            label = "每秒费用 (Cost Per Second)",
                            keyboardType = KeyboardType.Decimal
                        )

                        if (!isOpenAi) {
                            SectionLabel("脚本", palette)
                            EditTextField(
                                value = scriptText,
                                onValueChange = { scriptText = it },
                                label = "JS 脚本",
                                minLines = 4,
                                maxLines = 12
                            )
                        }

                        SectionLabel("状态", palette)
                        CheckboxRow(
                            label = "启用",
                            checked = enabledState,
                            onCheckedChange = { enabledState = it },
                            palette = palette
                        )

                        if (isEditing) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LegadoMiuixActionButton(
                                text = "删除服务商",
                                palette = palette,
                                onClick = { delete() },
                                modifier = Modifier.fillMaxWidth(),
                                danger = true,
                                cornerRadius = style.actionRadius,
                                minHeight = 46.dp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    LegadoMiuixActionButton(
                        text = "保存",
                        palette = palette,
                        onClick = { save() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        primary = true,
                        cornerRadius = style.actionRadius,
                        minHeight = 46.dp
                    )
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String, palette: LegadoMiuixPalette) {
        Text(
            text = text,
            color = palette.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    private fun bind(provider: AiVideoProviderConfig?) {
        nameText = provider?.name.orEmpty()
        providerType = provider?.type ?: AiVideoProviderConfig.TYPE_OPENAI
        baseUrlText = provider?.baseUrl.orEmpty()
        apiKeyText = provider?.apiKey.orEmpty()
        modelText = provider?.model.orEmpty()
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
        alert(title = "删除服务商", message = "确定要删除此视频服务商配置吗？") {
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

@Composable
private fun EditTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1
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
