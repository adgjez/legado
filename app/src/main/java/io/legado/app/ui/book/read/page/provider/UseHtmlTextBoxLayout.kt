package io.legado.app.ui.book.read.page.provider

import io.legado.app.model.localBook.EpubCss
import java.util.Locale
import kotlin.math.roundToInt

internal data class UseHtmlTextBoxLayout(
    val startOffset: Float,
    val width: Int
)

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
            marginLeftValue.isNotBlank() ||
            marginRightValue.isNotBlank()
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
        val offset = when {
            leftAuto && rightAuto && hasExplicitWidth -> maxOffset / 2f
            leftAuto && hasExplicitWidth -> (visibleWidth - width - (marginRight ?: 0)).toFloat()
            else -> (marginLeft ?: 0).toFloat()
        }.coerceIn(0f, maxOffset.toFloat())

        return UseHtmlTextBoxLayout(
            startOffset = offset,
            width = width
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

    private fun String?.isAuto(): Boolean {
        return this?.trim()?.lowercase(Locale.ROOT) == "auto"
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
