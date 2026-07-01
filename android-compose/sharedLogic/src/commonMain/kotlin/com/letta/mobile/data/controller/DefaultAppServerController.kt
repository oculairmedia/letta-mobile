package com.letta.mobile.data.controller

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default implementation of [AppServerController].
 *
 * Manages one App Server process connection via the provided [client].
 * Caches started runtimes by (agent_id, conversation_id) key and delegates
 * turn execution to [AppServerTurnEngine].
 *
 * Thread-safe: all state mutations are protected by [runtimeMutex].
 */
class DefaultAppServerController(
    private val client: AppServerClient,
    private val clientInfo: AppServerRuntimeStartClientInfo = DEFAULT_CLIENT_INFO,
    private val requestIdFactory: () -> String = ::defaultRequestId,
) : AppServerController {
    private val _state = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
    override val state: StateFlow<AppServerControllerState> = _state.asStateFlow()

    /**
     * Cache of started runtimes, keyed by (agentId, conversationId).
     * Thread-safe access via [runtimeMutex].
     */
    private val runtimeCache = mutableMapOf<RuntimeKey, CanonicalRuntime>()
    private val runtimeMutex = Mutex()

    /**
     * Turn engine instance. Created lazily and reused for all turns.
     * The engine itself serializes turns, so we don't need additional locking here.
     */
    private val turnEngine by lazy {
        AppServerTurnEngine(
            client = client,
            clientInfo = clientInfo,
            requestIdFactory = requestIdFactory,
        )
    }

    override suspend fun startRuntime(
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String?,
        mode: AppServerPermissionMode?,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): CanonicalRuntime = runtimeMutex.withLock {
        val key = RuntimeKey(agentId.value, conversationId.value)

        // Return cached runtime if already started for this agent+conversation
        runtimeCache[key]?.let { return it }

        // Issue runtime_start
        val response = try {
            client.runtimeStart(
                AppServerCommand.RuntimeStart(
                    requestId = requestIdFactory(),
                    agentId = agentId.value,
                    conversationId = conversationId.value,
                    cwd = cwd,
                    mode = mode,
                    clientInfo = clientInfo,
                    recoverApprovals = recoverApprovals,
                    forceDeviceStatus = forceDeviceStatus,
                ),
            )
        } catch (e: Exception) {
            _state.value = AppServerControllerState.Error(
                message = "Failed to start runtime: ${e.message}",
                cause = e,
            )
            throw AppServerControllerException("Failed to start runtime for $key", e)
        }

        if (!response.success) {
            val errorMsg = response.error ?: "Unknown error"
            _state.value = AppServerControllerState.Error(
                message = "Runtime start failed: $errorMsg",
            )
            throw AppServerControllerException("Runtime start failed for $key: $errorMsg")
        }

        val scope = response.runtime
            ?: throw AppServerControllerException("Runtime start succeeded but returned no runtime scope")

        // Store canonical runtime
        val canonical = CanonicalRuntime(
            scope = scope,
            agent = response.agent,
            conversation = response.conversation,
            created = response.created,
        )

        runtimeCache[key] = canonical
        canonical
    }

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> =
        turnEngine.runTurn(command)

    override suspend fun sync(
        runtime: AppServerRuntimeScope,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): AppServerInboundFrame.SyncResponse {
        return try {
            client.sync(
                AppServerCommand.Sync(
                    runtime = runtime,
                    requestId = requestIdFactory(),
                    recoverApprovals = recoverApprovals,
                    forceDeviceStatus = forceDeviceStatus,
                ),
            )
        } catch (e: Exception) {
            throw AppServerControllerException("Failed to sync runtime ${runtime.agentId}/${runtime.conversationId}", e)
        }
    }

    override suspend fun abort(
        runtime: AppServerRuntimeScope,
        runId: String?,
    ): AppServerInboundFrame.AbortMessageResponse {
        return try {
            client.abort(
                AppServerCommand.AbortMessage(
                    runtime = runtime,
                    requestId = requestIdFactory(),
                    runId = runId,
                ),
            )
        } catch (e: Exception) {
            throw AppServerControllerException("Failed to abort runtime ${runtime.agentId}/${runtime.conversationId}", e)
        }
    }

    /**
     * Internal key for runtime cache.
     */
    private data class RuntimeKey(val agentId: String, val conversationId: String)

    companion object {
        private val DEFAULT_CLIENT_INFO = AppServerRuntimeStartClientInfo(
            name = "letta-mobile-controller",
            title = "Letta Mobile Controller",
            version = "0.2.0",
        )

        private var nextRequestId = 0

        private fun defaultRequestId(): String {
            nextRequestId += 1
            return "controller-req-$nextRequestId"
        }
    }
}
