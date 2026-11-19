package com.letta.mobile.data.controller.node.iroh.pins

import com.letta.mobile.data.controller.node.iroh.AtomicJsonFile
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.pins.shared.AgentPinsSnapshot
import com.letta.mobile.data.pins.shared.AgentPinsWire
import com.letta.mobile.data.serialization.LettaJson
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * Where the list is kept. The stored state is exactly the wire's [AgentPinsSnapshot] (ordered agent
 * ids plus revision), so a host state export can carry it as one section unchanged.
 */
internal interface AgentPinsPersistence {
    /** The stored list; empty at revision 0 when nothing was ever stored. */
    fun read(): AgentPinsSnapshot

    /** Replaces the stored list. */
    fun write(state: AgentPinsSnapshot)
}

/** The list in memory: tests, and hosts without a state directory. */
internal class InMemoryAgentPinsPersistence(private var state: AgentPinsSnapshot = AgentPinsSnapshot()) : AgentPinsPersistence {
    override fun read(): AgentPinsSnapshot = state

    override fun write(state: AgentPinsSnapshot) {
        this.state = state
    }
}

/** The list in one JSON file, replaced atomically. A missing file is an empty list at revision 0. */
internal class FileAgentPinsPersistence(private val path: Path) : AgentPinsPersistence {
    override fun read(): AgentPinsSnapshot {
        if (!Files.exists(path)) return AgentPinsSnapshot()
        return LettaJson.persisted.decodeFromString<AgentPinsSnapshot>(Files.readString(path))
    }

    override fun write(state: AgentPinsSnapshot) = AtomicJsonFile.write(path, LettaJson.persisted.encodeToString(state))
}

/** A refusal with one of the contract's codes. */
internal class AgentPinsRefusal(val code: String) : Exception(code)

/** What a write did: the list now, and whether it changed (and so must be pushed). */
internal class AgentPinsWrite(val snapshot: AgentPinsSnapshot, val changed: Boolean)

/** The host's one list of pinned agents. Writes are serialized, so revisions are issued exactly once. */
internal class AgentPinsStore(private val persistence: AgentPinsPersistence) {
    private val lock = Mutex()

    /** The list now. */
    suspend fun snapshot(): AgentPinsSnapshot = lock.withLock { persistence.read() }

    /** Replaces the list when [expectedRevision] is current; else refuses with a revision conflict. */
    suspend fun set(agentIds: List<AgentId>, expectedRevision: Long): AgentPinsWrite = lock.withLock {
        val stored = persistence.read()
        if (stored.revision != expectedRevision) throw AgentPinsRefusal(AgentPinsWire.REVISION_CONFLICT)
        if (stored.agentIds == agentIds) return@withLock AgentPinsWrite(stored, changed = false)
        val next = AgentPinsSnapshot(stored.revision + 1, agentIds)
        persistence.write(next)
        AgentPinsWrite(next, changed = true)
    }
}
