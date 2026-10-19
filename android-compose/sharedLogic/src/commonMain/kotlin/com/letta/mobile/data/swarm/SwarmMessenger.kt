package com.letta.mobile.data.swarm

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One message to an agent. [text] is sent with [from]'s sender line on top. */
data class SwarmSendRequest(
    val agent: AgentRef,
    val target: ConversationTarget,
    val text: String,
    val from: SenderLabel? = null,
    /** Reuse an id to retry or resume that same message (idempotent: never a second copy). */
    val clientMessageId: ClientMessageId = ClientMessageId.random(),
    /** The stage the send returns at; later stages can be checked with [SwarmMessenger.status]. */
    val waitFor: DeliveryStage = DeliveryStage.Processed,
    val timeout: Duration = 5.minutes,
) {
    init {
        require(text.isNotBlank()) { "message text cannot be blank" }
    }
}

/** Retry, polling and expiry knobs; the defaults are the CLI's. */
data class DeliveryPolicy(
    val maxDialAttempts: Int = 6,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 30.seconds,
    /** A never-sent outbox entry older than this is dropped instead of sent. */
    val ttl: Duration = 24.hours,
    val pollInterval: Duration = 1.seconds,
    /** History reads on resume before an unseen message is reported [DeliveryFailure.Unconfirmed]. */
    val resumeChecks: Int = 5,
    /** A resumed reply is final once the history shows no change for this long. */
    val resumeQuiet: Duration = 5.seconds,
    /** How long parallel approval requests have to land before the stop is reported. */
    val approvalSettle: Duration = 500.milliseconds,
)

/** A read's value, or why it failed. */
sealed interface SwarmResult<out T> {
    data class Ok<T>(val value: T) : SwarmResult<T>

    data class Failed(val failure: DeliveryFailure) : SwarmResult<Nothing>
}

/** What `message status` names: this machine's client id (from the outbox), or the server's message id. */
sealed interface StatusRef {
    data class Client(val id: ClientMessageId) : StatusRef

    data class Server(val id: MessageId) : StatusRef
}

/** Where a message stands, read from the outbox and the history. */
data class SwarmStatus(
    val outboxStage: OutboxStage?,
    val persisted: MessageId?,
    val reply: SwarmReply,
) {
    val replied: Boolean get() = reply.messageIds.isNotEmpty()
}

/**
 * Talks to the agent swarm as a paired device, over the shared App Server
 * client and turn stack. [send] reports staged receipts (CONNECTED, ACCEPTED, PERSISTED,
 * PROCESSED) as a flow; the outbox makes it durable and idempotent: an entry is persisted before
 * anything is dialled, re-sent only while its input never left, and otherwise resumed from the
 * history by its client message id. Platform code supplies the [link] (the Iroh dial) and the
 * [outbox] (files); everything else is here, for any client that wants it.
 */
class SwarmMessenger(
    private val link: SwarmLink,
    private val outbox: SwarmOutbox,
    private val policy: DeliveryPolicy = DeliveryPolicy(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun send(request: SwarmSendRequest): Flow<DeliveryEvent> = channelFlow {
        val reporter = DeliveryReporter(channel)
        val holder = admit(request, reporter) ?: return@channelFlow
        SendLoop(request, holder, reporter, timeSource.markNow() + request.timeout).run()
    }

    /** One send's dial loop: each dial is an attempt; a link failure is retried with backoff or ends the send. */
    private inner class SendLoop(
        private val request: SwarmSendRequest,
        private val holder: OutboxEntryHolder,
        private val reporter: DeliveryReporter,
        private val deadline: TimeMark,
    ) {
        private var failures = 0

        suspend fun run() {
            while (nextDial() == LoopStep.Continue) Unit
        }

        private suspend fun nextDial(): LoopStep =
            if (deadline.hasPassedNow()) stop(DeliveryFailure.TimedOut(reporter.reached)) else dialOnce()

        private suspend fun dialOnce(): LoopStep =
            when (val result = link.withSession { SendAttempt(it, holder, request, policy, deadline, reporter).run() }) {
                is LinkResult.Ok -> result.value.toLoopStep()
                is LinkResult.Failed -> afterLinkFailure(result.failure)
            }

        private suspend fun afterLinkFailure(failure: LinkFailure): LoopStep {
            failures += 1
            val retry = retryAfter(failure, failures) ?: return stop(failure.toDeliveryFailure())
            reporter.report(Retrying(failures, failure.reasonText(), retry.inWholeMilliseconds))
            delay(retry)
            return LoopStep.Continue
        }

        private suspend fun stop(failure: DeliveryFailure): LoopStep {
            reporter.report(failure)
            return LoopStep.Stop
        }
    }

    /** The agents in this device's scope. */
    suspend fun agents(): SwarmResult<List<SwarmAgent>> = withLookup { SwarmDirectory(it).agents() }

    /** [conversation]'s messages after [since] (or its newest [limit]), refused unless it is [agent]'s. */
    suspend fun read(
        agent: AgentRef,
        conversation: ConversationId,
        since: MessageId?,
        limit: Int = SwarmLimits.MAX_READ_MESSAGES,
    ): SwarmResult<List<SwarmMessage>> = withLookup { session ->
        val directory = SwarmDirectory(session)
        directory.inScope(agent, conversation).then { scoped ->
            directory.messages(scoped, since, limit).then { messages -> Lookup.Found(messages.map { it.toSwarmMessage() }) }
        }
    }

    suspend fun status(agent: AgentRef, conversation: ConversationId, ref: StatusRef): SwarmResult<SwarmStatus> {
        val entry = (ref as? StatusRef.Client)?.let { outbox.get(it.id) }
        return withLookup { session ->
            val directory = SwarmDirectory(session)
            val history = SwarmHistory(directory)
            directory.inScope(agent, conversation).then { scoped ->
                persistedOf(history, scoped, ref, entry).then { persisted ->
                    val reply = persisted?.let { (history.replyAfter(scoped, it) as? Lookup.Found)?.value } ?: SwarmReply.Empty
                    Lookup.Found(SwarmStatus(entry?.stage, persisted, reply))
                }
            }
        }
    }

    /** Messages this machine never got through: entries still in the outbox. */
    /** The outbox record of [id] (delivered ones too, for the time to live), if this machine sent it. */
    suspend fun outboxEntry(id: ClientMessageId): OutboxEntry? = outbox.get(id)

    suspend fun pending(): List<OutboxEntry> = outbox.list().filter { it.stage != OutboxStage.Delivered }

    private suspend fun persistedOf(
        history: SwarmHistory,
        conversation: ConversationId,
        ref: StatusRef,
        entry: OutboxEntry?,
    ): Lookup<MessageId?> = when (ref) {
        is StatusRef.Server -> Lookup.Found(ref.id)
        is StatusRef.Client -> entry?.persistedAs?.let { Lookup.Found(it) }
            ?: entry?.let { history.findPersisted(conversation, it) }
            ?: Lookup.Found(null)
    }

    /** The send's outbox entry: stored before anything else, or the stored one when the id is resumed. */
    private suspend fun admit(request: SwarmSendRequest, reporter: DeliveryReporter): OutboxEntryHolder? {
        val text = request.text.withSenderLine(request.from)
        require(text.length <= SwarmLimits.MAX_MESSAGE_CHARS) { "message exceeds ${SwarmLimits.MAX_MESSAGE_CHARS} characters" }
        val fresh = OutboxEntry(request.clientMessageId, request.agent, request.target, text, createdAtMs = nowMs())
        pruneTombstones()
        val stored = outbox.get(request.clientMessageId) ?: return OutboxEntryHolder(outbox, fresh.also { outbox.put(it) })
        return when {
            !stored.sameMessageAs(fresh) -> refuse(reporter, DeliveryFailure.IdReused(fresh.id))
            stored.isExpiredUnsent() -> {
                outbox.remove(stored.id)
                refuse(reporter, DeliveryFailure.Expired(stored.id))
            }
            else -> OutboxEntryHolder(outbox, stored)
        }
    }

    private suspend fun refuse(reporter: DeliveryReporter, failure: DeliveryFailure): OutboxEntryHolder? {
        reporter.report(failure)
        return null
    }

    /** A never-sent entry past its time to live: dropped, never sent late. */
    private fun OutboxEntry.isExpiredUnsent(): Boolean = !isPastSending() && isOlderThanTtl()

    /** Delivered entries are kept for the time to live only. */
    private suspend fun pruneTombstones() {
        outbox.list().filter { it.stage == OutboxStage.Delivered && it.isOlderThanTtl() }.forEach { outbox.remove(it.id) }
    }

    private fun OutboxEntry.isOlderThanTtl(): Boolean = nowMs() - createdAtMs > policy.ttl.inWholeMilliseconds

    /** The backoff before the next dial, or null when [failure] is final (fail-closed) or the budget is spent. */
    private fun retryAfter(failure: LinkFailure, failures: Int): Duration? = when (failure) {
        LinkFailure.NotPaired, is LinkFailure.Denied -> null
        is LinkFailure.Unreachable -> if (failures >= policy.maxDialAttempts) {
            null
        } else {
            (policy.initialBackoff * (1 shl (failures - 1).coerceAtMost(MAX_BACKOFF_SHIFT))).coerceAtMost(policy.maxBackoff)
        }
    }

    private suspend fun <T> withLookup(block: suspend (SwarmSession) -> Lookup<T>): SwarmResult<T> =
        when (val result = link.withSession(block)) {
            is LinkResult.Failed -> SwarmResult.Failed(result.failure.toDeliveryFailure())
            is LinkResult.Ok -> when (val lookup = result.value) {
                is Lookup.Found -> SwarmResult.Ok(lookup.value)
                is Lookup.Missing -> SwarmResult.Failed(lookup.failure)
            }
        }

    private companion object {
        const val MAX_BACKOFF_SHIFT = 10
    }
}

/** [conversation], once [agent] is resolved in scope and owns it. */
internal suspend fun SwarmDirectory.inScope(agent: AgentRef, conversation: ConversationId): Lookup<ConversationId> =
    resolve(agent).then { resolved -> conversationOf(resolved, conversation) }

/** Whether the dial loop goes on after one dial. */
internal enum class LoopStep { Continue, Stop }

/** A finished attempt ends the send; a lost link dials again (the next attempt resumes). */
internal fun AttemptOutcome.toLoopStep(): LoopStep = when (this) {
    AttemptOutcome.Finished -> LoopStep.Stop
    is AttemptOutcome.LinkLost -> LoopStep.Continue
}

internal fun LinkFailure.toDeliveryFailure(): DeliveryFailure = when (this) {
    LinkFailure.NotPaired -> DeliveryFailure.NotPaired
    is LinkFailure.Denied -> DeliveryFailure.Denied(reason)
    is LinkFailure.Unreachable -> DeliveryFailure.Unreachable(reason)
}

private fun LinkFailure.reasonText(): String = when (this) {
    LinkFailure.NotPaired -> "not paired"
    is LinkFailure.Denied -> reason
    is LinkFailure.Unreachable -> reason
}
