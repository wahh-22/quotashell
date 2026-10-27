package dev.wahh.quotashell.core

import dev.wahh.quotashell.core.ui.appGlitchFrame
import org.junit.Assert.*
import org.junit.Test

class AppEffectsTest {
    @Test fun burstIsShortDeterministicAndSettles() {
        assertEquals(glitchFrame(42, true), appGlitchFrame(42, 0, "GLITCH", true))
        assertEquals(glitchFrame(42, true), appGlitchFrame(42, 149, "GLITCH", true))
        assertEquals(glitchFrame(43, true), appGlitchFrame(42, 150, "GLITCH", true))
        assertEquals(glitchFrame(43, true), appGlitchFrame(42, 299, "GLITCH", true))
        for (elapsed in listOf(-1L, 300L, 400L, Long.MAX_VALUE)) {
            assertEquals(GlitchFrame(), appGlitchFrame(42, elapsed, "GLITCH", true))
        }
    }

    @Test fun reducedMotionAndOtherAnimationsNeverGlitch() {
        for (animation in listOf("NONE", "DEFAULT", "FADE", "unknown", "GLITCH")) {
            assertEquals(GlitchFrame(), appGlitchFrame(7, 10, animation, false))
            if (animation != "GLITCH") assertEquals(GlitchFrame(), appGlitchFrame(7, 10, animation, true))
        }
    }
}
