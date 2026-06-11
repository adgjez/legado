package io.legado.app.ui.config

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette

@Composable
internal fun CoverCollectionManageScreen(
    isNight: Boolean,
    entries: List<CoverCollectionManager.Entry>,
    palette: AppSettingPalette,
    miuixPalette: LegadoMiuixPalette,
    onTabChanged: (Boolean) -> Unit,
    onItemClick: (CoverCollectionManager.Entry) -> Unit,
    onItemMore: (CoverCollectionManager.Entry) -> Unit,
    onAddClick: () -> Unit
) {
    val panelRadiusDp = palette.panelRadiusPx.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.page)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Tab bar
        CoverCollectionTabBar(
            isNight = isNight,
            palette = miuixPalette,
            onTabChanged = onTabChanged
        )
        Spacer(modifier = Modifier.height(8.dp))
        // List
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(entries, key = { _, entry -> "${entry.collection.id}_${entry.source}" }) { _, entry ->
                CoverCollectionItemRow(
                    entry = entry,
                    palette = miuixPalette,
                    onClick = { onItemClick(entry) },
                    onMoreClick = { onItemMore(entry) }
                )
            }
        }
        // Add button
        LegadoMiuixActionButton(
            text = stringResource(R.string.cover_collection_add),
            palette = miuixPalette,
            onClick = onAddClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            cornerRadius = panelRadiusDp
        )
    }
}

@Composable
private fun CoverCollectionTabBar(
    isNight: Boolean,
    palette: LegadoMiuixPalette,
    onTabChanged: (Boolean) -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 10.dp, end = 16.dp),
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = 22.dp,
        insidePadding = PaddingValues(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TabButton(
                text = stringResource(R.string.theme_day),
                selected = !isNight,
                palette = palette,
                onClick = { onTabChanged(false) },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            TabButton(
                text = stringResource(R.string.theme_night),
                selected = isNight,
                palette = palette,
                onClick = { onTabChanged(true) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LegadoMiuixCard(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) palette.surface else Color.Transparent,
        contentColor = if (selected) palette.accent else palette.primaryText,
        cornerRadius = 18.dp,
        insidePadding = PaddingValues(vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = if (selected) palette.accent else palette.primaryText,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CoverCollectionItemRow(
    entry: CoverCollectionManager.Entry,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val collection = entry.collection
    val sourceText = when (entry.source) {
        CoverCollectionManager.Source.LOCAL -> stringResource(R.string.theme_source_local)
        CoverCollectionManager.Source.REMOTE -> stringResource(R.string.theme_source_remote)
        CoverCollectionManager.Source.BOTH -> stringResource(R.string.theme_source_both)
    }
    val infoText = "${stringResource(R.string.cover_collection_images_count, collection.images.size)} · $sourceText"

    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = 16.dp,
        insidePadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Preview image
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { imageView ->
                    Glide.with(imageView)
                        .load(collection.images.firstOrNull())
                        .centerCrop()
                        .into(imageView)
                },
                modifier = Modifier
                    .size(width = 54.dp, height = 72.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            // Text info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = collection.name,
                    color = palette.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = infoText,
                    color = palette.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // More button
            LegadoMiuixActionButton(
                text = stringResource(R.string.more),
                palette = palette,
                onClick = onMoreClick,
                cornerRadius = 12.dp,
                minWidth = 56.dp,
                minHeight = 34.dp,
                insidePadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
