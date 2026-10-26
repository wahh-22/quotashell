package dev.wahh.quotashell.core

const val DONUT_START_DEGREES = -90f

/** Unknown values have an empty track; the caller keeps N/A distinct from zero. */
fun donutSweep(percent: Int?): Float = (percent ?: 0).coerceIn(0, 100) * 3.6f

/** Strip only presentation age, not error/stale state, quota, inventory or reset changes. */
private fun Board.contentKey(): Board =
    copy(
        cards =
            cards.map { card ->
                card.copy(status = card.status.replace(Regex(" · (just now|[0-9]+[mhd] ago)$", RegexOption.IGNORE_CASE), ""))
            },
        stamp = "",
    )

fun Board.glitchSeed(): Int = contentKey().hashCode()

fun shouldGlitchBurst(
    previous: Board?,
    current: Board,
    animationsEnabled: Boolean,
    animation: String,
): Boolean =
    animationsEnabled && animation == "GLITCH" && previous != null &&
        (current.alertCount > previous.alertCount || current.contentKey() != previous.contentKey())

data class GlitchSlice(
    val topFraction: Float,
    val heightFraction: Float,
    val offset: Int,
)

/** Small deterministic integer PRNG, independent of wall time and platform random state. */
fun glitchSlices(seed: Int): List<GlitchSlice> {
    var state = seed

    fun next(bound: Int): Int {
        state = state * 1664525 + 1013904223
        return (state ushr 1) % bound
    }
    return List(3) { GlitchSlice(next(950) / 1000f, (10 + next(31)) / 1000f, next(7) - 3) }
}
