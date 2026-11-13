package com.letta.mobile.data.voice.engine

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import kotlinx.coroutines.flow.Flow

/**
 * Who answers an utterance.
 *
 * Today the conversation's agent answers directly. Later a fast responder can answer at once and
 * delegate to the slow agent: it streams its own short answer, emits
 * [VoiceReplyEvent.AwaitingLateReply] so the loop goes back to listening, and keeps the same reply
 * open until the agent's answer lands, which it streams as more text. That later text is a "late
 * reply"; the loop's late-reply policy decides when it is spoken.
 */
interface VoiceResponder {
    /** Starts answering [utterance]. Nothing is sent until [VoiceReply.events] is collected. */
    fun respond(utterance: VoiceUtterance): VoiceReply
}

/** One answer in progress. */
interface VoiceReply {
    /**
     * The answer as it arrives. Cold and single-use: collecting sends the utterance; the flow ends
     * after [VoiceReplyEvent.Completed] or [VoiceReplyEvent.Failed].
     */
    val events: Flow<VoiceReplyEvent>

    /**
     * Asks the answerer to stop. A request that can fail: it suspends until the answer has stopped,
     * was already finished, or did not stop within the responder's own budget.
     */
    suspend fun cancel(): VoiceCancelOutcome
}

/** How a request to stop an answer ended. */
enum class VoiceCancelOutcome {
    /** The answer stopped. */
    STOPPED,

    /** The answer had already finished (or never started); nothing needed stopping. */
    ALREADY_FINISHED,

    /** The answerer did not confirm the stop within its budget; text may still arrive. */
    UNRESPONSIVE,
}

/** What an answer reports as it arrives. */
sealed interface VoiceReplyEvent {
    /** More reply text, appended to what came before. */
    data class Text(
        /** The new text. */
        val delta: VoiceText,
    ) : VoiceReplyEvent

    /** The current part of the answer is complete; any partial sentence may be spoken now. */
    data object PartEnded : VoiceReplyEvent

    /**
     * Nothing more is coming right now; the rest arrives later as a late reply (an asynchronous
     * agent delegation). The loop may go back to listening.
     */
    data object AwaitingLateReply : VoiceReplyEvent

    /** The answer is complete. */
    data object Completed : VoiceReplyEvent

    /** The answer failed. */
    data class Failed(
        /** What failed. */
        val error: VoiceError,
    ) : VoiceReplyEvent
}
