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
import kotlinx.coroutines.CompletableDeferred
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

/** Budgets for answering through a voice chat session. */
data class ChatReplyPolicy(
    /** Longest wait from sending until the session publishes the run the message started. */
    val deliveryTimeout: Duration = 30.seconds,
    /** Longest wait for a stopped run to end before a cancel reports UNRESPONSIVE. */
    val stopTimeout: Duration = 10.seconds,
)

/**
 * The responder for the conversation's agent, over a [VoiceChatSession].
 *
 * Collecting a reply sends the utterance and follows, by identity only, the run the session says
 * that message started: assistant text rows of that run are spoken as they stream; reasoning,
 * tool rows and errors are not. It completes when that run is no longer in flight, reports
 * RUN_FAILED when it failed, NOT_DELIVERED when the message never appears, UNATTRIBUTED (with
 * nothing spoken) when the message appears but its run is never published within
 * [ChatReplyPolicy.deliveryTimeout], and CANCELLED when the conversation changes. Cancelling stops
 * the run only when it is this message's run and in flight, and waits for it to end.
 *
 * A second utterance supersedes the first: when its reply is collected the earlier reply is
 * cancelled first; a reply cancelled before it sends sends nothing.
 *
 * [respond] is called from one coroutine at a time (the loop's).
 */
class VoiceChatResponder(
    private val session: VoiceChatSession,
    private val policy: ChatReplyPolicy = ChatReplyPolicy(),
) : VoiceResponder {
    private var latest: VoiceChatReply? = null

    override fun respond(utterance: VoiceUtterance): VoiceReply {
        val reply = VoiceChatReply(session, utterance.text, policy, latest)
        latest = reply
        return reply
    }
}

/** Where one reply is in its life. */
private enum class ReplyStage { NEW, PREPARING, SENDING, SENT, FINISHED, CANCELLED }

/** One utterance sent through the session and the run that answers it. */
private class VoiceChatReply(
    private val session: VoiceChatSession,
    private val text: VoiceText,
    private val policy: ChatReplyPolicy,
    private var previous: VoiceChatReply?,
) : VoiceReply {
    private val stage = MutableStateFlow(ReplyStage.NEW)
    private val sentId = CompletableDeferred<ClientMessageId>()

    override val events: Flow<VoiceReplyEvent> = flow {
        if (!stage.compareAndSet(ReplyStage.NEW, ReplyStage.PREPARING)) return@flow
        previous?.cancel()
        previous = null
        if (!stage.compareAndSet(ReplyStage.PREPARING, ReplyStage.SENDING)) return@flow
        val start = session.state.value
        val sent = session.send(text).id
        sentId.complete(sent)
        if (!stage.compareAndSet(ReplyStage.SENDING, ReplyStage.SENT)) return@flow
        follow(ChatReplyTracker(start, sent))
    }

    override suspend fun cancel(): VoiceCancelOutcome {
        previous = null
        val before = stage.getAndUpdate { if (it == ReplyStage.FINISHED) it else ReplyStage.CANCELLED }
        return when (before) {
            ReplyStage.SENT -> stopOwnRun(sentId.await())
            ReplyStage.SENDING -> withTimeoutOrNull(policy.stopTimeout) { sentId.await() }?.let { stopOwnRun(it) }
                ?: VoiceCancelOutcome.UNRESPONSIVE
            else -> VoiceCancelOutcome.ALREADY_FINISHED
        }
    }

    /** Stops the run only when it is this message's run and in flight. */
    private suspend fun stopOwnRun(id: ClientMessageId): VoiceCancelOutcome {
        if (!ownRunActive(session.state.value, id)) return VoiceCancelOutcome.ALREADY_FINISHED
        session.stopRun()
        val ended = withTimeoutOrNull(policy.stopTimeout) { session.state.first { !ownRunActive(it, id) } }
        return if (ended == null) VoiceCancelOutcome.UNRESPONSIVE else VoiceCancelOutcome.STOPPED
    }

    private fun ownRunActive(state: VoiceChatState, id: ClientMessageId): Boolean =
        state.startedRuns[id].orEmpty().any { it in state.activeRuns }

    private fun observed(): Flow<Pair<VoiceChatState, ReplyStage>> = combine(session.state, stage, ::Pair)

    private suspend fun FlowCollector<VoiceReplyEvent>.follow(tracker: ChatReplyTracker) {
        var last = session.state.value
        val delivered = withTimeoutOrNull(policy.deliveryTimeout) {
            observed().first { (state, now) ->
                last = state
                now == ReplyStage.CANCELLED || tracker.settled(state)
            }
        }
        when {
            delivered == null -> finish(failure(if (tracker.anchored(last)) VoiceErrorCode.UNATTRIBUTED else VoiceErrorCode.NOT_DELIVERED))
            delivered.second == ReplyStage.CANCELLED -> Unit
            else -> observed().first { (state, now) -> progress(tracker, state, now) }
        }
    }

    /** Emits what changed in [state]; true when following is over. */
    private suspend fun FlowCollector<VoiceReplyEvent>.progress(
        tracker: ChatReplyTracker,
        state: VoiceChatState,
        now: ReplyStage,
    ): Boolean {
        if (now == ReplyStage.CANCELLED) return true
        val changed = tracker.conversationChanged(state)
        if (!changed) tracker.advance(state)?.let { emit(VoiceReplyEvent.Text(it)) }
        return ending(tracker, state)?.let { finish(it) } ?: false
    }

    /** How following ends in [state], or null while the run is still going. */
    private fun ending(tracker: ChatReplyTracker, state: VoiceChatState): VoiceReplyEvent? = when {
        tracker.conversationChanged(state) -> failure(VoiceErrorCode.CANCELLED)
        tracker.running(state) -> null
        tracker.failed(state) -> failure(VoiceErrorCode.RUN_FAILED)
        else -> VoiceReplyEvent.Completed
    }

    private suspend fun FlowCollector<VoiceReplyEvent>.finish(event: VoiceReplyEvent): Boolean {
        if (stage.compareAndSet(ReplyStage.SENT, ReplyStage.FINISHED)) emit(event)
        return true
    }

    private fun failure(code: VoiceErrorCode): VoiceReplyEvent = VoiceReplyEvent.Failed(VoiceError(code, VoicePort.RESPONDER))
}
