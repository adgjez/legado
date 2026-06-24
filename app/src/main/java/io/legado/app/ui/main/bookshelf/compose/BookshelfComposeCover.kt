package io.legado.app.ui.main.bookshelf.compose

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.CoverThumbnailCache
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.CoverCollectionManager.isRealCoverPath
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover

private var cachedFallbackDrawable: Drawable? = null
private var cachedFallbackBitmap: Bitmap? = null

@Composable
fun BookshelfComposeCover(
    item: BookshelfItemUi,
    modifier: Modifier = Modifier,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    fillBounds: Boolean = false
) {
    val context = LocalContext.current
    val coverRequest = remember(item.coverIdentityKey()) {
        item.toCoverRequest()
    }
    var bitmap by remember(coverRequest.loadKey) { mutableStateOf<Bitmap?>(null) }
    val fallbackBitmap = remember(BookCover.defaultDrawable) {
        defaultCoverBitmap()
    }
    val target = remember(coverRequest.loadKey) {
        object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                bitmap = resource
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                bitmap = (placeholder as? BitmapDrawable)?.bitmap
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                bitmap = (errorDrawable as? BitmapDrawable)?.bitmap ?: fallbackBitmap
            }
        }
    }
    DisposableEffect(target) {
        onDispose {
            val appContext = context.applicationContext ?: context
            runCatching { com.bumptech.glide.Glide.with(appContext).clear(target) }
        }
    }
    LaunchedEffect(coverRequest.loadKey, fragment, lifecycle) {
        bitmap = fallbackBitmap
        val useThumb = coverRequest.preferThumb && !AppConfig.loadCoverHighQuality
        val thumbFile = if (useThumb) {
            CoverThumbnailCache.existing(context, coverRequest.thumbKey)
        } else {
            null
        }
        if (AppConfig.useDefaultCover && !coverRequest.forcePath) {
            return@LaunchedEffect
        }
        val builder = when {
            thumbFile != null -> {
                ImageLoader.loadBitmap(context, thumbFile.absolutePath)
            }

            fragment != null && lifecycle != null -> {
                ImageLoader.loadBitmap(fragment, lifecycle, coverRequest.path)
            }

            else -> {
                ImageLoader.loadBitmap(context, coverRequest.path)
            }
        }
        var options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .disallowHardwareConfig()
            .set(OkHttpModelLoader.loadOnlyWifiOption, false)
        coverRequest.sourceOrigin?.let {
            options = options.set(OkHttpModelLoader.sourceOriginOption, it)
        }
        builder
            .apply(options)
            .placeholder(BookCover.defaultDrawable)
            .error(BookCover.defaultDrawable)
            .priority(if (AppConfig.loadCoverHighQuality) Priority.NORMAL else Priority.HIGH)
            .override(
                if (useThumb) 240 else Target.SIZE_ORIGINAL,
                if (useThumb) 320 else Target.SIZE_ORIGINAL
            )
            .centerCrop()
            .addListener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap>,
                    isFirstResource: Boolean
                ): Boolean {
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: Target<Bitmap>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    if (useThumb && thumbFile == null) {
                        CoverThumbnailCache.saveAsync(context, coverRequest.thumbKey, BitmapDrawable(context.resources, resource))
                    }
                    return false
                }
            })
            .into(target)
    }
    val coverModifier = if (fillBounds) {
        modifier
    } else {
        modifier.aspectRatio(0.75f)
    }
    Box(
        modifier = coverModifier.background(Color.Transparent)
    ) {
        Image(
            bitmap = (bitmap ?: fallbackBitmap).asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

private data class BookshelfCoverRequest(
    val path: String?,
    val name: String?,
    val author: String?,
    val sourceOrigin: String?,
    val preferThumb: Boolean,
    val forcePath: Boolean,
    val loadKey: String,
    val thumbKey: String
)

private fun BookshelfItemUi.toCoverRequest(): BookshelfCoverRequest {
    return when (this) {
        is BookshelfBookItemUi -> display.toCoverRequest()
        is BookshelfFolderItemUi -> group.toCoverRequest()
    }
}

private fun BookShelfDisplay.toCoverRequest(): BookshelfCoverRequest {
    val originalCover = getDisplayCover()
    val collectionCover = CoverCollectionManager.selectedCollectionCover(
        bookKey = bookUrl.ifBlank { "$origin|$name|$author" },
        coverPath = originalCover
    )
    val usingCollectionCover = collectionCover != null
    val forceOriginalCover = collectionCover == null &&
        CoverCollectionManager.isMixedMode() &&
        originalCover.isRealCoverPath()
    val forcePath = usingCollectionCover || forceOriginalCover
    val allowNameOverlay = usingCollectionCover || !originalCover.isRealCoverPath()
    val displayKey = listOf(
        collectionCover ?: originalCover,
        name,
        author,
        origin,
        AppConfig.useDefaultCover.toString(),
        CoverCollectionManager.selectionKey(),
        forcePath.toString(),
        allowNameOverlay.toString()
    ).joinToString("|")
    return buildCoverRequest(
        path = collectionCover ?: originalCover,
        name = name,
        author = author,
        sourceOrigin = origin,
        preferThumb = true,
        forcePath = forcePath,
        displayKey = displayKey
    )
}

private fun BookGroup.toCoverRequest(): BookshelfCoverRequest {
    return buildCoverRequest(
        path = cover,
        name = groupName,
        author = null,
        sourceOrigin = null,
        preferThumb = true,
        forcePath = false,
        displayKey = listOf(cover.orEmpty(), groupName, AppConfig.useDefaultCover.toString()).joinToString("|")
    )
}

private fun buildCoverRequest(
    path: String?,
    name: String?,
    author: String?,
    sourceOrigin: String?,
    preferThumb: Boolean,
    forcePath: Boolean,
    displayKey: String
): BookshelfCoverRequest {
    val useThumb = preferThumb && !AppConfig.loadCoverHighQuality
    val loadKey = listOf(
        displayKey,
        useThumb.toString(),
        forcePath.toString()
    ).joinToString("|")
    return BookshelfCoverRequest(
        path = path,
        name = name,
        author = author,
        sourceOrigin = sourceOrigin,
        preferThumb = preferThumb,
        forcePath = forcePath,
        loadKey = loadKey,
        thumbKey = "$sourceOrigin|$path|$name|$author"
    )
}

private fun BookshelfItemUi.coverIdentityKey(): String {
    val configKey = listOf(
        AppConfig.useDefaultCover.toString(),
        AppConfig.loadCoverHighQuality.toString(),
        CoverCollectionManager.selectionKey()
    ).joinToString("|")
    return when (this) {
        is BookshelfBookItemUi -> "$configKey|book|${display.bookUrl}|${display.getDisplayCover()}|${display.name}|${display.author}"
        is BookshelfFolderItemUi -> "$configKey|folder|${group.groupId}|${group.cover}|${group.groupName}"
    }
}

private fun defaultCoverBitmap(): Bitmap {
    val drawable = BookCover.defaultDrawable
    cachedFallbackBitmap?.takeIf {
        cachedFallbackDrawable === drawable && !it.isRecycled
    }?.let {
        return it
    }
    return drawable.toBitmap(width = 240, height = 320).also {
        cachedFallbackDrawable = drawable
        cachedFallbackBitmap = it
    }
}
