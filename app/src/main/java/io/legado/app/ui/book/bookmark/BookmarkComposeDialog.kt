package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookmarkComposeDialog : ComposeDialogFragment() {

    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 620

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: let {
            dismissAllowingStateLoss()
            return View(requireContext())
        }

        @Suppress("DEPRECATION")
        val bookmark = args.getParcelable<Bookmark>("bookmark")
        if (bookmark == null) {
            dismissAllowingStateLoss()
            return View(requireContext())
        }
        val editPos = args.getInt("editPos", -1)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                BookmarkDialogContent(
                    bookmark = bookmark,
                    editPos = editPos,
                    onSave = { saved ->
                        lifecycleScope.launch {
                            withContext(IO) {
                                appDb.bookmarkDao.insert(saved)
                            }
                            dismissAllowingStateLoss()
                        }
                    },
                    onDelete = { target ->
                        lifecycleScope.launch {
                            withContext(IO) {
                                appDb.bookmarkDao.delete(target)
                            }
                            dismissAllowingStateLoss()
                        }
                    },
                    onCancel = { dismissAllowingStateLoss() }
                )
            }
        }
    }

    companion object {
        fun create(bookmark: Bookmark, editPos: Int = -1): BookmarkComposeDialog {
            return BookmarkComposeDialog().apply {
                arguments = Bundle().apply {
                    putInt("editPos", editPos)
                    putParcelable("bookmark", bookmark)
                }
            }
        }
    }
}

@Composable
private fun BookmarkDialogContent(
    bookmark: Bookmark,
    editPos: Int,
    onSave: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onCancel: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var bookText by rememberSaveable { mutableStateOf(bookmark.bookText) }
    var content by rememberSaveable { mutableStateOf(bookmark.content) }

    AppDialogFrame(
        title = stringResource(R.string.bookmark),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (bookmark.chapterName.isNotBlank()) {
                    Text(
                        text = bookmark.chapterName,
                        color = style.primaryText,
                        fontSize = 14.sp,
                        fontFamily = style.bodyFontFamily
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                BookmarkTextField(
                    value = bookText,
                    onValueChange = { bookText = it },
                    label = stringResource(R.string.content),
                    style = style
                )
                Spacer(modifier = Modifier.height(10.dp))
                BookmarkTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = stringResource(R.string.note_content),
                    style = style
                )
            }
        },
        actions = {
            if (editPos >= 0) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.delete),
                    palette = palette,
                    onClick = { onDelete(bookmark) },
                    danger = true,
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = {
                    onSave(bookmark.copy(bookText = bookText, content = content))
                },
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun BookmarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text(
                text = label,
                fontSize = 13.sp
            )
        },
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 15.sp,
            fontFamily = style.bodyFontFamily,
            color = style.primaryText
        ),
        shape = RoundedCornerShape(style.actionRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = style.accent,
            unfocusedBorderColor = style.stroke,
            focusedLabelColor = style.accent,
            unfocusedLabelColor = style.secondaryText,
            cursorColor = style.accent,
            focusedContainerColor = style.fieldSurface,
            unfocusedContainerColor = style.fieldSurface
        ),
        minLines = 2,
        maxLines = 6
    )
}
