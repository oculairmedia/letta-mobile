package com.letta.mobile.data.swarm

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.runtime.TurnHandoverCancellation
import com.letta.mobile.data.runtime.TurnInputAcknowledgement
import com.letta.mobile.data.runtime.TurnInputAcknowledgementListener
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.TimeMark
import com.letta.mobile.runtime.ConversationId as RuntimeConversationId

/** A reply that has landed (it has messages) and did not change since the previous read. */
internal fun SwarmReply?.isUnchangedReplyOf(previous: SwarmReply?): Boolean =
    this != null && messageIds.isNotEmpty() && this == previous

/** The same parked call, whether it arrived as a control request or as a streamed approval message. */
internal fun PendingSwarmApproval.isSameRequestAs(other: PendingSwarmApproval): Boolean =
    toolCallId == other.toolCallId || approvalId == other.approvalId

/** How one dial of a send ended. */
internal sealed interface AttemptOutcome {
    /** The send reported its final event. */
    data object Finished : AttemptOutcome

    /** The link dropped mid-send; the next dial resumes [entry]. */
    data class LinkLost(val entry: OutboxEntry) : AttemptOutcome
}

/** Emits a send's events and remembers the furthest stage confirmed (for [DeliveryFailure.TimedOut]). */
internal class DeliveryReporter(private val channel: SendChannel<DeliveryEvent>) {
    private val furthest = MutableStateFlow<DeliveryStage?>(null)

    val reached: DeliveryStage? get() = furthest.value

    suspend fun report(event: DeliveryEvent) {
        if (event is DeliveryReceipt) furthest.update { current -> maxOf(current ?: event.stage, event.stage) }
        channel.send(event)
    }
}

/** The send's outbox entry: every change is written to the outbox before it is used. */
internal class OutboxEntryHolder(private val outbox: SwarmOutbox, initial: OutboxEntry) {
    private val lock = Mutex()
    private val state = MutableStateFlow(initial)

    val current: OutboxEntry get() = state.value

    suspend fun update(transform: (OutboxEntry) -> OutboxEntry): OutboxEntry = lock.withLock {
        transform(state.value).also { next ->
            outbox.put(next)
            state.value = next
        }
    }

    /** The send reported its outcome: the entry stays as a tombstone so the id is never sent again. */
    suspend fun delivered() {
        update { it.copy(stage = OutboxStage.Delivered) }
    }
}

/**
 * letta-mobile-jvctq: one dial's worth of a send: resolve the agent and conversation in scope
 * (CONNECTED), then either run the turn through the shared engine (ACCEPTED -> PERSISTED ->
 * PROCESSED) or, when the input may already have left on an earlier dial, resume from the history.
 * Watching stops with [TurnHandoverCancellation], never an abort: the server turn keeps running.
 */
internal class SendAttempt(
    private val session: SwarmSession,
    private val holder: OutboxEntryHolder,
    private val request: SwarmSendRequest,
    private val policy: DeliveryPolicy,
    private val deadline: TimeMark,
    private val reporter: DeliveryReporter,
) {
    private val directory = SwarmDirectory(session)
    private val history = SwarmHistory(directory)

    suspend fun run(): AttemptOutcome {
        val (agent, conversation) = directory.resolve(holder.current.agentRef)
            .then { agent -> conversationFor(agent).then { Lookup.Found(agent to it) } }
            .orReport() ?: return AttemptOutcome.Finished
        val entry = holder.update { it.copy(agentId = agent.id, conversation = conversation) }
        reporter.report(DeliveryReceipt.Connected(agent, conversation, entry.id))
        if (entry.isPastSending()) return resume(conversation)
        val anchor = when (val latest = directory.latestMessageId(conversation)) {
            is Lookup.Missing -> return AttemptOutcome.Finished.also { reporter.report(latest.failure) }
            is Lookup.Found -> latest.value
        }
        // Recorded BEFORE the input can leave: from here on a retry resumes instead of re-sending.
        holder.update { it.copy(stage = OutboxStage.Sending, anchor = anchor, attempts = it.attempts + 1) }
        return outcomeOf(watchTurn(agent, conversation), conversation)
    }

    private suspend fun conversationFor(agent: SwarmAgent): Lookup<ConversationId> =
        when (val target = holder.current.target) {
            is ConversationTarget.Existing -> directory.conversationOf(agent, target.id)
            ConversationTarget.New -> holder.current.conversation?.let { directory.conversationOf(agent, it) }
                ?: directory.createConversation(agent).also { created ->
                    if (created is Lookup.Found) holder.update { it.copy(conversation = created.value) }
                }
        }

    private suspend fun watchTurn(agent: SwarmAgent, conversation: ConversationId): TurnStop = coroutineScope {
        val stop = CompletableDeferred<TurnStop>()
        val watch = TurnWatch(this, conversation, stop)
        val turn = launch(TurnInputAcknowledgementListener(watch::onAcknowledged)) {
            session.turnEngine.runTurn(command(agent, conversation))
                .catch { failure -> stop.complete(TurnStop.Terminal(TurnTerminal.Failed(failure.message ?: "turn failed"))) }
                .collect { draft -> watch.onDraft(draft) }
            stop.complete(TurnStop.LinkLost)
        }
        val link = launch {
            session.connected.first { connected -> !connected }
            stop.complete(TurnStop.LinkLost)
        }
        val result = withTimeoutOrNull(deadline.remaining()) { stop.await() } ?: TurnStop.TimedOut
        // Stop watching WITHOUT an abort; once the turn coroutine is gone nothing starts a helper.
        turn.cancel(TurnHandoverCancellation(HANDOVER_REASON))
        turn.join()
        link.cancel()
        watch.close()
        result
    }

    private suspend fun outcomeOf(stop: TurnStop, conversation: ConversationId): AttemptOutcome {
        when (stop) {
            is TurnStop.Terminal -> finish(stop.terminal, conversation)
            TurnStop.WaitReached -> Unit
            is TurnStop.Rejected -> {
                reporter.report(DeliveryFailure.Rejected(stop.reason))
                holder.delivered()
            }
            TurnStop.TimedOut -> reporter.report(DeliveryFailure.TimedOut(reporter.reached))
            TurnStop.LinkLost -> return AttemptOutcome.LinkLost(holder.current)
        }
        return AttemptOutcome.Finished
    }

    /** PERSISTED (once more, if the watcher never saw it) and PROCESSED with the stored reply. */
    private suspend fun finish(terminal: TurnTerminal, conversation: ConversationId) {
        val persisted = holder.current.persistedAs ?: lookUpPersisted(conversation)
        val reply = (persisted ?: holder.current.anchor)?.let { after ->
            (history.replyAfter(conversation, after) as? Lookup.Found)?.value
        } ?: SwarmReply.Empty
        reporter.report(DeliveryReceipt.Processed(terminal, reply))
        holder.delivered()
    }

    /** The persisted message, recorded and reported the first time it is seen. */
    private suspend fun lookUpPersisted(conversation: ConversationId): MessageId? {
        val found = (history.findPersisted(conversation, holder.current) as? Lookup.Found)?.value ?: return null
        var firstSeen = false
        holder.update { entry ->
            if (entry.persistedAs != null) return@update entry
            firstSeen = true
            entry.copy(stage = OutboxStage.Persisted, persistedAs = found)
        }
        if (firstSeen) reporter.report(DeliveryReceipt.Persisted(found))
        return holder.current.persistedAs
    }

    /** The input may have left on an earlier dial: never re-send, read the history instead. */
    private suspend fun resume(conversation: ConversationId): AttemptOutcome {
        val persisted = holder.current.persistedAs?.also { reporter.report(DeliveryReceipt.Persisted(it)) }
            ?: pollPersisted(conversation)
        if (persisted == null) {
            reporter.report(DeliveryFailure.Unconfirmed(holder.current.id))
            return AttemptOutcome.Finished
        }
        if (request.waitFor <= DeliveryStage.Persisted) return AttemptOutcome.Finished
        awaitReplyFromHistory(conversation, persisted)
        return AttemptOutcome.Finished
    }

    private suspend fun pollPersisted(conversation: ConversationId): MessageId? {
        repeat(policy.resumeChecks) { check ->
            lookUpPersisted(conversation)?.let { return it }
            if (check < policy.resumeChecks - 1) delay(policy.pollInterval)
        }
        return null
    }

    /** The reply as the history shows it once it stops growing; its terminal was not seen ([TurnTerminal.Unobserved]). */
    private suspend fun awaitReplyFromHistory(conversation: ConversationId, persisted: MessageId) {
        var last: SwarmReply? = null
        var quietFor = Duration.ZERO
        while (deadline.remaining() > Duration.ZERO) {
            val reply = (history.replyAfter(conversation, persisted) as? Lookup.Found)?.value
            if (reply.isUnchangedReplyOf(last)) {
                quietFor += policy.pollInterval
                if (quietFor >= policy.resumeQuiet) {
                    reporter.report(DeliveryReceipt.Processed(TurnTerminal.Unobserved, reply))
                    holder.delivered()
                    return
                }
            } else {
                quietFor = Duration.ZERO
            }
            last = reply
            delay(policy.pollInterval)
        }
        reporter.report(DeliveryFailure.TimedOut(reporter.reached))
    }

    private fun command(agent: SwarmAgent, conversation: ConversationId) = TurnCommand(
        backendId = BackendId(SWARM_BACKEND_ID),
        runtimeId = RuntimeId("$SWARM_BACKEND_ID:${conversation.value}"),
        agentId = agent.id,
        conversationId = RuntimeConversationId(conversation.value),
        input = TurnInput.UserMessage(localMessageId = holder.current.id.value, text = holder.current.text),
    )

    private suspend fun <T> Lookup<T>.orReport(): T? = when (this) {
        is Lookup.Found -> value
        is Lookup.Missing -> null.also { reporter.report(failure) }
    }

    private fun TimeMark.remaining(): Duration = (-elapsedNow()).coerceAtLeast(Duration.ZERO)

    /** The per-turn reactions: input acknowledgement, drafts, the PERSISTED watcher, approval settling. */
    private inner class TurnWatch(
        private val scope: CoroutineScope,
        private val conversation: ConversationId,
        private val stop: CompletableDeferred<TurnStop>,
    ) {
        private val approvals = MutableStateFlow<List<PendingSwarmApproval>>(emptyList())

        /** The watch's helper coroutines (PERSISTED polling, approval settling): [close] ends them all. */
        private val helpers = MutableStateFlow<List<Job>>(emptyList())

        suspend fun onAcknowledged(ack: TurnInputAcknowledgement) {
            if (!ack.accepted) {
                stop.complete(TurnStop.Rejected(ack.error ?: "input rejected"))
                return
            }
            val disposition = if (ack.disposition == TurnInputAcknowledgement.QUEUED) InputDisposition.Queued else InputDisposition.Started
            val entry = holder.update { it.copy(stage = OutboxStage.Accepted) }
            reporter.report(DeliveryReceipt.Accepted(entry.id, disposition))
            if (request.waitFor == DeliveryStage.Accepted) {
                stop.complete(TurnStop.WaitReached)
            } else {
                launchHelper { watchPersisted() }
            }
        }

        suspend fun onDraft(draft: RuntimeEventDraft) {
            when (val signal = draft.toSignal()) {
                is DraftSignal.Report -> reporter.report(signal.activity)
                is DraftSignal.Terminal -> stop.complete(TurnStop.Terminal(signal.terminal))
                is DraftSignal.Approval -> onApproval(signal.approval)
                DraftSignal.Ignored -> Unit
            }
        }

        private suspend fun onApproval(approval: PendingSwarmApproval) {
            val known = approvals.value
            if (known.any(approval::isSameRequestAs)) return
            approvals.value = known + approval
            reporter.report(Activity.ApprovalRequested(approval))
            // Parallel tool calls park together: let their requests land before reporting the stop.
            if (known.isEmpty()) {
                launchHelper {
                    delay(policy.approvalSettle)
                    stop.complete(TurnStop.Terminal(TurnTerminal.NeedsApproval(approvals.value)))
                }
            }
        }

        private suspend fun watchPersisted() {
            while (holder.current.persistedAs == null) {
                lookUpPersisted(conversation)?.let { _ ->
                    if (request.waitFor == DeliveryStage.Persisted) stop.complete(TurnStop.WaitReached)
                    return
                }
                delay(policy.pollInterval)
            }
        }

        private fun launchHelper(block: suspend () -> Unit) {
            val job = scope.launch { block() }
            helpers.update { it + job }
        }

        suspend fun close() {
            helpers.value.forEach { it.cancelAndJoin() }
        }
    }

    private companion object {
        const val SWARM_BACKEND_ID = "swarm"
        const val HANDOVER_REASON = "meridian cli stopped watching; the turn continues on the host"
    }
}
