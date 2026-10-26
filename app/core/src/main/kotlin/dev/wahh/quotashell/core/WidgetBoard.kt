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
        ProviderId.CODEX to ("CDX-01" to "CODEX"),
        ProviderId.CLAUDE to ("CLD-02" to "CLAUDE"),
        ProviderId.OPENCODE_GO to ("OCG-03" to "OPENCODE GO"),
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
): Board {
    val cards =
        ProviderId.entries.map { provider ->
            val snapshot = snapshots.firstOrNull { it.provider == provider } ?: ProviderSnapshot(provider)
            card(snapshot, present(snapshot, now, staleAfterSeconds), zone)
        }
    val latest = snapshots.mapNotNull { it.capturedAt }.maxOrNull()
    return Board(
        cards,
        latest?.let { "LAST SYNC " + stampFormat.format(it.atZone(zone)) } ?: "NO DATA",
        cards.count { it.alert },
    )
}

private fun card(
    snapshot: ProviderSnapshot,
    display: ProviderDisplay,
    zone: ZoneId,
): BoardCard {
    val (code, name) = codes.getValue(snapshot.provider)
    val status =
        when (snapshot.connection) {
            Connection.DISCONNECTED -> "NOT CONNECTED"
            Connection.CONNECTED -> if (display.state == "Connected") "SYNC · ${display.age}" else display.state.withAge(display.age)
            else -> display.state.withAge(display.age)
        }.uppercase(Locale.US)
    val alert =
        snapshot.connection == Connection.RECONNECT_REQUIRED ||
            snapshot.connection == Connection.ERROR ||
            display.state.startsWith("Stale")
    val rows =
        snapshot.windows.zip(display.windows) { window, shown ->
            val percent = window.usedPercent?.let { kotlin.math.round(it).toInt() }
            BoardRow(
                shown.title.uppercase(Locale.US),
                percent?.let { "$it%" } ?: "N/A",
                percent,
                window.resetsAt?.let { resetFormat.format(it.atZone(zone)).uppercase(Locale.US) } ?: "--",
                percent != null && percent >= HOT_PERCENT,
            )
        }
    val note =
        if (snapshot.provider == ProviderId.CODEX && snapshot.capturedAt != null) {
            "RESETS AVAILABLE · " + (snapshot.resetInventoryCount?.toString() ?: "UNKNOWN")
        } else {
            null
        }
    return BoardCard(code, name, status, alert, rows, note)
}

private fun String.withAge(age: String): String = if (age == "No snapshot" || contains(age)) this else "$this · $age"
