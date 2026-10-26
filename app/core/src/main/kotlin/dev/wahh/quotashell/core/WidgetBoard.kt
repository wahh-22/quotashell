package dev.wahh.quotashell.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Usage at or above this percentage is highlighted on the widget. */
const val HOT_PERCENT = 85

data class BoardRow(
    val label: String,
    val usage: String,
    val percent: Int?,
    val reset: String,
    val hot: Boolean,
)

data class BoardCard(
    val code: String,
    val name: String,
    val status: String,
    val alert: Boolean,
    val rows: List<BoardRow>,
    val note: String?,
)

data class Board(
    val cards: List<BoardCard>,
    val stamp: String,
    val alertCount: Int,
)

private val resetFormat = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.US)
private val stampFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

private val codes =
    mapOf(
        ProviderId.CODEX to ("CDX-01" to "Codex"),
        ProviderId.CLAUDE to ("CLD-02" to "Claude"),
        ProviderId.OPENCODE_GO to ("OCG-03" to "OpenCode Go"),
    )

/**
 * One widget showing every provider in a fixed order. Built on [present] so states and ages
 * follow the same rules as the dashboard; resets are shown in the device's zone.
 */
fun board(
    snapshots: List<ProviderSnapshot>,
    now: Instant,
    staleAfterSeconds: Long,
    zone: ZoneId,
    allCaps: Boolean = true,
): Board {
    val cards =
        ProviderId.entries.map { provider ->
            val snapshot = snapshots.firstOrNull { it.provider == provider } ?: ProviderSnapshot(provider)
            card(snapshot, present(snapshot, now, staleAfterSeconds), zone)
        }
    val latest = snapshots.mapNotNull { it.capturedAt }.maxOrNull()
    return Board(
        cards,
        latest?.let { "Last sync " + stampFormat.format(it.atZone(zone)) } ?: "No data",
        cards.count { it.alert },
    ).withCasing(allCaps)
}

private fun card(
    snapshot: ProviderSnapshot,
    display: ProviderDisplay,
    zone: ZoneId,
): BoardCard {
    val (code, name) = codes.getValue(snapshot.provider)
    val status =
        when (snapshot.connection) {
            Connection.DISCONNECTED -> "Not connected"
            Connection.CONNECTED -> if (display.state == "Connected") "Sync · ${display.age}" else display.state.withAge(display.age)
            else -> display.state.withAge(display.age)
        }
    val alert =
        snapshot.connection == Connection.RECONNECT_REQUIRED ||
            snapshot.connection == Connection.ERROR ||
            display.state.startsWith("Stale")
    val rows =
        snapshot.windows.zip(display.windows) { window, shown ->
            val percent = window.usedPercent?.let { kotlin.math.round(it).toInt() }
            BoardRow(
                shown.title,
                percent?.let { "$it%" } ?: "N/A",
                percent,
                window.resetsAt?.let { resetFormat.format(it.atZone(zone)) } ?: "--",
                percent != null && percent >= HOT_PERCENT,
            )
        }
    val note =
        if (snapshot.provider == ProviderId.CODEX && snapshot.capturedAt != null) {
            "Resets · " + (snapshot.resetInventoryCount?.toString() ?: "Unknown")
        } else {
            null
        }
    return BoardCard(code, name, status, alert, rows, note)
}

private fun String.withAge(age: String): String = if (age == "No snapshot" || contains(age)) this else "$this · $age"

/** Natural text stays intact until the presentation boundary; default board callers retain legacy casing. */
fun Board.withCasing(allCaps: Boolean): Board {
    if (!allCaps) return this

    fun String.caps() = uppercase(Locale.US)
    return copy(
        cards =
            cards.map { card ->
                card.copy(
                    name = card.name.caps(),
                    status = card.status.caps(),
                    note = card.note?.caps(),
                    rows = card.rows.map { it.copy(label = it.label.caps(), reset = it.reset.caps()) },
                )
            },
        stamp = stamp.caps(),
    )
}
