package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.SpeechSkipReason
import com.letta.mobile.data.voice.SpeechStopReason
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

/**
 * One answer the loop awaits or speaks. Answers are spoken in the order their utterances were
 * sent; text that arrives before an answer's turn to speak is held on it.
 */
internal data class PendingReply(
    val key: TurnKey,
    /** The answer may still produce text. */
    val open: Boolean = true,
    /** The answer has produced text (maybe not yet a whole sentence); the thinking timeout no longer applies. */
    val textArrived: Boolean = false,
    /** Segments that arrived before this answer could be spoken. */
    val held: List<VoiceText> = emptyList(),
    /** What the person said, kept so a failed answer can be asked again. */
    val utterance: VoiceUtterance? = null,
)

/** The answers outstanding, oldest first, and the person's own turn. */
internal data class TurnState(
    /** Answers awaited or being spoken, in send order; the head is spoken next (or now). */
    val replies: List<PendingReply> = emptyList(),
    /**
     * The ear reported speech and the turn is still open (the person is talking). Reply speech is
     * held only while this is true.
     */
    val userSpeaking: Boolean = false,
    /** Turns of the person's that closed and whose transcript has not arrived yet; they hold nothing back. */
    val transcribing: Int = 0,
    /** Interrupted turns whose answers may still arrive; their text is dropped. */
    val interrupting: Set<TurnKey> = emptySet(),
    /** An interrupt was not confirmed in time. */
    val interruptUnresponsive: Boolean = false,
    /** The next turn's key. */
    val nextKey: Long = 1,
) {
    /** The answer spoken next, or now. */
    val head: PendingReply? get() = replies.firstOrNull()

    /** The head answer's turn. */
    val active: TurnKey? get() = head?.key

    /** Whether the head answer may still produce text. */
    val replyOpen: Boolean get() = head?.open == true

    /** Whether the head answer has produced text. */
    val textArrived: Boolean get() = head?.textArrived == true

    /** The pending answer of [key], or null. */
    fun find(key: TurnKey): PendingReply? = replies.firstOrNull { it.key == key }

    /** These answers with [key]'s changed by [change]. */
    fun update(key: TurnKey, change: PendingReply.() -> PendingReply): TurnState =
        copy(replies = replies.map { if (it.key == key) it.change() else it })

    /** These answers without [key]'s. */
    fun without(key: TurnKey): TurnState = copy(replies = replies.filterNot { it.key == key })
}

/** Segments handed to the mouth and not yet finished, in order, and the answer they belong to. */
internal data class SpeechQueue(
    val outstanding: Map<SpeechSegmentId, VoiceText> = emptyMap(),
    val nextId: Long = 1,
    /** The answer whose segments are outstanding; null for standalone speech (late or held text). */
    val owner: TurnKey? = null,
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
    /** The last answer or speech that failed while the loop kept going. */
    val turnFailure: VoiceError? = null,
    /** The utterance whose answer failed and may be asked again. */
    val retryUtterance: VoiceUtterance? = null,
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

    /** The person asked to send again the utterance whose answer failed. */
    data object RetryReply : LoopInput

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
    /**
     * Ask the responder to answer [utterance] under generation [turn]. The driver sends it only
     * once every interrupt still settling has settled (bounded), so a stop never meets it.
     */
    data class Respond(val turn: TurnKey, val utterance: VoiceUtterance) : LoopEffect

    /** Ask the responder to stop [turn]'s answer, without waiting; the outcome comes back as an input. */
    data class RequestInterrupt(val turn: TurnKey) : LoopEffect

    /** Stop following [turn]'s answer without stopping it (its interrupt settled, or it is no longer spoken). */
    data class ReleaseReply(val turn: TurnKey) : LoopEffect

    /** Cancel every answer and wait (bounded) for each to settle. */
    data object CancelAllReplies : LoopEffect

    /** Hand [segment] to the mouth. */
    data class Speak(val segment: SpeechSegment) : LoopEffect

    /** Stop the mouth for [reason] and wait (bounded) until it has stopped. */
    data class StopMouth(val reason: SpeechStopReason) : LoopEffect

    /** Tell the mouth no more text is coming for now. */
    data object FlushMouth : LoopEffect

    /** Tell the ear the person finished their turn. */
    data object CommitTurn : LoopEffect

    /** Ask the ear to retry the capture [handle]. */
    data class RetryCapture(val handle: CapturedAudioHandle) : LoopEffect

    /** Report that [segments] segments (or a whole unspoken answer) were not spoken, for [reason]. */
    data class SpeechSkipped(val reason: SpeechSkipReason, val segments: Int) : LoopEffect
}

/** A transition's result. */
internal data class Step(val model: LoopModel, val effects: List<LoopEffect> = emptyList()) {
    /** This step followed by [more] effects. */
    operator fun plus(more: List<LoopEffect>): Step = copy(effects = effects + more)

    /** This step preceded by [prior] effects. */
    fun after(prior: List<LoopEffect>): Step = copy(effects = prior + effects)
}
