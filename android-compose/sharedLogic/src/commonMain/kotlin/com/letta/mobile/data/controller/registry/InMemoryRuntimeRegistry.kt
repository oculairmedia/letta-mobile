package com.letta.mobile.data.controller.registry

import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.ConversationId
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementation of [RuntimeRegistry].
 *
 * This implementation stores runtime records in a mutable map and is suitable for:
 * - Testing (default test registry)
 * - Ephemeral sessions where persistence is not required
 * - Development/debugging
 *
 * Thread-safe: all operations are protected by [mutex].
 *
 * NOTE: This registry does NOT survive process restart. For production use,
 * platform-backed registries (Room, file-based) should be used via expect/actual.
 */
class InMemoryRuntimeRegistry : RuntimeRegistry {
    private val records = mutableMapOf<String, RuntimeRecord>()
    private val mutex = Mutex()

    override suspend fun save(record: RuntimeRecord): Unit = mutex.withLock {
        records[record.id] = record
    }

    override suspend fun load(id: String): RuntimeRecord? = mutex.withLock {
        records[id]
    }

    override suspend fun list(): List<RuntimeRecord> = mutex.withLock {
        records.values.toList()
    }

    override suspend fun remove(id: String): Unit = mutex.withLock {
        records.remove(id)
    }

    override suspend fun markStarted(
        id: String,
        canonicalRuntime: CanonicalRuntime,
        lastStartedAt: Instant,
    ): Unit = mutex.withLock {
        val existing = records[id]
            ?: throw RuntimeRegistryException("Runtime record not found: $id")

        records[id] = existing.copy(
            canonicalRuntime = canonicalRuntime,
            lastStartedAt = lastStartedAt,
        )
    }

    override suspend fun findByAgentAndConversation(
        agentId: AgentId,
        conversationId: ConversationId,
    ): RuntimeRecord? = mutex.withLock {
        records.values.find {
            it.agentId == agentId && it.conversationId == conversationId
        }
    }
}
