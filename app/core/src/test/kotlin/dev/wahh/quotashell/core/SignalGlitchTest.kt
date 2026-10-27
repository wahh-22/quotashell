package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt

class SignalGlitchTest {
    @Test fun lossPlansAreDistinctBoundedDeterministicAndProtectRows() {
        val protected = setOf(0, 1, 10, 11)
        val plans =
            (0L..999L).map { seed ->
                val plan = signalLossPlan(seed, 2, 12, 40, protected)
                assertEquals(plan, signalLossPlan(seed, 2, 12, 40, protected))
                assertTrue(plan.shifts.size in 1..3)
                assertEquals(
                    plan.shifts.size,
                    plan.shifts
                        .map { it.row }
                        .toSet()
                        .size,
                )
                plan.shifts.forEach {
                    assertTrue(kotlin.math.abs(it.cells) in 1..2)
                    assertFalse(it.row in protected)
                }
                assertTrue(plan.corruptions.size in 4..10)
                assertFalse(plan.corruptions.joinToString("") { it.glyph.toString() }.contains("N O   S I G N A L"))
                plan.corruptions.forEach {
                    assertFalse(it.row in protected)
                    assertTrue(it.column in 0 until 40)
                    assertTrue(it.glyph in SIGNAL_GLYPHS)
                    assertTrue(it.colorRole in SIGNAL_NOISE_ROLES)
                }
                assertTrue(plan.staticRows.all { it !in protected })
                plan
            }
        assertTrue(plans.toSet().size > 900)
        assertTrue(plans.count { it.staticRows.isNotEmpty() } in 280..390)
        assertNotEquals(signalLossPlan(42, 0, 12, 40), signalLossPlan(42, 1, 12, 40))
        assertTrue(signalLossPlan(42, 0, 2, 4, setOf(0, 1)).shifts.isEmpty())
    }

    @Test fun tinyAndEmptyBoardsKeepCellsDistinctWithoutTouchingProtectedRows() {
        for (seed in 0L..100L) {
            val cells = signalLossPlan(seed, 0, 3, 5, setOf(0)).corruptions
            assertEquals(cells.size, cells.map { it.row to it.column }.toSet().size)
            assertTrue(cells.size in 4..10)
        }
        assertEquals(1, signalLossPlan(42, 0, 1, 1).corruptions.size)
        assertTrue(signalLossPlan(42, 0, 0, 0).corruptions.isEmpty())
        assertTrue(signalLossPlan(42, 0, 3, 0).staticRows.isEmpty())
    }

    @Test fun decodingUsesTheReferenceFormulaAndHalfOfChangedCells() {
        for (column in listOf(0.0, 0.25, 0.5, 1.0)) {
            assertEquals(0.2 + 0.7 * (1 - sqrt(1 - column)) + 0.03, decodeResolveTime(column, 0.3), 1e-12)
        }
        assertTrue(decodeResolveTime(0.1, 1.0) < decodeResolveTime(0.9, 0.0))
        val text = "Q".repeat(1000)
        val changed = decodeReveal(" ".repeat(1000), text, 42, 0, 0.0)
        assertEquals(changed, decodeReveal(" ".repeat(1000), text, 42, 0, 0.0))
        assertTrue(changed.count { it != 'Q' } in 400..600)
        assertTrue(changed.all { it == 'Q' || it in DECODE_GLYPHS })
        val counts = listOf(0.0, 0.3, 0.6, 1.0).map { p -> decodeReveal("".padEnd(1000), text, 42, 0, p).count { it != 'Q' } }
        assertEquals(counts.sortedDescending(), counts)
        assertEquals(text, decodeReveal("", text, 42, 0, 1.0))
        assertEquals(text, decodeReveal(text, text, 42, 0, 0.0))
        assertEquals("", decodeReveal("old", "", 42, 0, 0.0))
    }

    @Test fun absoluteSyncUsesDeviceZoneAndPreservesStateWithoutRelativeAge() {
        val instant = Instant.parse("2026-11-02T13:21:00Z")
        assertEquals("Sync 08:21", formatLocalSync(instant, ZoneId.of("America/Bogota")))
        assertEquals("Sync 22:21", formatLocalSync(instant, ZoneId.of("Asia/Tokyo")))
        assertEquals("No snapshot", formatLocalSync(null, ZoneId.of("UTC")))
        val snapshot = ProviderSnapshot(ProviderId.CODEX, Connection.CONNECTED, instant)
        assertEquals("Sync 08:21", providerSyncStatus(snapshot, present(snapshot, instant, 3600), ZoneId.of("America/Bogota")))
        val old = instant.plusSeconds(7200)
        assertEquals("Stale · Sync 08:21", providerSyncStatus(snapshot, present(snapshot, old, 3600), ZoneId.of("America/Bogota")))
    }
}
