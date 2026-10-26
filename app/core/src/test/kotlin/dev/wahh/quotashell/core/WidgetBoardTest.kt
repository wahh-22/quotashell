package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WidgetBoardTest {
    private val now = Instant.parse("2026-10-26T22:00:00Z")
    private val bogota = ZoneId.of("America/Bogota")

    @Test fun allThreeProvidersAppearInFixedOrderEvenWithoutData() {
        val result = board(listOf(ProviderSnapshot(ProviderId.CLAUDE)), now, 3600, bogota)
        assertEquals(listOf("CDX-01", "CLD-02", "OCG-03"), result.cards.map { it.code })
        assertEquals(listOf("CODEX", "CLAUDE", "OPENCODE GO"), result.cards.map { it.name })
        result.cards.forEach {
            assertEquals("NOT CONNECTED", it.status)
            assertFalse(it.alert)
            assertTrue(it.rows.isEmpty())
            assertNull(it.note)
        }
        assertEquals("NO DATA", result.stamp)
        assertEquals(0, result.alertCount)
    }

    @Test fun connectedCardShowsWindowsWithLocalResetsAndHotFlag() {
        val claude =
            ProviderSnapshot(
                ProviderId.CLAUDE,
                Connection.CONNECTED,
                now.minusSeconds(8 * 60),
                listOf(
                    QuotaWindow(WindowKind.FIVE_HOUR, 38.4, Instant.parse("2026-10-27T00:29:00Z")),
                    QuotaWindow(WindowKind.WEEK, 90.0, Instant.parse("2026-10-29T07:59:00Z"), 604800),
                ),
            )
        val card = board(listOf(claude), now, 3600, bogota).cards[1]
        assertEquals("SYNC · 8M AGO", card.status)
        assertFalse(card.alert)
        assertEquals(listOf("5H", "WEEK"), card.rows.map { it.label })
        assertEquals(listOf("38%", "90%"), card.rows.map { it.usage })
        assertEquals(listOf(38, 90), card.rows.map { it.percent })
        assertEquals(listOf("26 OCT 19:29", "29 OCT 02:59"), card.rows.map { it.reset })
        assertEquals(listOf(false, true), card.rows.map { it.hot })
    }

    @Test fun reconnectErrorAndStaleRaiseAlertsWithoutHidingAge() {
        val old = now.minusSeconds(2 * 3600)
        val result =
            board(
                listOf(
                    ProviderSnapshot(ProviderId.CODEX, Connection.RECONNECT_REQUIRED, old),
                    ProviderSnapshot(ProviderId.CLAUDE, Connection.ERROR, old, failure = Failure.NETWORK),
                    ProviderSnapshot(ProviderId.OPENCODE_GO, Connection.CONNECTED, old),
                ),
                now,
                3600,
                bogota,
            )
        assertEquals(
            listOf("RECONNECT REQUIRED · 2H AGO", "ERROR · NETWORK · 2H AGO", "STALE · 2H AGO"),
            result.cards.map { it.status },
        )
        assertTrue(result.cards.all { it.alert })
        assertEquals(3, result.alertCount)
        assertEquals("LAST SYNC 15:00", result.stamp)
    }

    @Test fun codexNoteReportsResetInventoryAndUnknownStaysUnknown() {
        val counted =
            ProviderSnapshot(
                ProviderId.CODEX,
                Connection.CONNECTED,
                now,
                listOf(QuotaWindow(WindowKind.WEEK, null, null)),
                resetInventoryCount = 0,
            )
        val card = board(listOf(counted), now, 3600, bogota).cards[0]
        assertEquals("RESETS AVAILABLE · 0", card.note)
        assertEquals("N/A", card.rows.single().usage)
        assertNull(card.rows.single().percent)
        assertEquals("--", card.rows.single().reset)
        assertFalse(card.rows.single().hot)
        val unknown = board(listOf(counted.copy(resetInventoryCount = null)), now, 3600, bogota).cards[0]
        assertEquals("RESETS AVAILABLE · UNKNOWN", unknown.note)
        val claude = board(listOf(ProviderSnapshot(ProviderId.CLAUDE, Connection.CONNECTED, now)), now, 3600, bogota)
        assertNull(claude.cards[1].note)
    }
}
