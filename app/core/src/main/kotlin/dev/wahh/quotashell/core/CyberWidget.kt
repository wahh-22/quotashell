package dev.wahh.quotashell.core

import kotlin.math.roundToInt

enum class ChartStyle { CELLS, TICKS, RING }

data class ColumnRect(
    val left: Float,
    val right: Float,
) {
    val width: Float get() = right - left
}

/**
 * Keep measured minimums; the renderer scales the whole board if the host is too narrow.
 * Surplus width follows [weights] (e.g. chart counts) so a column with fewer charts stays narrow
 * even when its text needs as much room as the others; by default it follows the minimums.
 */
fun allocateColumns(
    requiredWidths: List<Float>,
    availableWidth: Float,
    gap: Float,
    weights: List<Float>? = null,
): List<ColumnRect> {
    require(availableWidth.isFinite() && availableWidth >= 0 && gap.isFinite() && gap >= 0)
    require(requiredWidths.all { it.isFinite() && it >= 0 })
    require(weights == null || (weights.size == requiredWidths.size && weights.all { it.isFinite() && it >= 0 }))
    if (requiredWidths.isEmpty()) return emptyList()
    val minimums = requiredWidths.map { it.coerceAtLeast(48f) }
    val total = minimums.sum()
    val surplus = (availableWidth - gap * (minimums.size - 1)).coerceAtLeast(total) - total
    val shares = weights ?: minimums
    val shareTotal = shares.sum()
    var left = 0f
    return minimums.mapIndexed { index, required ->
        val extra = if (shareTotal > 0f) surplus * shares[index] / shareTotal else surplus / minimums.size
        val rect = ColumnRect(left, left + required + extra)
        left = rect.right + gap
        rect
    }
}

/** Nearest segment; unknown stays empty and the caller labels it N/A. */
fun filledSegments(
    percent: Int?,
    count: Int,
): Int = ((percent ?: 0).coerceIn(0, 100) / 100f * count.coerceAtLeast(0)).roundToInt()

class GlitchSchedule {
    val children = 40
    val glitchIndices = setOf(38, 39)
    val intervalMillis = 200
    val periodMillis get() = children * intervalMillis
}

fun periodicGlitchEnabled(
    animation: String,
    animationsEnabled: Boolean,
): Boolean = animationsEnabled && animation == "GLITCH"

fun shouldEnqueueWidgetRefresh(pending: Boolean): Boolean = !pending
