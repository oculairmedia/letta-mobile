package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.letta.mobile.data.transport.iroh.AgentSendResult
import com.letta.mobile.data.transport.iroh.HostEndpointAddressStore
import com.letta.mobile.data.transport.iroh.IrohAgentAddressResolver
import com.letta.mobile.data.transport.iroh.IrohAgentIdentity
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import com.letta.mobile.data.transport.iroh.IrohAgentMessageSender
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * letta-mobile-bn008.4: the letta-mobile-side entry point for the messaging tool.
 *
 * The built-in matrix_messaging/talk_to_agent tool (a letta-code harness tool)
 * re-points its transport to invoke THIS command so an agent-to-agent message
 * routes over Iroh direct (bn008.2 send + bn008.1 resolver) instead of Matrix.
 * Interface unchanged for agents — only the transport underneath swaps. No HTTP
 * fallback. Emits a JSON result line the harness parses.
 *
 * Usage: meridian agent-message send --from <agentId> --to <agentId> --body <text>
 * [--conversation-id <convId>]  (5m1qy: target an existing conversation on the recipient)
 *
 * letta-mobile-e12nf: also accepts `--body-file <path|->`, matching the
 * `resolveRequestBody`-style convention used by the REST commands
 * ([ResourceCommands], [RestCommands]) — `-` means "read from stdin". This
 * is the LOAD-BEARING path for [com.letta.mobile.data.controller.extras.
 * CustomIrohMessagingTool] / [com.letta.mobile.data.controller.extras.
 * DefaultIrohCliRunner], which always invokes this command with
 * `--body-file -` and pipes the body over stdin specifically so multi-line
 * bodies, embedded quotes, and shell-special characters round-trip exactly
 * (see DefaultIrohCliRunner KDoc — this is what avoids the Meridian→Lester
 * body-collapse regression). `--body "<inline>"` is kept for interactive/
 * scripted use where shell quoting is the caller's own problem; exactly one
 * of `--body` / `--body-file` must be supplied.
 *
 * letta-mobile-xmpqm: the address book is the host-level
 * [HostEndpointAddressStore]. No backend membership oracle is wired here
 * (this is a sender-only CLI; the recipient's wrapper owns the host record).
 * Membership is therefore disabled — resolve() returns Found for any agentId
 * the caller asks about as long as the host record is present. The previous
 * [FileIrohAgentAddressStore] answer was stricter (per-agent row required);
 * the new contract is "ask the host, not the row".
 */
class AgentMessageSendCommand : CliktCommand(name = "send") {
    private val fromAgentId by option("--from", help = "Sender agentId.").required()
    private val toAgentId by option("--to", help = "Target agentId.").required()
    private val bodyInline by option("--body", help = "Message body (inline; shell-quoting is the caller's responsibility).")
    private val bodyFile by option("--body-file", help = "Path to a file containing the message body, or '-' to read from stdin. Preferred over --body for multi-line / quote-heavy bodies.")
    private val msgId by option("--msg-id", help = "Idempotency id (defaults to a random uuid).").default("")
    private val conversationId by option("--conversation-id", help = "Target conversation id on the recipient (omit to create/use the most recent interactive conversation).").default("")
    private val identityDir by option("--identity-dir", help = "Per-agent Iroh identity dir.")
        .default(File(System.getProperty("user.home"), ".letta/iroh/identities").path)
    private val addressStore by option("--address-store", help = "Agent address book kv file.")
        .default(File(System.getProperty("user.home"), ".letta/iroh/agent-addresses.kv").path)

    override fun run() = runBlocking {
        val id = msgId.ifBlank { "msg-${java.util.UUID.randomUUID()}" }
        val body = resolveBody(bodyInline, bodyFile)
        val identity = IrohAgentIdentity.loadOrCreate(fromAgentId, File(identityDir))
        val endpoint = Endpoint.bind(
            EndpointOptions(
                relayMode = RelayMode.defaultMode(),
                secretKey = identity.secretKeyBytes,
                alpns = listOf(IrohAgentMessage.ALPN),
            ),
        )
        try {
            endpoint.online()
            // No backend store injected — this CLI is a sender, not a
            // membership oracle. The host record is the recipient's
            // wrapper's responsibility; resolve() here returns Found for any
            // agentId the caller asks about as long as the host record is
            // present. Garbage ids still surface as `unknown_host` (no host
            // record on disk) — not silently Found.
            val resolver = IrohAgentAddressResolver(HostEndpointAddressStore(File(addressStore)))
            val sender = IrohAgentMessageSender(endpoint, resolver)
            val result = sender.send(
                IrohAgentMessage(
                    fromAgentId = fromAgentId,
                    toAgentId = toAgentId,
                    body = body,
                    msgId = id,
                    ts = System.currentTimeMillis(),
                    conversationId = conversationId.ifBlank { null },
                ),
            )
            println(resultJson(result, id))
            when (result) {
                is AgentSendResult.Delivered -> exitProcess(0)
                is AgentSendResult.Accepted, is AgentSendResult.Unaddressable, is AgentSendResult.Failed -> exitProcess(1)
            }
        } finally {
            runCatching { endpoint.shutdown() }
        }
    }

    private fun resultJson(result: AgentSendResult, id: String): String = agentSendResultJson(result, id)
}

/** Parent group so the subcommand reads `meridian agent-message send ...`. */
class AgentMessageCommand : CliktCommand(name = "agent-message") {
    override fun run() = Unit
}

/**
 * letta-mobile-e12nf: resolve the message body from exactly one of `--body`
 * (inline) or `--body-file` (path, or `-` for stdin). Mirrors
 * [resolveRequestBody]'s "exactly one of" contract in [CliRestRequest.kt] for
 * consistency across this CLI's commands, but is standalone here rather than
 * reused because that helper is REST-body-shaped (returns null when both are
 * absent, since a REST body is optional) — an a2a message body is always
 * required, so this throws on the empty case instead of returning null.
 */
internal fun resolveBody(inlineBody: String?, bodyFile: String?): String {
    if (inlineBody != null && bodyFile != null) {
        throw UsageError("Use only one of --body or --body-file")
    }
    return when {
        inlineBody != null -> inlineBody
        bodyFile == "-" -> try {
            System.`in`.readBytes().toString(Charsets.UTF_8)
        } catch (error: IOException) {
            throw UsageError("Unable to read body from stdin: ${error.message}")
        }
        bodyFile != null -> try {
            String(Files.readAllBytes(Paths.get(bodyFile)), Charsets.UTF_8)
        } catch (error: IOException) {
            throw UsageError("Unable to read --body-file '$bodyFile': ${error.message}")
        } catch (error: RuntimeException) {
            throw UsageError("Unable to read --body-file '$bodyFile': ${error.message}")
        }
        else -> throw UsageError("Provide the message body via --body or --body-file")
    }
}

/** The harness-facing JSON result contract for the a2a-send tool entry point. */
internal fun agentSendResultJson(result: AgentSendResult, id: String): String {
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return when (result) {
        is AgentSendResult.Delivered ->
            """{"ok":true,"accepted":true,"applicationDelivered":true,"msgId":${q(result.msgId)}}"""
        is AgentSendResult.Accepted ->
            """{"ok":true,"accepted":true,"applicationDelivered":false,"msgId":${q(result.msgId)},"toAgentId":${q(result.toAgentId)}}"""
        is AgentSendResult.Unaddressable ->
            """{"ok":false,"accepted":false,"applicationDelivered":false,"msgId":${q(id)},"error":"unaddressable","toAgentId":${q(result.toAgentId)},"reason":${q(result.reason)}}"""
        is AgentSendResult.Failed ->
            """{"ok":false,"accepted":false,"applicationDelivered":false,"msgId":${q(id)},"error":"failed","toAgentId":${q(result.toAgentId)},"reason":${q(result.reason)}}"""
    }
}
