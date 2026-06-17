package io.legado.app.ui.main

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.widget.compose.ComposeThemeImageLayer
import io.legado.app.ui.widget.compose.ComposeThemeImageCrop
import io.legado.app.ui.widget.compose.ComposeThemeImageState
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ImageTypeUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import java.io.File

@Composable
fun MainThemeBackgroundLayer(
    version: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state = remember(version, context) {
        MainThemeBackgroundState.from(context)
    }
    ComposeThemeImageLayer(
        state = ComposeThemeImageState(
            file = state.file,
            animated = state.animated && state.blur == 0,
            crop = state.crop,
            fallbackColor = state.fallbackColor
        ),
        modifier = modifier
    )
}

data class MainThemeBackgroundState(
    val file: File?,
    val animated: Boolean,
    val blur: Int,
    val crop: ComposeThemeImageCrop?,
    val fallbackColor: Int
) {
    companion object {
        fun from(context: Context): MainThemeBackgroundState {
            val fallbackColor = ThemeConfig.getFallbackBackgroundColor(context)
            if (AppConfig.isEInkMode) {
                return MainThemeBackgroundState(null, animated = false, blur = 0, crop = null, fallbackColor)
            }
            val themeMode = ThemeConfig.getTheme()
            val imageKey = when (themeMode) {
                Theme.Light -> PreferKey.bgImage
                Theme.Dark -> PreferKey.bgImageN
                else -> return MainThemeBackgroundState(null, animated = false, blur = 0, crop = null, fallbackColor)
            }
            val blurKey = when (themeMode) {
                Theme.Light -> PreferKey.bgImageBlurring
                Theme.Dark -> PreferKey.bgImageNBlurring
                else -> null
            }
            val cropKey = when (themeMode) {
                Theme.Light -> PreferKey.bgImageCrop
                Theme.Dark -> PreferKey.bgImageNCrop
                else -> null
            }
            val resolvedPath = resolveBackgroundPath(context, imageKey)
            val file = resolvedPath?.let(::File)?.takeIf { it.isFile && it.canRead() }
            return MainThemeBackgroundState(
                file = file,
                animated = ImageTypeUtils.isAnimatedImage(file),
                blur = blurKey?.let { context.getPrefInt(it, 0) } ?: 0,
                crop = cropKey?.let { context.getPrefString(it).toComposeCrop() },
                fallbackColor = fallbackColor
            )
        }

        private fun String?.toComposeCrop(): ComposeThemeImageCrop? {
            val normalized = ThemeConfig.normalizeBackgroundCrop(this) ?: return null
            val parts = normalized.split(',').mapNotNull { it.toFloatOrNull() }
            if (parts.size != 4) return null
            return ComposeThemeImageCrop(parts[0], parts[1], parts[2], parts[3])
        }

        private fun resolveBackgroundPath(context: Context, imageKey: String): String? {
            val path = context.getPrefString(imageKey)?.takeIf { it.isNotBlank() } ?: return null
            if (!path.startsWith("http", ignoreCase = true)) return path
            val name = urlToBackgroundFileName(path)
            val filePath = FileUtils.getPath(context.externalFiles, imageKey, name)
            return filePath.takeIf { FileUtils.exist(it) }
        }

        private fun urlToBackgroundFileName(url: String): String {
            val suffix = when {
                url.contains(".9.png", ignoreCase = true) -> ".9.png"
                url.contains(".png", ignoreCase = true) -> ".png"
                url.contains(".gif", ignoreCase = true) -> ".gif"
                url.contains("webp", ignoreCase = true) -> ".webp"
                else -> ".jpg"
            }
            return MD5Utils.md5Encode16(url) + suffix
        }
    }
}
