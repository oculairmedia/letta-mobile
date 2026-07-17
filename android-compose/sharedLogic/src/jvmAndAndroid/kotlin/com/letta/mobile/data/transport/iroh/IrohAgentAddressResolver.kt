package com.letta.mobile.data.transport.iroh

import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import java.io.File

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
 * Swappable behind this interface. Layer-1 default is a single JSON/kv file
 * ([FileIrohAgentAddressStore]) — the smallest correct option: there is no live
 * multi-writer requirement yet (each agent publishes its own entry on bind), so a
 * flat file keyed by agentId is sufficient and trivially inspectable. A Postgres/kv
 * store can replace it later without touching the resolver or callers.
 */
interface IrohAgentAddressStore {
    fun register(address: IrohAgentAddress)
    fun resolve(agentId: String): AddressResolution
    fun unregister(agentId: String)
}

/**
 * File-backed [IrohAgentAddressStore]: one line per agent, "agentId=<wire>".
 * Justification (smallest correct, layer-1): no live multi-writer contention,
 * human-inspectable, zero new deps; swap for Postgres/kv when multi-host lands.
 */
class FileIrohAgentAddressStore(private val file: File) : IrohAgentAddressStore {

    @Synchronized
    override fun register(address: IrohAgentAddress) {
        val entries = readAll().toMutableMap()
        entries[address.agentId] = address.toWire()
        writeAll(entries)
    }

    @Synchronized
    override fun resolve(agentId: String): AddressResolution {
        val wire = readAll()[agentId]
            ?: return AddressResolution.Unavailable(agentId, "not_registered")
        return runCatching { IrohAgentAddress.fromWire(agentId, wire) }
            .fold(
                onSuccess = { AddressResolution.Found(it) },
                onFailure = { AddressResolution.Unavailable(agentId, "corrupt_entry") },
            )
    }

    @Synchronized
    override fun unregister(agentId: String) {
        val entries = readAll().toMutableMap()
        if (entries.remove(agentId) != null) writeAll(entries)
    }

    private fun readAll(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
            .toMap()
    }

    private fun writeAll(entries: Map<String, String>) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        file.writeText(entries.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }
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
