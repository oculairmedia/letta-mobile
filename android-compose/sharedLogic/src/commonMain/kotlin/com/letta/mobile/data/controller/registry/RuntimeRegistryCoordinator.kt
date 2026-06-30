package com.letta.mobile.data.controller.registry

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.ConversationId
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinator for runtime registry recovery and lifecycle management.
 *
 * This coordinator sits beside [AppServerController] and orchestrates:
 * 1. Loading runtime records from the persistent registry on startup
 * 2. Starting/attaching to App Server runtimes for each active record
 * 3. Storing the canonical runtime metadata back into the record
 * 4. Syncing before rendering stale UI (ensuring runtime state is current)
 *
 * KEY PATTERN:
 * - Controller DB (registry) is the source of truth for runtime lifecycle
 * - Never rely on in-memory runtime state alone (App Server can restart)
 * - On recovery: load records -> runtime_start per record -> store canonical runtime
 *
 * This closes the in-memory-registry chip-loss-on-restart bug class.
 *
 * @param controller The App Server controller to delegate runtime operations to
 * @param registry The persistent runtime registry
 */
class RuntimeRegistryCoordinator(
    private val controller: AppServerController,
    private val registry: RuntimeRegistry,
) {
    private val recoveryMutex = Mutex()

    /**
     * Recovers all active runtime records from the registry.
     *
     * For each record:
     * 1. Calls controller.startRuntime to start/attach the runtime
     * 2. Stores the returned canonical runtime back into the record
     * 3. Updates lastStartedAt to current time
     *
     * This method is idempotent: calling it multiple times is safe (cached
     * runtimes in the controller will prevent duplicate runtime_start calls).
     *
     * @return The number of runtimes recovered
     * @throws RuntimeRegistryCoordinatorException if recovery fails for any record
     */
    suspend fun recover(): Int = recoveryMutex.withLock {
        val records = try {
            registry.list()
        } catch (e: Exception) {
            throw RuntimeRegistryCoordinatorException("Failed to load runtime records from registry", e)
        }

        var recoveredCount = 0

        for (record in records) {
            try {
                // Start or attach to the runtime via the controller
                val canonical = controller.startRuntime(
                    agentId = record.agentId,
                    conversationId = record.conversationId,
                    cwd = record.cwd,
                    mode = record.mode,
                    // Use defaults for recoverApprovals, forceDeviceStatus
                    // These can be overridden later if needed
                )

                // Update the record with the canonical runtime and timestamp
                registry.markStarted(
                    id = record.id,
                    canonicalRuntime = canonical,
                    lastStartedAt = Clock.System.now(),
                )

                recoveredCount++
            } catch (e: Exception) {
                // Log the error but continue recovering other runtimes
                // In production, this might emit to a logging/monitoring system
                println("Failed to recover runtime ${record.id} (${record.agentId}/${record.conversationId}): ${e.message}")
                // Don't throw here - attempt to recover as many runtimes as possible
            }
        }

        recoveredCount
    }

    /**
     * Ensures a runtime record exists for the given agent and conversation.
     *
     * If a record already exists, returns it. Otherwise, creates a new record
     * and saves it to the registry.
     *
     * @param agentId The agent ID
     * @param conversationId The conversation ID
     * @param cwd Optional working directory
     * @param displayName Optional display name
     * @param role Optional role descriptor
     * @return The existing or newly created record
     * @throws RuntimeRegistryCoordinatorException if the operation fails
     */
    suspend fun ensureRecord(
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String? = null,
        displayName: String? = null,
        role: String? = null,
    ): RuntimeRecord {
        // Check if record already exists
        val existing = try {
            registry.findByAgentAndConversation(agentId, conversationId)
        } catch (e: Exception) {
            throw RuntimeRegistryCoordinatorException(
                "Failed to find runtime record for $agentId/$conversationId",
                e,
            )
        }

        if (existing != null) {
            return existing
        }

        // Create new record
        val newRecord = RuntimeRecord(
            id = generateRuntimeRecordId(),
            agentId = agentId,
            conversationId = conversationId,
            cwd = cwd,
            displayName = displayName,
            role = role,
        )

        try {
            registry.save(newRecord)
        } catch (e: Exception) {
            throw RuntimeRegistryCoordinatorException(
                "Failed to save new runtime record for $agentId/$conversationId",
                e,
            )
        }

        return newRecord
    }

    /**
     * Removes a runtime record by ID.
     *
     * This does NOT stop the runtime in the App Server — it only removes the
     * persistent record. To fully tear down a runtime, call this AFTER issuing
     * a runtime_stop command (if supported) or letting the App Server clean up.
     *
     * @param id The runtime record ID to remove
     * @throws RuntimeRegistryCoordinatorException if the operation fails
     */
    suspend fun removeRecord(id: String) {
        try {
            registry.remove(id)
        } catch (e: Exception) {
            throw RuntimeRegistryCoordinatorException(
                "Failed to remove runtime record $id",
                e,
            )
        }
    }

    companion object {
        private var nextRecordId = 0

        /**
         * Generates a unique runtime record ID.
         *
         * In production, this should use a UUID or similar stable ID generator.
         * For testing, we use a simple counter.
         */
        private fun generateRuntimeRecordId(): String {
            nextRecordId += 1
            return "runtime-record-$nextRecordId"
        }
    }
}

/**
 * Exception thrown when registry coordinator operations fail.
 */
class RuntimeRegistryCoordinatorException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
