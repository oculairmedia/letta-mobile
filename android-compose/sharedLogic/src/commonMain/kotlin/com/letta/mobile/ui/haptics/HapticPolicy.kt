package com.letta.mobile.ui.haptics

import kotlin.jvm.JvmInline
/** Opaque identity of one agent turn, used to latch once-per-turn events. */
@JvmInline
value class HapticTurnKey(val value: String)

/** Live gates consulted for every cue; each is read at play time, never cached. */
class HapticGates(
    val enabled: () -> Boolean,
    val foreground: () -> Boolean,
    val reducedMotion: () -> Boolean,
)

/**
 * Central haptic policy in front of a platform [backend]. Drops everything when haptics are
 * disabled or the app is backgrounded, drops motion-coupled events under reduced motion, and
 * rate-limits with a global [GLOBAL_FLOOR_MILLIS] floor plus each cue's own floor. Dropped
 * events never advance the floors. Not thread-safe: call from the UI thread.
 */
class HapticPolicy(
    private val backend: Haptics,
    private val clock: () -> Long,
    private val gates: HapticGates,
) : Haptics {
    private var lastAnyAt: Long? = null
    private val lastCueAt = HashMap<LettaHapticCue, Long>()
    private val latchedTurn = HashMap<LettaHapticCue, HapticTurnKey>()

    override fun play(cue: LettaHapticCue) {
        tryPlay(cue)
    }

    /**
     * Plays [cue] at most once for [turnKey], so a streaming turn raises it once. The latch is
     * consumed only when the cue actually plays; a new key re-arms it.
     */
    fun playOncePer(turnKey: HapticTurnKey, cue: LettaHapticCue) {
        if (latchedTurn[cue] == turnKey) return
        if (tryPlay(cue)) latchedTurn[cue] = turnKey
    }

    private fun tryPlay(cue: LettaHapticCue): Boolean {
        val now = clock()
        if (!allowed(cue) || rateLimited(cue, now)) return false
        lastAnyAt = now
        lastCueAt[cue] = now
        backend.play(cue)
        return true
    }

    private fun allowed(cue: LettaHapticCue): Boolean =
        gates.enabled() && gates.foreground() && !(cue.motionCoupled && gates.reducedMotion())

    private fun rateLimited(cue: LettaHapticCue, now: Long): Boolean =
        within(lastAnyAt, now, GLOBAL_FLOOR_MILLIS) ||
            within(lastCueAt[cue], now, cue.minIntervalMillis)

    private fun within(last: Long?, now: Long, floorMillis: Int): Boolean =
        last != null && now - last < floorMillis

    private companion object {
        const val GLOBAL_FLOOR_MILLIS = 50
    }
}
