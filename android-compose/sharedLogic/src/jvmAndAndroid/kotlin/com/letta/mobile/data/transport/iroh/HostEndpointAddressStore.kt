package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.model.AgentIdNamespace
import java.io.File

/**
 * letta-mobile-xmpqm: host-level address-book store.
 *
 * Background (BookStack page 703, Phase 1). Every agent on this host shares the
 * wrapper's single [computer.iroh.Endpoint]; per-agent kv rows duplicated the
 * SAME (nodeId, directAddrs) pair under each agentId. At bind that was
 * `register() = readAll() + writeAll()` of the whole file per agent — O(agents²)
 * bytes of file I/O (~2.4 GB for the live 1462 agents), and every rebind rewrote
 * all rows. Any missed update was a stale-address blackhole (the live 49357 bug,
 * multiplied by the row count).
 *
 * This store splits identity from reachability. Reachability is a HOST property
 * (the wrapper's Endpoint is one); identity is a membership property (does this
 * agentId exist in the local backend dir?). The kv file holds EXACTLY ONE line
 * — `host:<hostKey>=<wire>` — regardless of how many agents share the host. A
 * second host (per-host routing) is additive: another `host:<k>=<wire>` line
 * keyed by `hostKey`, with membership resolved separately per host. NOT built
 * today (YAGNI for Phase 1) — but the layout supports it without a rewrite.
 *
 * The [IrohAgentAddressStore] interface is unchanged, so callers (resolver,
 * CLI send command, sender tests) move not at all. The change is behind the
 * interface (Parnas: reachability policy is the store's secret).
 */
class HostEndpointAddressStore(
    private val file: File,
    /**
     * Backend membership oracle. Injected so tests can supply an in-memory fake;
     * production wires the real [LocalBackendAdminStore] over the letta-code
     * on-disk root. `null` => no membership check (back-compat for callers that
     * do not have a backend root handy — the store still answers "is the host
     * bound?", it just answers "yes" for any agentId the caller asks about).
     */
    private val backendStore: LocalBackendAdminStore? = null,
) : IrohAgentAddressStore {

    @Synchronized
    override fun register(address: IrohAgentAddress) {
        // hostKey = first 8 hex chars of the node id. One wrapper = one node id
        // = one hostKey today. The 8-char prefix is just an identifier, NOT a
        // security claim — collisions across truly-distinct node ids are
        // astronomically unlikely in 8 hex chars (2^32 space) and would only
        // cause two hosts to share an entry, which is fine because both rows
        // would carry the same wire shape and the membership check still gates
        // per-agent reachability.
        val hostKey = address.nodeIdHex.take(HOST_KEY_LENGTH)
        val record = "host:$hostKey=${address.toWire()}"
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        file.writeText("$record\n")
    }

    @Synchronized
    override fun resolve(agentId: String): AddressResolution {
        val hostRecord = readHostRecord() ?: return AddressResolution.Unavailable(
            agentId, "unknown_host",
        )
        val parsed = runCatching { IrohAgentAddress.fromWire(agentId, hostRecord.wire) }
            .getOrElse {
                return AddressResolution.Unavailable(agentId, "corrupt_entry")
            }
        // Membership gate. The host is reachable iff the requested agentId
        // names an agent that exists in the local backend. Canonicalize the
        // spelling so `agent-X` and `letta_agent-X` resolve the same way the
        // u6hwa keyspace demands (widens SPELLINGS, never membership — an id
        // that names no agent stays unaddressable in both forms).
        val canonicalAgentId = AgentIdNamespace.normalizeToBareId(agentId)
        if (backendStore != null && !backendStore.agentExists(canonicalAgentId)) {
            return AddressResolution.Unavailable(agentId, "unknown_agent")
        }
        // Echo the agentId the CALLER used back in the address — an operator
        // reading a dial failure must see the spelling they sent, not a
        // canonicalized form they didn't.
        return AddressResolution.Found(parsed.copy(agentId = agentId))
    }

    @Synchronized
    override fun unregister(agentId: String) {
        // Host records are keyed by host, not agentId. There is nothing to
        // unregister for a single agent — unregistering the whole host (on
        // wrapper shutdown) is a different operation and is owned by the
        // caller that holds the file. Keep this a no-op so callers that call
        // unregister on agentId (CLI send command, etc.) don't crash.
    }

    /**
     * Read the kv file and return the single `host:`-prefixed record. Returns
     * null if the file does not exist OR if no host record exists yet (e.g.
     * a legacy file with only per-agent rows has not been migrated by a write).
     *
     * Migrate-on-write is owned by [register]; here we just READ. A legacy
     * per-agent file present at startup means the wrapper has not bound since
     * this store replaced [FileIrohAgentAddressStore] — the next bind's
     * [register] call evicts the legacy rows as a side-effect of writing the
     * single host record.
     */
    private fun readHostRecord(): HostRecord? {
        if (!file.exists()) return null
        return file.readLines()
            .mapNotNull(::parseHostLine)
            .firstOrNull()
    }

    private fun parseHostLine(line: String): HostRecord? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(HOST_PREFIX)) return null
        val eq = trimmed.indexOf('=', HOST_PREFIX.length)
        if (eq <= HOST_PREFIX.length) return null
        val key = trimmed.substring(HOST_PREFIX.length, eq)
        val wire = trimmed.substring(eq + 1)
        if (key.isEmpty() || wire.isEmpty()) return null
        return HostRecord(key, wire)
    }

    private data class HostRecord(val hostKey: String, val wire: String)

    companion object {
        const val HOST_PREFIX = "host:"
        const val HOST_KEY_LENGTH = 8

        /**
         * Convenience factory: build a [HostEndpointAddressStore] bound to a
         * [LocalBackendAdminStore] over [localBackendDir]. Used by the wrapper's
         * a2a wiring so callers don't have to construct both halves by hand.
         *
         * `null` localBackendDir => membership-check disabled (the store still
         * answers host reachability, just not per-agent membership). This is
         * the safe default — the seed-only deployments still want the host
         * record, and an absent backend dir is recoverable (seed it later).
         */
        fun withBackend(file: File, localBackendDir: File?): HostEndpointAddressStore =
            HostEndpointAddressStore(
                file = file,
                backendStore = localBackendDir?.let { LocalBackendAdminStore(it) },
            )
    }
}
