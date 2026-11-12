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
    /** After a hit the confirmer accepted, further hits are ignored for this long. */
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

/** After an accepted hit, nothing is admitted until [cooldown] of audio time has passed. */
internal class CooldownGate(private val cooldown: Duration) {
    private var lastAccepted: Duration? = null

    /** True while a hit at [at] falls inside the cooldown of the last accepted hit. */
    fun coolingAt(at: Duration): Boolean {
        val last = lastAccepted ?: return false
        return at - last < cooldown
    }

    /** Starts the cooldown from a hit accepted at [at]. */
    fun record(at: Duration) {
        lastAccepted = at
    }
}

/**
 * The app's own speech as the listener sees it: whether it is playing now, and how many speech
 * episodes have started, so a start and stop that both happen between two frames are not lost.
 */
internal data class AppSpeech(val speaking: Boolean = false, val episode: Long = 0)

/** Suppresses hits while the app speaks and for [tail] of audio time after it stops. */
internal class OutputSuppressionGate(private val tail: Duration) {
    private var wasSpeaking = false
    private var episode = 0L
    private var endedAt: Duration? = null

    fun suppressed(speech: AppSpeech, at: Duration): Boolean {
        if (speech.speaking) {
            wasSpeaking = true
            episode = speech.episode
            return true
        }
        if (wasSpeaking || speech.episode != episode) endedAt = at
        wasSpeaking = false
        episode = speech.episode
        val ended = endedAt ?: return false
        return at - ended < tail
    }
}
