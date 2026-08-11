package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
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
    private val body by option("--body", help = "Message body.").required()
    private val msgId by option("--msg-id", help = "Idempotency id (defaults to a random uuid).").default("")
    private val conversationId by option("--conversation-id", help = "Target conversation id on the recipient (omit to create/use the most recent interactive conversation).").default("")
    private val identityDir by option("--identity-dir", help = "Per-agent Iroh identity dir.")
        .default(File(System.getProperty("user.home"), ".letta/iroh/identities").path)
    private val addressStore by option("--address-store", help = "Agent address book kv file.")
        .default(File(System.getProperty("user.home"), ".letta/iroh/agent-addresses.kv").path)

    override fun run() = runBlocking {
        val id = msgId.ifBlank { "msg-${java.util.UUID.randomUUID()}" }
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
                is AgentSendResult.Unaddressable, is AgentSendResult.Failed -> exitProcess(1)
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

/** The harness-facing JSON result contract for the a2a-send tool entry point. */
internal fun agentSendResultJson(result: AgentSendResult, id: String): String {
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return when (result) {
        is AgentSendResult.Delivered ->
            """{"ok":true,"delivered":true,"msgId":${q(result.msgId)}}"""
        is AgentSendResult.Unaddressable ->
            """{"ok":false,"delivered":false,"msgId":${q(id)},"error":"unaddressable","toAgentId":${q(result.toAgentId)},"reason":${q(result.reason)}}"""
        is AgentSendResult.Failed ->
            """{"ok":false,"delivered":false,"msgId":${q(id)},"error":"failed","toAgentId":${q(result.toAgentId)},"reason":${q(result.reason)}}"""
    }
}
