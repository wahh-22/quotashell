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
        assertEquals(67f, quotaItemWidth(12f, listOf(50f, 30f)), 0f)
        assertEquals(17f, quotaItemWidth(12f, emptyList()), 0f)
        assertEquals(91f, quotaItemWidth(36f, listOf(50f)), 0f)
        val widths = allocateColumns(listOf(67f, 134f, 201f), 500f, 5f, listOf(1f, 2f, 3f))
        assertEquals(500f, widths.last().right, 0.001f)
        assertTrue(widths[0].width < widths[1].width)
    }

    @Test fun frameParametersAreDeterministicBoundedAndVaryWithSeed() {
        assertEquals(glitchFrame(42, true), glitchFrame(42, true))
        assertNotEquals(glitchFrame(42, true), glitchFrame(43, true))
        (-100..100).forEach { seed ->
            val frame = glitchFrame(seed, true)
            assertTrue(frame.rgbOffset in 0.8f..2f)
            assertTrue(frame.jitterX in -1f..1f)
            frame.slices.forEach {
                assertTrue(it.topFraction >= 0f)
                assertTrue(it.heightFraction > 0f)
                assertTrue(it.topFraction + it.heightFraction <= 1f)
                assertTrue(it.offset in -3..3)
            }
        }
    }

    @Test fun glyphSubstitutionPreservesLengthAndOnlySelectedIndices() {
        val input = "OpenCode weekly quota"
        val indices = glitchGlyphIndices(input.length, 42)
        assertTrue(indices.isNotEmpty())
        assertTrue(indices.all { it in input.indices })
        val output = substituteGlitchGlyphs(input, indices, 42)
        assertEquals(input.length, output.length)
        input.indices.forEach { index ->
            if (index in indices) {
                assertTrue(output[index] in GLITCH_GLYPHS)
            } else {
                assertEquals(input[index], output[index])
            }
        }
        assertEquals(output, substituteGlitchGlyphs(input, indices, 42))
        assertEquals("", substituteGlitchGlyphs("", emptySet(), 42))
        assertTrue(glitchGlyphIndices(0, 42).isEmpty())
    }

    @Test fun disabledFramesNeverDisplaceSplitOrSubstitute() {
        val frame = glitchFrame(42, false)
        assertTrue(frame.slices.isEmpty())
        assertEquals(0f, frame.rgbOffset, 0f)
        assertEquals(0f, frame.jitterX, 0f)
        assertFalse(frame.substituteGlyphs)
        listOf("NONE", "FADE", "DEFAULT", "future").forEach {
            assertEquals(frame, glitchFrame(42, periodicGlitchEnabled(it, true)))
        }
        assertEquals(frame, glitchFrame(42, periodicGlitchEnabled("GLITCH", false)))
    }
}
