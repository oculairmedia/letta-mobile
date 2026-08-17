package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.registry.RuntimeRegistry
import com.letta.mobile.data.controller.registry.RuntimeRecord
import com.letta.mobile.util.Telemetry
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    // isReconnectNeeded() reads connectionState.value, so a StateFlow is
    // required. DefaultAppServerController.state is a StateFlow, so the default
    // works for the production controller. For controllers whose state is a
    // plain Flow (e.g. test doubles), pass an explicit StateFlow instead of
    // relying on this default — the guarded cast fails loudly with a clear
    // message rather than an opaque ClassCastException at a later .value read.
    private val connectionState: StateFlow<AppServerControllerState> =
        controller.state as? StateFlow<AppServerControllerState>
            ?: error(
                "ReconnectCoordinator requires a StateFlow connectionState; " +
                    "${controller::class.simpleName}.state is not a StateFlow. " +
                    "Pass connectionState explicitly.",
            ),
    private val clock: Clock = Clock.System,
) {
    companion object {
        val DEFAULT_RESUME_SYNC_TIMEOUT: Duration = 5.seconds
        val DEFAULT_RESUME_COOLDOWN: Duration = 5.seconds
    }

    private val reconnectMutex = Mutex()
    private val resumeSyncMutex = Mutex()
    private var lastResumeSyncAt: Instant? = null

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
        lastResumeSyncAt = clock.now()
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
     * Executes a lightweight resume sync across all active runtimes when the app returns
     * to the foreground.
     *
     * Planned for wiring with bead `letta-mobile-2u1s6.2` (the desktop local node /
     * controller-owning host with an application lifecycle). Note: has no production
     * caller on Android — the mobile client has no [AppServerController] and routes
     * lifecycle resume through [TimelineRepository.reconcileRecentMessages].
     *
     * Invariants:
     * - Debounced by [cooldown] (default 5s) against rapid app-switching.
     * - Skips if [isReconnectNeeded] is true or if a full reconnect is currently in flight.
     * - Bounded by [timeoutPerRuntime] per active runtime concurrently without holding [reconnectMutex].
     * - Emits outcome telemetry.
     */
    suspend fun onAppResumed(
        timeoutPerRuntime: Duration = DEFAULT_RESUME_SYNC_TIMEOUT,
        cooldown: Duration = DEFAULT_RESUME_COOLDOWN,
    ): ResumeSyncResult {
        if (isReconnectNeeded()) {
            Telemetry.event(
                "AppServer", "resume.sync.skipped",
                "reason" to "reconnect_needed",
            )
            return ResumeSyncResult.Skipped(reason = "reconnect_needed")
        }

        if (reconnectMutex.isLocked) {
            Telemetry.event(
                "AppServer", "resume.sync.skipped",
                "reason" to "reconnect_in_flight",
            )
            return ResumeSyncResult.Skipped(reason = "reconnect_in_flight")
        }

        if (!resumeSyncMutex.tryLock()) {
            Telemetry.event(
                "AppServer", "resume.sync.skipped",
                "reason" to "resume_sync_in_flight",
            )
            return ResumeSyncResult.Skipped(reason = "resume_sync_in_flight")
        }

        try {
            if (reconnectMutex.isLocked || isReconnectNeeded()) {
                Telemetry.event(
                    "AppServer", "resume.sync.skipped",
                    "reason" to "reconnect_in_flight",
                )
                return ResumeSyncResult.Skipped(reason = "reconnect_in_flight")
            }

            val now = clock.now()
            val last = lastResumeSyncAt
            if (last != null && (now - last) < cooldown) {
                Telemetry.event(
                    "AppServer", "resume.sync.skipped",
                    "reason" to "cooldown_active",
                    "elapsedMs" to (now - last).inWholeMilliseconds,
                )
                return ResumeSyncResult.Skipped(reason = "cooldown_active")
            }
            lastResumeSyncAt = now

            val records = try {
                registry.list()
            } catch (e: Exception) {
                Telemetry.event(
                    "AppServer", "resume.sync.failed",
                    "reason" to "load_records_failed",
                    "error" to (e.message ?: "unknown"),
                    level = Telemetry.Level.WARN,
                )
                return ResumeSyncResult.Failed(
                    errors = listOf(
                        ReconnectError(
                            runtimeRecordId = null,
                            phase = ReconnectPhase.LOAD_RECORDS,
                            message = "Failed to load runtime records from registry on resume",
                            cause = e,
                        ),
                    ),
                )
            }

            val eligibleRecords = records.filter { it.canonicalRuntime?.scope != null }
            if (eligibleRecords.isEmpty()) {
                Telemetry.event(
                    "AppServer", "resume.sync.success",
                    "syncedCount" to 0,
                )
                return ResumeSyncResult.Success(syncedCount = 0)
            }

            val results: List<Result<String>> = coroutineScope {
                eligibleRecords.map { record ->
                    async {
                        val recordId = record.id
                        val canonicalScope = record.canonicalRuntime?.scope
                            ?: return@async Result.success(recordId)

                        try {
                            val timedOut = withTimeoutOrNull(timeoutPerRuntime) {
                                controller.sync(
                                    runtime = canonicalScope,
                                    recoverApprovals = false,
                                    forceDeviceStatus = true,
                                )
                            }
                            if (timedOut == null) {
                                Result.failure(
                                    ResumeSyncException(
                                        ReconnectError(
                                            runtimeRecordId = recordId,
                                            phase = ReconnectPhase.RECONNECT_RUNTIME,
                                            message = "Sync timed out after ${timeoutPerRuntime.inWholeMilliseconds}ms for runtime $recordId",
                                            cause = null,
                                        ),
                                    ),
                                )
                            } else {
                                Result.success(recordId)
                            }
                        } catch (t: Throwable) {
                            Result.failure(
                                ResumeSyncException(
                                    ReconnectError(
                                        runtimeRecordId = recordId,
                                        phase = ReconnectPhase.RECONNECT_RUNTIME,
                                        message = "Failed to sync runtime $recordId on resume: ${t.message}",
                                        cause = t,
                                    ),
                                ),
                            )
                        }
                    }
                }.awaitAll()
            }

            var successCount = 0
            val errors = mutableListOf<ReconnectError>()
            for (res in results) {
                res.fold(
                    onSuccess = { successCount++ },
                    onFailure = { err ->
                        if (err is ResumeSyncException) {
                            errors += err.error
                        } else {
                            errors += ReconnectError(
                                runtimeRecordId = null,
                                phase = ReconnectPhase.RECONNECT_RUNTIME,
                                message = err.message ?: "Unknown error",
                                cause = err,
                            )
                        }
                    },
                )
            }

            val result = when {
                errors.isEmpty() -> ResumeSyncResult.Success(syncedCount = successCount)
                successCount > 0 -> ResumeSyncResult.Partial(syncedCount = successCount, errors = errors)
                else -> ResumeSyncResult.Failed(errors = errors)
            }

            Telemetry.event(
                "AppServer", "resume.sync",
                "successCount" to successCount,
                "errorCount" to errors.size,
                "status" to result::class.simpleName,
                level = if (errors.isNotEmpty()) Telemetry.Level.WARN else Telemetry.Level.INFO,
            )

            return result
        } finally {
            resumeSyncMutex.unlock()
        }
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
            recoverApprovals = true,
            forceDeviceStatus = true,
        )

        // Step 1b: Persist the new canonical runtime back into the registry.
        // The App Server may have restarted and handed us a fresh runtime scope;
        // without this write the record's canonicalRuntime stays stale until the
        // next markStarted, so subsequent readers would target a dead runtime.
        registry.markStarted(
            id = record.id,
            canonicalRuntime = canonical,
            lastStartedAt = Clock.System.now(),
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
     * Checks if reconnect is needed based on the current connection state.
     *
     * @return true if the controller is disconnected or in error state
     */
    fun isReconnectNeeded(): Boolean {
        val state = connectionState.value
        return state is AppServerControllerState.Disconnected ||
            state is AppServerControllerState.Error
    }
}

/**
 * Result of an app resume sync operation.
 */
sealed interface ResumeSyncResult {
    /**
     * Successfully synced all active runtimes.
     */
    data class Success(val syncedCount: Int) : ResumeSyncResult

    /**
     * Resume sync was skipped due to cooldown or an active/needed reconnect.
     */
    data class Skipped(val reason: String) : ResumeSyncResult

    /**
     * Some runtimes succeeded and some failed or timed out.
     */
    data class Partial(val syncedCount: Int, val errors: List<ReconnectError>) : ResumeSyncResult

    /**
     * All runtime syncs failed or loading records failed.
     */
    data class Failed(val errors: List<ReconnectError>) : ResumeSyncResult
}

private class ResumeSyncException(val error: ReconnectError) : Exception(error.message, error.cause)

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

