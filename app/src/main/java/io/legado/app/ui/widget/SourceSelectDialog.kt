package io.legado.app.ui.widget

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.dpToPx
import io.legado.app.utils.windowSize
import splitties.systemservices.windowManager

object SourceSelectDialog {

    fun <T> show(
        context: Context,
        title: CharSequence,
        items: List<T>,
        selectedKey: String?,
        displayName: (T) -> String,
        searchTexts: (T) -> List<String>,
        itemKey: (T) -> String,
        showTitle: Boolean = true,
        onSelect: (T) -> Unit
    ) {
        if (items.isEmpty()) return
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    SourceSelectContent(
                        title = title.toString(),
                        items = items,
                        selectedKey = selectedKey,
                        displayName = displayName,
                        searchTexts = searchTexts,
                        itemKey = itemKey,
                        showTitle = showTitle,
                        onSelect = {
                            dialog.dismiss()
                            onSelect(it)
                        }
                    )
                }
            }
        )
        dialog.setOnShowListener {
            val width = minOf(
                (context.windowManager.windowSize.widthPixels * 0.94f).toInt(),
                520.dpToPx()
            )
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
    }
}

@Composable
private fun <T> SourceSelectContent(
    title: String,
    items: List<T>,
    selectedKey: String?,
    displayName: (T) -> String,
    searchTexts: (T) -> List<String>,
    itemKey: (T) -> String,
    showTitle: Boolean,
    onSelect: (T) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var query by remember { mutableStateOf("") }
    val filteredItems = remember(items, query) {
        val key = query.trim()
        if (key.isBlank()) {
            items
        } else {
            items.filter { item ->
                searchTexts(item).any { it.contains(key, ignoreCase = true) }
            }
        }
    }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
        ) {
            if (showTitle) {
                Text(
                    text = title,
                    color = style.primaryText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.screen_find)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                textStyle = LocalTextStyle.current.copy(
                    color = style.primaryText,
                    fontSize = 15.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = style.primaryText,
                    unfocusedTextColor = style.primaryText,
                    focusedContainerColor = style.fieldSurface,
                    unfocusedContainerColor = style.fieldSurface,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedLabelColor = style.accent,
                    unfocusedLabelColor = style.secondaryText,
                    cursorColor = style.accent
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (filteredItems.isEmpty()) {
                Text(
                    text = stringResource(R.string.empty),
                    color = style.secondaryText,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredItems) { item ->
                        LegadoMiuixChoiceRow(
                            text = displayName(item),
                            selected = itemKey(item) == selectedKey,
                            palette = palette,
                            onClick = { onSelect(item) },
                            minHeight = 46.dp
                        )
                    }
                }
            }
        }
    }
}
