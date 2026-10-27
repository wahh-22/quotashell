package dev.wahh.quotashell.core

const val DONUT_START_DEGREES = -90f

/** Unknown values have an empty track; the caller keeps N/A distinct from zero. */
fun donutSweep(percent: Int?): Float = (percent ?: 0).coerceIn(0, 100) * 3.6f

/** Strip only presentation age, not error/stale state, quota, inventory or reset changes. */
private fun Board.contentKey(): Board =
    copy(
        cards =
            cards.map { card ->
                card.copy(status = card.status.replace(Regex("Sync [0-9]{2}:[0-9]{2}$", RegexOption.IGNORE_CASE), "Sync"))
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
