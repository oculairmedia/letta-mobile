package com.letta.mobile.data.controller

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.registry.RuntimeRecord
import com.letta.mobile.data.controller.registry.RuntimeRegistry
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.TurnContextPreflight
import kotlin.time.Clock
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.atomicfu.atomic
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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
    /**
     * Optional durable registry of desired runtimes (lgns8.5). When provided,
     * every successful runtime_start upserts a [RuntimeRecord] so the reconnect
     * flow knows which runtimes to reattach after socket loss or App Server
     * restart. The in-memory [runtimeCache] alone is NOT recovery state — it is
     * cleared wholesale on [onTransportDisconnected].
     */
    private val runtimeRegistry: RuntimeRegistry? = null,
    /**
     * lgns8.17: controller-owned external tools, forwarded to the turn engine so
     * every external_tool_call_request the App Server emits gets a matched
     * response (executed when advertised here, otherwise a synthesized is_error)
     * and tool-call turns never hang. Null = no controller tools.
     */
    private val externalToolRegistry: ExternalToolRegistry? = null,
    /**
     * Optional safety check for backend-owned active context.
     */
    private val turnContextPreflight: TurnContextPreflight = TurnContextPreflight.None,
    private val clock: Clock = Clock.System,
    /**
     * Extra context for the controller-owned router collector. Tests pass
     * [kotlinx.coroutines.Dispatchers.Unconfined] so SharedFlow emissions are
     * observed synchronously (avoids Default-dispatcher races under runTest).
     */
    private val parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AppServerController {
    private val controllerScope = CoroutineScope(SupervisorJob() + parentCoroutineContext)
    private val eventRouter = AppServerRuntimeEventRouter()
    /** lgns8.22.4: bumps on every transport disconnect so leases are generation-scoped. */
    private val connectionGeneration = atomic(0L)

    init {
        eventRouter.attach(controllerScope, client.events)
    }

    private val _state = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
    override val state: StateFlow<AppServerControllerState> = _state.asStateFlow()

    /**
     * Cache of started runtimes, keyed by (agentId, conversationId).
     * Thread-safe access via [runtimeMutex].
     */
    private val runtimeCache = mutableMapOf<RuntimeKey, CanonicalRuntime>()
    private val runtimePermissionModes = mutableMapOf<RuntimeKey, AppServerPermissionMode>()
    private val runtimeMutex = Mutex()

    /**
     * Turn engine instance. Created lazily and reused for all turns.
     * The engine itself serializes turns, so we don't need additional locking here.
     */
    private val turnEngine by lazy {
        AppServerTurnEngine(
            client = client,
            clientInfo = clientInfo,
            permissionModeProvider = { command ->
                runtimePermissionModes[RuntimeKey(command.agentId.value, command.conversationId.value)]
                    ?: AppServerPermissionMode.Standard
            },
            requestIdFactory = requestIdFactory,
            externalToolRegistry = externalToolRegistry,
            turnContextPreflight = turnContextPreflight,
            eventRouter = eventRouter,
            runtimeScopeResolver = { command ->
                runtimeMutex.withLock {
                    runtimeCache[RuntimeKey(command.agentId.value, command.conversationId.value)]?.scope
                }
            },
            connectionGenerationProvider = { connectionGeneration.value },
            onRuntimeInvalidated = {
                runtimeMutex.withLock { runtimeCache.clear() }
            },
            onRuntimeEnsured = { command, response, startedGeneration ->
                refillEnsuredRuntime(command, response, startedGeneration)
            },
        )
    }

    /**
     * Refill the controller cache (and durable registry) after an engine-issued
     * `runtime_start`. Ignores completions that raced a generation bump so a
     * dead-generation scope cannot undo [onTransportDisconnected]'s clear.
     */
    private suspend fun refillEnsuredRuntime(
        command: TurnCommand,
        response: AppServerInboundFrame.RuntimeStartResponse,
        startedGeneration: Long,
    ) {
        val key = RuntimeKey(command.agentId.value, command.conversationId.value)
        val recordId = "${command.agentId.value}:${command.conversationId.value}"
        // Preserve cwd from the durable registry so reconnect doesn't lose the
        // project directory when a mutating preflight restarts the runtime.
        val existingCwd = runtimeRegistry?.load(recordId)?.cwd
        val canonical = runtimeMutex.withLock {
            if (connectionGeneration.value != startedGeneration) return@withLock null
            val scope = response.runtime ?: return@withLock null
            // Engine-started runtimes default to Standard when no mode was stored;
            // record it so a later startRuntime(Standard) reuses the cache.
            if (key !in runtimePermissionModes) {
                runtimePermissionModes[key] = AppServerPermissionMode.Standard
            }
            CanonicalRuntime(
                scope = scope,
                agent = response.agent,
                conversation = response.conversation,
                created = response.created,
            ).also { runtimeCache[key] = it }
        } ?: return
        recordStartedRuntime(
            agentId = command.agentId,
            conversationId = command.conversationId,
            cwd = existingCwd,
            canonical = canonical,
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
        startRuntimeLocked(
            agentId = agentId,
            conversationId = conversationId,
            cwd = cwd,
            mode = mode,
            recoverApprovals = recoverApprovals,
            forceDeviceStatus = forceDeviceStatus,
        )
    }

    private suspend fun startRuntimeLocked(
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String?,
        mode: AppServerPermissionMode?,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): CanonicalRuntime {
        val key = RuntimeKey(agentId.value, conversationId.value)
        val effectiveMode = mode ?: AppServerPermissionMode.Standard
        evictCachedRuntimeIfModeMismatch(key, effectiveMode)?.let { return it }
        runtimePermissionModes[key] = effectiveMode
        val response = startRuntimeRemote(
            key = key,
            agentId = agentId,
            conversationId = conversationId,
            cwd = cwd,
            effectiveMode = effectiveMode,
            recoverApprovals = recoverApprovals,
            forceDeviceStatus = forceDeviceStatus,
        )
        return cacheStartedRuntime(key, agentId, conversationId, cwd, response)
    }

    /**
     * Returns a matching cached runtime, or null when a fresh start is required.
     * Evicts and invalidates the engine when the cached permission mode differs.
     */
    private suspend fun evictCachedRuntimeIfModeMismatch(
        key: RuntimeKey,
        effectiveMode: AppServerPermissionMode,
    ): CanonicalRuntime? {
        val cached = runtimeCache[key] ?: return null
        if (runtimePermissionModes[key] == effectiveMode) return cached
        runtimeCache.remove(key)
        turnEngine.invalidateRuntime(notifyHost = false)
        return null
    }

    private suspend fun startRuntimeRemote(
        key: RuntimeKey,
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String?,
        effectiveMode: AppServerPermissionMode,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): AppServerInboundFrame.RuntimeStartResponse {
        val response = try {
            client.runtimeStart(
                AppServerCommand.RuntimeStart(
                    requestId = requestIdFactory(),
                    agentId = agentId.value,
                    conversationId = conversationId.value,
                    cwd = cwd,
                    mode = effectiveMode,
                    clientInfo = clientInfo,
                    recoverApprovals = recoverApprovals,
                    forceDeviceStatus = forceDeviceStatus,
                ),
            )
        } catch (e: Exception) {
            turnEngine.invalidateRuntime(notifyHost = false)
            _state.value = AppServerControllerState.Error(
                message = "Failed to start runtime: ${e.message}",
                cause = e,
            )
            throw AppServerControllerException("Failed to start runtime for $key", e)
        }
        if (!response.success) {
            turnEngine.invalidateRuntime(notifyHost = false)
            val errorMsg = response.error ?: "Unknown error"
            _state.value = AppServerControllerState.Error(
                message = "Runtime start failed: $errorMsg",
            )
            throw AppServerControllerException("Runtime start failed for $key: $errorMsg")
        }
        return response
    }

    private suspend fun cacheStartedRuntime(
        key: RuntimeKey,
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String?,
        response: AppServerInboundFrame.RuntimeStartResponse,
    ): CanonicalRuntime {
        val scope = response.runtime
            ?: run {
                turnEngine.invalidateRuntime(notifyHost = false)
                throw AppServerControllerException("Runtime start succeeded but returned no runtime scope")
            }
        val canonical = CanonicalRuntime(
            scope = scope,
            agent = response.agent,
            conversation = response.conversation,
            created = response.created,
        )
        runtimeCache[key] = canonical
        recordStartedRuntime(agentId, conversationId, cwd, canonical)
        return canonical
    }

    private suspend fun recordStartedRuntime(
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String?,
        canonical: CanonicalRuntime,
    ) {
        val registry = runtimeRegistry ?: return
        val recordId = "${agentId.value}:${conversationId.value}"
        registry.save(
            RuntimeRecord(
                id = recordId,
                agentId = agentId,
                conversationId = conversationId,
                cwd = cwd,
                lastStartedAt = clock.now(),
                canonicalRuntime = canonical,
            ),
        )
    }

    override suspend fun onTransportDisconnected(reason: String?) {
        // Do not detach the router here: ReconnectingAppServerClient.events is a
        // stable pipe across generations. Detaching would drop recovery
        // runtime_start/sync terminals before markConnected() re-attaches.
        connectionGeneration.incrementAndGet()
        runtimeMutex.withLock {
            // Canonical runtime scopes are generation-local: every cached scope
            // was minted by the generation that just died and must be re-fetched
            // via runtime_start on the next one. Desired permission modes and
            // the durable registry survive — they are intent, not server state.
            runtimeCache.clear()
        }
        turnEngine.invalidateRuntime(notifyHost = false)
        _state.value = AppServerControllerState.Disconnected(reason)
    }

    override fun markConnected() {
        // Collector is retained across disconnect (see onTransportDisconnected);
        // only attach if it died. Re-attaching would cancel the stable pipe and
        // drop zero-replay SharedFlow frames during the gap.
        if (!eventRouter.isAttached()) {
            eventRouter.attach(controllerScope, client.events)
        }
        _state.value = AppServerControllerState.Connected
    }

    override suspend fun stopRuntime(agentId: AgentId) {
        // letta-mobile-eeu5p: drop every cached runtime for this agent so the
        // next startRuntime re-issues runtime_start and reseeds the model from
        // the updated agent record. Keyed by (agentId, conversationId) — evict
        // all conversations for the agent since the model is agent-level.
        runtimeMutex.withLock {
            val evicted = runtimeCache.keys.filter { it.agentId == agentId.value }
            evicted.forEach {
                runtimeCache.remove(it)
                runtimePermissionModes.remove(it)
            }
        }
        // Drop in-flight turn state so the next turn cannot reuse a runtime that
        // was started with the pre-update agent configuration.
        turnEngine.invalidateRuntime(notifyHost = false)
    }

    override suspend fun stopAllRuntimes() {
        runtimeMutex.withLock {
            runtimeCache.clear()
            runtimePermissionModes.clear()
        }
        turnEngine.invalidateRuntime(notifyHost = false)
    }

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> =
        turnEngine.runTurn(command)

    override suspend fun submitApproval(
        agentId: AgentId,
        conversationId: ConversationId?,
        approvalRequestId: String,
        approve: Boolean,
        reason: String?,
        toolCallId: String?,
        updatedInput: kotlinx.serialization.json.JsonObject?,
    ) {
        val runtime = runtimeMutex.withLock {
            val conversationValue = conversationId?.value
            runtimeCache.entries.firstOrNull { (key, _) ->
                key.agentId == agentId.value && (conversationValue == null || key.conversationId == conversationValue)
            }?.value?.scope
        } ?: throw AppServerControllerException("No active runtime found for approval $approvalRequestId")

        // letta-mobile-vilsn: the structured close payload (e.g. an AskUserQuestion
        // answer) is now threaded as a first-class `updated_input` param (decoded
        // upstream in MessageRepositoryApproval), so this terminal no longer
        // re-decodes the `reason` sentinel. When present, close the tool call by
        // returning the answer as `updated_input` rather than a bare allow.
        val answerUpdatedInput = if (approve) updatedInput else null
        // letta-mobile-vilsn: interactive tools (AskUserQuestion) are gated by
        // letta-code's `can_use_tool` control request, whose id (e.g.
        // `perm-call_…`) is NOT the display approval id the app renders from and
        // is NOT reliably derivable from the tool_call_id across LLM providers
        // (`call_…` vs `toolu_…`). Answer against the REAL id the engine captured
        // when it surfaced the approval; fall back to the historical heuristic
        // only if nothing was captured.
        val capturedRequestId = toolCallId?.let(turnEngine::userInputApprovalId)
        val effectiveRequestId = capturedRequestId ?: approvalRequestId

        val decision = AppServerApprovalDecisions.decide(
            approve = approve,
            updatedInput = answerUpdatedInput,
            message = reason,
            defaultApproveMessage = "Approved by mobile client.",
            defaultDenyMessage = "Denied by mobile client.",
        )
        client.input(
            AppServerCommand.Input(
                runtime = runtime,
                payload = AppServerInputPayload.ApprovalResponse(
                    requestId = effectiveRequestId,
                    decision = decision,
                ),
            ),
        )
        if (toolCallId != null && capturedRequestId != null) {
            turnEngine.clearUserInputApprovalId(toolCallId, capturedRequestId)
        }
    }

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

    /** Tear down the inbound router collector and controller scope (tests / shutdown). */
    override fun close() {
        eventRouter.detach()
        controllerScope.cancel()
    }

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
