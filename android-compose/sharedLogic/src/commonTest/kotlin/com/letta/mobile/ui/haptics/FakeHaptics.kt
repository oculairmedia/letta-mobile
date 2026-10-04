package com.letta.mobile.ui.haptics

/** Records every played cue with the time it was played at, for tests. */
class FakeHaptics(private val clock: () -> Long = { 0L }) : Haptics {
    data class Played(val cue: LettaHapticCue, val atMillis: Long)

    private val recorded = mutableListOf<Played>()
    val played: List<Played> get() = recorded
    val events: List<LettaHapticCue> get() = recorded.map { it.cue }

    override fun play(cue: LettaHapticCue) {
        recorded += Played(cue, clock())
    }
}
