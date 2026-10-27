package dev.wahh.quotashell.core.ui

import dev.wahh.quotashell.core.GlitchFrame
import dev.wahh.quotashell.core.glitchFrame
import dev.wahh.quotashell.core.periodicGlitchEnabled

/** Two event-driven frames, then clean at 300 ms. No Activity periodic schedule. */
fun appGlitchFrame(
    seed: Int,
    elapsedMillis: Long,
    animation: String,
    animationsEnabled: Boolean,
): GlitchFrame =
    glitchFrame(
        seed + (elapsedMillis / 150).toInt(),
        elapsedMillis in 0 until 300 && periodicGlitchEnabled(animation, animationsEnabled),
    )
