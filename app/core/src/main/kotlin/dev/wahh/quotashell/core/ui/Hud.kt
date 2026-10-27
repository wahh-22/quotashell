package dev.wahh.quotashell.core.ui

import dev.wahh.quotashell.core.Connection
import dev.wahh.quotashell.core.ProviderSnapshot
import dev.wahh.quotashell.core.present
import dev.wahh.quotashell.core.signalMix
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation only: authentication failures always outrank an in-flight refresh. */
fun hudStatus(
    snapshot: ProviderSnapshot,
    now: Instant,
    staleAfterSeconds: Long,
    syncing: Boolean = false,
): String =
    when {
        snapshot.connection == Connection.RECONNECT_REQUIRED -> "RECONNECT"
        snapshot.connection == Connection.DISCONNECTED || snapshot.connection == Connection.ERROR -> "NO CARRIER"
        syncing -> "SYNCING"
        snapshot.capturedAt == null -> "NO CARRIER"
        present(snapshot, now, staleAfterSeconds).state.startsWith("Stale") -> "STALE"
        else -> "LINK OK"
    }

/** Link availability, not a physical radio-strength reading. */
fun hudSignal(links: Int): String =
    "SIG " + "▂▄█".mapIndexed { index, char -> if (index < links.coerceIn(0, 3)) char else '▯' }.joinToString("")

fun hudClock(
    now: Instant,
    zone: ZoneId,
): String = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(zone).format(now)

fun hudBurstDelay(seed: Long): Long = 10_000L + (signalMix(seed, 701).toULong() % 12_001u).toLong()

fun hudBurstDuration(seed: Long): Long = 600L + (signalMix(seed, 702).toULong() % 401u).toLong()
