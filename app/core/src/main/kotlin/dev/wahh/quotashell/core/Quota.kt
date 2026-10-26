package dev.wahh.quotashell.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class ProviderId(
    val title: String,
) {
    CODEX("OpenAI · Codex"),
    CLAUDE("Claude"),
    OPENCODE_GO("OpenCode Go"),
}

enum class WindowKind(
    val title: String,
) {
    FIVE_HOUR("5h"),
    WEEK("Week"),
    MONTH("Month"),
    OTHER("Reported window"),
    OTHER_SECONDARY("Second reported window"),
}

enum class Connection { DISCONNECTED, CONNECTED, STALE, RECONNECT_REQUIRED, ERROR }

enum class Failure(
    val title: String,
) {
    NETWORK("Network"),
    RATE_LIMIT("Rate limited"),
    PERMISSION("Permission denied"),
    SERVICE("Service unavailable"),
    INVALID_DATA("Invalid response"),
    STORAGE("Snapshot storage"),
}

/** Percent USED, never money or remaining credit. Null means unreported, not zero. */
data class QuotaWindow(
    val kind: WindowKind,
    val usedPercent: Double? = null,
    val resetsAt: Instant? = null,
    val durationSeconds: Long? = null,
) {
    init {
        require(usedPercent == null || (usedPercent.isFinite() && usedPercent in 0.0..100.0))
        require(durationSeconds == null || durationSeconds > 0)
    }
}

/** No credentials, raw responses, account identifiers or error strings belong here. */
data class ProviderSnapshot(
    val provider: ProviderId,
    val connection: Connection = Connection.DISCONNECTED,
    val capturedAt: Instant? = null,
    val windows: List<QuotaWindow> = emptyList(),
    val resetInventoryCount: Int? = null,
    val failure: Failure? = null,
    val resetInventoryFailure: Failure? = null,
) {
    init {
        require(resetInventoryCount == null || resetInventoryCount >= 0)
        require(windows.map { it.kind }.distinct().size == windows.size)
    }
}

data class WindowDisplay(
    val title: String,
    val usage: String,
    val progress: Int?,
    val reset: String,
)

data class ProviderDisplay(
    val title: String,
    val state: String,
    val age: String,
    val windows: List<WindowDisplay>,
    val resetInventory: String?,
)

private val resetFormat =
    DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)

fun present(
    snapshot: ProviderSnapshot,
    now: Instant,
    staleAfterSeconds: Long = 900,
): ProviderDisplay {
    val ageSeconds = snapshot.capturedAt?.let { Duration.between(it, now).seconds }
    val state =
        when (snapshot.connection) {
            Connection.DISCONNECTED -> {
                "Disconnected"
            }

            Connection.RECONNECT_REQUIRED -> {
                "Reconnect required"
            }

            Connection.ERROR -> {
                "Error" + (snapshot.failure?.let { " · ${it.title}" } ?: "")
            }

            Connection.STALE -> {
                "Stale"
            }

            Connection.CONNECTED -> {
                when {
                    ageSeconds == null -> "No snapshot"
                    ageSeconds < 0 -> "Stale · Clock mismatch"
                    ageSeconds >= staleAfterSeconds -> "Stale"
                    else -> "Connected"
                }
            }
        }
    val age =
        when {
            ageSeconds == null -> "No snapshot"
            ageSeconds < 0 -> "Clock mismatch"
            ageSeconds < 60 -> "just now"
            ageSeconds < 3600 -> "${ageSeconds / 60}m ago"
            ageSeconds < 86400 -> "${ageSeconds / 3600}h ago"
            else -> "${ageSeconds / 86400}d ago"
        }
    return ProviderDisplay(
        snapshot.provider.title,
        state,
        age,
        snapshot.windows.map {
            WindowDisplay(
                // Recognized windows keep their name; only unrecognized durations are spelled out.
                it.durationSeconds
                    ?.takeIf { _ -> it.kind == WindowKind.OTHER || it.kind == WindowKind.OTHER_SECONDARY }
                    ?.let { seconds -> if (seconds % 3600L == 0L) "${seconds / 3600}h" else "${seconds}s" }
                    ?: it.kind.title,
                it.usedPercent?.let { value -> "${java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()}% used" }
                    ?: "Unavailable",
                it.usedPercent?.roundToInt(),
                it.resetsAt?.let(resetFormat::format) ?: "Reset unknown",
            )
        },
        if (snapshot.provider == ProviderId.CODEX) {
            snapshot.resetInventoryCount?.let {
                "Codex resets available: $it"
            } ?: snapshot.resetInventoryFailure?.let { "Codex resets available: Unknown · ${it.title}" }
        } else {
            null
        },
    )
}

/** Global dashboard/widget preferences. Content remains opaque even on a translucent panel. */
data class TerminalTheme(
    val accentHex: String = DEFAULT_ACCENT_HEX,
    val opacityPercent: Int = 90,
) {
    val accentArgb: Int get() {
        val hex = accentHex.removePrefix("#")
        return (
            0xFF000000L or (
                hex
                    .takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }
                    ?.toLong(16) ?: DEFAULT_ACCENT_HEX.toLong(16)
            )
        ).toInt()
    }
    val backgroundAlpha: Int get() = (opacityPercent.coerceIn(0, 100) * 255.0 / 100).roundToInt()
    val backgroundArgb: Int get() = (backgroundAlpha shl 24) or PANEL_RGB

    companion object {
        /** Rounded RGB from the user's PhantomShell blue.json theme. */
        const val DEFAULT_ACCENT_HEX = "347AFF"
        const val PANEL_RGB = 0x070B1A
    }
}

/** Implement in T3. Called off-main; return sanitized snapshots, retaining timestamps on failures. */
fun interface RefreshGateway {
    fun refresh(current: List<ProviderSnapshot>): RefreshResult
}

sealed interface RefreshResult {
    data class Updated(
        val snapshots: List<ProviderSnapshot>,
    ) : RefreshResult

    data object NotConfigured : RefreshResult
}
