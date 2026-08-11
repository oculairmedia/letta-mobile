package com.letta.mobile.data.transport.iroh

import computer.iroh.EndpointAddr
import computer.iroh.EndpointId

/**
 * letta-mobile-bn008.1: a published Iroh address for an agent — the dialable
 * (node id + relay/direct addrs) coordinates a sender needs to reach it directly.
 *
 * Serialized as the same wire shape IrohAppServerTransportAdapter.parseIrohAddress
 * already understands: "<hexNodeId>@<directAddr>,<directAddr>" (relay resolved by
 * n0 from the node id when directAddrs are empty).
 */
data class IrohAgentAddress(
    val agentId: String,
    val nodeIdHex: String,
    val directAddrs: List<String> = emptyList(),
) {
    /** Wire form: "<hexNodeId>@a,b" (or just "<hexNodeId>" when no direct addrs). */
    fun toWire(): String = if (directAddrs.isEmpty()) nodeIdHex else "$nodeIdHex@${directAddrs.joinToString(",")}"

    /** Build a dialable iroh [EndpointAddr] (caller owns/closes it). */
    fun toEndpointAddr(): EndpointAddr {
        val bytes = nodeIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val id: EndpointId = EndpointId.fromBytes(bytes)
        return EndpointAddr(id, null, directAddrs)
    }

    companion object {
        fun fromWire(agentId: String, wire: String): IrohAgentAddress {
            val at = wire.indexOf('@')
            return if (at < 0) {
                IrohAgentAddress(agentId, wire.trim(), emptyList())
            } else {
                val node = wire.substring(0, at).trim()
                val addrs = wire.substring(at + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                IrohAgentAddress(agentId, node, addrs)
            }
        }
    }
}

/** Result of resolving an agentId to an address — a TYPED result, never a throw. */
sealed interface AddressResolution {
    data class Found(val address: IrohAgentAddress) : AddressResolution
    /** The agent is not registered / offline / unaddressable right now. */
    data class Unavailable(val agentId: String, val reason: String) : AddressResolution
}

/**
 * The address book: agentId -> current published [IrohAgentAddress].
 *
 * Swappable behind this interface. Layer-1 default is the host-level
 * [HostEndpointAddressStore] (letta-mobile-xmpqm, Phase 1) — one record per host,
 * reachability gated by backend membership rather than per-agent duplication.
 *
 * The interface stays unchanged so callers (resolver, CLI send command, sender
 * tests) move not at all. The change is behind the interface (Parnas:
 * reachability policy is the store's secret).
 */
interface IrohAgentAddressStore {
    fun register(address: IrohAgentAddress)
    fun resolve(agentId: String): AddressResolution
    fun unregister(agentId: String)
}

/**
 * Resolves a target agentId to its dialable Iroh address. Delegates storage to a
 * swappable [IrohAgentAddressStore]; NEVER throws — an unknown/offline agent
 * returns [AddressResolution.Unavailable].
 */
class IrohAgentAddressResolver(private val store: IrohAgentAddressStore) {
    fun resolve(agentId: String): AddressResolution {
        if (agentId.isBlank()) return AddressResolution.Unavailable(agentId, "blank_agent_id")
        return runCatching { store.resolve(agentId) }
            .getOrElse { AddressResolution.Unavailable(agentId, "store_error:${it.message ?: it::class.simpleName}") }
    }

    /** Publish this agent's current address on bind. */
    fun publish(address: IrohAgentAddress) = store.register(address)
}
