package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test

class PhantomLayoutTest {
    @Test fun fitGrowsAndShrinksWithEitherAxisAndCapsGrowth() {
        assertEquals(1.5f, boardFit(600f, 300f, 400f, 200f), 0f)
        assertEquals(1.8f, boardFit(1600f, 800f, 400f, 200f), 0f)
        assertEquals(0.5f, boardFit(200f, 300f, 400f, 200f), 0f)
        assertEquals(0.5f, boardFit(800f, 100f, 400f, 200f), 0f)
    }

    @Test fun itemWidthsAddChartGapAndWidestTextRatherThanStacking() {
        assertEquals(65f, quotaItemWidth(12f, listOf(50f, 30f)), 0f)
        assertEquals(15f, quotaItemWidth(12f, emptyList()), 0f)
        assertEquals(89f, quotaItemWidth(36f, listOf(50f)), 0f)
        val widths = allocateColumns(listOf(65f, 130f, 195f), 500f, 2f, listOf(1f, 2f, 3f))
        assertEquals(500f, widths.last().right, 0.001f)
        assertTrue(widths[0].width < widths[1].width)
    }

    @Test fun frameParametersAreDeterministicBoundedAndVaryWithSeed() {
        assertEquals(signalLossPlan(42, 0, 20, 60), signalLossPlan(42, 0, 20, 60))
        assertNotEquals(signalLossPlan(42, 0, 20, 60), signalLossPlan(43, 0, 20, 60))
        (-100L..100L).forEach { seed ->
            val frame = signalLossPlan(seed, 0, 20, 60, setOf(0, 19))
            assertTrue(frame.shifts.size in 1..3)
            frame.shifts.forEach {
                assertTrue(it.row in 1..18)
                assertTrue(kotlin.math.abs(it.cells) in 1..2)
            }
            assertTrue(frame.staticRows.size in 0..1)
            assertTrue(frame.corruptions.size in 4..10)
            assertTrue(frame.corruptions.all { it.row in 1..18 && it.column in 0..59 })
        }
    }

    @Test fun glyphSubstitutionPreservesLengthAndOnlyChangedCells() {
        val input = "OpenCode weekly quota"
        val previous = "OpenCode oldxxx quota"
        val output = decodeReveal(previous, input, 42, 0, 0.0)
        assertEquals(input.length, output.length)
        input.indices.forEach { index ->
            if (input[index] == previous[index]) {
                assertEquals(input[index], output[index])
            } else {
                assertTrue(output[index] == input[index] || output[index] in DECODE_GLYPHS)
            }
        }
        assertEquals(output, decodeReveal(previous, input, 42, 0, 0.0))
        assertEquals("", decodeReveal("", "", 42, 0, 0.0))
        assertEquals(input, decodeReveal(previous, input, 42, 0, 1.0))
    }

    @Test fun syncGeometryUsesIdenticalPixelAlignedDrawAndHitBoundsAtEveryScale() {
        for (fit in listOf(0.4f, 1f, 1.4f, 1.8f)) {
            for (density in listOf(1f, 2.25f, 3f)) {
                val geometry = widgetSyncGeometry(fit, 66f, 0f, density)
                assertEquals(geometry.widthPixels / density, geometry.widthDp, 0.0001f)
                assertEquals(geometry.heightPixels / density, geometry.heightDp, 0.0001f)
                assertEquals(geometry.topPixels / density, geometry.topDp, 0.0001f)
                assertEquals(geometry.endPixels / density, geometry.endDp, 0.0001f)
                assertTrue(geometry.widthPixels > 0 && geometry.heightPixels > 0)
            }
        }
    }

    @Test fun disabledAndProtectedFramesNeverDisplaceOrSubstitute() {
        val protected = signalLossPlan(42, 0, 3, 20, setOf(0, 1, 2))
        assertTrue(protected.shifts.isEmpty())
        assertTrue(protected.staticRows.isEmpty())
        assertTrue(protected.corruptions.isEmpty())
        assertEquals("clean", decodeReveal("old", "clean", 42, 0, 1.0))
        listOf("NONE", "FADE", "DEFAULT", "future").forEach {
            assertFalse(periodicGlitchEnabled(it, true))
        }
        assertFalse(periodicGlitchEnabled("GLITCH", false))
    }
}
