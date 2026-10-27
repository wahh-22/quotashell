package dev.wahh.quotashell.core

import kotlin.math.min

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
    return chartWidth + 5f + (textWidths.maxOrNull() ?: 0f)
}

data class GlitchFrame(
    val slices: List<GlitchSlice> = emptyList(),
    val rgbOffset: Float = 0f,
    val jitterX: Float = 0f,
    val substituteGlyphs: Boolean = false,
)

fun glitchFrame(
    seed: Int,
    enabled: Boolean,
): GlitchFrame =
    if (!enabled) {
        GlitchFrame()
    } else {
        GlitchFrame(
            glitchSlices(seed),
            0.8f + (seed.toUInt() % 7u).toFloat() * 0.2f,
            ((seed.toUInt() % 5u).toInt() - 2) * 0.5f,
            true,
        )
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

const val GLITCH_GLYPHS = "█▓▒░#@$%&/\\<>01"

/** At most one in eight UTF-16 positions, stable across platforms; short labels still glitch. */
fun glitchGlyphIndices(
    length: Int,
    seed: Int,
): Set<Int> {
    require(length >= 0)
    if (length == 0) return emptySet()
    val start = (seed.toUInt() % min(8, length).toUInt()).toInt()
    return (start until length step 8).toSet()
}

fun substituteGlitchGlyphs(
    value: String,
    indices: Set<Int>,
    seed: Int,
): String =
    value
        .mapIndexed { index, char ->
            if (index in indices) GLITCH_GLYPHS[((seed.toUInt() + index.toUInt()) % GLITCH_GLYPHS.length.toUInt()).toInt()] else char
        }.joinToString("")
