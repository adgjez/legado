package io.legado.app.ui.widget.compose

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
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
import androidx.appcompat.widget.AppCompatImageView
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
                alpha = state.alpha,
                crop = state.crop
            )
        }
    }
}

@Composable
private fun ComposeThemeImage(
    file: File,
    animate: Boolean,
    alpha: Float,
    crop: ComposeThemeImageCrop?
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            CropAwareImageView(context).apply {
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
            imageView.crop = crop
            if (crop != null) {
                imageView.scaleType = ImageView.ScaleType.MATRIX
                if (animate) {
                    Glide.with(imageView).asGif().load(file).into(imageView)
                } else {
                    Glide.with(imageView).asBitmap().load(file).into(imageView)
                }
            } else {
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                if (animate) {
                    Glide.with(imageView).asGif().load(file).centerCrop().into(imageView)
                } else {
                    Glide.with(imageView).asBitmap().load(file).centerCrop().into(imageView)
                }
            }
        }
    )
}

private class CropAwareImageView(context: Context) : AppCompatImageView(context) {

    var crop: ComposeThemeImageCrop? = null
        set(value) {
            field = value
            applyCropMatrixIfNeeded()
        }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { applyCropMatrixIfNeeded() }
        (drawable as? Animatable)?.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (drawable as? Animatable)?.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCropMatrixIfNeeded()
    }

    private fun applyCropMatrixIfNeeded() {
        val crop = crop ?: return
        if (scaleType != ImageView.ScaleType.MATRIX) return
        val drawable = drawable ?: return
        val drawableWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: return
        val drawableHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: return
        val viewWidth = width.takeIf { it > 0 } ?: return
        val viewHeight = height.takeIf { it > 0 } ?: return
        val cropRect = crop.toRect(drawableWidth.toFloat(), drawableHeight.toFloat()) ?: return
        val scale = maxOf(viewWidth / cropRect.width(), viewHeight / cropRect.height())
        val dx = -cropRect.left * scale + (viewWidth - cropRect.width() * scale) / 2f
        val dy = -cropRect.top * scale + (viewHeight - cropRect.height() * scale) / 2f
        imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }
}

data class ComposeThemeImageState(
    val file: File?,
    val animated: Boolean = file?.extension.equals("gif", ignoreCase = true),
    val alpha: Float = 1f,
    val crop: ComposeThemeImageCrop? = null,
    val fallbackColor: Int
)

data class ComposeThemeImageCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toRect(width: Float, height: Float): RectF? {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeTop = top.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(0f, 1f)
        val safeBottom = bottom.coerceIn(0f, 1f)
        if (safeRight <= safeLeft || safeBottom <= safeTop) return null
        return RectF(
            safeLeft * width,
            safeTop * height,
            safeRight * width,
            safeBottom * height
        )
    }
}
