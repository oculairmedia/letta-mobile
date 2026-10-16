package com.letta.mobile.cli.swarm

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.swarm.AgentRef
import com.letta.mobile.data.swarm.ClientMessageId
import com.letta.mobile.data.swarm.ConversationTarget
import com.letta.mobile.data.swarm.DeliveryEvent
import com.letta.mobile.data.swarm.DeliveryStage
import com.letta.mobile.data.swarm.SenderLabel
import com.letta.mobile.data.swarm.StatusRef
import com.letta.mobile.data.swarm.SwarmLimits
import com.letta.mobile.data.swarm.SwarmResult
import com.letta.mobile.data.swarm.SwarmSendRequest
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

/** `meridian message send`: one message to an agent, with staged delivery receipts. */
class MessageSendCommand(environment: SwarmCliEnvironment = SwarmCliEnvironment()) : SwarmCommand(
    "send",
    "Send a message to an agent as this paired device and wait for its reply. Without --conversation a new " +
        "conversation is created for it (the default for orchestrator traffic). Tool approvals are never answered: " +
        "a turn that needs one exits ${SwarmExit.NeedsApproval.code} and waits for the person in the app.",
    environment,
) {
    private val agent by option("--agent", help = "Agent id or name (case-insensitive), within this device's scope.")
        .convert { AgentRef(it) }
        .required()
    private val conversation by option("--conversation", help = "Existing conversation of that agent.")
        .convert { ConversationId(it) }
    private val newConversation by option("--new", help = "Create a new conversation (the default without --conversation).")
        .flag()
    private val text by option("--text", help = "The message text.")
    private val fromStdin by option("--stdin", help = "Read the message text from stdin (UTF-8).").flag()
    private val from by option("--from", help = "Short self-declared sender label put on the first line (display only).")
        .convert { raw -> SenderLabel.of(raw) ?: fail("--from has no printable characters") }
    private val wait by option("--wait", help = "Return once this stage is confirmed: accepted, persisted or processed (default).")
        .choice("accepted" to DeliveryStage.Accepted, "persisted" to DeliveryStage.Persisted, "processed" to DeliveryStage.Processed)
        .default(DeliveryStage.Processed)
    private val timeoutMs by option("--timeout-ms", help = "Overall deadline (default 300000). The host turn is never aborted on timeout.")
        .long()
        .restrictTo(min = 1)
        .default(DEFAULT_TIMEOUT_MS)
    private val clientMessageId by option(
        "--client-message-id",
        help = "Idempotency key; reuse it to retry or resume the same message (default: a new id).",
    ).convert { raw -> ClientMessageId.parse(raw) ?: fail("--client-message-id must be 1-96 of [A-Za-z0-9._-]") }

    override fun run() = runBlocking {
        val request = request()
        var last: DeliveryEvent? = null
        environment.messenger(profile).send(request).collect { event ->
            last = event
            print(event)
        }
        val exit = sendExit(last)
        if (exit != SwarmExit.Ok) throw ProgramResult(exit.code)
    }

    internal fun request(): SwarmSendRequest {
        if (newConversation && conversation != null) throw UsageError("use either --conversation or --new, not both")
        val body = messageText()
        if (body.isBlank()) throw UsageError("the message is empty")
        return SwarmSendRequest(
            agent = agent,
            target = conversation?.let(ConversationTarget::Existing) ?: ConversationTarget.New,
            text = body,
            from = from,
            clientMessageId = clientMessageId ?: ClientMessageId.random(),
            waitFor = wait,
            timeout = timeoutMs.milliseconds,
        )
    }

    private fun messageText(): String = when {
        text != null && fromStdin -> throw UsageError("use either --text or --stdin, not both")
        fromStdin -> readBounded(environment.stdin(), SwarmLimits.MAX_MESSAGE_CHARS)
        else -> text?.also { inline ->
            if (inline.length > SwarmLimits.MAX_MESSAGE_CHARS) throw UsageError("--text is longer than ${SwarmLimits.MAX_MESSAGE_CHARS} characters")
        } ?: throw UsageError("give the message with --text or --stdin")
    }

    private fun print(event: DeliveryEvent) {
        when (output) {
            OutputFormat.Json -> echo(event.toJson().printable())
            OutputFormat.Text -> event.toText()?.let { line -> echo(line.text, err = line.toStderr) }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 300_000L
    }
}

/** `meridian message read`: a conversation's messages, refused unless the agent in scope owns it. */
class MessageReadCommand(environment: SwarmCliEnvironment = SwarmCliEnvironment()) : SwarmCommand(
    "read",
    "Read a conversation of an agent in this device's scope (oldest first). --since returns only what came after a message id.",
    environment,
) {
    private val agent by option("--agent", help = "Agent id or name.").convert { AgentRef(it) }.required()
    private val conversation by option("--conversation", help = "Conversation id.").convert { ConversationId(it) }.required()
    private val since by option("--since", help = "Only messages after this message id.").convert { MessageId(it) }
    private val limit by option("--limit", help = "At most this many messages (1-${SwarmLimits.MAX_READ_MESSAGES}, default 50).")
        .int()
        .restrictTo(1, SwarmLimits.MAX_READ_MESSAGES)
        .default(DEFAULT_LIMIT)

    override fun run() = runBlocking {
        when (val result = environment.messenger(profile).read(agent, conversation, since, limit)) {
            is SwarmResult.Ok -> echo(if (output == OutputFormat.Json) result.value.toJson().printable() else result.value.toText())
            is SwarmResult.Failed -> failWith(result.failure, output)
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}

/** `meridian message status`: where a message stands (outbox, persisted, replied). */
class MessageStatusCommand(environment: SwarmCliEnvironment = SwarmCliEnvironment()) : SwarmCommand(
    "status",
    "Check a message sent earlier: by its client message id (kept in this machine's outbox for 24 h) or by the " +
        "server message id a `persisted` receipt printed.",
    environment,
) {
    private val agent by option("--agent", help = "Agent id or name.").convert { AgentRef(it) }.required()
    private val conversation by option("--conversation", help = "Conversation id.").convert { ConversationId(it) }.required()
    private val messageId by option("--message-id", help = "Client or server message id.").required()

    override fun run() = runBlocking {
        val messenger = environment.messenger(profile)
        val clientId = ClientMessageId.parse(messageId)?.takeIf { id -> messenger.outboxEntry(id) != null }
        val ref = clientId?.let(StatusRef::Client) ?: StatusRef.Server(MessageId(messageId))
        when (val result = messenger.status(agent, conversation, ref)) {
            is SwarmResult.Ok -> echo(if (output == OutputFormat.Json) result.value.toJson().printable() else result.value.toText())
            is SwarmResult.Failed -> failWith(result.failure, output)
        }
    }
}

/** `meridian message pending`: outbox entries not yet delivered (resume one with `send --client-message-id`). */
class MessagePendingCommand(environment: SwarmCliEnvironment = SwarmCliEnvironment()) : SwarmCommand(
    "pending",
    "List this machine's undelivered outbox entries. Re-run `message send` with the same --client-message-id (and the " +
        "same agent, target and text) to retry or resume one; it is never sent twice.",
    environment,
) {
    override fun run() = runBlocking {
        val entries = environment.messenger(profile).pending()
        val lines = entries.map { entry ->
            "${entry.id.value}\t${entry.stage.name.lowercase()}\t${entry.agentRef.value}\t${entry.conversation?.value ?: "(new)"}"
        }
        echo(if (output == OutputFormat.Json) pendingJson(entries).printable() else lines.joinToString("\n"))
    }
}
