package io.legado.app.model.localBook.epubcore.layout

import io.legado.app.model.localBook.epubcore.style.EpubComputedStyle
import io.legado.app.model.localBook.epubcore.style.EpubSizeValue
import kotlin.math.max

internal fun EpubSizeValue.resolve(relativeTo: Float): Float? {
    val base = relativeTo.takeIf { it.isFinite() && it > 0f } ?: return when (this) {
        EpubSizeValue.Auto -> null
        is EpubSizeValue.Percent -> null
        is EpubSizeValue.Px -> value
    }
    return when (this) {
        EpubSizeValue.Auto -> null
        is EpubSizeValue.Percent -> base * value
        is EpubSizeValue.Px -> value
    }
}

internal fun Float.coerceInStyleBounds(
    min: EpubSizeValue,
    max: EpubSizeValue,
    relativeTo: Float
): Float {
    val minPx = min.resolve(relativeTo)
    val maxPx = max.resolve(relativeTo)
    val minBounded = minPx?.let { coerceAtLeast(it) } ?: this
    return maxPx?.let { minBounded.coerceAtMost(it) } ?: minBounded
}

internal fun EpubComputedStyle.resolveBorderBoxWidth(availableWidth: Float): Float {
    val available = availableWidth.validCssAvailable(1f)
    val resolved = width.resolve(available) ?: available
    return resolved
        .coerceInStyleBounds(minWidth, maxWidth, available)
        .coerceAtMost(available)
        .coerceAtLeast(1f)
}

internal fun EpubComputedStyle.resolveContentWidth(availableWidth: Float): Float {
    return (resolveBorderBoxWidth(availableWidth) - padding.horizontalPx - border.widthPx * 2f)
        .coerceAtLeast(1f)
}

internal fun EpubComputedStyle.resolveBorderBoxHeight(contentBottom: Float, availableHeight: Float): Float {
    val fallback = contentBottom.coerceAtLeast(1f)
    val relative = availableHeight.validCssAvailable(fallback)
    val resolved = height.resolve(relative) ?: fallback
    return resolved
        .coerceInStyleBounds(minHeight, maxHeight, relative)
        .coerceAtLeast(1f)
}

internal fun collapseVerticalMargins(previousBottom: Float, nextTop: Float): Float {
    val previous = previousBottom.coerceAtLeast(0f)
    val next = nextTop.coerceAtLeast(0f)
    return max(previous, next)
}

internal fun Float.validCssAvailable(fallback: Float): Float {
    return takeIf { it.isFinite() && it > 0f } ?: fallback.coerceAtLeast(1f)
}
