package io.legado.app.ui.book.read.page.provider

import io.legado.app.model.localBook.EpubCss
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

internal data class UseHtmlTextBoxLayout(
    val startOffset: Float,
    val width: Int
)

internal data class UseHtmlEdgeInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f
) {
    val horizontal: Float get() = left + right
    val vertical: Float get() = top + bottom
}

internal data class UseHtmlBoxStyle(
    val borderBoxStartOffset: Float,
    val borderBoxWidth: Float,
    val contentStartOffset: Float,
    val contentWidth: Int,
    val padding: UseHtmlEdgeInsets = UseHtmlEdgeInsets(),
    val borderWidth: Float = 0f,
    val borderRadius: Float = 0f,
    val backgroundColor: Int? = null,
    val borderColor: Int? = null
) {
    val hasDecoration: Boolean
        get() = backgroundColor != null || borderColor != null
}

internal object UseHtmlTextBoxLayoutResolver {

    private const val ATTR_LAYOUT = "data-legado-layout"
    private const val ATTR_WIDTH = "data-legado-width"
    private const val ATTR_MIN_WIDTH = "data-legado-min-width"
    private const val ATTR_MAX_WIDTH = "data-legado-max-width"
    private const val ATTR_MARGIN_LEFT = "data-legado-margin-left"
    private const val ATTR_MARGIN_RIGHT = "data-legado-margin-right"

    fun resolve(
        attributes: Map<String, String>,
        style: String,
        visibleWidth: Int,
        emPx: Float
    ): UseHtmlTextBoxLayout? {
        if (visibleWidth <= 0 || emPx <= 0f) return null

        val attrs = attributes.mapKeys { it.key.lowercase(Locale.ROOT) }
        val declarations = if (style.isBlank()) {
            emptyMap()
        } else {
            EpubCss.declarations(style)
        }

        val explicitLayout = (attrs[ATTR_LAYOUT]
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.let { it == "text-box" || it == "box" } == true)

        val widthValue = attrs.firstNotBlank(ATTR_WIDTH) ?: declarations["width"].orEmpty()
        val minWidthValue = attrs.firstNotBlank(ATTR_MIN_WIDTH) ?: declarations["min-width"].orEmpty()
        val maxWidthValue = attrs.firstNotBlank(ATTR_MAX_WIDTH) ?: declarations["max-width"].orEmpty()
        val marginLeftValue = attrs.firstNotBlank(ATTR_MARGIN_LEFT)
            ?: declarations.marginValue("margin-left")
        val marginRightValue = attrs.firstNotBlank(ATTR_MARGIN_RIGHT)
            ?: declarations.marginValue("margin-right")

        val hasLayoutHint = explicitLayout ||
            widthValue.isNotBlank() ||
            minWidthValue.isNotBlank() ||
            maxWidthValue.isNotBlank() ||
            marginLeftValue.isMeaningfulHorizontalMargin() ||
            marginRightValue.isMeaningfulHorizontalMargin()
        if (!hasLayoutHint) return null

        val leftAuto = marginLeftValue.isAuto()
        val rightAuto = marginRightValue.isAuto()
        val marginLeft = marginLeftValue.takeUnless { leftAuto }.toCssPx(visibleWidth, emPx)
            ?.coerceAtLeast(0)
        val marginRight = marginRightValue.takeUnless { rightAuto }.toCssPx(visibleWidth, emPx)
            ?.coerceAtLeast(0)

        val hasExplicitWidth = widthValue.isNotBlank()
        var width = widthValue.toCssPx(visibleWidth, emPx)
            ?: (visibleWidth - (marginLeft ?: 0) - (marginRight ?: 0))

        minWidthValue.toCssPx(visibleWidth, emPx)?.let { width = width.coerceAtLeast(it) }
        maxWidthValue.toCssPx(visibleWidth, emPx)?.let { width = width.coerceAtMost(it) }
        width = width.coerceIn(1, visibleWidth)

        val maxOffset = (visibleWidth - width).coerceAtLeast(0)
        val hasConstrainedWidth = hasExplicitWidth || width < visibleWidth
        val rightEdgeInset = rightEdgeInsetPx(emPx).coerceAtMost(maxOffset)
        val offset = when {
            leftAuto && rightAuto && hasConstrainedWidth -> maxOffset / 2f
            leftAuto && hasConstrainedWidth -> (
                visibleWidth - width - (marginRight ?: 0) - rightEdgeInset
                ).toFloat()
            else -> (marginLeft ?: 0).toFloat()
        }.coerceIn(0f, maxOffset.toFloat())

        return UseHtmlTextBoxLayout(
            startOffset = offset,
            width = width
        )
    }

    fun resolveBox(
        attributes: Map<String, String>,
        style: String,
        visibleWidth: Int,
        emPx: Float,
        backgroundColor: Int?,
        borderColor: Int?,
        borderWidth: Float,
        borderRadius: Float
    ): UseHtmlBoxStyle? {
        val layout = resolve(attributes, style, visibleWidth, emPx) ?: return null
        val declarations = if (style.isBlank()) {
            emptyMap()
        } else {
            EpubCss.declarations(style)
        }
        val attrs = attributes.mapKeys { it.key.lowercase(Locale.ROOT) }
        val marginLeftValue = attrs.firstNotBlank(ATTR_MARGIN_LEFT)
            ?: declarations.marginValue("margin-left")
        val marginRightValue = attrs.firstNotBlank(ATTR_MARGIN_RIGHT)
            ?: declarations.marginValue("margin-right")
        val leftAuto = marginLeftValue.isAuto()
        val rightAuto = marginRightValue.isAuto()
        val marginLeft = marginLeftValue.takeUnless { leftAuto }.toCssPx(visibleWidth, emPx)
            ?.coerceAtLeast(0)
        val marginRight = marginRightValue.takeUnless { rightAuto }.toCssPx(visibleWidth, emPx)
            ?.coerceAtLeast(0)
        val padding = declarations.edgeInsets("padding", visibleWidth, emPx)
        val boxSizing = declarations["box-sizing"]?.trim()?.lowercase(Locale.ROOT)
        val borderWidthPx = borderWidth.coerceAtLeast(0f)
        val borderBoxWidth = if (boxSizing == "border-box") {
            layout.width.toFloat()
        } else {
            layout.width + padding.horizontal + borderWidthPx * 2f
        }.coerceIn(1f, visibleWidth.toFloat())
        val maxOffset = (visibleWidth - borderBoxWidth).coerceAtLeast(0f)
        val rightEdgeInset = rightEdgeInsetPx(emPx).coerceAtMost(maxOffset.roundToInt())
        val borderBoxStart = when {
            leftAuto && rightAuto -> maxOffset / 2f
            leftAuto -> visibleWidth - borderBoxWidth - (marginRight ?: 0) - rightEdgeInset
            else -> (marginLeft ?: layout.startOffset.roundToInt()).toFloat()
        }.coerceIn(0f, maxOffset)
        val contentStart = borderBoxStart + borderWidthPx + padding.left
        val contentWidth = (borderBoxWidth - padding.horizontal - borderWidthPx * 2f)
            .roundToInt()
            .coerceAtLeast(1)
        return UseHtmlBoxStyle(
            borderBoxStartOffset = borderBoxStart,
            borderBoxWidth = borderBoxWidth,
            contentStartOffset = contentStart,
            contentWidth = contentWidth,
            padding = padding,
            borderWidth = borderWidthPx,
            borderRadius = borderRadius.coerceAtLeast(0f),
            backgroundColor = backgroundColor,
            borderColor = borderColor
        )
    }

    private fun Map<String, String>.firstNotBlank(name: String): String? {
        return this[name]?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun Map<String, String>.marginValue(name: String): String {
        this[name]?.let { return it }
        val values = this["margin"]?.let { EpubCss.splitValueList(it) }.orEmpty()
        if (values.isEmpty()) return ""
        val top = values.getOrNull(0).orEmpty()
        val right = values.getOrNull(1) ?: top
        val bottom = values.getOrNull(2) ?: top
        val left = values.getOrNull(3) ?: right
        return when (name.substringAfter('-')) {
            "right" -> right
            "left" -> left
            "top" -> top
            "bottom" -> bottom
            else -> ""
        }
    }

    private fun Map<String, String>.edgeInsets(
        name: String,
        baseWidth: Int,
        emPx: Float
    ): UseHtmlEdgeInsets {
        val values = this[name]?.let { EpubCss.splitValueList(it) }.orEmpty()
        val top = this["$name-top"] ?: values.getOrNull(0)
        val right = this["$name-right"] ?: values.getOrNull(1) ?: top
        val bottom = this["$name-bottom"] ?: values.getOrNull(2) ?: top
        val left = this["$name-left"] ?: values.getOrNull(3) ?: right
        return UseHtmlEdgeInsets(
            left = left.toCssPx(baseWidth, emPx)?.coerceAtLeast(0)?.toFloat() ?: 0f,
            top = top.toCssPx(baseWidth, emPx)?.coerceAtLeast(0)?.toFloat() ?: 0f,
            right = right.toCssPx(baseWidth, emPx)?.coerceAtLeast(0)?.toFloat() ?: 0f,
            bottom = bottom.toCssPx(baseWidth, emPx)?.coerceAtLeast(0)?.toFloat() ?: 0f
        )
    }

    private fun String?.isAuto(): Boolean {
        return this?.trim()?.lowercase(Locale.ROOT) == "auto"
    }

    private fun String.isMeaningfulHorizontalMargin(): Boolean {
        val clean = trim().lowercase(Locale.ROOT)
        return clean.isNotBlank() && clean != "0" && clean != "0px" && clean != "0em" && clean != "0rem"
    }

    private fun rightEdgeInsetPx(emPx: Float): Int {
        return max(2, (emPx * 0.15f).roundToInt())
    }

    private fun String?.toCssPx(baseWidth: Int, emPx: Float): Int? {
        val clean = this?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        if (clean == "auto") return null
        val number = when {
            clean.endsWith("%") -> {
                val percentage = clean.dropLast(1).toFloatOrNull() ?: return null
                baseWidth * percentage / 100f
            }
            clean.endsWith("rem") -> {
                val rem = clean.dropLast(3).toFloatOrNull() ?: return null
                emPx * rem
            }
            clean.endsWith("em") -> {
                val em = clean.dropLast(2).toFloatOrNull() ?: return null
                emPx * em
            }
            clean.endsWith("px") -> clean.dropLast(2).toFloatOrNull() ?: return null
            else -> clean.toFloatOrNull() ?: return null
        }
        return number.roundToInt()
    }
}
