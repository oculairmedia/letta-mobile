package com.letta.mobile.data.controller

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ToolAdvertisementState
import com.letta.mobile.data.controller.extras.ToolAudience
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.ApprovalDecisionCache
import com.letta.mobile.data.controller.registry.RuntimeRecord
import com.letta.mobile.data.controller.registry.RuntimeRegistry
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerQueueSnapshot
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.CancelledQueuedInput
import com.letta.mobile.data.runtime.TurnRuntimeKey
import com.letta.mobile.data.runtime.reanswerReplayedApproval
import com.letta.mobile.data.runtime.releaseUserInputGateUnlessRejected
import com.letta.mobile.data.runtime.DeviceStateChanger
import com.letta.mobile.data.runtime.RuntimePermissionDefaults
import com.letta.mobile.data.runtime.TurnInboundSource
import com.letta.mobile.data.runtime.ApprovalBinding
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.runtime.approvalIdForSuggestions
import com.letta.mobile.data.runtime.pendingApprovalDetails
import com.letta.mobile.data.runtime.pendingApprovalsByRuntime
import com.letta.mobile.data.runtime.stricter
import kotlin.time.Clock
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRequestIdFactory
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
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
    private val requestIdFactory: () -> String = defaultRequestId,
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
    private val clock: Clock = Clock.System,
    /**
     * Extra context for the controller-owned router collector. Tests pass
     * [kotlinx.coroutines.Dispatchers.Unconfined] so SharedFlow emissions are
     * observed synchronously (avoids Default-dispatcher races under runTest).
     */
    private val parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    /**
     * letta-mobile-h5t1g: the mode used whenever a caller passes no explicit mode
     * and nothing is cached for the runtime key (cold start, reconnect, eviction).
     * Single source of truth for every fallback in this class — an explicit mode
     * (e.g. from a `runtime_start` frame) always wins. letta-mobile-4mps3 will bind
     * the settings toggle here instead of the hardcoded default.
     */
    private val defaultPermissionMode: () -> AppServerPermissionMode = {
        RuntimePermissionDefaults.DEFAULT_MODE
    },
) : AppServerController {
    @Suppress("NoDetachedCoroutineLifecycle") // Controller lifecycle owned; cancelled in close().
    private val controllerScope = CoroutineScope(SupervisorJob() + parentCoroutineContext)
    /** lgns8.22.4: bumps on every transport disconnect so leases are generation-scoped. */
    private val connectionGeneration = atomic(0L)
    /** letta-mobile-qygvv.5: decisions sent, shared by the router and the turn engine. */
    private val approvalDecisionCache = ApprovalDecisionCache()
    private val eventRouter = AppServerRuntimeEventRouter(
        connectionGenerationProvider = { connectionGeneration.value },
        approvalDecisionCache = approvalDecisionCache,
    )

    init {
        eventRouter.bindApprovalReplayResponder(::onApprovalReplay)
        eventRouter.attach(controllerScope, client.events)
        attachUnleasedExternalToolAnswerer()
        attachQueueObserver()
    }

    /**
     * letta-mobile-qygvv.6: the engine subscribes through [eventRouter], so it only sees frames
     * while a turn holds a lease. Queue snapshots and the post-abort `turn_finished` must be seen
     * between turns too.
     */
    private fun attachQueueObserver() {
        controllerScope.launch {
            client.events.collect { received -> turnEngine.observeQueueFrame(received.frame) }
        }
    }

    override val queueSnapshots: StateFlow<Map<TurnRuntimeKey, AppServerQueueSnapshot>>
        get() = turnEngine.queueSnapshots

    override val cancelledQueuedInputs: Flow<CancelledQueuedInput>
        get() = turnEngine.cancelledQueuedInputs

    /** letta-mobile-qygvv.28: a passive read of the client's frames, like the queue observer's. */
    override fun observeRuntimeFrames(runtime: AppServerRuntimeScope): Flow<AppServerReceivedFrame> =
        client.events.filter { received ->
            val scope = received.frame.runtime
            scope != null && scope.agentId == runtime.agentId && scope.conversationId == runtime.conversationId
        }

    /**
     * lgns8.17(d): standing answerer for `external_tool_call_request` frames that
     * arrive while NO turn holds a lease for their runtime.
     *
     * The turn engine can only answer from inside a turn's collect loop, so a
     * server-initiated or reconnect-replayed request that lands on an idle client
     * had no observer and blocked the App Server for its full 5-minute
     * `EXTERNAL_TOOL_CALL_TIMEOUT_MS` — the original lgns8.17 hang class, one level
     * up from the in-turn case the guarantor already covers.
     *
     * Collects `client.events` DIRECTLY rather than subscribing to the fanout: the
     * fanout is keyed by {agent, conversation} and a runtime-less request belongs
     * to no subscriber key. [AppServerTurnEngine.answerUnleasedExternalToolCall]
     * no-ops whenever a lease exists, so this never steals a frame from a live turn,
     * and the inbound-control registry makes the claim exclusive even if it raced.
     *
     * Each answer is launched so a slow tool cannot stall this collector.
     *
     * letta-mobile-qygvv.3: unleased `control_request` approvals get the same treatment, with
     * the policy the lease would have applied (auto-allow under Unrestricted, interactive
     * user-input tools left pending for a viewer). They used to sit in the fanout's pending
     * buffer while the server turn, and the conversation queue behind it, waited.
     */
    private fun attachUnleasedExternalToolAnswerer() {
        controllerScope.launch {
            client.events.collect { received ->
                when (val frame = received.frame) {
                    is AppServerInboundFrame.ExternalToolCallRequest ->
                        launch { turnEngine.answerUnleasedExternalToolCall(frame) }
                    is AppServerInboundFrame.ControlRequest -> launch {
                        runCatching { turnEngine.answerUnleasedControlRequest(frame, received.connectionGeneration) }
                            .onFailure { error ->
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                Telemetry.error(
                                    "DefaultAppServerController", "approval.unleasedAnswerFailed", error,
                                    "requestId" to frame.requestId,
                                )
                            }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * letta-mobile-qygvv.5: a `control_request` for a request this client already
     * answered is a server replay — the server still considers it pending, so our
     * answer was lost. Re-send the SAME cached decision (awaiting input_accepted)
     * instead of dropping the replay or showing a second approval card.
     */
    private fun onApprovalReplay(frame: AppServerInboundFrame.ControlRequest): Boolean {
        val cached = approvalDecisionCache.lookup(frame) ?: return false
        controllerScope.launch { turnEngine.reanswerReplayedApproval(cached) }
        return true
    }

    private val _state = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
    override val state: StateFlow<AppServerControllerState> = _state.asStateFlow()

    /**
     * Cache of started runtimes, keyed by (agentId, conversationId).
     * Thread-safe access via [runtimeMutex].
     */
    private val runtimeCache = mutableMapOf<RuntimeKey, CanonicalRuntime>()
    private val runtimePermissionModes = mutableMapOf<RuntimeKey, AppServerPermissionMode>()

    /**
     * letta-mobile-bzvro.40: modes a relayed client chose with [changePermissionMode]. A later start
     * (a client's `runtime_start` naming its own default, a reconnect, an eviction) never runs the
     * runtime looser than this; only another [changePermissionMode] can relax it.
     */
    private val pinnedPermissionModes = mutableMapOf<RuntimeKey, AppServerPermissionMode>()
    private val runtimeMutex = Mutex()

    /**
     * letta-mobile-s416w.27: when the registry's live tool sources change, every runtime in
     * [runtimeCache] (controller- and engine-started alike) is sent a fresh `runtime_start` carrying
     * the new set. Null without a registry.
     */
    private val toolReadvertiser = externalToolRegistry?.let { registry ->
        ExternalToolReadvertiser(
            client = client,
            registry = registry,
            clientInfo = clientInfo,
            requestIdFactory = requestIdFactory,
            activeRuntimes = { runtimeMutex.withLock { runtimeCache.values.map { it.scope } } },
        )
    }

    /**
     * Whether a change to the external tools is still being re-advertised; a change reaches an
     * agent from its next turn either way (letta-code snapshots the tools per turn).
     */
    val toolAdvertisementState: StateFlow<ToolAdvertisementState> =
        toolReadvertiser?.state ?: MutableStateFlow(ToolAdvertisementState()).asStateFlow()

    init {
        // After runtimeCache and runtimeMutex exist: the collector reads them.
        toolReadvertiser?.attach(controllerScope)
    }

    /** letta-mobile-qygvv.7: in-place mode changes over the controller-owned router. */
    private val deviceState = DeviceStateChanger(client, TurnInboundSource(client, eventRouter))

    /**
     * Turn engine instance. Created lazily and reused for all turns.
     * The engine itself serializes turns, so we don't need additional locking here.
     */
    private val turnEngine by lazy {
        AppServerTurnEngine(
            client = client,
            clientInfo = clientInfo,
            permissionModeProvider = { command ->
                val key = RuntimeKey(command.agentId.value, command.conversationId.value)
                runtimePermissionModes[key] ?: pinnedPermissionModes[key] ?: defaultPermissionMode()
            },
            requestIdFactory = requestIdFactory,
            externalToolRegistry = externalToolRegistry,
            eventRouter = eventRouter,
            runtimeScopeResolver = { command ->
                runtimeMutex.withLock {
                    runtimeCache[RuntimeKey(command.agentId.value, command.conversationId.value)]?.scope
                }
            },
            connectionGenerationProvider = { connectionGeneration.value },
            inboundControlRegistry = eventRouter.inboundControlRegistry(),
            approvalDecisionCache = approvalDecisionCache,
            onRuntimeInvalidated = {
                runtimeMutex.withLock { runtimeCache.clear() }
            },
            onRuntimeEnsured = { command, response, startedGeneration ->
                refillEnsuredRuntime(command, response, startedGeneration)
            },
            queueHygieneScope = controllerScope,
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
        // project directory when the runtime is restarted.
        val existingCwd = runtimeRegistry?.load(recordId)?.cwd
        val canonical = runtimeMutex.withLock {
            if (connectionGeneration.value != startedGeneration) return@withLock null
            val scope = response.runtime ?: return@withLock null
            // letta-mobile-h5t1g: engine-started runtimes use the default mode when
            // none was stored; record it so a later startRuntime with the same mode
            // reuses the cache instead of evicting on a phantom mismatch.
            if (key !in runtimePermissionModes) {
                runtimePermissionModes[key] = defaultPermissionMode()
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
    ): CanonicalRuntime {
        val key = RuntimeKey(agentId.value, conversationId.value)
        val requested = runtimeMutex.withLock { pinnedMode(key, mode) }
        when (val lookup = runtimeMutex.withLock { lookupCachedRuntime(key, requested) }) {
            is CachedRuntimeLookup.Current -> return lookup.runtime
            is CachedRuntimeLookup.ModeChange -> changeModeInPlace(key, lookup)?.let { return it }
            null -> Unit
        }
        return runtimeMutex.withLock {
            startRuntimeLocked(
                agentId = agentId,
                conversationId = conversationId,
                cwd = cwd,
                mode = requested,
                recoverApprovals = recoverApprovals,
                forceDeviceStatus = forceDeviceStatus,
            )
        }
    }

    /** letta-mobile-bzvro.40: [mode], never looser than a pinned choice; the pin when nothing was asked for. */
    private fun pinnedMode(key: RuntimeKey, mode: AppServerPermissionMode?): AppServerPermissionMode? {
        val pinned = pinnedPermissionModes[key] ?: return mode
        return mode?.let { stricter(pinned, it) } ?: pinned
    }

    /** letta-mobile-qygvv.7: what the cache holds for [key] under the requested [mode]. */
    private fun lookupCachedRuntime(key: RuntimeKey, mode: AppServerPermissionMode?): CachedRuntimeLookup? {
        val cached = runtimeCache[key] ?: return null
        val effectiveMode = mode ?: runtimePermissionModes[key] ?: defaultPermissionMode()
        if (runtimePermissionModes[key] == effectiveMode) return CachedRuntimeLookup.Current(cached)
        return CachedRuntimeLookup.ModeChange(cached, effectiveMode, connectionGeneration.value)
    }

    /**
     * letta-mobile-qygvv.7: changes the cached runtime's mode with `change_device_state`. Returns
     * the runtime when the server confirmed it; otherwise evicts it (unless a reconnect or another
     * start already replaced it) and returns null so the caller cold-starts.
     */
    private suspend fun changeModeInPlace(key: RuntimeKey, change: CachedRuntimeLookup.ModeChange): CanonicalRuntime? {
        val confirmed = deviceState.changePermissionMode(change.cached.scope, change.effectiveMode)
        return runtimeMutex.withLock {
            val stillCached = connectionGeneration.value == change.generation && runtimeCache[key] === change.cached
            when {
                !stillCached -> null
                confirmed -> change.cached.also { runtimePermissionModes[key] = change.effectiveMode }
                else -> {
                    runtimeCache.remove(key)
                    turnEngine.invalidateRuntime(notifyHost = false)
                    null
                }
            }
        }
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
        // Explicit request wins; otherwise honor a mode retained across disconnect
        // (ReconnectCoordinator calls startRuntime without mode). Only the fully
        // absent case falls back to the cold-start default (letta-mobile-h5t1g).
        val effectiveMode = mode ?: runtimePermissionModes[key] ?: defaultPermissionMode()
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
                    // lgns8.17(a): ADVERTISE the controller-owned tools. This field
                    // is the only registration seam the App Server has — it calls
                    // registerRuntimeExternalTools() with exactly this list and will
                    // never emit an external_tool_call_request for a name absent
                    // from it. Re-sent on every runtime_start, which is also how
                    // reconnect re-advertises (see ExternalToolRegistry.reRegisterAll)
                    // and how a live tool change does (ExternalToolReadvertiser).
                    externalTools = externalToolRegistry?.advertisedToolsCommandGroups(audience = ToolAudience(agentId.value)),
                ),
            )
        } catch (e: CancellationException) {
            throw e
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
        val priorGeneration = connectionGeneration.value
        connectionGeneration.incrementAndGet()
        eventRouter.inboundControlRegistry().failGeneration(priorGeneration)
        runtimeMutex.withLock {
            // Canonical runtime scopes are generation-local: every cached scope
            // was minted by the generation that just died and must be re-fetched
            // via runtime_start on the next one. Desired permission modes and
            // the durable registry survive — they are intent, not server state.
            runtimeCache.clear()
        }
        turnEngine.invalidateRuntime(notifyHost = false)
        turnEngine.cancelActiveLeaseForGeneration(
            failedGeneration = priorGeneration,
            reason = "connection generation superseded: ${reason ?: "disconnect"}",
        )
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
    ): ApprovalSubmitResult = submitApproval(
        ControllerApprovalAnswer(agentId, conversationId, approvalRequestId, approve, reason, toolCallId, updatedInput),
    )

    override suspend fun submitApproval(answer: ControllerApprovalAnswer): ApprovalSubmitResult {
        val runtime = cachedScopeFor(answer.agentId, answer.conversationId)
            ?: throw AppServerControllerException("No active runtime found for approval ${answer.approvalRequestId}")
        // letta-mobile-vilsn: interactive tools (AskUserQuestion) are gated by
        // letta-code's `can_use_tool` control request, whose id (e.g.
        // `perm-call_…`) is NOT the display approval id the app renders from and
        // is NOT reliably derivable from the tool_call_id across LLM providers
        // (`call_…` vs `toolu_…`). Answer against the REAL id the engine captured
        // when it surfaced the approval; fall back to the historical heuristic
        // only if nothing was captured.
        val capturedRequestId = answer.toolCallId?.let(turnEngine::userInputApprovalId)
        // letta-mobile-bzvro.40: an always-allow is honoured only for the request the card drew,
        // while it is still parked and still offers every chosen rule.
        val effectiveRequestId = if (answer.selectedSuggestionIds.isEmpty()) {
            capturedRequestId ?: answer.approvalRequestId
        } else {
            suggestionApprovalId(answer) ?: return ApprovalSubmitResult.Rejected(RULE_NOT_OFFERED)
        }
        // letta-mobile-vilsn: a structured close payload (an AskUserQuestion answer) closes the
        // call with `Allow(updated_input=…)` rather than a bare allow.
        val decision = AppServerApprovalDecisions.decide(
            approve = answer.approve,
            updatedInput = answer.updatedInput.takeIf { answer.approve },
            message = answer.reason,
            defaultApproveMessage = "Approved by mobile client.",
            defaultDenyMessage = "Denied by mobile client.",
        ).withSelectedSuggestions(answer.selectedSuggestionIds)
        // lgns8.22.4.1.4: capture the generation this decision is sent ON. Reading
        // the live generation after the send would let an old-connection response
        // mark a successor-generation recovery replay Answered, dropping the replay
        // even though the server may never have received the decision.
        val claimGeneration = connectionGeneration.value
        // letta-mobile-qygvv.5: awaits input_accepted. A rejected decision leaves
        // the inbound control request (and the captured user-input gate id) open
        // and is returned so the caller can show the failure.
        val result = turnEngine.submitApprovalResponse(
            ApprovalSubmission(runtime, effectiveRequestId, decision),
            claimGeneration = claimGeneration,
        )
        turnEngine.releaseUserInputGateUnlessRejected(result, answer.toolCallId, capturedRequestId)
        return result
    }

    private suspend fun cachedScopeFor(agentId: AgentId, conversationId: ConversationId?): AppServerRuntimeScope? =
        runtimeMutex.withLock {
            val conversationValue = conversationId?.value
            runtimeCache.entries.firstOrNull { (key, _) ->
                key.agentId == agentId.value && (conversationValue == null || key.conversationId == conversationValue)
            }?.value?.scope
        }

    private fun suggestionApprovalId(answer: ControllerApprovalAnswer): String? {
        val toolCallId = answer.toolCallId ?: return null
        val parked: PendingApprovalDetails? = turnEngine.pendingApprovalDetails.value[toolCallId]
        return parked.approvalIdForSuggestions(ApprovalBinding(toolCallId, answer.approvalRequestId), answer.selectedSuggestionIds)
    }

    override val pendingApprovals: StateFlow<Map<TurnRuntimeKey, List<PendingApprovalDetails>>>
        get() = turnEngine.pendingApprovalsByRuntime

    override suspend fun permissionModeOf(runtime: AppServerRuntimeScope): AppServerPermissionMode? {
        val key = RuntimeKey(runtime.agentId, runtime.conversationId)
        return runtimeMutex.withLock { runtimePermissionModes[key] ?: pinnedPermissionModes[key] }
    }

    override suspend fun changePermissionMode(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): Boolean {
        val key = RuntimeKey(runtime.agentId, runtime.conversationId)
        runtimeMutex.withLock { pinnedPermissionModes[key] = mode }
        startRuntime(
            agentId = AgentId(runtime.agentId),
            conversationId = ConversationId(runtime.conversationId),
            mode = mode,
            recoverApprovals = false,
            forceDeviceStatus = false,
        )
        return runtimeMutex.withLock { runtimePermissionModes[key] == mode }
    }

    override suspend fun sync(
        runtime: AppServerRuntimeScope,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): AppServerInboundFrame.SyncResponse = runtime.controllerCall("sync") {
        client.sync(
            AppServerCommand.Sync(
                runtime = runtime,
                requestId = requestIdFactory(),
                recoverApprovals = recoverApprovals,
                forceDeviceStatus = forceDeviceStatus,
            ),
        )
    }

    override suspend fun abort(
        runtime: AppServerRuntimeScope,
        runId: String?,
    ): AppServerInboundFrame.AbortMessageResponse = runtime.controllerCall("abort") {
        // letta-mobile-qygvv.6: through the engine so a confirmed abort also cleans up this
        // client's parked queue items and resumes the queue.
        turnEngine.abort(runtime, runId)
    }

    override suspend fun removeQueueItem(
        runtime: AppServerRuntimeScope,
        itemId: String,
    ): AppServerInboundFrame.RemoveQueueItemResponse = runtime.controllerCall("remove_queue_item") {
        client.removeQueueItem(
            AppServerCommand.RemoveQueueItem(requestId = requestIdFactory(), runtime = runtime, itemId = itemId),
        )
    }

    override suspend fun resumeQueue(runtime: AppServerRuntimeScope): AppServerInboundFrame.ResumeQueueResponse =
        runtime.controllerCall("resume_queue") {
            client.resumeQueue(AppServerCommand.ResumeQueue(runtime = runtime, requestId = requestIdFactory()))
        }

    /**
     * Internal key for runtime cache.
     */
    private data class RuntimeKey(val agentId: String, val conversationId: String)

    /** letta-mobile-qygvv.7: a cached runtime that already matches, or one whose mode must change. */
    private sealed interface CachedRuntimeLookup {
        data class Current(val runtime: CanonicalRuntime) : CachedRuntimeLookup

        data class ModeChange(
            val cached: CanonicalRuntime,
            val effectiveMode: AppServerPermissionMode,
            val generation: Long,
        ) : CachedRuntimeLookup
    }

    /** Tear down the inbound router collector and controller scope (tests / shutdown). */
    override fun close() {
        eventRouter.detach()
        controllerScope.cancel()
    }

    companion object {
        private const val RULE_NOT_OFFERED = "the permission rule is no longer offered for this request"

        private val DEFAULT_CLIENT_INFO = AppServerRuntimeStartClientInfo(
            name = "letta-mobile-controller",
            title = "Letta Mobile Controller",
            version = "0.2.0",
        )

        private val defaultRequestId = AppServerRequestIdFactory("controller-req")
    }
}
