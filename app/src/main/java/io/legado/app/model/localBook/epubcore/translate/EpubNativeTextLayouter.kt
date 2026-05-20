package io.legado.app.model.localBook.epubcore.translate

import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.style.EpubTextAlign
import kotlin.math.roundToInt

data class EpubNativeLaidDocument(
    val source: EpubNativeDocument,
    val blocks: List<EpubNativeLaidBlock>
)

sealed interface EpubNativeLaidBlock {
    val block: EpubNativeBlock
    val heightPx: Float
}

data class EpubNativeLaidTextBlock(
    override val block: EpubNativeTextBlock,
    val text: CharSequence,
    val layout: StaticLayout,
    val anchors: List<EpubNativeTextAnchor>,
    val contentWidthPx: Int
) : EpubNativeLaidBlock {
    override val heightPx: Float get() = layout.height.toFloat()
}

data class EpubNativeLaidImageBlock(
    override val block: EpubNativeImageBlock,
    val widthPx: Float,
    override val heightPx: Float
) : EpubNativeLaidBlock

data class EpubNativeLaidFallbackBlock(
    override val block: EpubNativeFallbackBlock,
    override val heightPx: Float
) : EpubNativeLaidBlock

data class EpubNativeTextAnchor(
    val source: EpubSourceRange,
    val startOffset: Int,
    val endOffset: Int
)

class EpubNativeTextLayouter(
    private val config: EpubCoreLayoutConfig
) {

    fun layout(document: EpubNativeDocument): EpubNativeLaidDocument {
        return EpubNativeLaidDocument(
            source = document,
            blocks = document.blocks.map { block -> layoutBlock(block) }
        )
    }

    private fun layoutBlock(block: EpubNativeBlock): EpubNativeLaidBlock {
        return when (block) {
            is EpubNativeTextBlock -> layoutTextBlock(block)
            is EpubNativeImageBlock -> layoutImageBlock(block)
            is EpubNativeFallbackBlock -> EpubNativeLaidFallbackBlock(
                block = block,
                heightPx = block.estimatedHeightPx ?: config.textPaint.textSize * 2f
            )
        }
    }

    private fun layoutTextBlock(block: EpubNativeTextBlock): EpubNativeLaidTextBlock {
        val styledText = block.toStyledText()
        val contentWidth = resolveContentWidth(block.style)
        val paint = textPaintFor(block.style)
        val layout = makeStaticLayout(styledText.text, paint, block.style, contentWidth)
        return EpubNativeLaidTextBlock(
            block = block,
            text = styledText.text,
            layout = layout,
            anchors = styledText.anchors,
            contentWidthPx = contentWidth
        )
    }

    private fun layoutImageBlock(block: EpubNativeImageBlock): EpubNativeLaidImageBlock {
        val width = config.contentWidthPx.toFloat()
        val height = block.intrinsicHeightPx?.toFloat()
            ?: block.intrinsicWidthPx?.takeIf { it > 0 }?.let {
                width * ((block.intrinsicHeightPx ?: it).toFloat() / it.toFloat())
            }
            ?: width
        return EpubNativeLaidImageBlock(
            block = block,
            widthPx = width,
            heightPx = height.coerceAtLeast(1f)
        )
    }

    private data class StyledNativeText(
        val text: CharSequence,
        val anchors: List<EpubNativeTextAnchor>
    )

    private fun EpubNativeTextBlock.toStyledText(): StyledNativeText {
        val builder = SpannableStringBuilder()
        val anchors = ArrayList<EpubNativeTextAnchor>()
        listMarker?.takeIf { it.isNotEmpty() }?.let { builder.append(it) }
        inline.forEach { item ->
            val start = builder.length
            when (item) {
                is EpubNativeInline.Text -> builder.append(item.value)
                is EpubNativeInline.Image -> builder.append(ObjectReplacementText)
                is EpubNativeInline.Fallback -> builder.append(item.html.ifBlank { item.reason.name })
            }
            val end = builder.length
            if (end > start) {
                builder.applySpans(start, end, item.style)
                anchors += EpubNativeTextAnchor(item.source, start, end)
            }
        }
        val indent = style.textIndentPx.roundToInt()
        if (indent > 0 && builder.isNotEmpty()) {
            builder.setSpan(
                LeadingMarginSpan.Standard(indent, 0),
                0,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return StyledNativeText(builder, anchors)
    }

    private fun SpannableStringBuilder.applySpans(start: Int, end: Int, style: EpubNativeStyle) {
        if (start >= end) return
        val typefaceStyle = when {
            (style.fontWeight ?: 400) >= 600 && style.italic -> Typeface.BOLD_ITALIC
            (style.fontWeight ?: 400) >= 600 -> Typeface.BOLD
            style.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        if (typefaceStyle != Typeface.NORMAL) {
            setSpan(StyleSpan(typefaceStyle), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (style.underline) {
            setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        style.color?.let { setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        style.fontSizePx?.takeIf { it > 0f }?.let {
            setSpan(AbsoluteSizeSpan(it.roundToInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun resolveContentWidth(style: EpubNativeStyle): Int {
        val horizontalInset = style.padding.horizontalPx + style.borderWidthPx * 2f
        return (config.contentWidthPx - horizontalInset).roundToInt().coerceAtLeast(1)
    }

    private fun textPaintFor(style: EpubNativeStyle): TextPaint {
        return TextPaint(config.textPaint).apply {
            style.color?.let { color = it }
            style.fontSizePx?.takeIf { it > 0f }?.let { textSize = it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && style.letterSpacingPx != 0f && textSize > 0f) {
                letterSpacing = style.letterSpacingPx / textSize
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun makeStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        style: EpubNativeStyle,
        width: Int
    ): StaticLayout {
        val lineSpacingExtra = style.lineHeightPx?.let {
            (it - paint.textSize).coerceAtLeast(0f)
        } ?: config.lineSpacingExtraPx
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(style.toLayoutAlignment())
                .setLineSpacing(lineSpacingExtra, config.lineSpacingMultiplier)
                .setIncludePad(false)
                .build()
        } else {
            StaticLayout(
                text,
                paint,
                width,
                style.toLayoutAlignment(),
                config.lineSpacingMultiplier,
                lineSpacingExtra,
                false
            )
        }
    }

    private fun EpubNativeStyle.toLayoutAlignment(): Layout.Alignment {
        return when (textAlign) {
            EpubTextAlign.Center -> Layout.Alignment.ALIGN_CENTER
            EpubTextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
            EpubTextAlign.Start,
            EpubTextAlign.Justify -> config.alignment
        }
    }

    private companion object {
        private const val ObjectReplacementText = "\uFFFC"
    }
}
