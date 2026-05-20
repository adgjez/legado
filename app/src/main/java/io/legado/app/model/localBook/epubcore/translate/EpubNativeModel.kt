package io.legado.app.model.localBook.epubcore.translate

import io.legado.app.model.localBook.epubcore.style.EpubBackground
import io.legado.app.model.localBook.epubcore.style.EpubBorderRadius
import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle
import io.legado.app.model.localBook.epubcore.style.EpubEdgeValue
import io.legado.app.model.localBook.epubcore.style.EpubTextAlign
import io.legado.app.model.localBook.epubcore.style.EpubWhiteSpace

data class EpubNativeDocument(
    val protocolVersion: Int,
    val chapterIndex: Int,
    val chapterHref: String,
    val title: String?,
    val blocks: List<EpubNativeBlock>,
    val diagnostics: EpubTranslationDiagnostics = EpubTranslationDiagnostics()
)

sealed interface EpubNativeBlock {
    val id: String
    val source: EpubSourceRange
    val style: EpubNativeStyle
    val nodeOrder: Int
    val fallbackReason: EpubFallbackReason?
}

data class EpubNativeTextBlock(
    override val id: String,
    override val source: EpubSourceRange,
    override val style: EpubNativeStyle,
    override val nodeOrder: Int,
    val tagName: String,
    val inline: List<EpubNativeInline>,
    val text: String,
    val blockKind: EpubNativeTextBlockKind = EpubNativeTextBlockKind.Paragraph,
    val listMarker: String? = null
) : EpubNativeBlock {
    override val fallbackReason: EpubFallbackReason? = null
}

data class EpubNativeImageBlock(
    override val id: String,
    override val source: EpubSourceRange,
    override val style: EpubNativeStyle,
    override val nodeOrder: Int,
    val href: String,
    val alt: String?,
    val intrinsicWidthPx: Int? = null,
    val intrinsicHeightPx: Int? = null,
    val isBackgroundImage: Boolean = false
) : EpubNativeBlock {
    override val fallbackReason: EpubFallbackReason? = null
}

data class EpubNativeFallbackBlock(
    override val id: String,
    override val source: EpubSourceRange,
    override val style: EpubNativeStyle,
    override val nodeOrder: Int,
    override val fallbackReason: EpubFallbackReason,
    val html: String,
    val text: String = "",
    val estimatedHeightPx: Float? = null
) : EpubNativeBlock

sealed interface EpubNativeInline {
    val source: EpubSourceRange
    val style: EpubNativeStyle

    data class Text(
        val value: String,
        override val source: EpubSourceRange,
        override val style: EpubNativeStyle
    ) : EpubNativeInline

    data class Image(
        val href: String,
        val alt: String?,
        override val source: EpubSourceRange,
        override val style: EpubNativeStyle
    ) : EpubNativeInline

    data class Fallback(
        val html: String,
        val reason: EpubFallbackReason,
        override val source: EpubSourceRange,
        override val style: EpubNativeStyle
    ) : EpubNativeInline
}

data class EpubNativeStyle(
    val computed: EpubComputedStyle = EpubComputedStyle(),
    val display: String? = null,
    val position: String? = null,
    val writingMode: String? = null,
    val direction: String? = null,
    val fontFamily: String? = computed.fontFamily,
    val fontSizePx: Float? = computed.fontSizePx,
    val lineHeightPx: Float? = computed.lineHeightPx,
    val fontWeight: Int? = computed.fontWeight,
    val italic: Boolean = computed.italic,
    val underline: Boolean = computed.underline,
    val color: Int? = computed.color,
    val backgroundColor: Int? = computed.backgroundColor,
    val background: EpubBackground = computed.background,
    val opacity: Float = computed.opacity,
    val margin: EpubEdgeValue = computed.margin,
    val padding: EpubEdgeValue = computed.padding,
    val borderColor: Int? = computed.border.color,
    val borderWidthPx: Float = computed.border.widthPx,
    val borderRadius: EpubBorderRadius = computed.borderRadius,
    val textAlign: EpubTextAlign = computed.textAlign,
    val textIndentPx: Float = computed.textIndentPx,
    val letterSpacingPx: Float = computed.letterSpacingPx,
    val wordSpacingPx: Float = computed.wordSpacingPx,
    val whiteSpace: EpubWhiteSpace = computed.whiteSpace,
    val extra: Map<String, String> = emptyMap()
)

data class EpubSourceAnchor(
    val chapterIndex: Int,
    val chapterHref: String,
    val nodePath: String,
    val nodeOrder: Int,
    val textOffset: Int
) : Comparable<EpubSourceAnchor> {
    override fun compareTo(other: EpubSourceAnchor): Int {
        return compareValuesBy(
            this,
            other,
            EpubSourceAnchor::chapterIndex,
            EpubSourceAnchor::nodeOrder,
            EpubSourceAnchor::textOffset,
            EpubSourceAnchor::nodePath
        )
    }
}

data class EpubSourceRange(
    val start: EpubSourceAnchor,
    val end: EpubSourceAnchor
) {
    val chapterIndex: Int get() = start.chapterIndex
    val chapterHref: String get() = start.chapterHref
    val nodePath: String get() = start.nodePath
    val nodeOrder: Int get() = start.nodeOrder
    val startOffset: Int get() = start.textOffset
    val endOffset: Int get() = end.textOffset
}

data class EpubTranslationResult(
    val document: EpubNativeDocument,
    val rawSourceHash: String? = null,
    val cacheKey: String? = null
)

data class EpubTranslationDiagnostics(
    val nativeBlockCount: Int = 0,
    val fallbackBlockCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val timingsMs: Map<String, Long> = emptyMap(),
    val fallbackReasons: Map<EpubFallbackReason, Int> = emptyMap()
)

enum class EpubNativeTextBlockKind {
    Paragraph,
    Heading,
    Quote,
    ListItem,
    Preformatted,
    Caption
}

enum class EpubFallbackReason {
    UnsupportedDisplay,
    UnsupportedPosition,
    UnsupportedWritingMode,
    UnsupportedTransform,
    UnsupportedFloat,
    UnsupportedTable,
    UnsupportedRuby,
    UnsupportedOverflow,
    ComplexBackground,
    ComplexInline,
    CollectorFailure,
    Unknown
}
