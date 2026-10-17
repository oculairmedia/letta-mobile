package com.letta.mobile.ui.mascot.frame

/**
 * A live onion skin over the surface: the last [frames] rendered frames, sampled every [stride]th
 * frame, drawn under the live one. The ramp is the one `rive/mascot/onion.py` uses so the bench and
 * the CLI tool read the same - alpha [minAlpha]..[maxAlpha] oldest to newest, and with [tint] a
 * cool-to-warm paper tint that fades out as the frames approach now.
 *
 * Passing null (the default, and what every production call site does) allocates no ring at all.
 */
data class RiveOnionSkin(
    val frames: Int = 8,
    val stride: Int = 3,
    val tint: Boolean = true,
    val minAlpha: Float = 0.15f,
    val maxAlpha: Float = 0.6f,
)
