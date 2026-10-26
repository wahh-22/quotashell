package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PresentationTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    @Test fun disconnectedNeverInventsQuotaOrResetInventory() {
        val display = present(ProviderSnapshot(ProviderId.CODEX), now)
        assertEquals("Disconnected", display.state)
        assertEquals("No snapshot", display.age)
        assertTrue(display.windows.isEmpty())
        assertNull(display.resetInventory)
    }

    @Test fun optionalWindowsKeepUnknownSeparateFromZeroAndResetCredits() {
        val display =
            present(
                ProviderSnapshot(
                    provider = ProviderId.CODEX,
                    connection = Connection.CONNECTED,
                    capturedAt = now,
                    windows =
                        listOf(
                            QuotaWindow(WindowKind.FIVE_HOUR, 0.0, now.plusSeconds(3600)),
                            QuotaWindow(WindowKind.WEEK, null, null),
                        ),
                    resetInventoryCount = 3,
                ),
                now,
            )
        assertEquals("Connected", display.state)
        assertEquals(0, display.windows[0].progress)
        assertEquals("0% used", display.windows[0].usage)
        assertEquals("2026-01-01 13:00 UTC", display.windows[0].reset)
        assertNull(display.windows[1].progress)
        assertEquals("Unavailable", display.windows[1].usage)
        assertEquals("Reset unknown", display.windows[1].reset)
        assertEquals("Codex resets available: 3", display.resetInventory)
    }

    @Test fun recognizedWindowsKeepTheirNameAndOnlyUnknownShowDuration() {
        val display =
            present(
                ProviderSnapshot(
                    ProviderId.CODEX,
                    Connection.CONNECTED,
                    now,
                    listOf(
                        QuotaWindow(WindowKind.WEEK, 35.0, null, 604800),
                        QuotaWindow(WindowKind.FIVE_HOUR, 1.0, null, 18000),
                        QuotaWindow(WindowKind.OTHER, 2.0, null, 86400),
                    ),
                ),
                now,
            )
        assertEquals(listOf("Week", "5h", "24h"), display.windows.map { it.title })
    }

    @Test fun staleBoundaryAndAuthErrorsDoNotHideOldSnapshot() {
        val snapshot =
            ProviderSnapshot(
                ProviderId.CLAUDE,
                Connection.CONNECTED,
                now.minusSeconds(900),
                listOf(QuotaWindow(WindowKind.WEEK, 42.5)),
            )
        assertEquals("Stale", present(snapshot, now).state)
        assertEquals("Connected", present(snapshot, now.minusSeconds(1)).state)
        val reconnect = present(snapshot.copy(connection = Connection.RECONNECT_REQUIRED), now)
        assertEquals("Reconnect required", reconnect.state)
        assertEquals(43, reconnect.windows.single().progress)
        assertEquals("15m ago", reconnect.age)
        assertEquals(
            "Error · Network",
            present(
                snapshot.copy(
                    connection = Connection.ERROR,
                    failure = Failure.NETWORK,
                ),
                now,
            ).state,
        )
    }

    @Test fun unknownTimestampAndFutureClockNeverClaimFreshness() {
        assertEquals(
            "No snapshot",
            present(
                ProviderSnapshot(
                    ProviderId.OPENCODE_GO,
                    Connection.CONNECTED,
                ),
                now,
            ).state,
        )
        assertEquals(
            "Clock mismatch",
            present(
                ProviderSnapshot(
                    ProviderId.CODEX,
                    Connection.CONNECTED,
                    now.plusSeconds(120),
                ),
                now,
            ).age,
        )
    }

    @Test fun themeDefaultsAndNormalizationAreDeterministic() {
        // PhantomShell's measured blue and panel fill.
        assertEquals(0xFF347AFF.toInt(), TerminalTheme().accentArgb)
        assertEquals(0xE6070B19.toInt(), TerminalTheme().backgroundArgb)
        assertEquals(230, TerminalTheme().backgroundAlpha)
        assertEquals(0, TerminalTheme(opacityPercent = -9).backgroundAlpha)
        assertEquals(255, TerminalTheme(opacityPercent = 130).backgroundAlpha)
        assertEquals(TerminalTheme().accentArgb, TerminalTheme(accentHex = "bad!").accentArgb)
        assertEquals(0xFF00AAFF.toInt(), TerminalTheme(accentHex = "#00aaff").accentArgb)
    }

    @Test fun invalidPercentCannotTurnIntoBelievableQuota() {
        for (invalid in listOf(-1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { QuotaWindow(WindowKind.MONTH, invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderSnapshot(ProviderId.CODEX, resetInventoryCount = -1)
        }
    }
}
