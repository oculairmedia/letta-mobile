package com.letta.mobile.data.voice.wake

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A second-stage check on a hit that passed every gate, for example a stronger model or a
 * speaker check. Returning false drops the hit.
 */
fun interface WakeHitConfirmer {
    /** Whether [hit] is real. */
    suspend fun confirm(hit: WakeWordHit): Boolean

    /** Shared confirmers. */
    companion object {
        /** Accepts every hit. */
        val AcceptAll: WakeHitConfirmer = WakeHitConfirmer { true }
    }
}

/** How the listener gates raw hits. All times are audio time, measured from the capture's samples. */
data class WakeWordPolicy(
    /** After an accepted hit, further hits are ignored for this long. */
    val cooldown: Duration = 2.seconds,
    /** After the app stops speaking, hits are still ignored for this long (echo tail). */
    val speechTail: Duration = 700.milliseconds,
    /** The second-stage check. */
    val confirmer: WakeHitConfirmer = WakeHitConfirmer.AcceptAll,
)

/** A hit passes when its confidence reaches its phrase's sensitivity threshold. */
internal object SensitivityGate {
    fun passes(hit: WakeWordHit): Boolean = hit.confidence.value >= hit.phrase.sensitivity.threshold
}

/** Admits one hit, then nothing until [cooldown] of audio time has passed. */
internal class CooldownGate(private val cooldown: Duration) {
    private var lastAdmitted: Duration? = null

    fun admit(at: Duration): Boolean {
        val last = lastAdmitted
        if (last != null && at - last < cooldown) return false
        lastAdmitted = at
        return true
    }
}

/** Suppresses hits while the app speaks and for [tail] of audio time after it stops. */
internal class OutputSuppressionGate(private val tail: Duration) {
    private var speaking = false
    private var endedAt: Duration? = null

    fun suppressed(appSpeaking: Boolean, at: Duration): Boolean {
        if (speaking && !appSpeaking) endedAt = at
        speaking = appSpeaking
        if (appSpeaking) return true
        val ended = endedAt ?: return false
        return at - ended < tail
    }
}
