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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

@Composable
internal fun AiImageProviderManageScreen(
    providers: List<AiImageProviderConfig>,
    currentProviderId: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenProvider: (AiImageProviderConfig) -> Unit,
    onShowActions: (AiImageProviderConfig) -> Unit
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
                AiImageProviderTopBar(onBack = onBack)
                Text(
                    text = stringResource(R.string.ai_image_provider_manage_summary),
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
                            AiImageProviderEmptyCard()
                        }
                    } else {
                        items(providers, key = { it.id }) { provider ->
                            AiImageProviderCard(
                                provider = provider,
                                current = provider.id == currentProviderId,
                                onClick = { onOpenProvider(provider) },
                                onMore = { onShowActions(provider) }
                            )
                        }
                    }
                }
                LegadoMiuixActionButton(
                    text = stringResource(R.string.add),
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
private fun AiImageProviderTopBar(onBack: () -> Unit) {
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
private fun AiImageProviderCard(
    provider: AiImageProviderConfig,
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
                        text = provider.displayName(),
                        color = style.primaryText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (current) {
                        AiImageProviderCurrentBadge()
                    }
                }
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = if (provider.type == AiImageProviderConfig.TYPE_OPENAI) {
                        provider.baseUrl.ifBlank { "OpenAI" }
                    } else {
                        stringResource(R.string.ai_image_provider_js)
                    },
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = provider.model.ifBlank {
                        if (provider.type == AiImageProviderConfig.TYPE_OPENAI) "gpt-image-1" else "JS"
                    },
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        if (current) append(stringResource(R.string.ai_current_provider)).append(" · ")
                        append(stringResource(if (provider.enabled) R.string.enabled else R.string.disabled))
                    },
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
private fun AiImageProviderCurrentBadge() {
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
private fun AiImageProviderEmptyCard() {
    val style = rememberAppDialogStyle()
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.panelRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp)
    ) {
        Text(
            text = stringResource(R.string.ai_image_provider_manage),
            color = style.primaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ai_image_provider_manage_summary),
            color = style.secondaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}
