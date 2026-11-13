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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Budgets for answering through a voice chat session. */
data class ChatReplyPolicy(
    /**
     * Longest wait for the session to publish the run the message started, counted from when it
     * can start: once the message has left the chat's send queue and the reply before it has ended.
     */
    val deliveryTimeout: Duration = 30.seconds,
    /** Longest wait for a stopped run to end before a cancel reports UNRESPONSIVE. */
    val stopTimeout: Duration = 10.seconds,
)

/**
 * The responder for the conversation's agent, over a [VoiceChatSession].
 *
 * Collecting a reply sends the utterance and follows, by identity only, the run the session says
 * that message started: assistant text rows of that run are spoken as they stream, each sentence as
 * soon as it is complete (a message's last sentence once a later row of the run follows it, or the
 * run ends); reasoning, tool rows and errors are not. It completes when that run is no longer in flight, reports
 * RUN_FAILED when it failed, NOT_DELIVERED when the message never appears, UNATTRIBUTED (with
 * nothing spoken) when the message appears but its run is never published within
 * [ChatReplyPolicy.deliveryTimeout], and CANCELLED when the conversation changes. Cancelling stops
 * the run only when it is this message's run and in flight, and waits for it to end. Cancelling a
 * sent message whose run has not started yet leaves a pending stop for that message's identity
 * ([PendingStops]): the run is stopped the moment the session publishes it as this message's run,
 * and its text is never followed. The cancel waits for that (bounded by
 * [ChatReplyPolicy.stopTimeout]) and reports UNRESPONSIVE when the run has not appeared by then;
 * the stop stays pending and the next reply of this responder that observes the session applies it.
 *
 * A second utterance never cancels the first: it is sent as a follow-up (the chat queues it behind
 * the run in flight) and each reply follows its own run. The delivery wait of a follow-up starts
 * only when it can have started: once it has left the chat's send queue
 * ([VoiceChatState.queued]) and the reply before it has ended, so a long first answer does not
 * make the follow-up look undelivered. A reply cancelled before it sends sends nothing.
 *
 * [respond] is called from one coroutine at a time (the loop's), in the order the utterances are
 * sent.
 */
class VoiceChatResponder(
    session: VoiceChatSession,
    policy: ChatReplyPolicy = ChatReplyPolicy(),
) : VoiceResponder {
    private val context = ReplyContext(session, policy, PendingStops())
    private var latest: VoiceChatReply? = null

    override fun respond(utterance: VoiceUtterance): VoiceReply {
        val reply = VoiceChatReply(context, utterance.text, latest)
        latest = reply
        return reply
    }
}

/** Where one reply is in its life. */
private enum class ReplyStage { NEW, PREPARING, SENDING, SENT, FINISHED, CANCELLED }

/** What every reply of one responder shares: the session, the budgets and the stops still pending. */
private class ReplyContext(
    val session: VoiceChatSession,
    val policy: ChatReplyPolicy,
    val pendingStops: PendingStops,
)

/** One utterance sent through the session and the run that answers it. */
private class VoiceChatReply(
    context: ReplyContext,
    private val text: VoiceText,
    previous: VoiceChatReply?,
) : VoiceReply {
    private val session = context.session
    private val policy = context.policy
    private val pendingStops = context.pendingStops
    private val stage = MutableStateFlow(ReplyStage.NEW)
    private val sentId = CompletableDeferred<ClientMessageId>()

    /** Following this reply is over: it finished, was cancelled, or its collection ended. */
    val ended = MutableStateFlow(false)

    /** Whether the reply sent before this one has ended (only its flag is kept, not the reply). */
    private val previousEnded: Flow<Boolean> = previous?.ended ?: flowOf(true)

    override val events: Flow<VoiceReplyEvent> = flow {
        if (!stage.compareAndSet(ReplyStage.NEW, ReplyStage.PREPARING)) return@flow
        try {
            pendingStops.applyTo(session, session.state.value)
            if (!stage.compareAndSet(ReplyStage.PREPARING, ReplyStage.SENDING)) return@flow
            val start = session.state.value
            val sent = session.send(text).id
            sentId.complete(sent)
            if (stage.compareAndSet(ReplyStage.SENDING, ReplyStage.SENT)) {
                follow(ChatReplyTracker(start, sent))
            } else {
                pendingStops.add(sent)
            }
        } finally {
            ended.value = true
        }
    }

    override suspend fun cancel(): VoiceCancelOutcome {
        val before = stage.getAndUpdate { if (it == ReplyStage.FINISHED) it else ReplyStage.CANCELLED }
        ended.value = true
        return when (before) {
            ReplyStage.SENT -> stopOwnRun(sentId.await())
            ReplyStage.SENDING -> withTimeoutOrNull(policy.stopTimeout) { sentId.await() }?.let { stopOwnRun(it) }
                ?: VoiceCancelOutcome.UNRESPONSIVE
            else -> VoiceCancelOutcome.ALREADY_FINISHED
        }
    }

    /**
     * Stops the message's own run: at once when it is in flight, as a pending stop when the session
     * has not published it yet, and not at all when it already ended.
     */
    private suspend fun stopOwnRun(id: ClientMessageId): VoiceCancelOutcome {
        val state = session.state.value
        if (state.startedRuns[id].isNullOrEmpty()) return stopWhenStarted(id)
        if (!ownRunActive(state, id)) return VoiceCancelOutcome.ALREADY_FINISHED
        session.stopRun()
        return awaitEnd(id)
    }

    /** Leaves a pending stop for [id] and waits (bounded) for its run to appear, be stopped and end. */
    private suspend fun stopWhenStarted(id: ClientMessageId): VoiceCancelOutcome {
        pendingStops.add(id)
        withTimeoutOrNull(policy.stopTimeout) { session.state.first { !it.startedRuns[id].isNullOrEmpty() } }
            ?: return VoiceCancelOutcome.UNRESPONSIVE
        pendingStops.applyTo(session, session.state.value)
        return awaitEnd(id)
    }

    /** Waits (bounded) until [id]'s run is no longer in flight. */
    private suspend fun awaitEnd(id: ClientMessageId): VoiceCancelOutcome {
        val ended = withTimeoutOrNull(policy.stopTimeout) { session.state.first { !ownRunActive(it, id) } }
        return if (ended == null) VoiceCancelOutcome.UNRESPONSIVE else VoiceCancelOutcome.STOPPED
    }

    private fun ownRunActive(state: VoiceChatState, id: ClientMessageId): Boolean =
        state.startedRuns[id].orEmpty().any { it in state.activeRuns }

    /** The session and this reply's stage; every state seen also applies the responder's pending stops. */
    private fun observed(): Flow<Pair<VoiceChatState, ReplyStage>> = combine(session.state, stage) { state, now ->
        pendingStops.applyTo(session, state)
        state to now
    }

    private suspend fun FlowCollector<VoiceReplyEvent>.follow(tracker: ChatReplyTracker) {
        if (!awaitTurn(tracker)) return
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

    /**
     * Waits, without a deadline, while the message cannot have started its run: it is still in the
     * chat's send queue, or the reply sent before it is still being followed. Ends early when its
     * run is published or the conversation changes. False when this reply was cancelled meanwhile.
     */
    private suspend fun awaitTurn(tracker: ChatReplyTracker): Boolean {
        val now = combine(observed(), previousEnded) { (state, now), before -> now.takeIf { mayFollow(tracker, state, now, before) } }
            .filterNotNull()
            .first()
        return now != ReplyStage.CANCELLED
    }

    /** Waiting is over: cancelled, delivery already decided, or the message can have started by now. */
    private fun mayFollow(tracker: ChatReplyTracker, state: VoiceChatState, now: ReplyStage, previousDone: Boolean): Boolean = when {
        now == ReplyStage.CANCELLED -> true
        tracker.settled(state) -> true
        else -> previousDone && !tracker.queued(state)
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
        if (!changed && tracker.partEnded(state)) emit(VoiceReplyEvent.PartEnded)
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
