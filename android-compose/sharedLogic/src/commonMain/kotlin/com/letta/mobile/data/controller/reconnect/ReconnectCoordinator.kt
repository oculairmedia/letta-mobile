package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.registry.RuntimeRegistry
import com.letta.mobile.data.controller.registry.RuntimeRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinator for App Server reconnect/replay aligned to sync.
 *
 * This coordinator drives the documented reconnect flow on top of the
 * AppServerController + RuntimeRegistry:
 *
 * 1. On a connection drop signal (from the controller's connection-state Flow),
 *    execute reconnect: re-establish the controller connection; for each active
 *    RuntimeRecord, runtime_start (if App Server restarted) and sync(recoverApprovals=true,
 *    forceDeviceStatus=true); rebuild in-memory runtime state from the replayed events
 *    + the durable registry records.
 *
 * 2. Call the ExternalToolRegistrar hook after each runtime_start on reconnect
 *    (since external tools are startup-bound).
 *
 * 3. Align turn-settlement: expose/consume update_loop_status / update_queue so
 *    a reconnect rebuilds the correct in-flight/queued turn state.
 *
 * This is a thin coordinator that composes the controller — it does NOT rewrite
 * the controller.
 *
 * @param controller The App Server controller to delegate runtime operations to
 * @param registry The persistent runtime registry
 * @param externalToolRegistrar Hook for re-registering external tools (defaults to no-op)
 * @param connectionState The controller's connection state Flow
 */
class ReconnectCoordinator(
    private val controller: AppServerController,
    private val registry: RuntimeRegistry,
    private val externalToolRegistrar: ExternalToolRegistrar = NoOpExternalToolRegistrar(),
    private val connectionState: Flow<AppServerControllerState> = controller.state,
) {
    private val reconnectMutex = Mutex()
    private var lastObservedState: AppServerControllerState? = (connectionState as? StateFlow<AppServerControllerState>)?.value

    /**
     * Executes the reconnect/replay flow.
     *
     * This method:
     * 1. Loads all active runtime records from the registry
     * 2. For each record, calls controller.startRuntime (which may issue runtime_start
     *    if the App Server restarted)
     * 3. Calls externalToolRegistrar.reRegisterAll for each runtime (to restore
     *    external tools after App Server restart)
     * 4. Calls controller.sync with recoverApprovals=true and forceDeviceStatus=true
     *    to replay events and rebuild state
     *
     * This method is idempotent: calling it multiple times is safe (cached
     * runtimes in the controller will prevent duplicate runtime_start calls).
     *
     * @return ReconnectResult with the number of runtimes reconnected and any errors
     */
    suspend fun reconnect(): ReconnectResult = reconnectMutex.withLock {
        val errors = mutableListOf<ReconnectError>()
        var successCount = 0

        // Load all active runtime records
        val records = try {
            registry.list()
        } catch (e: Exception) {
            return ReconnectResult(
                reconnectedCount = 0,
                errors = listOf(
                    ReconnectError(
                        runtimeRecordId = null,
                        phase = ReconnectPhase.LOAD_RECORDS,
                        message = "Failed to load runtime records from registry",
                        cause = e,
                    ),
                ),
            )
        }

        // Reconnect each runtime
        for (record in records) {
            try {
                reconnectRuntime(record)
                successCount++
            } catch (e: Exception) {
                errors += ReconnectError(
                    runtimeRecordId = record.id,
                    phase = ReconnectPhase.RECONNECT_RUNTIME,
                    message = "Failed to reconnect runtime ${record.id} (${record.agentId}/${record.conversationId})",
                    cause = e,
                )
            }
        }

        ReconnectResult(
            reconnectedCount = successCount,
            errors = errors,
        )
    }

    /**
     * Reconnects a single runtime.
     *
     * This method:
     * 1. Calls controller.startRuntime (which may issue runtime_start if App Server restarted)
     * 2. Calls externalToolRegistrar.reRegisterAll (to restore external tools)
     * 3. Calls controller.sync with recoverApprovals=true and forceDeviceStatus=true
     *
     * @param record The runtime record to reconnect
     */
    private suspend fun reconnectRuntime(record: RuntimeRecord) {
        // Step 1: Start runtime (or attach to existing)
        val canonical = controller.startRuntime(
            agentId = record.agentId,
            conversationId = record.conversationId,
            cwd = record.cwd,
            mode = record.mode,
            recoverApprovals = true,
            forceDeviceStatus = true,
        )

        // Step 2: Re-register external tools
        // External tools are startup-bound on runtime_start, so we must
        // re-register them after each runtime_start on reconnect
        externalToolRegistrar.reRegisterAll(canonical.scope)

        // Step 3: Sync with recover_approvals=true and force_device_status=true
        // This replays events and rebuilds in-memory runtime state
        controller.sync(
            runtime = canonical.scope,
            recoverApprovals = true,
            forceDeviceStatus = true,
        )
    }

    /**
     * Waits for the next connection drop and then executes reconnect.
     *
     * This is a convenience method for automatically reconnecting when the
     * controller signals a disconnection.
     *
     * @return ReconnectResult from the reconnect operation
     */
    suspend fun waitForDisconnectAndReconnect(): ReconnectResult {
        val state = connectionState.first { state ->
            lastObservedState = state
            state is AppServerControllerState.Disconnected ||
                state is AppServerControllerState.Error
        }
        lastObservedState = state

        return reconnect()
    }

    /**
     * Checks if reconnect is needed based on the current connection state.
     *
     * @return true if the controller is disconnected or in error state
     */
    fun isReconnectNeeded(): Boolean {
        val state = (connectionState as? StateFlow<AppServerControllerState>)?.value ?: lastObservedState
        return state is AppServerControllerState.Disconnected ||
            state is AppServerControllerState.Error
    }
}

/**
 * Result of a reconnect operation.
 *
 * @property reconnectedCount Number of runtimes successfully reconnected
 * @property errors List of errors encountered during reconnect (empty if all succeeded)
 */
data class ReconnectResult(
    val reconnectedCount: Int,
    val errors: List<ReconnectError>,
) {
    /**
     * Whether the reconnect was fully successful (all runtimes reconnected, no errors).
     */
    val isFullySuccessful: Boolean get() = errors.isEmpty()

    /**
     * Whether the reconnect was partially successful (some runtimes reconnected, some errors).
     */
    val isPartiallySuccessful: Boolean get() = reconnectedCount > 0 && errors.isNotEmpty()

    /**
     * Whether the reconnect completely failed (no runtimes reconnected).
     */
    val isFailed: Boolean get() = reconnectedCount == 0 && errors.isNotEmpty()
}

/**
 * Error encountered during reconnect.
 *
 * @property runtimeRecordId The runtime record ID that failed (null if error was during load)
 * @property phase The phase of reconnect where the error occurred
 * @property message Error message
 * @property cause The underlying exception (if any)
 */
data class ReconnectError(
    val runtimeRecordId: String?,
    val phase: ReconnectPhase,
    val message: String,
    val cause: Throwable?,
)

/**
 * Phase of the reconnect process.
 */
enum class ReconnectPhase {
    /**
     * Loading runtime records from the registry.
     */
    LOAD_RECORDS,

    /**
     * Reconnecting a specific runtime.
     */
    RECONNECT_RUNTIME,
}

/**
 * Exception thrown when reconnect operations fail.
 */
class ReconnectCoordinatorException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
