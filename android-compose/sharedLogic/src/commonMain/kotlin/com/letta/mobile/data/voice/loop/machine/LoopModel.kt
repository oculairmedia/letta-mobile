package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import kotlinx.coroutines.CompletableDeferred
import kotlin.jvm.JvmInline

/** The generation token of one turn: every reply event carries it, and stale ones are dropped. */
@JvmInline
internal value class TurnKey(val value: Long)

/** The current turn and the answers still outstanding. */
internal data class TurnState(
    /** The turn whose answer is being awaited or spoken. */
    val active: TurnKey? = null,
    /** Whether the active turn's answer may still produce text before going back to listening. */
    val replyOpen: Boolean = false,
    /** The active answer has produced text (maybe not yet a whole sentence); the thinking timeout no longer applies. */
    val textArrived: Boolean = false,
    /** A failure to report once the queued speech has been spoken. */
    val pendingFailure: VoiceError? = null,
    /** The ear reported speech in this listening window and no end of turn yet. */
    val userSpeaking: Boolean = false,
    /** Interrupted turns whose answers may still arrive; their text is dropped. */
    val interrupting: Set<TurnKey> = emptySet(),
    /** An interrupt was not confirmed in time. */
    val interruptUnresponsive: Boolean = false,
    /** The next turn's key. */
    val nextKey: Long = 1,
)

/** Segments handed to the mouth and not yet finished, in order. */
internal data class SpeechQueue(
    val outstanding: Map<SpeechSegmentId, VoiceText> = emptyMap(),
    val nextId: Long = 1,
)

/** Delegated answers that may still arrive, and late text held until the person is quiet. */
internal data class LateState(
    val delegated: Set<TurnKey> = emptySet(),
    val held: List<VoiceText> = emptyList(),
)

/** What the page sees that does not drive transitions. */
internal data class LoopView(
    val transcript: VoiceText? = null,
    val speaking: VoiceText? = null,
    val inputLevel: AudioLevel = AudioLevel.Silent,
    val outputLevel: AudioLevel = AudioLevel.Silent,
    val micOpen: Boolean = false,
    val failure: VoiceError? = null,
    val notice: VoiceError? = null,
    val activation: VoiceActivationKind? = null,
    val retryable: CapturedAudioHandle? = null,
)

/** The whole loop, as the pure machine sees it. */
internal data class LoopModel(
    val config: VoiceLoopConfig,
    val phase: VoicePhase = VoicePhase.IDLE,
    val turn: TurnState = TurnState(),
    val speech: SpeechQueue = SpeechQueue(),
    val late: LateState = LateState(),
    val view: LoopView = LoopView(),
    /** A capture retry waiting for the ear to report it is listening. */
    val pendingRetry: CapturedAudioHandle? = null,
) {
    /** Whether the microphone should be open: always while listening, while answering only for barge-in. */
    val micWanted: Boolean
        get() = when (phase) {
            VoicePhase.LISTENING -> true
            VoicePhase.THINKING, VoicePhase.SPEAKING -> config.bargeIn
            else -> false
        }

    /** Whether the app's own speech is queued or playing. */
    val outputActive: Boolean get() = speech.outstanding.isNotEmpty()
}

/** Everything the machine reacts to. */
internal sealed interface LoopInput {
    /** The person activated (tap, hotkey, push-to-talk press, wake word). */
    data class Activate(val kind: VoiceActivationKind) : LoopInput

    /** The person ended their turn (push-to-talk release, second tap). */
    data object EndTurn : LoopInput

    /**
     * The person stopped, or the conversation changed under the loop ([contextChanged]). [done] is
     * completed once the stop has been applied and every engine has let go.
     */
    data class Stop(val contextChanged: Boolean = false, val done: CompletableDeferred<Unit>? = null) : LoopInput

    /** The person asked to retry the retained capture. */
    data object RetryCapture : LoopInput

    /** An optional activation helper degraded. */
    data class Notice(val error: VoiceError) : LoopInput

    /** An ear event; the driver drops it unless [generation] is the running ear's. */
    data class FromEar(val event: VoiceEarEvent, val generation: Long = 0) : LoopInput

    /** A mouth event. */
    data class FromMouth(val event: VoiceMouthEvent) : LoopInput

    /** An answer event, tagged with its turn's generation token. */
    data class FromReply(val turn: TurnKey, val signal: ReplySignal) : LoopInput

    /** A requested interrupt of [turn] settled with [outcome]. */
    data class InterruptSettled(val turn: TurnKey, val outcome: VoiceCancelOutcome) : LoopInput

    /** An engine failed outside its own event stream (it ended, or would not let go). */
    data class Fault(val error: VoiceError) : LoopInput
}

/** An answer event after chunking. */
internal sealed interface ReplySignal {
    /** The answer produced its first text, before any sentence is complete; ends the thinking wait. */
    data object TextStarted : ReplySignal

    /** One speakable segment. */
    data class Segment(val text: VoiceText) : ReplySignal

    /** The rest arrives later. */
    data object Delegated : ReplySignal

    /** The answer is complete. */
    data object Completed : ReplySignal

    /** The answer failed. */
    data class Failed(val error: VoiceError) : ReplySignal

    /** No reply text arrived within the first-text timeout. Ignored once the answer produced text or closed. */
    data object ThinkingTimedOut : ReplySignal
}

/** What the driver must do after a transition. */
internal sealed interface LoopEffect {
    /** Ask the responder to answer [utterance] under generation [turn]. */
    data class Respond(val turn: TurnKey, val utterance: VoiceUtterance) : LoopEffect

    /** Ask the responder to stop [turn]'s answer, without waiting; the outcome comes back as an input. */
    data class RequestInterrupt(val turn: TurnKey) : LoopEffect

    /** Stop following [turn]'s answer (its interrupt settled). */
    data class ReleaseReply(val turn: TurnKey) : LoopEffect

    /** Cancel every answer and wait (bounded) for each to settle. */
    data object CancelAllReplies : LoopEffect

    /** Hand [segment] to the mouth. */
    data class Speak(val segment: SpeechSegment) : LoopEffect

    /** Stop the mouth and wait (bounded) until it has stopped. */
    data object StopMouth : LoopEffect

    /** Tell the mouth no more text is coming for now. */
    data object FlushMouth : LoopEffect

    /** Tell the ear the person finished their turn. */
    data object CommitTurn : LoopEffect

    /** Ask the ear to retry the capture [handle]. */
    data class RetryCapture(val handle: CapturedAudioHandle) : LoopEffect
}

/** A transition's result. */
internal data class Step(val model: LoopModel, val effects: List<LoopEffect> = emptyList()) {
    /** This step followed by [more] effects. */
    operator fun plus(more: List<LoopEffect>): Step = copy(effects = effects + more)

    /** This step preceded by [prior] effects. */
    fun after(prior: List<LoopEffect>): Step = copy(effects = prior + effects)
}
