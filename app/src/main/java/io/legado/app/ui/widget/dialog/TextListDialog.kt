package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
                val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                val maxListHeight = (screenHeight * 0.82f - 132.dp)
                    .coerceIn(120.dp, 560.dp)
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
                        if (values.isEmpty()) {
                            Text(
                                text = "暂无内容",
                                color = style.secondaryText,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                modifier = Modifier.padding(vertical = 18.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = maxListHeight),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(
                                    items = values,
                                    key = { index, item -> "$index:${item.hashCode()}" }
                                ) { index, item ->
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
}

@Composable
private fun TextListRow(
    text: String,
    index: Int,
    style: io.legado.app.ui.widget.compose.AppDialogStyle
) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(text, style.accent) {
        buildAutoLinkedText(text, style.accent)
    }
    val links = remember(annotated) {
        annotated.getStringAnnotations(TEXT_LIST_LINK_TAG, 0, annotated.length)
    }
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = if (index % 2 == 0) style.fieldSurface else style.surface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (links.isEmpty()) {
            SelectionContainer {
                Text(
                    text = text,
                    color = style.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        } else {
            SelectionContainer {
                ClickableText(
                    text = annotated,
                    style = TextStyle(
                        color = style.primaryText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = style.bodyFontFamily
                    ),
                    onClick = { offset ->
                        annotated.getStringAnnotations(TEXT_LIST_LINK_TAG, offset, offset)
                            .firstOrNull()
                            ?.item
                            ?.let { url ->
                                runCatching { uriHandler.openUri(url) }
                            }
                    }
                )
            }
        }
    }
}

private fun buildAutoLinkedText(text: String, accent: Color): AnnotatedString {
    return buildAnnotatedString {
        val spannable = SpannableString(text)
        if (!Linkify.addLinks(spannable, Linkify.WEB_URLS)) {
            append(text)
            return@buildAnnotatedString
        }
        val links = spannable.getSpans(0, spannable.length, URLSpan::class.java)
            .mapNotNull { span ->
                val start = spannable.getSpanStart(span)
                val end = spannable.getSpanEnd(span)
                if (start < 0 || end <= start || end > text.length) {
                    null
                } else {
                    AutoLinkedSpan(start, end, span.url)
                }
            }
            .sortedBy { it.start }
        var index = 0
        links.forEach { link ->
            if (link.start < index) return@forEach
            if (link.start > index) {
                append(text.substring(index, link.start))
            }
            pushStringAnnotation(TEXT_LIST_LINK_TAG, link.url)
            withStyle(
                SpanStyle(
                    color = accent,
                    textDecoration = TextDecoration.Underline
                )
            ) {
                append(text.substring(link.start, link.end))
            }
            pop()
            index = link.end
        }
        if (index < text.length) {
            append(text.substring(index))
        }
    }
}

private data class AutoLinkedSpan(
    val start: Int,
    val end: Int,
    val url: String
)

private const val TEXT_LIST_LINK_TAG = "text_list_link"
