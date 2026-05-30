package io.legado.app.model.localBook.epubcore.style

import android.graphics.Color
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import org.jsoup.nodes.Element
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.roundToInt

class EpubStyleComputer(
    private val baseTextSizePx: Float,
    private val baseTextColor: Int,
    private val loadCss: (baseHref: String, href: String) -> String,
    private val resolveHref: (baseHref: String, href: String) -> String = EpubPath::resolve
) {

    fun collectRules(root: Element, baseHref: String): List<EpubCss.Rule> {
        val rules = arrayListOf<EpubCss.Rule>()
        root.ownerDocument()?.head()?.select("style")?.forEach { style ->
            rules += EpubCss.parseRules(style.data().ifBlank { style.html() })
        }
        root.ownerDocument()?.head()?.select("link[href][rel~=stylesheet]")?.forEach { link ->
            link.attr("href").trim().takeIf { it.isNotBlank() }?.let { href ->
                rules += EpubCss.parseRules(loadCss(baseHref, href))
            }
        }
        root.select("style").forEach { style ->
            rules += EpubCss.parseRules(style.data().ifBlank { style.html() })
        }
        root.select("link[href][rel~=stylesheet]").forEach { link ->
            link.attr("href").trim().takeIf { it.isNotBlank() }?.let { href ->
                rules += EpubCss.parseRules(loadCss(baseHref, href))
            }
        }
        return rules.mapIndexed { index, rule -> rule.copy(order = index) }
    }

    fun matchRules(root: Element, rules: List<EpubCss.Rule>): IdentityHashMap<Element, MutableList<EpubCss.Rule>> {
        val matched = IdentityHashMap<Element, MutableList<EpubCss.Rule>>()
        rules.forEach { rule ->
            runCatching {
                if (root.`is`(rule.selector)) matched.getOrPut(root) { arrayListOf() }.add(rule)
                root.select(rule.selector).forEach { element ->
                    matched.getOrPut(element) { arrayListOf() }.add(rule)
                }
            }
        }
        return matched
    }

    fun compute(
        element: Element,
        parent: EpubComputedStyle,
        rules: List<EpubCss.Rule>,
        baseHref: String
    ): EpubComputedStyle {
        val merged = linkedMapOf<String, EpubStyleValue>()
        parent.rawDeclarations
            .filterKeys { it in inheritableProperties }
            .forEach { (name, value) ->
                merged[name] = value.copy(sourceRank = -1, specificity = 0, ruleOrder = -1, declarationOrder = -1)
            }
        element.tagDefaultDeclarations().forEach { declaration ->
            putDeclaration(merged, declaration, sourceRank = -1, specificity = 0, ruleOrder = -1)
        }
        rules.forEach { rule ->
            rule.declarations.forEach { declaration ->
                putDeclaration(merged, declaration, sourceRank = 0, specificity = rule.specificity, ruleOrder = rule.order)
            }
        }
        EpubCss.parseDeclarations(element.attr("style")).forEach { declaration ->
            putDeclaration(merged, declaration, sourceRank = 1, specificity = 1000, ruleOrder = Int.MAX_VALUE)
        }
        val resolved = merged.mapValues { (_, value) ->
            if (value.value.contains("url(", ignoreCase = true)) {
                value.copy(value = value.value.rewriteCssUrls { href -> resolveHref(baseHref, href) })
            } else {
                value
            }
        }
        return resolved.toComputedStyle(parent)
    }

    private fun putDeclaration(
        merged: LinkedHashMap<String, EpubStyleValue>,
        declaration: EpubCss.Declaration,
        sourceRank: Int,
        specificity: Int,
        ruleOrder: Int
    ) {
        val value = EpubStyleValue(
            value = declaration.value,
            important = declaration.important,
            sourceRank = sourceRank + if (declaration.important) 2 else 0,
            specificity = specificity,
            ruleOrder = ruleOrder,
            declarationOrder = declaration.order
        )
        val current = merged[declaration.name]
        if (current == null || value.hasHigherPriorityThan(current)) {
            merged[declaration.name] = value
        }
    }

    private fun Map<String, EpubStyleValue>.toComputedStyle(parent: EpubComputedStyle): EpubComputedStyle {
        fun raw(name: String): String? = this[name]?.value
        val display = raw("display").toDisplay()
        val position = raw("position").toPosition()
        val fontSize = raw("font-size").toFontSizePx(parent.fontSizePx ?: baseTextSizePx)
            ?: parent.fontSizePx
            ?: baseTextSizePx
        val lineHeight = raw("line-height").toLineHeightPx(fontSize)
            ?: parent.lineHeightPx
        return EpubComputedStyle(
            rawDeclarations = this,
            display = display,
            position = position,
            width = raw("width").toSizeValue(fontSize),
            height = raw("height").toSizeValue(fontSize),
            minWidth = raw("min-width").toSizeValue(fontSize),
            maxWidth = raw("max-width").toSizeValue(fontSize),
            minHeight = raw("min-height").toSizeValue(fontSize),
            maxHeight = raw("max-height").toSizeValue(fontSize),
            margin = edgeValue("margin", fontSize),
            padding = edgeValue("padding", fontSize),
            border = borderValue(fontSize),
            borderRadius = borderRadiusValue(fontSize),
            fontFamily = raw("font-family")?.cleanFontFamily() ?: parent.fontFamily,
            fontSizePx = fontSize,
            fontWeight = raw("font-weight").toFontWeight() ?: parent.fontWeight,
            italic = raw("font-style")?.contains("italic", true) == true || parent.italic,
            underline = raw("text-decoration-line")?.contains("underline", true) == true ||
                raw("text-decoration")?.contains("underline", true) == true ||
                parent.underline,
            lineHeightPx = lineHeight,
            color = raw("color").toColorInt() ?: parent.color ?: baseTextColor,
            backgroundColor = raw("background-color").toColorInt() ?: parent.backgroundColor,
            background = backgroundValue(parent),
            opacity = raw("opacity").toOpacity() ?: parent.opacity,
            letterSpacingPx = raw("letter-spacing").toSpacingPx(fontSize) ?: parent.letterSpacingPx,
            wordSpacingPx = raw("word-spacing").toSpacingPx(fontSize) ?: parent.wordSpacingPx,
            textTransform = raw("text-transform").toTextTransform() ?: parent.textTransform,
            verticalAlign = raw("vertical-align").toVerticalAlign() ?: parent.verticalAlign,
            textAlign = raw("text-align").toTextAlign() ?: parent.textAlign,
            textIndentPx = raw("text-indent").toLengthPx(fontSize, fontSize) ?: 0f,
            whiteSpace = raw("white-space").toWhiteSpace() ?: parent.whiteSpace,
            listStyle = listStyleValue(parent),
            pageBreakBefore = raw("page-break-before").toBreak(),
            pageBreakAfter = raw("page-break-after").toBreak(),
            pageBreakInside = raw("page-break-inside").toBreakInside()
        ).sanitize()
    }

    private fun EpubComputedStyle.sanitize(): EpubComputedStyle {
        return copy(
            display = when (display) {
                EpubDisplay.None -> EpubDisplay.None
                EpubDisplay.Flex, EpubDisplay.Table, EpubDisplay.TableRow, EpubDisplay.TableCell -> display
                else -> display
            },
            position = when (position) {
                EpubPosition.Fixed, EpubPosition.Absolute -> EpubPosition.Static
                else -> position
            }
        )
    }

    private fun Map<String, EpubStyleValue>.edgeValue(prefix: String, fontSizePx: Float): EpubEdgeValue {
        fun side(name: String): Float = this["$prefix-$name"]?.value.toLengthPx(fontSizePx, fontSizePx) ?: 0f
        return EpubEdgeValue(
            leftPx = side("left"),
            topPx = side("top"),
            rightPx = side("right"),
            bottomPx = side("bottom")
        )
    }

    private fun Map<String, EpubStyleValue>.borderValue(fontSizePx: Float): EpubBorderValue {
        val width = this["border-width"]?.value.toLengthPx(fontSizePx, fontSizePx)
            ?: this["border-top-width"]?.value.toLengthPx(fontSizePx, fontSizePx)
            ?: 0f
        val color = this["border-color"]?.value.toColorInt()
            ?: this["border-top-color"]?.value.toColorInt()
        return EpubBorderValue(color = color, widthPx = width)
    }

    private fun Map<String, EpubStyleValue>.borderRadiusValue(fontSizePx: Float): EpubBorderRadius {
        fun radius(name: String): Float = this[name]?.value
            ?.substringBefore('/')
            .toLengthPx(fontSizePx, fontSizePx)
            ?.coerceAtLeast(0f)
            ?: 0f
        return EpubBorderRadius(
            topLeftPx = radius("border-top-left-radius"),
            topRightPx = radius("border-top-right-radius"),
            bottomRightPx = radius("border-bottom-right-radius"),
            bottomLeftPx = radius("border-bottom-left-radius")
        )
    }

    private fun Map<String, EpubStyleValue>.backgroundValue(parent: EpubComputedStyle): EpubBackground {
        val image = this["background-image"]?.value.extractBackgroundUrl()
        return EpubBackground(
            imageHref = image,
            repeat = this["background-repeat"]?.value.toBackgroundRepeat() ?: EpubBackgroundRepeat.Repeat,
            size = this["background-size"]?.value.toBackgroundSize() ?: EpubBackgroundSize.Auto,
            position = this["background-position"]?.value.toBackgroundPosition() ?: EpubBackgroundPosition.Center
        )
    }

    private fun Map<String, EpubStyleValue>.listStyleValue(parent: EpubComputedStyle): EpubListStyle {
        return EpubListStyle(
            type = this["list-style-type"]?.value.toListStyleType() ?: parent.listStyle.type,
            position = this["list-style-position"]?.value.toListStylePosition() ?: parent.listStyle.position
        )
    }

    private fun Element.tagDefaultDeclarations(): List<EpubCss.Declaration> {
        val declarations = arrayListOf<EpubCss.Declaration>()
        fun add(name: String, value: String) {
            declarations += EpubCss.Declaration(name, value, important = false, order = declarations.size)
        }
        when (normalName()) {
            "b", "strong" -> add("font-weight", "bold")
            "i", "em", "cite" -> add("font-style", "italic")
            "u", "a" -> add("text-decoration", "underline")
            "center" -> add("text-align", "center")
            "h1" -> {
                add("font-size", "2em")
                add("font-weight", "bold")
            }
            "h2" -> {
                add("font-size", "1.5em")
                add("font-weight", "bold")
            }
            "h3" -> {
                add("font-size", "1.17em")
                add("font-weight", "bold")
            }
            "h4", "h5", "h6", "th" -> add("font-weight", "bold")
            "li" -> add("display", "list-item")
            "ul" -> add("list-style-type", "disc")
            "ol" -> add("list-style-type", "decimal")
        }
        attr("align").trim().lowercase(Locale.ROOT).toTextAlign()?.let {
            add("text-align", it.name.lowercase(Locale.ROOT))
        }
        return declarations
    }

    private fun String?.toDisplay(): EpubDisplay {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "none" -> EpubDisplay.None
            "inline" -> EpubDisplay.Inline
            "inline-block" -> EpubDisplay.InlineBlock
            "flex", "inline-flex" -> EpubDisplay.Flex
            "table" -> EpubDisplay.Table
            "table-row" -> EpubDisplay.TableRow
            "table-cell" -> EpubDisplay.TableCell
            "list-item" -> EpubDisplay.ListItem
            "grid", "inline-grid" -> EpubDisplay.Block
            else -> EpubDisplay.Block
        }
    }

    private fun String?.toPosition(): EpubPosition {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "relative" -> EpubPosition.Relative
            "absolute" -> EpubPosition.Absolute
            "fixed" -> EpubPosition.Fixed
            else -> EpubPosition.Static
        }
    }

    private fun String?.toTextAlign(): EpubTextAlign? {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "center", "middle", "-webkit-center" -> EpubTextAlign.Center
            "right", "end" -> EpubTextAlign.End
            "justify" -> EpubTextAlign.Justify
            "left", "start" -> EpubTextAlign.Start
            else -> null
        }
    }

    private fun String?.toWhiteSpace(): EpubWhiteSpace? {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "pre" -> EpubWhiteSpace.Pre
            "pre-wrap" -> EpubWhiteSpace.PreWrap
            "normal" -> EpubWhiteSpace.Normal
            else -> null
        }
    }

    private fun String?.toTextTransform(): EpubTextTransform? {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "uppercase" -> EpubTextTransform.Uppercase
            "lowercase" -> EpubTextTransform.Lowercase
            "capitalize" -> EpubTextTransform.Capitalize
            "none" -> EpubTextTransform.None
            else -> null
        }
    }

    private fun String?.toVerticalAlign(): EpubVerticalAlign? {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "sub" -> EpubVerticalAlign.Sub
            "super" -> EpubVerticalAlign.Super
            "top", "text-top" -> EpubVerticalAlign.Top
            "middle" -> EpubVerticalAlign.Middle
            "bottom", "text-bottom" -> EpubVerticalAlign.Bottom
            "baseline" -> EpubVerticalAlign.Baseline
            else -> null
        }
    }

    private fun String?.toOpacity(): Float? {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return null
        return clean.toFloatOrNull()?.coerceIn(0f, 1f)
    }

    private fun String?.toSpacingPx(fontSizePx: Float): Float? {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (clean == "normal") return 0f
        return clean.toLengthPx(fontSizePx, fontSizePx)
    }

    private fun String?.toBackgroundRepeat(): EpubBackgroundRepeat? {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "no-repeat" -> EpubBackgroundRepeat.NoRepeat
            "repeat-x" -> EpubBackgroundRepeat.RepeatX
            "repeat-y" -> EpubBackgroundRepeat.RepeatY
            "repeat" -> EpubBackgroundRepeat.Repeat
            else -> null
        }
    }

    private fun String?.toBackgroundSize(): EpubBackgroundSize? {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when (clean) {
            "auto" -> EpubBackgroundSize.Auto
            "cover" -> EpubBackgroundSize.Cover
            "contain" -> EpubBackgroundSize.Contain
            else -> {
                val values = EpubCss.splitValueList(clean)
                val width = values.getOrNull(0).toSizeValue(1f)
                val height = values.getOrNull(1).toSizeValue(1f)
                if (width != EpubSizeValue.Auto || height != EpubSizeValue.Auto) {
                    EpubBackgroundSize.Explicit(width, height)
                } else {
                    null
                }
            }
        }
    }

    private fun String?.toBackgroundPosition(): EpubBackgroundPosition? {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return null
        val values = EpubCss.splitValueList(clean)
        if (values.isEmpty()) return null
        fun axisPercent(token: String, isX: Boolean): Float? {
            return when (token) {
                "left", "top" -> 0f
                "center" -> 0.5f
                "right", "bottom" -> 1f
                else -> token.removeSuffix("%").toFloatOrNull()?.takeIf { token.endsWith("%") }?.let { it / 100f }
            }?.takeIf { isX || token !in setOf("left", "right") }
        }
        val first = values.getOrNull(0).orEmpty()
        val second = values.getOrNull(1).orEmpty()
        val x = axisPercent(first, true) ?: axisPercent(second, true) ?: 0.5f
        val y = axisPercent(second, false) ?: axisPercent(first, false) ?: 0.5f
        return EpubBackgroundPosition(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    private fun String?.toListStyleType(): EpubListStyleType? {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "none" -> EpubListStyleType.None
            "disc" -> EpubListStyleType.Disc
            "circle" -> EpubListStyleType.Circle
            "square" -> EpubListStyleType.Square
            "decimal" -> EpubListStyleType.Decimal
            "lower-alpha", "lower-latin" -> EpubListStyleType.LowerAlpha
            "upper-alpha", "upper-latin" -> EpubListStyleType.UpperAlpha
            "lower-roman" -> EpubListStyleType.LowerRoman
            "upper-roman" -> EpubListStyleType.UpperRoman
            else -> null
        }
    }

    private fun String?.toListStylePosition(): EpubListStylePosition? {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "inside" -> EpubListStylePosition.Inside
            "outside" -> EpubListStylePosition.Outside
            else -> null
        }
    }

    private fun String?.toBreak(): EpubBreak {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "always", "page", "left", "right" -> EpubBreak.Always
            "avoid" -> EpubBreak.Avoid
            else -> EpubBreak.Auto
        }
    }

    private fun String?.toBreakInside(): EpubBreakInside {
        return when (this?.trim()?.lowercase(Locale.ROOT)) {
            "avoid" -> EpubBreakInside.Avoid
            else -> EpubBreakInside.Auto
        }
    }

    private fun String?.toFontWeight(): Int? {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when (clean) {
            "bold", "bolder" -> 700
            "normal" -> 400
            "lighter" -> 300
            else -> clean.toIntOrNull()
        }
    }

    private fun String?.toFontSizePx(parentPx: Float): Float? {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when {
            clean == "xx-small" -> parentPx * 0.58f
            clean == "x-small" -> parentPx * 0.68f
            clean == "small" -> parentPx * 0.82f
            clean == "medium" -> parentPx
            clean == "large" -> parentPx * 1.18f
            clean == "x-large" -> parentPx * 1.36f
            clean == "xx-large" -> parentPx * 1.55f
            clean == "smaller" -> parentPx * 0.85f
            clean == "larger" -> parentPx * 1.18f
            else -> clean.toLengthPx(parentPx, parentPx)
        }
    }

    private fun String?.toLineHeightPx(fontSizePx: Float): Float? {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (clean == "normal") return fontSizePx * 1.35f
        if (clean.endsWith("%")) return clean.dropLast(1).toFloatOrNull()?.let { fontSizePx * it / 100f }
        clean.toFloatOrNull()?.let { return fontSizePx * it }
        return clean.toLengthPx(fontSizePx, fontSizePx)
    }

    private fun String?.toLengthPx(fontSizePx: Float, relativeTo: Float): Float? {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when {
            clean.endsWith("px") -> clean.dropLast(2).toFloatOrNull()
            clean.endsWith("em") -> clean.dropLast(2).toFloatOrNull()?.let { it * fontSizePx }
            clean.endsWith("rem") -> clean.dropLast(3).toFloatOrNull()?.let { it * baseTextSizePx }
            clean.endsWith("pt") -> clean.dropLast(2).toFloatOrNull()?.let { it * 4f / 3f }
            clean.endsWith("%") -> clean.dropLast(1).toFloatOrNull()?.let { relativeTo * it / 100f }
            clean == "thin" -> 1f
            clean == "medium" -> 2f
            clean == "thick" -> 4f
            else -> clean.toFloatOrNull()
        }
    }

    private fun String?.toSizeValue(fontSizePx: Float): EpubSizeValue {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return EpubSizeValue.Auto
        return when {
            clean == "auto" -> EpubSizeValue.Auto
            clean.endsWith("%") -> clean.dropLast(1).toFloatOrNull()?.let { EpubSizeValue.Percent(it / 100f) } ?: EpubSizeValue.Auto
            else -> clean.toLengthPx(fontSizePx, fontSizePx)?.let { EpubSizeValue.Px(it) } ?: EpubSizeValue.Auto
        }
    }

    private fun String?.toColorInt(): Int? {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when {
            clean == "transparent" -> Color.TRANSPARENT
            clean.startsWith("#") -> runCatching {
                val hex = clean.removePrefix("#")
                when (hex.length) {
                    3 -> Color.rgb(
                        hex[0].toString().repeat(2).toInt(16),
                        hex[1].toString().repeat(2).toInt(16),
                        hex[2].toString().repeat(2).toInt(16)
                    )
                    6 -> Color.rgb(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
                    8 -> Color.argb(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16), hex.substring(6, 8).toInt(16))
                    else -> Color.parseColor(clean)
                }
            }.getOrNull()
            clean.startsWith("rgb") -> clean.substringAfter('(').substringBeforeLast(')')
                .split(',')
                .map { it.trim().removeSuffix("%") }
                .takeIf { it.size >= 3 }
                ?.let { parts ->
                    val r = parts[0].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
                    val g = parts[1].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
                    val b = parts[2].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
                    Color.rgb(r, g, b)
                }
            else -> namedColors[clean]
        }
    }

    private fun String.cleanFontFamily(): String {
        return split(',').firstOrNull().orEmpty().trim().trim('\'', '"')
    }

    private fun String?.extractBackgroundUrl(): String? {
        val clean = this?.trim() ?: return null
        if (clean.equals("none", true)) return null
        return clean.substringAfter("url(", "")
            .substringBeforeLast(")", "")
            .trim()
            .trim('\'', '"')
            .takeIf { it.isNotBlank() }
    }

    private fun String.rewriteCssUrls(resolve: (String) -> String): String {
        val builder = StringBuilder(length)
        var index = 0
        while (index < length) {
            val start = indexOf("url(", index, ignoreCase = true)
            if (start < 0) {
                builder.append(substring(index))
                break
            }
            builder.append(substring(index, start))
            val end = indexOf(')', start + 4)
            if (end < 0) {
                builder.append(substring(start))
                break
            }
            val raw = substring(start + 4, end).trim().trim('\'', '"')
            val resolved = if (raw.startsWith("data:", true) || raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
                raw
            } else {
                resolve(raw)
            }
            builder.append("url('").append(resolved).append("')")
            index = end + 1
        }
        return builder.toString()
    }

    private companion object {
        private val inheritableProperties = setOf(
            "color", "font-family", "font-size", "font-style", "font-weight",
            "line-height", "text-align", "text-decoration", "text-decoration-line",
            "white-space"
        )

        private val namedColors = mapOf(
            "black" to Color.BLACK,
            "white" to Color.WHITE,
            "red" to Color.RED,
            "green" to Color.rgb(0, 128, 0),
            "blue" to Color.BLUE,
            "gray" to Color.GRAY,
            "grey" to Color.GRAY,
            "silver" to Color.rgb(192, 192, 192),
            "yellow" to Color.YELLOW,
            "cyan" to Color.CYAN,
            "aqua" to Color.CYAN,
            "magenta" to Color.MAGENTA,
            "fuchsia" to Color.MAGENTA,
            "orange" to Color.rgb(255, 165, 0),
            "purple" to Color.rgb(128, 0, 128),
            "transparent" to Color.TRANSPARENT
        )
    }
}
