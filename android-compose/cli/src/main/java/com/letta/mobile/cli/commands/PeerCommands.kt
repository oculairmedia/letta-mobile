package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Operator-side paired-device management over Iroh admin_rpc (d6e8g.7 slice 2).
 *
 * Dials a running `app-server-serve-iroh` endpoint, authenticates with the
 * admin bearer token, and drives the `pair.*` admin methods shipped in
 * slice 1 (#981). This is the CLI half of "paired-device inventory,
 * revocation, rotation, and recovery" — invite/list/get/rename/set-capabilities/
 * revoke — so an operator can manage devices without the app UI.
 *
 * Usage: `meridian peer <invite|list|get|rename|set-capabilities|revoke> --ticket <server> [--auth-token <tok>] ...`
 * The server address accepts a full Iroh ticket, a 64-hex NodeId, or
 * `<nodeid-hex>@host:port` (e.g. the local wrapper at `…@127.0.0.1:4501`).
 */
class PeerCommand : CliktCommand(name = "peer") {
    override fun run() = Unit
}

/** Builds the admin_rpc params for a peer subcommand; pure for testability. */
/** dpv7m.1: what a minted invite grants (role or explicit capabilities, optional agent scope). */
internal data class InviteGrant(
    val role: String? = null,
    val capabilities: String? = null,
    val agents: String? = null,
)

internal object PeerParams {
    fun invite(name: String?, ttlMs: Long?, grant: InviteGrant = InviteGrant()): JsonObject = buildJsonObject {
        name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
        ttlMs?.let { put("ttl_ms", it) }
        grant.role?.takeIf { it.isNotBlank() }?.let { put("role", it) }
        grant.capabilities?.takeIf { it.isNotBlank() }?.let { put("capabilities", it) }
        grant.agents?.takeIf { it.isNotBlank() }?.let { put("agent_ids", it) }
        // dpv7m.1: always ask for the scannable form; old hosts ignore the flag.
        put("qr", true)
    }

    fun inviteId(inviteId: String): JsonObject = buildJsonObject { put("invite_id", inviteId) }

    fun setAgents(nodeId: String, agents: List<String>?): JsonObject = buildJsonObject {
        put("node_id", nodeId)
        put("agent_ids", agents?.let { list -> JsonArray(list.map { JsonPrimitive(it) }) } ?: kotlinx.serialization.json.JsonNull)
    }

    fun nodeId(nodeId: String): JsonObject = buildJsonObject { put("node_id", nodeId) }

    fun rename(nodeId: String, name: String): JsonObject = buildJsonObject {
        put("node_id", nodeId)
        put("name", name)
    }

    fun setCapabilities(nodeId: String, capabilities: List<String>): JsonObject = buildJsonObject {
        put("node_id", nodeId)
        put("capabilities", JsonArray(capabilities.map { JsonPrimitive(it) }))
    }
}

private val prettyJson = Json { prettyPrint = true }

internal fun formatAdminResponse(success: Boolean, result: kotlinx.serialization.json.JsonElement?, error: String?): String =
    if (success) {
        result?.let { prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) } ?: "{}"
    } else {
        """{"success":false,"error":${JsonPrimitive(error ?: "unknown error")}}"""
    }

/**
 * Shared dial+auth harness. Ephemeral client identity (the bearer token
 * authorizes, so a stable NodeId is unnecessary); the endpoint is always closed.
 */
internal class IrohAdminDialer(
    private val ticket: String,
    private val token: String?,
    private val timeoutMs: Long,
) {
    suspend fun <T> use(block: suspend (AppServerClient) -> T): T = supervisorScope {
        // The transport's reader coroutines are children of this SUPERVISOR
        // scope, not of the caller's: on teardown the connection ends with
        // ConnectionLost, and a supervised reader failing cannot clobber an
        // already-delivered result. They are cancelled in finally so teardown
        // never outlives the returned value.
        val transportScope = this + Dispatchers.Default
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode()))
        try {
            // Bring the endpoint online so its native driver/reactor stays alive
            // for the lifetime of the call (matches AgentMessageSendCommand);
            // without this the driver future is dropped mid-request.
            endpoint.online()
            val transport = IrohAppServerTransportAdapter(endpoint).createTransport(
                endpoint = AppServerEndpoint(scheme = "iroh", address = ticket),
                scope = transportScope,
            ) as IrohAppServerTransport
            transport.awaitConnectionReady()
            val client = DefaultAppServerClient(transport, requestTimeoutMs = timeoutMs)
            val auth = client.auth(
                AppServerCommand.Auth(
                    requestId = "peer-auth-${UUID.randomUUID()}",
                    token = token ?: "",
                    capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
                ),
            )
            if (!auth.success) error("Iroh auth failed: ${auth.error ?: "denied"}")
            block(client)
        } finally {
            coroutineContext.cancelChildren()
            runCatching { endpoint.close() }
        }
    }
}

/** Base for `peer` subcommands: shares the dial options and the call/print flow. */
abstract class PeerAdminSubcommand(name: String, private val helpText: String) : CliktCommand(name = name) {
    override fun help(context: Context): String = helpText

    protected val ticket by option(
        "--ticket",
        envvar = "LETTA_IROH_SERVER_TICKET",
        help = "Server Iroh ticket, 64-hex NodeId, or <nodeid>@host:port (e.g. the local wrapper).",
    ).required()
    protected val authToken by option(
        "--auth-token",
        envvar = "LETTA_IROH_AUTH_TOKEN",
        help = "Admin bearer token (pair.* requires admin.full).",
    )
    protected val timeoutMs by option("--timeout-ms", help = "Request timeout (ms).").default("30000")

    protected abstract fun method(): String

    protected open fun params(): JsonObject? = null

    override fun run() = runBlocking {
        val response = IrohAdminDialer(ticket, authToken, timeoutMs.toLongOrNull() ?: 30000L).use { client ->
            client.adminRpc(
                AppServerCommand.AdminRpc(
                    requestId = "peer-${UUID.randomUUID()}",
                    method = method(),
                    params = params(),
                ),
            )
        }
        println(formatAdminResponse(response.success, response.result, response.error))
        if (!response.success) exitProcess(1)
    }
}

class PeerInviteCommand : PeerAdminSubcommand(
    "invite",
    "Mint a one-time pairing invite for a new device (default grant: chat, no admin). " +
        "The response's pair_link is the scannable QR value; it contains the secret.",
) {
    private val name by option("--name", help = "Suggested device label.")
    private val ttlMs by option("--ttl-ms", help = "Invite lifetime (ms; default 5 min, capped at 1 h).")
    private val role by option("--role", help = "chat (default) | desktop | vibesync | admin.")
    private val capabilities by option("--capabilities", help = "Explicit comma-separated capabilities (overrides --role).")
    private val agents by option("--agents", help = "Comma-separated agent ids the device may use (default: all).")

    override fun method() = "pair.invite.create"
    override fun params() = PeerParams.invite(name, ttlMs?.toLongOrNull(), InviteGrant(role, capabilities, agents))
}

class PeerInviteListCommand : PeerAdminSubcommand("invite-list", "List unredeemed invites (ids and expiry only, no secrets).") {
    override fun method() = "pair.invite.list"
}

class PeerInviteRevokeCommand : PeerAdminSubcommand("invite-revoke", "Revoke an unredeemed invite by its id.") {
    private val inviteId by option("--invite-id", help = "Invite id from invite/invite-list.").required()

    override fun method() = "pair.invite.revoke"
    override fun params() = PeerParams.inviteId(inviteId)
}

class PeerSetAgentsCommand : PeerAdminSubcommand("set-agents", "Limit a paired device to some agents (or --all).") {
    private val nodeId by option("--node-id", help = "Device NodeId (64 hex).").required()
    private val agents by option("--agents", help = "Comma-separated agent ids.")
    private val all by option("--all", help = "Lift the agent scope (every agent).").flag(default = false)

    override fun method() = "pair.peer.set_agents"
    override fun params() = PeerParams.setAgents(
        nodeId,
        if (all) null else agents.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
    )
}

class PeerListCommand : PeerAdminSubcommand("list", "List all paired devices.") {
    override fun method() = "pair.peer.list"
}

class PeerGetCommand : PeerAdminSubcommand("get", "Show one paired device.") {
    private val nodeId by option("--node-id", help = "Device NodeId (64 hex).").required()

    override fun method() = "pair.peer.get"
    override fun params() = PeerParams.nodeId(nodeId)
}

class PeerRenameCommand : PeerAdminSubcommand("rename", "Relabel a paired device.") {
    private val nodeId by option("--node-id", help = "Device NodeId (64 hex).").required()
    private val name by option("--name", help = "New label.").required()

    override fun method() = "pair.peer.rename"
    override fun params() = PeerParams.rename(nodeId, name)
}

class PeerSetCapabilitiesCommand :
    PeerAdminSubcommand("set-capabilities", "Replace a paired device's capability grants.") {
    private val nodeId by option("--node-id", help = "Device NodeId (64 hex).").required()
    private val capabilities by option(
        "--capabilities",
        help = "Comma-separated capabilities, e.g. chat.read,chat.send,admin.full.",
    ).required()

    override fun method() = "pair.peer.set_capabilities"
    override fun params() =
        PeerParams.setCapabilities(nodeId, capabilities.split(",").map { it.trim() }.filter { it.isNotEmpty() })
}

class PeerRevokeCommand : PeerAdminSubcommand("revoke", "Revoke a paired device immediately.") {
    private val nodeId by option("--node-id", help = "Device NodeId (64 hex).").required()

    override fun method() = "pair.peer.revoke"
    override fun params() = PeerParams.nodeId(nodeId)
}
