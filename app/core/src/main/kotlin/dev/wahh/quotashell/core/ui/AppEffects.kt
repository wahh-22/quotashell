package dev.wahh.quotashell.core.ui

import dev.wahh.quotashell.core.periodicGlitchEnabled

/** Two event-driven signal-loss frames, then clean at 300 ms. No Activity periodic schedule. */
fun appSignalActive(
    elapsedMillis: Long,
    animation: String,
    animationsEnabled: Boolean,
): Boolean = elapsedMillis in 0 until 300 && periodicGlitchEnabled(animation, animationsEnabled)
