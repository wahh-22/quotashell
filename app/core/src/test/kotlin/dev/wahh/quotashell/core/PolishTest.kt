package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PolishTest {
    @Test fun localResetMatchesBoardAcrossZonesAndMissingValues() {
        val instant = Instant.parse("2026-11-02T13:21:00Z")
        val snapshot = ProviderSnapshot(ProviderId.CODEX, windows = listOf(QuotaWindow(WindowKind.WEEK, 35.0, instant)))
        for (zone in listOf("America/Bogota", "Asia/Tokyo", "America/New_York")) {
            val local = formatLocalReset(instant, ZoneId.of(zone))
            assertEquals(local, board(listOf(snapshot), instant, 3600, ZoneId.of(zone), allCaps = false).cards[0].rows[0].reset)
        }
        assertEquals("02 Nov 08:21", formatLocalReset(instant, ZoneId.of("America/Bogota")))
        assertEquals("--", formatLocalReset(null, ZoneId.of("UTC")))
    }

    @Test fun selectedElementsAreBoundedDistinctStableAndVary() {
        for (count in 0..30) {
            val selections =
                (0..100).map { seed ->
                    val selected = glitchElementIndices(seed, count)
                    assertEquals(selected, glitchElementIndices(seed, count))
                    assertEquals(selected.size, selected.toSet().size)
                    assertTrue(selected.all { it in 0 until count })
                    assertTrue(if (count == 0) selected.isEmpty() else selected.size in 1..minOf(3, count))
                    selected
                }
            if (count > 1) assertTrue(selections.toSet().size > 1)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeElementCountIsRejected() {
        glitchElementIndices(1, -1)
    }
}
