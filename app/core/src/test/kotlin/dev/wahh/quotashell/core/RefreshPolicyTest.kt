package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RefreshPolicyTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    @Test fun defaultStaleThresholdOutlivesTheRefreshInterval() {
        assertEquals(60, RefreshPolicy.staleMinutes(null))
        assertTrue(RefreshPolicy.staleMinutes(null) > RefreshPolicy.INTERVAL_MINUTES)
    }

    @Test fun storedValuesBelowTheIntervalAreRaisedAndHugeValuesCapped() {
        assertEquals(30, RefreshPolicy.staleMinutes(5))
        assertEquals(30, RefreshPolicy.staleMinutes(30))
        assertEquals(90, RefreshPolicy.staleMinutes(90))
        assertEquals(1440, RefreshPolicy.staleMinutes(5000))
    }

    @Test fun scheduledDataIsNotStaleBeforeTheNextRefresh() {
        val snapshot =
            ProviderSnapshot(
                ProviderId.OPENCODE_GO,
                Connection.CONNECTED,
                now.minusSeconds(29 * 60),
                listOf(QuotaWindow(WindowKind.WEEK, 1.0)),
            )
        assertEquals("Connected", present(snapshot, now, RefreshPolicy.staleMinutes(5) * 60L).state)
    }

    @Test fun settingsAcceptOnlyTheSupportedRange() {
        assertFalse(RefreshPolicy.isValidStaleMinutes(29))
        assertTrue(RefreshPolicy.isValidStaleMinutes(30))
        assertTrue(RefreshPolicy.isValidStaleMinutes(1440))
        assertFalse(RefreshPolicy.isValidStaleMinutes(1441))
    }
}
