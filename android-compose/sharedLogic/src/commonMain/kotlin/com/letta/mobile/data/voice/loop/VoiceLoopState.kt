package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.InterruptStatus
import com.letta.mobile.data.voice.SpeechSkipReason
import com.letta.mobile.data.voice.SpeechStopReason
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceResponder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Everything a page or the avatar needs to show the loop. Carries no audio. */
data class VoiceLoopState(
    /** Where the loop is. */
    val phase: VoicePhase = VoicePhase.IDLE,
    /** The live transcript of the current turn, or the last finished one. */
    val transcript: VoiceText? = null,
    /** The segment being spoken right now. */
    val speaking: VoiceText? = null,
    /** Microphone loudness, for a meter. */
    val inputLevel: AudioLevel = AudioLevel.Silent,
    /** Speaker loudness, for the avatar's mouth. */
    val outputLevel: AudioLevel = AudioLevel.Silent,
    /** Whether the microphone is open. */
    val microphoneOpen: Boolean = false,
    /**
     * The ear heard the person start a turn that has not ended yet (still speaking, or being
     * transcribed). Reply speech is held only while they are still speaking.
     */
    val userSpeaking: Boolean = false,
    /** Why the loop stopped, in [VoicePhase.FAILED]. */
    val failure: VoiceError? = null,
    /** A degraded optional helper (a missing wake-word asset); the loop still works. */
    val activationNotice: VoiceError? = null,
    /** What started the current session. */
    val activation: VoiceActivationKind? = null,
    /** A failed capture the ear holds for one retry ([VoiceLoop.retryCapture]). */
    val retryableCapture: CapturedAudioHandle? = null,
    /** Whether an interrupted answer is still arriving. */
    val interrupt: InterruptStatus = InterruptStatus.NONE,
    /** Whether an answer was delegated and may still arrive as a late reply. */
    val awaitingLateReply: Boolean = false,
    /** Late reply segments held until the person is quiet. */
    val heldLateSegments: Int = 0,
    /**
     * The last answer (or its speech) that failed while the loop went on listening; cleared when
     * the person says something new. Only failures the loop cannot go on after land in [failure].
     */
    val turnFailure: VoiceError? = null,
    /** Whether the utterance behind [turnFailure] can be sent again ([VoiceLoop.retryReply]). */
    val replyRetryable: Boolean = false,
    /** How many answers are awaited or being spoken. */
    val pendingReplies: Int = 0,
    /** When [phase] was entered, from the loop's clock. */
    val phaseSince: Instant = Instant.fromEpochMilliseconds(0),
)

/** The three engines the loop drives. */
data class VoiceEngines(
    /** Hearing. */
    val ear: VoiceEar,
    /** Who answers. */
    val responder: VoiceResponder,
    /** Speaking. */
    val mouth: VoiceMouth,
)

/** Where the loop runs: the owner's [scope], the [dispatcher] it serialises on, its [clock] and its [observer]. */
data class VoiceLoopRuntime(
    /** The lifecycle owner's scope; cancelling it ends the loop without releasing the engines (call shutdown for that). */
    val scope: CoroutineScope,
    /** The dispatcher the loop's coroutines run on. */
    val dispatcher: CoroutineDispatcher,
    /** The clock behind [VoiceLoopState.phaseSince]. */
    val clock: Clock = Clock.System,
    /** Told about decisions that do not show in the state (diagnostics). */
    val observer: VoiceLoopObserver = VoiceLoopObserver.None,
)

/** Decisions of the loop that leave no trace in its state, for diagnostics. Never given text or audio. */
interface VoiceLoopObserver {
    /** [segments] segments of answer text (or one whole unspoken answer) were not spoken, for [reason]. */
    fun speechSkipped(reason: SpeechSkipReason, segments: Int) = Unit

    /** The app's speech was stopped for [reason]; the speaker was silent [silentAfter] the request. */
    fun speechStopped(reason: SpeechStopReason, silentAfter: Duration) = Unit

    /** An observer that ignores everything. */
    companion object {
        /** Ignores everything. */
        val None: VoiceLoopObserver = object : VoiceLoopObserver {}
    }
}
