package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CyberWidgetTest {
    @Test fun columnsKeepRequirementsAndDistributeSurplusProportionally() {
        val columns = allocateColumns(listOf(60f, 100f, 140f), 400f, 5f)
        assertEquals(listOf(78f, 130f, 182f), columns.map { it.width })
        assertEquals(0f, columns.first().left, 0f)
        assertEquals(83f, columns[1].left, 0f)
        assertEquals(400f, columns.last().right, 0.001f)
    }

    @Test fun surplusFollowsChartWeightsSoFewerChartsMeanNarrowerColumns() {
        // Same text requirement (status line) but 1, 2 and 3 charts: Codex must not match the others.
        val columns = allocateColumns(listOf(100f, 100f, 100f), 400f, 5f, weights = listOf(1f, 2f, 3f))
        assertEquals(listOf(115f, 130f, 145f), columns.map { it.width })
        assertEquals(400f, columns.last().right, 0.001f)
        val noWeight = allocateColumns(listOf(100f, 100f), 230f, 0f, weights = listOf(0f, 0f))
        assertEquals(listOf(115f, 115f), noWeight.map { it.width })
    }

    @Test fun constrainedColumnsKeepMinimumsForCallerToScaleRatherThanClip() {
        val columns = allocateColumns(listOf(10f, 100f, 150f), 100f, 5f)
        assertEquals(48f, columns.first().width, 0f)
        assertEquals(308f, columns.last().right, 0f)
        assertTrue(allocateColumns(emptyList(), 100f, 5f).isEmpty())
    }

    @Test fun equalAndZeroRequirementsHaveStableMinimumsWithoutGaps() {
        val columns = allocateColumns(listOf(0f, 0f), 120f, 0f)
        assertEquals(listOf(60f, 60f), columns.map { it.width })
        assertEquals(columns.first().right, columns.last().left, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteMeasurementIsRejected() {
        allocateColumns(listOf(Float.NaN), 100f, 5f)
    }

    @Test fun sharedSegmentsClampAndKeepUnknownEmpty() {
        assertEquals(0, filledSegments(null, 12))
        assertEquals(0, filledSegments(-1, 12))
        assertEquals(0, filledSegments(0, 12))
        assertEquals(6, filledSegments(50, 12))
        assertEquals(10, filledSegments(85, 12))
        assertEquals(12, filledSegments(101, 12))
        assertEquals(0, filledSegments(50, 0))
    }

    @Test fun chartStylesDefaultAndRoundTripWithoutChangingLauncherFields() {
        val theme = PhantomTheme.default()
        assertEquals(ChartStyle.CELLS, theme.chartStyle)
        ChartStyle.entries.forEach { style ->
            val edited = theme.withChartStyle(style)
            assertEquals(style, PhantomTheme.parse(edited.toJson()).chartStyle)
            assertEquals(theme.color("accent"), edited.color("accent"))
            assertTrue(edited.toJson().contains("quotaShellChartStyle"))
        }
        val unknown = PhantomTheme.parse(theme.toJson().dropLast(1) + ",\"quotaShellChartStyle\":\"future\"}")
        assertEquals(ChartStyle.CELLS, unknown.chartStyle)
        assertTrue(unknown.toJson().contains("future"))
    }

    @Test fun periodicGlitchHasTinyDutyCycleAndHonorsBothAnimationSwitches() {
        val schedule = GlitchSchedule()
        assertEquals(40, schedule.children)
        assertEquals(setOf(38, 39), schedule.glitchIndices)
        assertEquals(8000, schedule.periodMillis)
        assertEquals(200, schedule.intervalMillis)
    }

    @Test fun periodicGlitchEnableDecision() {
        assertTrue(periodicGlitchEnabled("GLITCH", true))
        assertFalse(periodicGlitchEnabled("GLITCH", false))
        listOf("NONE", "FADE", "DEFAULT", "future").forEach {
            assertFalse(periodicGlitchEnabled(it, true))
        }
    }

    @Test fun syncingBoardAndDuplicateTapGuard() {
        val idle = board(emptyList(), Instant.EPOCH, 3600, ZoneId.of("UTC"), allCaps = false)
        assertFalse(idle.syncing)
        val pending = board(emptyList(), Instant.EPOCH, 3600, ZoneId.of("UTC"), allCaps = false, syncing = true)
        assertTrue(pending.syncing)
        assertEquals(idle.cards, pending.cards)
    }

    @Test fun concurrentRefreshCallersWaitForTheSameGuardedResult() {
        val callbacks = RefreshCallbacks()
        val results = mutableListOf<String>()
        assertTrue(callbacks.join { results.add("first:$it") })
        assertFalse(callbacks.join { results.add("widget:$it") })
        assertTrue(results.isEmpty())
        callbacks.finish("failed")
        assertEquals(listOf("first:failed", "widget:failed"), results)
        callbacks.finish("ignored")
        assertEquals(2, results.size)
        assertTrue(callbacks.join { results.add(it) })
        callbacks.finish("done")
        assertEquals("done", results.last())
    }

    @Test fun duplicateTapGuard() {
        assertTrue(shouldEnqueueWidgetRefresh(false))
        assertFalse(shouldEnqueueWidgetRefresh(true))
    }
}
