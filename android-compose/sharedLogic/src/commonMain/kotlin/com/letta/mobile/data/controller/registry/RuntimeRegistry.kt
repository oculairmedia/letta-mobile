package com.letta.mobile.data.controller.registry

import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.ConversationId
import kotlin.time.Instant

/**
 * Persistent registry for runtime records.
 *
 * This registry stores runtime records across App Server restarts. The controller
 * uses this to:
 * 1. Save runtime records when they are created/started
 * 2. Load all records on startup to recover active runtimes
 * 3. Update records with canonical runtime data after runtime_start
 * 4. Remove records when runtimes are explicitly stopped/deleted
 *
 * IMPLEMENTATIONS:
 * - In-memory: For testing and ephemeral sessions (default, implemented in commonMain)
 * - Platform-backed: Room (Android), file-based (Desktop/CLI) — provided via expect/actual
 *
 * THREAD-SAFETY:
 * Implementations must be thread-safe. Callers may access the registry from multiple
 * coroutines concurrently.
 */
interface RuntimeRegistry {
    /**
     * Saves a runtime record to the registry.
     *
     * If a record with the same ID already exists, it is replaced.
     *
     * @param record The runtime record to save
     * @throws RuntimeRegistryException if the save operation fails
     */
    suspend fun save(record: RuntimeRecord)

    /**
     * Loads a runtime record by ID.
     *
     * @param id The runtime record ID
     * @return The record, or null if not found
     * @throws RuntimeRegistryException if the load operation fails
     */
    suspend fun load(id: String): RuntimeRecord?

    /**
     * Lists all runtime records in the registry.
     *
     * @return List of all records (may be empty)
     * @throws RuntimeRegistryException if the list operation fails
     */
    suspend fun list(): List<RuntimeRecord>

    /**
     * Removes a runtime record by ID.
     *
     * If the record doesn't exist, this is a no-op (does not throw).
     *
     * @param id The runtime record ID to remove
     * @throws RuntimeRegistryException if the remove operation fails
     */
    suspend fun remove(id: String)

    /**
     * Updates a record to mark it as started with the given canonical runtime.
     *
     * This is a convenience method that loads the record, updates the fields,
     * and saves it back. If the record doesn't exist, this throws.
     *
     * @param id The runtime record ID
     * @param canonicalRuntime The canonical runtime from App Server runtime_start
     * @param lastStartedAt Timestamp of the start
     * @throws RuntimeRegistryException if the record doesn't exist or update fails
     */
    suspend fun markStarted(
        id: String,
        canonicalRuntime: CanonicalRuntime,
        lastStartedAt: Instant,
    )

    /**
     * Finds a runtime record by agent ID and conversation ID.
     *
     * @param agentId The agent ID
     * @param conversationId The conversation ID
     * @return The matching record, or null if not found
     * @throws RuntimeRegistryException if the find operation fails
     */
    suspend fun findByAgentAndConversation(
        agentId: AgentId,
        conversationId: ConversationId,
    ): RuntimeRecord?
}

/**
 * Exception thrown when registry operations fail.
 */
class RuntimeRegistryException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
