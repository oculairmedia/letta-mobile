package com.letta.mobile.data.voice.responder

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.data.voice.engine.VoiceResponder
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Budgets for answering through a chat session. */
data class ChatReplyPolicy(
    /** Longest wait from sending until the utterance shows in the conversation with its run started. */
    val deliveryTimeout: Duration = 30.seconds,
    /** Longest wait for a stopped run to end before a cancel reports UNRESPONSIVE. */
    val stopTimeout: Duration = 10.seconds,
)

/**
 * The v1 responder: the conversation's agent answers through [ChatSessionPort].
 *
 * Collecting a reply sends the utterance with `sendText`, then follows the assistant text of the
 * run it started (every assistant text message after the utterance, in order, as each streams;
 * reasoning, tool rows, errors and compaction summaries are never spoken) until the run is no
 * longer in flight. A failed run reports RUN_FAILED; a run that never starts within
 * [ChatReplyPolicy.deliveryTimeout] reports NOT_DELIVERED; a run that ends without assistant text
 * completes with nothing to say. A change of agent while following reports CANCELLED. Cancelling
 * stops the run with `stopRun()` and waits for it to end.
 *
 * A second utterance supersedes the first: when its reply is collected the earlier reply is
 * cancelled first, and the session queues the new send behind the stopping run.
 *
 * Assumes the session shows a sent utterance in [ChatUiState.messages] once its run starts.
 * [respond] is called from one coroutine at a time (the loop's).
 */
class ChatSessionVoiceResponder(
    private val session: ChatSessionPort,
    private val policy: ChatReplyPolicy = ChatReplyPolicy(),
) : VoiceResponder {
    private var latest: ChatSessionReply? = null

    override fun respond(utterance: VoiceUtterance): VoiceReply {
        val reply = ChatSessionReply(session, utterance.text, policy, latest)
        latest = reply
        return reply
    }
}

/** Where one reply is in its life. */
private enum class ReplyStage { NEW, SENT, FINISHED, CANCELLED }

/** One utterance sent through the session and the run that answers it. */
private class ChatSessionReply(
    private val session: ChatSessionPort,
    private val text: VoiceText,
    private val policy: ChatReplyPolicy,
    private val previous: ChatSessionReply?,
) : VoiceReply {
    private val stage = MutableStateFlow(ReplyStage.NEW)

    override val events: Flow<VoiceReplyEvent> = flow {
        if (!stage.compareAndSet(ReplyStage.NEW, ReplyStage.SENT)) return@flow
        previous?.cancel()
        val tracker = ChatReplyTracker(session.uiState.value)
        session.actions.sendText(text.value)
        follow(tracker)
    }

    override suspend fun cancel(): VoiceCancelOutcome {
        val before = stage.getAndUpdate { if (it == ReplyStage.FINISHED) it else ReplyStage.CANCELLED }
        return if (before == ReplyStage.SENT) stopRun() else VoiceCancelOutcome.ALREADY_FINISHED
    }

    private suspend fun stopRun(): VoiceCancelOutcome {
        if (!session.uiState.value.isRunInFlight) return VoiceCancelOutcome.ALREADY_FINISHED
        session.actions.stopRun()
        val ended = withTimeoutOrNull(policy.stopTimeout) { session.uiState.first { !it.isRunInFlight } }
        return if (ended == null) VoiceCancelOutcome.UNRESPONSIVE else VoiceCancelOutcome.STOPPED
    }

    private fun observed(): Flow<Pair<ChatUiState, ReplyStage>> = combine(session.uiState, stage, ::Pair)

    private suspend fun FlowCollector<VoiceReplyEvent>.follow(tracker: ChatReplyTracker) {
        val delivered = withTimeoutOrNull(policy.deliveryTimeout) {
            observed().first { (state, now) -> now == ReplyStage.CANCELLED || tracker.delivered(state) }
        }
        when {
            delivered == null -> finish(failure(VoiceErrorCode.NOT_DELIVERED))
            delivered.second == ReplyStage.CANCELLED -> Unit
            else -> observed().first { (state, now) -> progress(tracker, state, now) }
        }
    }

    /** Emits what changed in [state]; true when following is over. */
    private suspend fun FlowCollector<VoiceReplyEvent>.progress(
        tracker: ChatReplyTracker,
        state: ChatUiState,
        now: ReplyStage,
    ): Boolean {
        if (now == ReplyStage.CANCELLED) return true
        if (tracker.agentChanged(state)) return finish(failure(VoiceErrorCode.CANCELLED))
        tracker.advance(state)?.let { emit(VoiceReplyEvent.Text(it)) }
        if (state.isRunInFlight) return false
        return finish(if (tracker.failed(state)) failure(VoiceErrorCode.RUN_FAILED) else VoiceReplyEvent.Completed)
    }

    private suspend fun FlowCollector<VoiceReplyEvent>.finish(event: VoiceReplyEvent): Boolean {
        if (stage.compareAndSet(ReplyStage.SENT, ReplyStage.FINISHED)) emit(event)
        return true
    }

    private fun failure(code: VoiceErrorCode): VoiceReplyEvent = VoiceReplyEvent.Failed(VoiceError(code, VoicePort.RESPONDER))
}
