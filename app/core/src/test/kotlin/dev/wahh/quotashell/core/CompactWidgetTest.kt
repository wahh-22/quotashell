package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CompactWidgetTest {
    private val now = Instant.parse("2026-10-26T22:00:00Z")
    private val snapshot =
        ProviderSnapshot(
            ProviderId.CODEX,
            Connection.CONNECTED,
            now,
            listOf(QuotaWindow(WindowKind.WEEK, 85.0, now)),
            resetInventoryCount = 0,
        )

    private fun content(at: Instant = now) = board(listOf(snapshot), at, 3600, ZoneId.of("America/Bogota"), false)

    @Test fun donutClampsAndUnknownIsEmptyRatherThanZero() {
        assertEquals(0f, donutSweep(null), 0f)
        assertEquals(0f, donutSweep(-10), 0f)
        assertEquals(0f, donutSweep(0), 0f)
        assertEquals(180f, donutSweep(50), 0f)
        assertEquals(360f, donutSweep(130), 0f)
        assertEquals(-90f, DONUT_START_DEGREES, 0f)
    }

    @Test fun compactBoardKeepsReportedWindowsLocalResetsAndInventory() {
        val card = content().cards.first()
        assertEquals("26 Oct 17:00", card.rows.single().reset)
        assertEquals("Resets · 0", card.note)
        assertEquals(3, content().cards.size)
        assertTrue(card.rows.single().hot)
        assertTrue(
            content()
                .cards
                .last()
                .rows
                .isEmpty(),
        )
    }

    @Test fun burstIgnoresAgeButDetectsQuotaResetStatusAndAlerts() {
        val old = content()
        assertFalse(shouldGlitchBurst(null, old, true, "GLITCH"))
        assertFalse(shouldGlitchBurst(old, content(now.plusSeconds(120)), true, "GLITCH"))
        val changed =
            old.copy(
                cards =
                    old.cards.mapIndexed { i, card ->
                        if (i == 0) card.copy(rows = card.rows.map { it.copy(percent = 86) }) else card
                    },
            )
        assertTrue(shouldGlitchBurst(old, changed, true, "GLITCH"))
        assertTrue(shouldGlitchBurst(old, old.copy(alertCount = 1), true, "GLITCH"))
        assertTrue(shouldGlitchBurst(old, content(now.plusSeconds(4000)), true, "GLITCH"))
        assertTrue(shouldGlitchBurst(old, old.copy(cards = old.cards.map { it.copy(note = "Resets · 1") }), true, "GLITCH"))
        assertFalse(shouldGlitchBurst(old, changed, false, "GLITCH"))
        listOf("FADE", "DEFAULT", "NONE", "future").forEach {
            assertFalse(shouldGlitchBurst(old, changed, true, it))
        }
    }

    @Test fun seedAndLossRowsAreStableAndBounded() {
        assertEquals(content().glitchSeed(), content(now.plusSeconds(120)).glitchSeed())
        val plan = signalLossPlan(123, 0, 10, 50)
        assertEquals(plan, signalLossPlan(123, 0, 10, 50))
        assertNotEquals(plan, signalLossPlan(456, 0, 10, 50))
        assertTrue(plan.shifts.size in 1..3)
        (-50L..50L).forEach { seed ->
            signalLossPlan(seed, 1, 10, 50).shifts.forEach {
                assertTrue(kotlin.math.abs(it.cells) in 1..2)
                assertTrue(it.row in 0..9)
            }
        }
        val timestampOnly = content().copy(cards = content().cards.map { it.copy(status = it.status.replace("17:00", "17:30")) })
        assertEquals(content().glitchSeed(), timestampOnly.glitchSeed())
        assertFalse(shouldGlitchBurst(content(), timestampOnly, true, "GLITCH"))
    }

    @Test fun notificationAnimationIsEditableAndUnknownRoundTrips() {
        val theme = PhantomTheme.default()
        assertEquals("GLITCH", theme.notificationAnimation)
        assertEquals(listOf("GLITCH", "FADE", "DEFAULT", "NONE"), PhantomTheme.NOTIFICATION_ANIMATIONS)
        val unknown = theme.withNotificationAnimation("future")
        assertEquals("future", PhantomTheme.parse(unknown.toJson()).notificationAnimation)
        assertEquals("NONE", unknown.withNotificationAnimation("NONE").notificationAnimation)
    }
}
