package io.legado.app.ui.book.read

import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.PorterDuff
import io.legado.app.R
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.help.book.library.LibraryCloudState
import io.legado.app.ui.book.read.ReadMenuButtonConfig
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.utils.dpToPx

private const val MENU_BUTTONS_PER_PAGE = 4

@Composable
fun ReadMenuTitleBar(
    bookName: String?,
    style: AppDialogStyle,
    onBookClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBookClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = null,
                tint = style.primaryText,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = bookName ?: "",
                color = style.primaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ReadMenuActionBar(
    chapterName: String?,
    chapterUrl: String?,
    isLocalBook: Boolean,
    sourceName: String?,
    showCustomButton: Boolean,
    showCloudIcon: Boolean,
    cloudState: LibraryCloudState,
    hasLogin: Boolean,
    hasVipChapter: Boolean,
    style: AppDialogStyle,
    onChapterClick: () -> Unit,
    onChapterLongClick: () -> Unit,
    onLoginClick: () -> Unit,
    onPayClick: () -> Unit,
    onEditSourceClick: () -> Unit,
    onDisableSourceClick: () -> Unit,
    onCustomButtonClick: () -> Unit,
    onCustomButtonLongClick: () -> Unit,
    onCloudClick: () -> Unit,
    onCloudLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSourceMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 章节信息
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onChapterClick)
            ) {
                chapterName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = style.primaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                chapterUrl?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = style.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // 云端图标
            if (showCloudIcon) {
                val cloudAlpha = when (cloudState) {
                    LibraryCloudState.READY -> 1f
                    LibraryCloudState.ERROR -> 0.9f
                    LibraryCloudState.DISABLED -> 0.35f
                    else -> 0.6f
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(style.actionRadius))
                        .background(style.fieldSurface)
                        .combinedClickable(
                            onClick = onCloudClick,
                            onLongClick = onCloudLongClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_outline_cloud_24),
                        contentDescription = null,
                        tint = style.primaryText.copy(alpha = cloudAlpha),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // 自定义按钮
            if (showCustomButton) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(style.actionRadius))
                        .background(style.fieldSurface)
                        .combinedClickable(
                            onClick = onCustomButtonClick,
                            onLongClick = onCustomButtonLongClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_custom),
                        contentDescription = null,
                        tint = style.accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // 书源操作（带弹出菜单）
            if (!isLocalBook) {
                Box {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(style.actionRadius))
                            .background(style.fieldSurface)
                            .clickable { showSourceMenu = true }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sourceName ?: stringResource(R.string.book_source),
                            color = style.primaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(
                        expanded = showSourceMenu,
                        onDismissRequest = { showSourceMenu = false }
                    ) {
                        if (hasLogin) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.login)) },
                                onClick = {
                                    showSourceMenu = false
                                    onLoginClick()
                                }
                            )
                        }
                        if (hasLogin && hasVipChapter) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chapter_pay)) },
                                onClick = {
                                    showSourceMenu = false
                                    onPayClick()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit_source)) },
                            onClick = {
                                showSourceMenu = false
                                onEditSourceClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.disable_source)) },
                            onClick = {
                                showSourceMenu = false
                                onDisableSourceClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReadMenuSeekBarRow(
    seekProgress: Int,
    seekMax: Int,
    canGoPrev: Boolean,
    canGoNext: Boolean,
    style: AppDialogStyle,
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekStop: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 上一章
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(style.actionRadius))
                .background(style.fieldSurface)
                .clickable(enabled = canGoPrev, onClick = onPrevClick)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.previous_chapter),
                color = if (canGoPrev) style.primaryText else style.secondaryText.copy(alpha = 0.5f),
                fontSize = 13.sp,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // SeekBar
        AndroidView(
            factory = { context ->
                android.widget.SeekBar(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        28.dpToPx()
                    )
                    setOnSeekBarChangeListener(object : io.legado.app.ui.widget.seekbar.SeekBarChangeListener {
                        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {
                            onSeekStart()
                        }
                        override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {}
                        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {
                            onSeekStop(seekBar.progress)
                        }
                    })
                }
            },
            update = { seekBar ->
                if (seekBar.max != seekMax) seekBar.max = seekMax
                if (seekBar.progress != seekProgress) seekBar.progress = seekProgress
            },
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        // 下一章
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(style.actionRadius))
                .background(style.fieldSurface)
                .clickable(enabled = canGoNext, onClick = onNextClick)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.next_chapter),
                color = if (canGoNext) style.primaryText else style.secondaryText.copy(alpha = 0.5f),
                fontSize = 13.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
fun ReadMenuBrightnessRow(
    brightness: Int,
    isAuto: Boolean,
    showBrightnessView: Boolean,
    style: AppDialogStyle,
    onAutoClick: () -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onBrightnessStop: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!showBrightnessView) return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.brightness),
            color = style.primaryText,
            fontSize = 12.sp,
            maxLines = 1
        )

        Spacer(modifier = Modifier.width(12.dp))

        // 自动按钮
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(style.actionRadius))
                .background(style.fieldSurface)
                .clickable(onClick = onAutoClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_brightness_auto),
                contentDescription = stringResource(R.string.brightness),
                tint = if (isAuto) style.accent else style.secondaryText.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 亮度 SeekBar
        AndroidView(
            factory = { context ->
                android.widget.SeekBar(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        28.dpToPx()
                    )
                    max = 255
                    progress = brightness
                    isEnabled = !isAuto
                    setOnSeekBarChangeListener(object : io.legado.app.ui.widget.seekbar.SeekBarChangeListener {
                        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
                        override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                            if (fromUser) onBrightnessChange(progress)
                        }
                        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {
                            onBrightnessStop(seekBar.progress)
                        }
                    })
                }
            },
            update = { seekBar ->
                if (seekBar.progress != brightness) seekBar.progress = brightness
                seekBar.isEnabled = !isAuto
            },
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
        )
    }
}

@Composable
fun ReadMenuButtonGrid(
    firstRow: List<ReadMenuButtonConfig.ButtonRef>,
    secondRow: List<ReadMenuButtonConfig.ButtonRef>,
    customButtonMetadata: Map<Long, ReadMenuCustomButton>,
    autoPageActive: Boolean,
    isNightTheme: Boolean,
    style: AppDialogStyle,
    onClick: (ReadMenuButtonConfig.ButtonRef) -> Unit,
    onLongClick: (ReadMenuButtonConfig.ButtonRef) -> Boolean,
    modifier: Modifier = Modifier
) {
    val hasFirstRow = firstRow.isNotEmpty()
    val hasSecondRow = secondRow.isNotEmpty()
    if (!hasFirstRow && !hasSecondRow) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (hasFirstRow) {
            ReadMenuButtonRow(
                buttons = firstRow,
                customButtonMetadata = customButtonMetadata,
                autoPageActive = autoPageActive,
                isNightTheme = isNightTheme,
                style = style,
                onClick = onClick,
                onLongClick = onLongClick
            )
        }
        if (hasFirstRow && hasSecondRow) {
            ReadMenuDivider(style = style)
        }
        if (hasSecondRow) {
            ReadMenuButtonRow(
                buttons = secondRow,
                customButtonMetadata = customButtonMetadata,
                autoPageActive = autoPageActive,
                isNightTheme = isNightTheme,
                style = style,
                onClick = onClick,
                onLongClick = onLongClick
            )
        }
    }
}

@Composable
private fun ReadMenuButtonRow(
    buttons: List<ReadMenuButtonConfig.ButtonRef>,
    customButtonMetadata: Map<Long, ReadMenuCustomButton>,
    autoPageActive: Boolean,
    isNightTheme: Boolean,
    style: AppDialogStyle,
    onClick: (ReadMenuButtonConfig.ButtonRef) -> Unit,
    onLongClick: (ReadMenuButtonConfig.ButtonRef) -> Boolean,
    modifier: Modifier = Modifier
) {
    if (buttons.size <= MENU_BUTTONS_PER_PAGE) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            buttons.forEach { ref ->
                ReadMenuButton(
                    ref = ref,
                    customButtonMetadata = customButtonMetadata,
                    autoPageActive = autoPageActive,
                    isNightTheme = isNightTheme,
                    style = style,
                    onClick = { onClick(ref) },
                    onLongClick = { onLongClick(ref) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        val pages = buttons.chunked(MENU_BUTTONS_PER_PAGE)
        val pagerState = rememberPagerState(pageCount = { pages.size })
        HorizontalPager(
            state = pagerState,
            modifier = modifier.fillMaxWidth()
        ) { page ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                pages[page].forEach { ref ->
                    ReadMenuButton(
                        ref = ref,
                        customButtonMetadata = customButtonMetadata,
                        autoPageActive = autoPageActive,
                        isNightTheme = isNightTheme,
                        style = style,
                        onClick = { onClick(ref) },
                        onLongClick = { onLongClick(ref) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(MENU_BUTTONS_PER_PAGE - pages[page].size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ReadMenuButton(
    ref: ReadMenuButtonConfig.ButtonRef,
    customButtonMetadata: Map<Long, ReadMenuCustomButton>,
    autoPageActive: Boolean,
    isNightTheme: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit,
    onLongClick: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val title = readMenuButtonTitle(ref, customButtonMetadata)
    val iconRes = readMenuButtonIconRes(ref, autoPageActive, isNightTheme)
    val customIconPath = if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) {
        ref.id.toLongOrNull()?.let { customButtonMetadata[it]?.iconPath }
    } else null

    Column(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = { onLongClick() }
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AndroidView(
            factory = { context ->
                AppCompatImageView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(24.dpToPx(), 24.dpToPx())
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setImageDrawable(
                        ReadMenuButtonIconHelper.drawable(context, ref, iconRes, customIconPath)
                    )
                    setColorFilter(style.primaryText.toArgb(), PorterDuff.Mode.SRC_IN)
                }
            },
            update = { imageView ->
                imageView.setImageDrawable(
                    ReadMenuButtonIconHelper.drawable(imageView.context, ref, iconRes, customIconPath)
                )
                imageView.setColorFilter(style.primaryText.toArgb(), PorterDuff.Mode.SRC_IN)
            },
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = title,
            color = style.primaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
        )
    }
}

@Composable
fun ReadMenuDivider(
    style: AppDialogStyle,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(style.stroke)
    )
}

private fun readMenuButtonTitle(
    ref: ReadMenuButtonConfig.ButtonRef,
    customButtonMetadata: Map<Long, ReadMenuCustomButton>
): String {
    ref.titleOverride.trim().takeIf { it.isNotBlank() }?.let { return it }
    return when (ref.type) {
        ReadMenuButtonConfig.TYPE_CUSTOM -> {
            ref.id.toLongOrNull()
                ?.let { customButtonMetadata[it]?.displayName() }
                ?: ref.id
        }
        else -> when (ref.id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> "搜索"
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> "自动"
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> "替换"
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> "夜间"
            ReadMenuButtonConfig.Builtin.CATALOG -> "目录"
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> "朗读"
            ReadMenuButtonConfig.Builtin.READ_STYLE -> "界面"
            ReadMenuButtonConfig.Builtin.SETTING -> "设置"
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> "助手"
            ReadMenuButtonConfig.Builtin.AI_SUMMARY -> "AI"
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> "段落"
            ReadMenuButtonConfig.Builtin.CHARACTERS -> "角色"
            else -> ref.id
        }
    }
}

private fun readMenuButtonIconRes(
    ref: ReadMenuButtonConfig.ButtonRef,
    autoPageActive: Boolean,
    isNightTheme: Boolean
): Int {
    if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) return R.drawable.ic_custom
    return when (ref.id) {
        ReadMenuButtonConfig.Builtin.SEARCH -> R.drawable.ic_search
        ReadMenuButtonConfig.Builtin.AUTO_PAGE -> {
            if (autoPageActive) R.drawable.ic_auto_page_stop else R.drawable.ic_auto_page
        }
        ReadMenuButtonConfig.Builtin.REPLACE_RULE -> R.drawable.ic_find_replace
        ReadMenuButtonConfig.Builtin.NIGHT_THEME -> {
            if (isNightTheme) R.drawable.ic_daytime else R.drawable.ic_brightness
        }
        ReadMenuButtonConfig.Builtin.CATALOG -> R.drawable.ic_toc
        ReadMenuButtonConfig.Builtin.READ_ALOUD -> R.drawable.ic_read_aloud
        ReadMenuButtonConfig.Builtin.READ_STYLE -> R.drawable.ic_interface_setting
        ReadMenuButtonConfig.Builtin.SETTING -> R.drawable.ic_settings
        ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> R.drawable.ic_bottom_ai_assistant
        ReadMenuButtonConfig.Builtin.AI_SUMMARY -> R.drawable.ic_bottom_ai
        ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> R.drawable.ic_code
        ReadMenuButtonConfig.Builtin.CHARACTERS -> R.drawable.ic_bottom_person
        else -> R.drawable.ic_custom
    }
}
