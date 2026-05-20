package io.legado.app.model.localBook.epubcore.layout

import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import io.legado.app.model.localBook.epubcore.model.InlineNode
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle
import io.legado.app.model.localBook.epubcore.style.EpubDisplay
import io.legado.app.model.localBook.epubcore.style.EpubListStyleType
import io.legado.app.model.localBook.epubcore.style.EpubTextTransform
import java.util.Locale
import kotlin.math.roundToInt

class EpubInlineLayouter(
    private val context: EpubLayoutContext
) {

    fun layout(node: EpubInlineNode, constraint: EpubConstraintBox): EpubLayoutBox? {
        val styled = node.inline.toStyledText(node.style)
        if (styled.text.isBlank()) return null
        val content = if (node.style.display == EpubDisplay.ListItem) {
            SpannableStringBuilder().apply {
                append(node.style.markerText(node.source?.blockIndex ?: 0))
                append(styled.text)
            }
        } else {
            styled.text
        }
        val markerOffset = content.length - styled.text.length
        val anchors = if (markerOffset > 0) {
            styled.anchors.map {
                it.copy(startOffset = it.startOffset + markerOffset, endOffset = it.endOffset + markerOffset)
            }
        } else {
            styled.anchors
        }
        val width = resolveContentWidth(node.style, constraint.widthPx)
        val layout = makeLayout(content, node.style, width.toInt().coerceAtLeast(1))
        val textBox = EpubTextLayoutBox(
            text = content,
            staticLayout = layout,
            anchors = anchors,
            frame = RectF(0f, 0f, width, layout.height.toFloat().coerceAtLeast(1f)),
            source = node.source,
            style = node.style
        )
        return textBox.wrapDecorationsIfNeeded()
    }

    private data class StyledText(
        val text: CharSequence,
        val anchors: List<EpubTextAnchor>
    )

    private fun List<InlineNode>.toStyledText(blockStyle: EpubComputedStyle): StyledText {
        val builder = SpannableStringBuilder()
        val anchors = ArrayList<EpubTextAnchor>()
        for (node in this) {
            when (node) {
                is InlineNode.Text -> {
                    val start = builder.length
                    builder.append(node.value.applyTextTransform(node.computedStyle.textTransform))
                    val end = builder.length
                    builder.applyInlineSpans(start, end, node.computedStyle, node.style.bold, node.style.italic, node.style.underline || node.style.linkHref != null)
                    if (end > start) anchors += EpubTextAnchor(node.source, start, end)
                }
                is InlineNode.FootnoteRef -> {
                    val start = builder.length
                    builder.append(node.label)
                    val end = builder.length
                    builder.applyInlineSpans(start, end, node.computedStyle, bold = false, italic = false, underline = true)
                    if (end > start) anchors += EpubTextAnchor(node.source, start, end)
                }
            }
        }
        val indent = blockStyle.textIndentPx.roundToInt()
        if (indent > 0 && builder.isNotEmpty()) {
            builder.setSpan(LeadingMarginSpan.Standard(indent, 0), 0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return StyledText(builder, anchors)
    }

    private fun SpannableStringBuilder.applyInlineSpans(
        start: Int,
        end: Int,
        style: EpubComputedStyle,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean
    ) {
        if (start >= end) return
        val typefaceStyle = when {
            (bold || (style.fontWeight ?: 400) >= 600) && (italic || style.italic) -> Typeface.BOLD_ITALIC
            bold || (style.fontWeight ?: 400) >= 600 -> Typeface.BOLD
            italic || style.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        if (typefaceStyle != Typeface.NORMAL) {
            setSpan(StyleSpan(typefaceStyle), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (underline || style.underline) {
            setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        style.color?.let { setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        style.fontSizePx?.takeIf { it > 0f }?.let {
            setSpan(AbsoluteSizeSpan(it.roundToInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun resolveContentWidth(style: EpubComputedStyle, availableWidth: Float): Float {
        val outerWidth = style.width.resolve(availableWidth) ?: availableWidth
        return (outerWidth.coerceInStyleBounds(style.minWidth, style.maxWidth, availableWidth) -
            style.padding.horizontalPx - style.border.widthPx * 2f).coerceAtLeast(1f)
    }

    @Suppress("DEPRECATION")
    private fun makeLayout(text: CharSequence, style: EpubComputedStyle, width: Int): StaticLayout {
        val config = context.config
        val lineSpacingExtra = style.lineHeightPx?.let { lineHeight ->
            (lineHeight - (style.fontSizePx ?: config.textPaint.textSize)).coerceAtLeast(0f)
        } ?: config.lineSpacingExtraPx
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, config.textPaint, width)
                .setAlignment(style.toLayoutAlignment(config.alignment))
                .setLineSpacing(lineSpacingExtra, config.lineSpacingMultiplier)
                .setIncludePad(false)
                .build()
        } else {
            StaticLayout(
                text,
                config.textPaint,
                width,
                style.toLayoutAlignment(config.alignment),
                config.lineSpacingMultiplier,
                lineSpacingExtra,
                false
            )
        }
    }
}

private fun EpubTextLayoutBox.wrapDecorationsIfNeeded(): EpubLayoutBox {
    val style = style
    val hasDecoration = style.backgroundColor != null ||
        style.background.imageHref != null ||
        style.border.widthPx > 0f ||
        style.borderRadius.maxPx > 0f ||
        style.padding.horizontalPx > 0f ||
        style.padding.verticalPx > 0f
    if (!hasDecoration) return this
    val child = copy(
        frame = RectF(
            style.padding.leftPx + style.border.widthPx,
            style.padding.topPx + style.border.widthPx,
            style.padding.leftPx + style.border.widthPx + frame.width(),
            style.padding.topPx + style.border.widthPx + frame.height()
        )
    )
    return EpubContainerLayoutBox(
        frame = RectF(
            0f,
            0f,
            child.frame.right + style.padding.rightPx + style.border.widthPx,
            child.frame.bottom + style.padding.bottomPx + style.border.widthPx
        ),
        source = source,
        style = style,
        children = listOf(child)
    )
}

private fun EpubComputedStyle.markerText(index: Int): String {
    return when (listStyle.type) {
        EpubListStyleType.None -> ""
        EpubListStyleType.Decimal -> "${index + 1}. "
        EpubListStyleType.LowerAlpha -> "${alphaIndex(index, false)}. "
        EpubListStyleType.UpperAlpha -> "${alphaIndex(index, true)}. "
        EpubListStyleType.LowerRoman -> "${romanIndex(index + 1).lowercase(Locale.ROOT)}. "
        EpubListStyleType.UpperRoman -> "${romanIndex(index + 1)}. "
        EpubListStyleType.Circle -> "\u25e6 "
        EpubListStyleType.Square -> "\u25aa "
        EpubListStyleType.Inherit,
        EpubListStyleType.Disc -> "\u2022 "
    }
}

private fun alphaIndex(index: Int, upper: Boolean): String {
    var value = index.coerceAtLeast(0)
    val builder = StringBuilder()
    do {
        val char = 'a' + value % 26
        builder.insert(0, char)
        value = value / 26 - 1
    } while (value >= 0)
    return if (upper) builder.toString().uppercase(Locale.ROOT) else builder.toString()
}

private fun romanIndex(value: Int): String {
    var number = value.coerceIn(1, 3999)
    val pairs = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
        100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
        10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
    )
    val builder = StringBuilder()
    pairs.forEach { (arabic, roman) ->
        while (number >= arabic) {
            builder.append(roman)
            number -= arabic
        }
    }
    return builder.toString()
}

private fun String.applyTextTransform(transform: EpubTextTransform): String {
    return when (transform) {
        EpubTextTransform.None -> this
        EpubTextTransform.Uppercase -> uppercase(Locale.ROOT)
        EpubTextTransform.Lowercase -> lowercase(Locale.ROOT)
        EpubTextTransform.Capitalize -> split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }
    }
}
