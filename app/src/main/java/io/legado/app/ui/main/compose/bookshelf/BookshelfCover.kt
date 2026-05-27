package io.legado.app.ui.main.compose.bookshelf

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover

@Composable
internal fun BookshelfCover(
    book: Book,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(book.bookUrl, book.getDisplayCover(), AppConfig.isNightTheme) {
        mutableStateOf<ImageBitmap?>(null)
    }
    DisposableEffect(context, book.bookUrl, book.getDisplayCover(), book.origin) {
        val target = object : CustomTarget<Drawable>(240, 320) {
            override fun onResourceReady(
                resource: Drawable,
                transition: Transition<in Drawable>?,
            ) {
                bitmap = resource.toBitmap(240, 320).asImageBitmap()
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                bitmap = placeholder?.toBitmap(240, 320)?.asImageBitmap()
            }
        }
        BookCover.load(
            context = context,
            path = book.getDisplayCover(),
            loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
            sourceOrigin = book.origin,
        ).override(240, 320).into(target)
        onDispose {
            Glide.with(context).clear(target)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap,
                contentDescription = book.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = book.name.ifBlank { "Book" },
                modifier = Modifier.padding(10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
