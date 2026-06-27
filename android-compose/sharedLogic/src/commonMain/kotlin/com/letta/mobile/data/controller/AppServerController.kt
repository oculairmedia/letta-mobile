package com.letta.mobile.data.controller

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single control client for one App Server process.
 *
 * This controller orchestrates the lifecycle of App Server runtimes by:
 * - Managing the connection to one App Server process via the transport seam
 * - Starting runtimes and storing the canonical runtime metadata
 * - Delegating turn execution to AppServerTurnEngine
 * - Providing sync/abort operations
 * - Exposing connection lifecycle state
 *
 * This is the orchestration layer ON TOP of the protocol client (#685): it holds
 * canonical runtimes, manages connection state, and presents the lifecycle API
 * that the rest of the app (fanout, registry) will build on.
 *
 * SCOPE:
 * - One controller per App Server process (1:1 control channel ownership)
 * - commonMain only, KMP-safe (no platform-specific process spawning)
 * - Takes an already-open transport/client for full testability
 * - Multi-client remote access requires external fanout/arbitration
 */
interface AppServerController {
    /**
     * Current connection state.
     */
    val state: Flow<AppServerControllerState>

    /**
     * Starts a runtime for the given agent and conversation.
     *
     * Issues `runtime_start` and stores/returns the canonical runtime exactly as
     * the App Server returns it. If a runtime is already started for the same
     * agent+conversation, returns the cached runtime without re-starting.
     *
     * @param agentId The agent ID (required)
     * @param conversationId The conversation ID (required)
     * @param cwd Optional working directory for the runtime
     * @param mode Optional permission mode (defaults to Standard)
     * @param recoverApprovals Whether to recover pending approvals (defaults to true)
     * @param forceDeviceStatus Whether to force device status update (defaults to true)
     * @return The canonical runtime scope returned by the App Server
     * @throws AppServerControllerException if runtime_start fails
     */
    suspend fun startRuntime(
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String? = null,
        mode: AppServerPermissionMode? = null,
        recoverApprovals: Boolean = true,
        forceDeviceStatus: Boolean = true,
    ): CanonicalRuntime

    /**
     * Executes a turn on the given runtime via AppServerTurnEngine.
     *
     * Delegates to the existing turn engine, which handles:
     * - Ensuring the runtime is started (via startRuntime if needed)
     * - Sending the input command
     * - Collecting events until stop_reason
     *
     * @param command The turn command containing agent/conversation/input
     * @return Flow of runtime events for this turn
     */
    fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft>

    /**
     * Synchronizes the runtime state with the App Server.
     *
     * Issues a `sync` command and waits for the response.
     *
     * @param runtime The runtime scope to sync
     * @param recoverApprovals Whether to recover pending approvals
     * @param forceDeviceStatus Whether to force device status update
     * @return The sync response from the App Server
     * @throws AppServerControllerException if sync fails
     */
    suspend fun sync(
        runtime: AppServerRuntimeScope,
        recoverApprovals: Boolean = false,
        forceDeviceStatus: Boolean = false,
    ): AppServerInboundFrame.SyncResponse

    /**
     * Aborts the current message in the given runtime.
     *
     * Issues an `abort_message` command and waits for the response.
     *
     * @param runtime The runtime scope to abort
     * @param runId Optional run ID to abort (if null, aborts the current active run)
     * @return The abort response from the App Server
     * @throws AppServerControllerException if abort fails
     */
    suspend fun abort(
        runtime: AppServerRuntimeScope,
        runId: String? = null,
    ): AppServerInboundFrame.AbortMessageResponse
}

/**
 * Connection state for the App Server controller.
 */
sealed interface AppServerControllerState {
    /**
     * Controller is connected and ready to accept commands.
     */
    data object Connected : AppServerControllerState

    /**
     * Controller is disconnected. Commands will fail.
     */
    data class Disconnected(val reason: String? = null) : AppServerControllerState

    /**
     * Controller encountered an error.
     */
    data class Error(val message: String, val cause: Throwable? = null) : AppServerControllerState
}

/**
 * Canonical runtime metadata returned by App Server runtime_start.
 *
 * Stores the exact runtime scope plus any additional metadata the App Server
 * returns (agent object, conversation object, created flags).
 */
data class CanonicalRuntime(
    /**
     * The runtime scope (agent_id, conversation_id, acting_user_id).
     */
    val scope: AppServerRuntimeScope,

    /**
     * The full agent object returned by runtime_start (if any).
     */
    val agent: kotlinx.serialization.json.JsonObject? = null,

    /**
     * The full conversation object returned by runtime_start (if any).
     */
    val conversation: kotlinx.serialization.json.JsonObject? = null,

    /**
     * Flags indicating which entities were created vs. already existed.
     */
    val created: com.letta.mobile.data.transport.appserver.AppServerCreatedRuntimeEntities? = null,
)

/**
 * Exception thrown when App Server controller operations fail.
 */
class AppServerControllerException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
