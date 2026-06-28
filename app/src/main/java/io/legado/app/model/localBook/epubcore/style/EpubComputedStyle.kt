package io.legado.app.model.localBook.epubcore.style

data class EpubComputedStyle(
    val rawDeclarations: Map<String, EpubStyleValue> = emptyMap(),
    val display: EpubDisplay = EpubDisplay.Block,
    val position: EpubPosition = EpubPosition.Static,
    val width: EpubSizeValue = EpubSizeValue.Auto,
    val height: EpubSizeValue = EpubSizeValue.Auto,
    val minWidth: EpubSizeValue = EpubSizeValue.Auto,
    val maxWidth: EpubSizeValue = EpubSizeValue.Auto,
    val minHeight: EpubSizeValue = EpubSizeValue.Auto,
    val maxHeight: EpubSizeValue = EpubSizeValue.Auto,
    val margin: EpubEdgeValue = EpubEdgeValue.Zero,
    val padding: EpubEdgeValue = EpubEdgeValue.Zero,
    val border: EpubBorderValue = EpubBorderValue.None,
    val borderRadius: EpubBorderRadius = EpubBorderRadius.Zero,
    val fontFamily: String? = null,
    val fontSizePx: Float? = null,
    val fontWeight: Int? = null,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val lineHeightPx: Float? = null,
    val color: Int? = null,
    val backgroundColor: Int? = null,
    val background: EpubBackground = EpubBackground.None,
    val opacity: Float = 1f,
    val letterSpacingPx: Float = 0f,
    val wordSpacingPx: Float = 0f,
    val textTransform: EpubTextTransform = EpubTextTransform.None,
    val verticalAlign: EpubVerticalAlign = EpubVerticalAlign.Baseline,
    val textAlign: EpubTextAlign = EpubTextAlign.Start,
    val textIndentPx: Float = 0f,
    val whiteSpace: EpubWhiteSpace = EpubWhiteSpace.Normal,
    val listStyle: EpubListStyle = EpubListStyle.Default,
    val pageBreakBefore: EpubBreak = EpubBreak.Auto,
    val pageBreakAfter: EpubBreak = EpubBreak.Auto,
    val pageBreakInside: EpubBreakInside = EpubBreakInside.Auto
)

enum class EpubDisplay {
    None,
    Block,
    Inline,
    InlineBlock,
    Flex,
    Table,
    TableRow,
    TableCell,
    ListItem
}

enum class EpubPosition {
    Static,
    Relative,
    Absolute,
    Fixed
}

enum class EpubTextAlign {
    Start,
    Center,
    End,
    Justify
}

enum class EpubWhiteSpace {
    Normal,
    Pre,
    PreWrap
}

enum class EpubTextTransform {
    None,
    Uppercase,
    Lowercase,
    Capitalize
}

enum class EpubVerticalAlign {
    Baseline,
    Sub,
    Super,
    Top,
    Middle,
    Bottom
}

enum class EpubBreak {
    Auto,
    Always,
    Avoid
}

enum class EpubBreakInside {
    Auto,
    Avoid
}

sealed interface EpubSizeValue {
    data object Auto : EpubSizeValue
    data class Px(val value: Float) : EpubSizeValue
    data class Percent(val value: Float) : EpubSizeValue
}

data class EpubEdgeValue(
    val leftPx: Float = 0f,
    val topPx: Float = 0f,
    val rightPx: Float = 0f,
    val bottomPx: Float = 0f
) {
    val horizontalPx: Float get() = leftPx + rightPx
    val verticalPx: Float get() = topPx + bottomPx

    companion object {
        val Zero = EpubEdgeValue()
    }
}

data class EpubBorderValue(
    val color: Int? = null,
    val widthPx: Float = 0f
) {
    companion object {
        val None = EpubBorderValue()
    }
}

data class EpubBorderRadius(
    val topLeftPx: Float = 0f,
    val topRightPx: Float = 0f,
    val bottomRightPx: Float = 0f,
    val bottomLeftPx: Float = 0f
) {
    val maxPx: Float
        get() = maxOf(topLeftPx, topRightPx, bottomRightPx, bottomLeftPx)

    companion object {
        val Zero = EpubBorderRadius()
    }
}

data class EpubBackground(
    val imageHref: String? = null,
    val repeat: EpubBackgroundRepeat = EpubBackgroundRepeat.Repeat,
    val size: EpubBackgroundSize = EpubBackgroundSize.Auto,
    val position: EpubBackgroundPosition = EpubBackgroundPosition.Center
) {
    companion object {
        val None = EpubBackground()
    }
}

enum class EpubBackgroundRepeat {
    Repeat,
    NoRepeat,
    RepeatX,
    RepeatY
}

sealed interface EpubBackgroundSize {
    data object Auto : EpubBackgroundSize
    data object Cover : EpubBackgroundSize
    data object Contain : EpubBackgroundSize
    data class Explicit(val width: EpubSizeValue, val height: EpubSizeValue = EpubSizeValue.Auto) : EpubBackgroundSize
}

data class EpubBackgroundPosition(
    val xPercent: Float,
    val yPercent: Float
) {
    companion object {
        val Center = EpubBackgroundPosition(0.5f, 0.5f)
    }
}

data class EpubListStyle(
    val type: EpubListStyleType = EpubListStyleType.Inherit,
    val position: EpubListStylePosition = EpubListStylePosition.Outside
) {
    companion object {
        val Default = EpubListStyle()
    }
}

enum class EpubListStyleType {
    Inherit,
    None,
    Disc,
    Circle,
    Square,
    Decimal,
    LowerAlpha,
    UpperAlpha,
    LowerRoman,
    UpperRoman
}

enum class EpubListStylePosition {
    Outside,
    Inside
}
