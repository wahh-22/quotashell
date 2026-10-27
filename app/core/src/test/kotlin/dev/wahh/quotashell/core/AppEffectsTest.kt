package dev.wahh.quotashell.core

import dev.wahh.quotashell.core.ui.appSignalActive
import org.junit.Assert.*
import org.junit.Test

class AppEffectsTest {
    @Test fun burstIsShortDeterministicAndSettles() {
        for (elapsed in listOf(0L, 149L, 150L, 299L)) {
            assertTrue(appSignalActive(elapsed, "GLITCH", true))
            val frame = (elapsed / 150).toInt()
            assertEquals(signalLossPlan(42, frame, 12, 40), signalLossPlan(42, frame, 12, 40))
            assertTrue(signalLossPlan(42, frame, 12, 40).corruptions.size in 4..10)
        }
        assertNotEquals(signalLossPlan(42, 0, 12, 40), signalLossPlan(42, 1, 12, 40))
        for (elapsed in listOf(-1L, 300L, 400L, Long.MAX_VALUE)) {
            assertFalse(appSignalActive(elapsed, "GLITCH", true))
        }
    }

    @Test fun reducedMotionAndOtherAnimationsNeverGlitch() {
        for (animation in listOf("NONE", "DEFAULT", "FADE", "unknown", "GLITCH")) {
            assertFalse(appSignalActive(10, animation, false))
            if (animation != "GLITCH") assertFalse(appSignalActive(10, animation, true))
        }
    }
}
