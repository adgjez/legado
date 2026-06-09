package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.ui.widget.compose.ComposeConfirmDialog
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class AiProviderManageActivity : BaseActivity<ViewBinding>() {

    private lateinit var composeView: ComposeView
    override val binding: ViewBinding by lazy {
        composeView = ComposeView(this)
        object : ViewBinding {
            override fun getRoot(): View = composeView
        }
    }

    private var providers by mutableStateOf<List<AiProviderConfig>>(emptyList())
    private var modelCounts by mutableStateOf<Map<String, Int>>(emptyMap())
    private var currentProviderId by mutableStateOf("")

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            AiProviderManageScreen(
                providers = providers,
                modelCounts = modelCounts,
                currentProviderId = currentProviderId,
                onBack = ::supportFinishAfterTransition,
                onAdd = { openEdit(null) },
                onOpenProvider = ::openEdit,
                onShowActions = ::showActions
            )
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        providers = AppConfig.aiProviderList
        modelCounts = AppConfig.aiModelConfigList.groupingBy { it.providerId }.eachCount()
        currentProviderId = AppConfig.aiCurrentProviderId.orEmpty()
    }

    private fun openEdit(provider: AiProviderConfig?) {
        startActivity(Intent(this, AiProviderEditActivity::class.java).apply {
            provider?.id?.let { putExtra(AiProviderEditActivity.EXTRA_PROVIDER_ID, it) }
        })
    }

    private fun showActions(provider: AiProviderConfig) {
        val actions = listOf(getString(R.string.edit), getString(R.string.delete))
        showDialogFragment(
            ComposeActionListDialog.create(
                title = provider.name,
                labels = actions,
                dangerIndices = setOf(1),
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    when (index) {
                        0 -> openEdit(provider)
                        1 -> confirmRemoveProvider(provider)
                    }
                }
            )
        )
    }

    private fun confirmRemoveProvider(provider: AiProviderConfig) {
        val relatedModelCount = AppConfig.aiModelConfigList.count { it.providerId == provider.id }
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = provider.name,
                message = getString(
                    if (relatedModelCount > 0) R.string.ai_remove_provider_confirm_with_models
                    else R.string.ai_remove_provider_confirm,
                    relatedModelCount
                ),
                positiveText = getString(R.string.delete),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = {
                    AppConfig.aiProviderList = AppConfig.aiProviderList.filterNot { it.id == provider.id }
                    notifyAiConfigChanged()
                    reload()
                    toastOnUi(R.string.ai_provider_removed)
                }
            )
        )
    }

    private fun notifyAiConfigChanged() {
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
    }
}

@Composable
private fun AiProviderManageScreen(
    providers: List<AiProviderConfig>,
    modelCounts: Map<String, Int>,
    currentProviderId: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenProvider: (AiProviderConfig) -> Unit,
    onShowActions: (AiProviderConfig) -> Unit
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
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
                AiProviderTopBar(onBack = onBack)
                Text(
                    text = stringResource(R.string.ai_provider_manage_summary),
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 6.dp, end = 16.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (providers.isEmpty()) {
                        item {
                            AiProviderEmptyCard()
                        }
                    } else {
                        items(providers, key = { it.id }) { provider ->
                            AiProviderCard(
                                provider = provider,
                                modelCount = modelCounts[provider.id] ?: 0,
                                current = provider.id == currentProviderId,
                                onClick = { onOpenProvider(provider) },
                                onMore = { onShowActions(provider) }
                            )
                        }
                    }
                }
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ai_add_provider),
                    palette = palette,
                    onClick = onAdd,
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
private fun AiProviderTopBar(onBack: () -> Unit) {
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
            text = stringResource(R.string.ai_provider_manage_title),
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
private fun AiProviderCard(
    provider: AiProviderConfig,
    modelCount: Int,
    current: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.panelRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name,
                        color = style.primaryText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (current) {
                        AiProviderCurrentBadge()
                    }
                }
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = provider.baseUrl.ifBlank { "OpenAI compatible" },
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.ai_manage_models_summary, modelCount),
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(if (current) R.string.ai_current_provider else R.string.ai_provider),
                    color = if (current) palette.accent else style.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onMore),
                shape = RoundedCornerShape(style.actionRadius),
                color = palette.surfaceVariant,
                contentColor = style.primaryText,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more),
                        tint = style.primaryText,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AiProviderCurrentBadge() {
    val style = rememberAppDialogStyle()
    Surface(
        shape = RoundedCornerShape(style.actionRadius),
        color = style.accent.copy(alpha = 0.14f),
        contentColor = style.accent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = stringResource(R.string.ai_current_provider),
            color = style.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AiProviderEmptyCard() {
    val style = rememberAppDialogStyle()
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.panelRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp)
    ) {
        Text(
            text = stringResource(R.string.ai_current_provider_summary_empty),
            color = style.primaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ai_add_provider_summary),
            color = style.secondaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}
