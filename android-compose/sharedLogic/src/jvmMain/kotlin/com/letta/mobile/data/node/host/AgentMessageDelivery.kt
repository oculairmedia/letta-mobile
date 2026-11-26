package com.letta.mobile.data.node.host

import com.letta.mobile.data.transport.iroh.AddressResolution
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

/**
 * Sends one agent-to-agent message over Iroh as its sending agent: the path behind both the
 * `meridian agent-message send` command and the in-process `agent_message_send` tool, so the two
 * cannot drift.
 */
object AgentMessageDelivery {
    /** `~/.letta/iroh/identities`, where each agent's Iroh identity key lives. */
    fun defaultIdentityDir(): File = File(System.getProperty("user.home"), ".letta/iroh/identities")

    /** `~/.letta/iroh/agent-addresses.kv`, the host address book. */
    fun defaultAddressStore(): File = File(System.getProperty("user.home"), ".letta/iroh/agent-addresses.kv")

    /**
     * Resolves [message]'s recipient in the address book [addressStore], then dials it from an Iroh
     * endpoint bound with the sender's identity from [identityDir], so the recipient can verify who
     * sent it. A recipient that does not resolve is reported without binding an endpoint. The
     * endpoint is shut down before this returns.
     */
    suspend fun send(message: IrohAgentMessage, identityDir: File, addressStore: File): AgentSendResult {
        val resolver = IrohAgentAddressResolver(HostEndpointAddressStore(addressStore))
        val resolution = resolver.resolve(message.toAgentId)
        if (resolution is AddressResolution.Unavailable) {
            return AgentSendResult.Unaddressable(message.toAgentId, resolution.reason)
        }
        val identity = IrohAgentIdentity.loadOrCreate(message.fromAgentId, identityDir)
        val endpoint = Endpoint.bind(
            EndpointOptions(
                relayMode = RelayMode.defaultMode(),
                secretKey = identity.secretKeyBytes,
                alpns = listOf(IrohAgentMessage.ALPN),
            ),
        )
        try {
            endpoint.online()
            return IrohAgentMessageSender(endpoint, resolver).send(message)
        } finally {
            runCatching { endpoint.shutdown() }
        }
    }
}
