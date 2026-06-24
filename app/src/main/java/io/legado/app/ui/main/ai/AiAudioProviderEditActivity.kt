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

class AiAudioProviderEditActivity : BaseActivity<ActivityAiVideoGalleryBinding>() {

    override val binding by viewBinding(ActivityAiVideoGalleryBinding::inflate)

    private var providerId: String? = null

    private var nameText by mutableStateOf("")
    private var providerType by mutableStateOf(AiAudioProviderConfig.TYPE_OPENAI)
    private var baseUrlText by mutableStateOf("")
    private var apiKeyText by mutableStateOf("")
    private var modelText by mutableStateOf("")
    private var defaultParamsJsonText by mutableStateOf("")
    private var scriptText by mutableStateOf("")
    private var submitEndpointText by mutableStateOf("/v1/audio/generations")
    private var statusEndpointText by mutableStateOf("/v1/audio/generations")
    private var pollIntervalText by mutableStateOf("5000")
    private var timeoutText by mutableStateOf("300000")
    private var costExpressionText by mutableStateOf("")
    private var enabledState by mutableStateOf(true)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        val provider = currentProvider()
        bind(provider)
        binding.titleBar.title = if (providerId == null) {
            "新建音频服务商"
        } else {
            "编辑音频服务商"
        }
        binding.composeView.setContent {
            EditScreen()
        }
    }

    @Composable
    private fun EditScreen() {
        val context = LocalContext.current
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val isOpenAi = providerType == AiAudioProviderConfig.TYPE_OPENAI
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
                                AiAudioProviderConfig.TYPE_OPENAI,
                                AiAudioProviderConfig.TYPE_JS
                            ),
                            selected = providerType,
                            optionLabel = {
                                if (it == AiAudioProviderConfig.TYPE_OPENAI) "OpenAI" else "JS 脚本"
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
                            EditTextField(
                                value = defaultParamsJsonText,
                                onValueChange = { defaultParamsJsonText = it },
                                label = "默认参数 (JSON)",
                                minLines = 3,
                                maxLines = 8
                            )
                        }

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

                        SectionLabel("端点配置", palette)
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

                        SectionLabel("费用", palette)
                        EditTextField(
                            value = costExpressionText,
                            onValueChange = { costExpressionText = it },
                            label = "费用表达式 (Cost Expression)"
                        )

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

    private fun bind(provider: AiAudioProviderConfig?) {
        nameText = provider?.name.orEmpty()
        providerType = provider?.type ?: AiAudioProviderConfig.TYPE_OPENAI
        baseUrlText = provider?.baseUrl.orEmpty()
        apiKeyText = provider?.apiKey.orEmpty()
        modelText = provider?.model.orEmpty()
        defaultParamsJsonText = provider?.defaultParamsJson.orEmpty()
        scriptText = provider?.script.orEmpty()
        submitEndpointText = provider?.submitEndpoint ?: "/v1/audio/generations"
        statusEndpointText = provider?.statusEndpoint ?: "/v1/audio/generations"
        pollIntervalText = (provider?.pollIntervalMillisecond ?: 5_000L).toString()
        timeoutText = (provider?.timeoutMillisecond ?: 300_000L).toString()
        costExpressionText = provider?.costExpression.orEmpty()
        enabledState = provider?.enabled ?: true
    }

    private fun save() {
        if (nameText.isBlank()) {
            toastOnUi("请输入名称")
            return
        }
        if (providerType == AiAudioProviderConfig.TYPE_OPENAI && baseUrlText.isBlank()) {
            toastOnUi("请输入 Base URL")
            return
        }
        val old = currentProvider()
        val updated = (old ?: AiAudioProviderConfig(name = nameText, type = providerType)).copy(
            name = nameText,
            type = providerType,
            baseUrl = baseUrlText,
            apiKey = apiKeyText,
            model = modelText,
            defaultParamsJson = defaultParamsJsonText,
            script = scriptText,
            submitEndpoint = submitEndpointText,
            statusEndpoint = statusEndpointText,
            pollIntervalMillisecond = pollIntervalText.toLongOrNull() ?: 5_000L,
            timeoutMillisecond = timeoutText.toLongOrNull() ?: 300_000L,
            costExpression = costExpressionText,
            enabled = enabledState
        )
        AppConfig.aiAudioProviderList = AppConfig.aiAudioProviderList
            .filterNot { it.id == updated.id } + updated
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
        finish()
    }

    private fun delete() {
        val id = providerId ?: return
        alert(title = "删除服务商", message = "确定要删除此音频服务商配置吗？") {
            okButton {
                AppConfig.aiAudioProviderList = AppConfig.aiAudioProviderList
                    .filterNot { it.id == id }
                postEvent(EventBus.AI_CONFIG_CHANGED, true)
                finish()
            }
            cancelButton()
        }
    }

    private fun currentProvider(): AiAudioProviderConfig? {
        val id = providerId ?: return null
        return AppConfig.aiAudioProviderList.firstOrNull { it.id == id }
    }

    companion object {
        private const val EXTRA_PROVIDER_ID = "providerId"

        fun start(context: Context, providerId: String? = null) {
            context.startActivity(
                Intent(context, AiAudioProviderEditActivity::class.java).apply {
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
