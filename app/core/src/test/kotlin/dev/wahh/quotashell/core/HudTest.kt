package dev.wahh.quotashell.core

import dev.wahh.quotashell.core.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HudTest {
    private val now = Instant.parse("2026-10-30T18:42:09Z")

    @Test fun statusPreservesFailureAndFreshnessPriority() {
        fun status(
            connection: Connection,
            time: Instant? = now,
            syncing: Boolean = false,
        ) = hudStatus(ProviderSnapshot(ProviderId.CODEX, connection, time), now, 3600, syncing)
        assertEquals("NO CARRIER", status(Connection.DISCONNECTED))
        assertEquals("RECONNECT", status(Connection.RECONNECT_REQUIRED, syncing = true))
        assertEquals("NO CARRIER", status(Connection.ERROR))
        assertEquals("STALE", status(Connection.STALE))
        assertEquals("NO CARRIER", status(Connection.CONNECTED, null))
        assertEquals("STALE", status(Connection.CONNECTED, now.minusSeconds(3600)))
        assertEquals("STALE", status(Connection.CONNECTED, now.plusSeconds(1)))
        assertEquals("LINK OK", status(Connection.CONNECTED))
        assertEquals("SYNCING", status(Connection.CONNECTED, syncing = true))
    }

    @Test fun signalReflectsAvailableLinksNotInventedRadioStrength() {
        assertEquals("SIG ▯▯▯", hudSignal(0))
        assertEquals("SIG ▂▯▯", hudSignal(1))
        assertEquals("SIG ▂▄▯", hudSignal(2))
        assertEquals("SIG ▂▄█", hudSignal(3))
        assertEquals(hudSignal(0), hudSignal(-1))
        assertEquals(hudSignal(3), hudSignal(99))
        assertEquals("18:42:09", hudClock(now, ZoneId.of("UTC")))
        assertEquals("13:42:09", hudClock(now, ZoneId.of("America/Bogota")))
    }

    @Test fun scheduleIsBoundedAndDeterministic() {
        for (seed in 0L..100L) {
            assertTrue(hudBurstDelay(seed) in 10_000L..22_000L)
            assertTrue(hudBurstDuration(seed) in 600L..1000L)
            assertEquals(hudBurstDelay(seed), hudBurstDelay(seed))
        }
    }
}
