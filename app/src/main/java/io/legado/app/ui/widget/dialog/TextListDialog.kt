package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle

@Suppress("unused")
class TextListDialog() : ComposeDialogFragment() {

    override val widthFraction: Float = 0.9f
    override val maxWidthDp: Int = 720

    constructor(title: String, values: ArrayList<String>) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putStringArrayList("values", values)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val title = arguments?.getString("title").orEmpty()
        val values = arguments?.getStringArrayList("values").orEmpty()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
                    .coerceAtMost(620.dp)
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    LegadoMiuixCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        color = style.surface,
                        contentColor = style.primaryText,
                        cornerRadius = style.panelRadius,
                        insidePadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
                    ) {
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
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxListHeight),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(values) { index, item ->
                                TextListRow(
                                    text = item,
                                    index = index,
                                    style = style
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextListRow(
    text: String,
    index: Int,
    style: io.legado.app.ui.widget.compose.AppDialogStyle
) {
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = if (index % 2 == 0) style.fieldSurface else style.surface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        SelectionContainer {
            Text(
                text = text,
                color = style.primaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}
