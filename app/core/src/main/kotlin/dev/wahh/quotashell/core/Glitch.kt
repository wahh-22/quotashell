package dev.wahh.quotashell.core

import kotlin.math.min
import kotlin.math.roundToInt

/** Fit both axes, including enlargement, without unbounded type/bitmap growth. */
fun boardFit(
    width: Float,
    height: Float,
    requiredWidth: Float,
    requiredHeight: Float,
): Float {
    require(listOf(width, height, requiredWidth, requiredHeight).all { it.isFinite() && it > 0f })
    return min(1.8f, min(width / requiredWidth, height / requiredHeight))
}

/** A quota item is a chart followed by its widest text line, not a vertical stack. */
fun quotaItemWidth(
    chartWidth: Float,
    textWidths: List<Float>,
): Float {
    require(chartWidth.isFinite() && chartWidth >= 0f && textWidths.all { it.isFinite() && it >= 0f })
    return chartWidth + 3f + (textWidths.maxOrNull() ?: 0f)
}

/** Pixel-aligned bounds used verbatim by both Canvas and the RemoteViews target. */
data class WidgetSyncGeometry(
    val widthPixels: Int,
    val heightPixels: Int,
    val topPixels: Int,
    val endPixels: Int,
    val density: Float,
) {
    val widthDp get() = widthPixels / density
    val heightDp get() = heightPixels / density
    val topDp get() = topPixels / density
    val endDp get() = endPixels / density
}

fun widgetSyncGeometry(
    fit: Float,
    buttonWidth: Float,
    extra: Float,
    density: Float,
): WidgetSyncGeometry {
    require(fit > 0f && buttonWidth > 0f && extra >= 0f && density > 0f)

    fun pixels(value: Float) = (value * fit * density).roundToInt().coerceAtLeast(1)
    return WidgetSyncGeometry(pixels(buttonWidth), pixels(34 + extra * 2), pixels(3f), pixels(6f), density)
}

/** Platform-independent sparse sampling: each frame touches only one to three elements. */
fun glitchElementIndices(
    seed: Int,
    elementCount: Int,
): Set<Int> {
    require(elementCount >= 0)
    if (elementCount == 0) return emptySet()
    var state = seed.toUInt() xor 0x9e3779b9u

    fun next(): UInt {
        state = state xor (state shl 13)
        state = state xor (state shr 17)
        state = state xor (state shl 5)
        return state
    }
    val count = 1 + (next() % min(3, elementCount).toUInt()).toInt()
    val remaining = (0 until elementCount).toMutableList()
    return buildSet {
        repeat(count) { add(remaining.removeAt((next() % remaining.size.toUInt()).toInt())) }
    }
}
