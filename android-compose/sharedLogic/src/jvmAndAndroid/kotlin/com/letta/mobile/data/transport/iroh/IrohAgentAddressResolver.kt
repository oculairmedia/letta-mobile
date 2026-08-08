package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.AgentIdNamespace
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
 *
 * letta-mobile-u6hwa: the keyspace is CANONICAL — every key is normalized to its
 * bare form ([AgentIdNamespace.normalizeToBareId]) on the way in AND on the way
 * out, so `agent-X` and `letta_agent-X` always denote one entry. This is a secret
 * of the address book, not knowledge each caller must carry: callers are many
 * (CLI send, wrapper publish, seed script) and each one that forgot to normalize
 * was a chance to write a duplicate row. The live incident this fixes: the wrapper
 * published under the bare key while an older bind had written a `letta_`-prefixed
 * key, so the prefixed row survived pointing at a DEAD port — a prefixed lookup
 * resolved to a stale address and blackholed rather than cleanly reporting
 * `not_registered`. Canonicalizing at the storage boundary makes that state
 * unrepresentable: one agent, one row, updated in place.
 */
class FileIrohAgentAddressStore(private val file: File) : IrohAgentAddressStore {

    @Synchronized
    override fun register(address: IrohAgentAddress) {
        val entries = readAll().toMutableMap()
        entries[AgentIdNamespace.normalizeToBareId(address.agentId)] = address.toWire()
        writeAll(entries)
    }

    @Synchronized
    override fun resolve(agentId: String): AddressResolution {
        // Resolve under the canonical key, but report the id the CALLER used —
        // an Unavailable that echoes a different id than was asked for would send
        // an operator hunting for the wrong agent.
        val wire = readAll()[AgentIdNamespace.normalizeToBareId(agentId)]
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
        if (entries.remove(AgentIdNamespace.normalizeToBareId(agentId)) != null) writeAll(entries)
    }

    /**
     * Read the file into a canonically-keyed map.
     *
     * Legacy files may hold BOTH a bare and a `letta_`-prefixed row for one agent
     * (see the class comment). Normalizing keys here collapses them, which means a
     * subsequent [register]/[unregister] rewrites the file with the duplicate
     * EVICTED rather than merely superseded — the stale row cannot linger and be
     * dialed. Bare wins on collision: it is the form the live publish path writes,
     * so it is the row backed by the current bind. Order matters (later entries
     * overwrite earlier ones), so prefixed rows are applied first and a bare row,
     * if present, lands on top.
     */
    private fun readAll(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val parsed = file.readLines()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
        val canonical = LinkedHashMap<String, String>(parsed.size)
        parsed.sortedBy { it.first == AgentIdNamespace.normalizeToBareId(it.first) }
            .forEach { (key, value) -> canonical[AgentIdNamespace.normalizeToBareId(key)] = value }
        return canonical
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
