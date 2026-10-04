package com.letta.mobile.ui.haptics

/** Plays a semantic [LettaHapticCue]. Implementations are platform backends or [HapticPolicy]. */
fun interface Haptics {
    fun play(cue: LettaHapticCue)
}

/** Backend that never vibrates; the desktop and wasm binding. */
object NoHaptics : Haptics {
    override fun play(cue: LettaHapticCue) = Unit
}
