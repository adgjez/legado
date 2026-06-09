package io.legado.app.ui.widget.compose

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor

@Immutable
data class AppSettingPalette(
    val page: Color,
    val row: Int,
    val rowPressed: Int,
    val divider: Color,
    val border: Int?,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val danger: Color,
    val disabledText: Color,
    val onAccent: Color,
    val panelRadiusPx: Float,
    val bodyFontFamily: FontFamily,
    val titleFontFamily: FontFamily
)

@Composable
fun rememberAppSettingPalette(): AppSettingPalette {
    val context = LocalContext.current
    val dialogStyle = rememberAppDialogStyle()
    val rowBaseColor = ContextCompat.getColor(context, R.color.background_card)
    val secondaryText = Color(ContextCompat.getColor(context, R.color.tv_text_summary))
    return AppSettingPalette(
        page = Color(context.backgroundColor),
        row = UiCorner.surfaceColor(rowBaseColor),
        rowPressed = UiCorner.surfaceColor(rowBaseColor, pressed = true),
        divider = Color(ContextCompat.getColor(context, R.color.bg_divider_line)),
        border = UiCorner.panelBorderColor(context),
        primaryText = Color(ContextCompat.getColor(context, R.color.primaryText)),
        secondaryText = secondaryText,
        accent = Color(context.accentColor),
        danger = dialogStyle.danger,
        disabledText = secondaryText.copy(alpha = 0.48f),
        onAccent = Color.White,
        panelRadiusPx = UiCorner.panelRadius(context),
        bodyFontFamily = dialogStyle.bodyFontFamily,
        titleFontFamily = dialogStyle.titleFontFamily
    )
}

@Composable
fun AppSettingSectionTitle(
    title: CharSequence?,
    palette: AppSettingPalette,
    modifier: Modifier = Modifier
) {
    if (title.isNullOrBlank()) return
    Text(
        text = title.toString(),
        color = palette.accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = palette.titleFontFamily,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
    )
}

fun Modifier.appSettingPanelBackground(
    normalColor: Int,
    panelImage: Drawable?,
    borderColor: Int?,
    radiusPx: Float
): Modifier {
    return drawWithCache {
        val path = Path()
        val rect = RectF(0f, 0f, size.width, size.height)
        path.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = normalColor
        }
        val strokePaint = borderColor?.let { color ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                this.color = color
            }
        }
        onDrawBehind {
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                nativeCanvas.drawPath(path, fillPaint)
                panelImage?.let { drawable ->
                    drawable.bounds = Rect(0, 0, size.width.toInt(), size.height.toInt())
                    drawable.draw(nativeCanvas)
                }
                strokePaint?.let { nativeCanvas.drawPath(path, it) }
            }
        }
    }
}

fun Modifier.appSettingRowDecoration(
    pressed: Boolean,
    pressedColor: Int,
    dividerColor: Color,
    showDivider: Boolean,
    radiusPx: Float,
    isFirst: Boolean = false,
    isLast: Boolean,
    danger: Boolean = false,
    dangerColor: Color = Color.Transparent
): Modifier {
    return drawWithCache {
        val path = Path()
        val rect = RectF(0f, 0f, size.width, size.height)
        val top = if (isFirst) radiusPx else 0f
        val bottom = if (isLast) radiusPx else 0f
        path.addRoundRect(
            rect,
            floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom),
            Path.Direction.CW
        )
        val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = pressedColor
        }
        val dangerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = dangerColor.copy(alpha = 0.10f).toArgb()
        }
        val dividerInset = 16.dp.toPx()
        onDrawBehind {
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                if (danger) {
                    nativeCanvas.drawPath(path, dangerPaint)
                }
                if (pressed) {
                    nativeCanvas.drawPath(path, pressedPaint)
                }
            }
            if (showDivider) {
                val y = size.height - 1f
                drawLine(
                    color = dividerColor,
                    start = Offset(dividerInset, y),
                    end = Offset(size.width - dividerInset, y),
                    strokeWidth = 1f
                )
            }
        }
    }
}
