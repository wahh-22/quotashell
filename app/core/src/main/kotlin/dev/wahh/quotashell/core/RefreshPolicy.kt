package dev.wahh.quotashell.core

/**
 * Background refresh cadence and the stale threshold derived from it. A threshold
 * below the interval would label scheduled, healthy data as stale between runs.
 */
object RefreshPolicy {
    const val INTERVAL_MINUTES = 30
    const val DEFAULT_STALE_MINUTES = 60
    const val MIN_STALE_MINUTES = INTERVAL_MINUTES
    const val MAX_STALE_MINUTES = 1440

    /** Normalizes a stored threshold; values saved by older builds below the interval are raised. */
    fun staleMinutes(stored: Int?): Int = (stored ?: DEFAULT_STALE_MINUTES).coerceIn(MIN_STALE_MINUTES, MAX_STALE_MINUTES)

    fun isValidStaleMinutes(minutes: Int): Boolean = minutes in MIN_STALE_MINUTES..MAX_STALE_MINUTES
}
