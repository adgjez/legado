package io.legado.app.ui.widget.compose

import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import java.io.File

@Composable
fun ComposeThemeImageLayer(
    state: ComposeThemeImageState,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(Color(state.fallbackColor))
    ) {
        if (state.file != null) {
            ComposeThemeImage(
                file = state.file,
                animate = state.animated,
                alpha = state.alpha
            )
        }
    }
}

@Composable
private fun ComposeThemeImage(
    file: File,
    animate: Boolean,
    alpha: Float
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        update = { imageView ->
            imageView.alpha = alpha.coerceIn(0f, 1f)
            val request = if (animate) {
                Glide.with(imageView).load(file)
            } else {
                Glide.with(imageView).asBitmap().load(file)
            }
            request.centerCrop().into(imageView)
        }
    )
}

data class ComposeThemeImageState(
    val file: File?,
    val animated: Boolean = file?.extension.equals("gif", ignoreCase = true),
    val alpha: Float = 1f,
    val fallbackColor: Int
)
