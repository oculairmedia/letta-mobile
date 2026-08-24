package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.subagent.ParentContext
import com.letta.mobile.data.repository.subagent.SubagentCorrelator
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.LivenessProbingChannelTransport
import com.letta.mobile.data.transport.api.RedialAwareChannelTransport
import com.letta.mobile.data.transport.api.RedialWhileTurnActive
import com.letta.mobile.data.controller.node.iroh.EphemeralIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.TurnContextPreflight
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.letta.mobile.data.runtime.AppServerRuntimeEventMapper
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.RuntimeEventDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import kotlin.time.Duration.Companion.milliseconds
/**
 * Mobile-compatible [IChannelTransport] backed by the App Server controller path over Iroh.
 *
 * It is selected by using an active backend URL of the form `iroh://<EndpointTicket>`.
 * This keeps the existing mobile send coordinator and [WsChatBridge] path intact while
 * swapping only the transport underneath it. The embedded/local runtime path is not touched.
 */
class IrohChannelTransport(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onConnect: () -> Unit = {},
    // Test/override hook: when non-blank, used instead of the compiled DEBUG_FORCE_IROH_URL.
    // Lets the on-host harness dial a live in-process node without rebuilding the constant.
    private val forcedIrohUrl: String = "",
    private val activeConfigProvider: () -> IrohConnectConfig? = { null },
    // d6e8g.9: persistent client identity. The default is ephemeral (a fresh
    // NodeId per dial, preserving prior behavior + tests); production apps pass
    // a persisted store so the device keeps ONE stable NodeId across reconnects
    // — a prerequisite for server-side pairing (a churning NodeId can never
    // bind to a paired peer).
    private val secretKeyStore: IrohSecretKeyStore = EphemeralIrohSecretKeyStore(),
    private val testDialer: (suspend (IrohConnectConfig) -> IrohConnectionHandle)? = null,
    // Bounded window (ms) to await the server's own terminal after an abort
    // before synthesizing a cancelled terminal. Overridable so tests need not
    // wait the full production window.
    private val serverTerminalWaitMs: Long = SERVER_TERMINAL_WAIT_MS,
    // letta-mobile-wxy4s: application-level liveness probe cadence. Overridable so
    // tests can compress the loop; production MUST keep non-zero defaults (an
    // interval of 0 disables the probe entirely — guarded by
    // IrohLivenessProbeWiringTest).
    internal val livenessProbeIntervalMs: Long = LIVENESS_PROBE_INTERVAL_MS,
    internal val livenessProbeTimeoutMs: Long = LIVENESS_PROBE_TIMEOUT_MS,
    internal val livenessProbeFailuresToDeclareDead: Int = LIVENESS_PROBE_FAILURES_TO_DECLARE_DEAD,
    // letta-mobile-parg0: congestion grace is overridable so compressed tests can
    // expire young-in-flight protection without waiting the production 45s window.
    private val livenessCongestionGraceMs: Long = IrohLivenessProbe.CONGESTION_GRACE_MS,
    private val livenessMaxDetectionMs: Long = IrohLivenessProbe.MAX_DETECTION_MS,
) : IChannelTransport, RedialAwareChannelTransport, LivenessProbingChannelTransport {
    private val _state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Idle)
    override val state: StateFlow<ChannelTransportState> = _state.asStateFlow()

    private val framePublisher = IrohFramePublisher()
    override val events: SharedFlow<ServerFrame> = framePublisher.events
    override val frameEvents: SharedFlow<TransportFrameEvent> = framePublisher.frameEvents

    private val _redialWhileTurnActive = MutableSharedFlow<RedialWhileTurnActive>(extraBufferCapacity = 8)
    override val redialWhileTurnActive: SharedFlow<RedialWhileTurnActive> = _redialWhileTurnActive.asSharedFlow()

    /** Emit to canonical frame publisher so both direct consumers and
     *  WsChatBridge (via frameEvents) see each frame exactly once without split histories. */
    private suspend fun emitBoth(frame: ServerFrame) {
        // letta-mobile-34xoj: record stream activity to prevent premature reconnect
        adminRpcExecutor.recordStreamActivity()
        Telemetry.event(
            "IrohGate", "gate1.emitBoth",
            "frame" to (frame::class.simpleName ?: ""),
            "messageId" to frameMessageId(frame),
            "conversationId" to frameConversationId(frame),
        )
        frameFlowContent(frame)?.let { (key, type, content) ->
            IrohFrameFlowDiagnostics.record("gate1.emit", key, type, content)
        }
        framePublisher.publish(frame)
    }

    /** (key, messageType, content) for content-bearing frames, for FrameFlowDiag. */
    private fun frameFlowContent(frame: ServerFrame): Triple<String, String, String>? = when (frame) {
        is ServerFrame.AssistantMessage -> {
            val f: ServerFrame.AssistantMessage = frame
            Triple(f.otid ?: f.id, "assistant_message", f.content)
        }
        is ServerFrame.ReasoningMessage -> {
            val f: ServerFrame.ReasoningMessage = frame
            Triple(f.id, "reasoning_message", f.reasoning)
        }
        else -> null
    }

    /**
     * letta-mobile-or40x: send jobs KEYED BY conversationId.
     *
     * Previously a single process-wide slot. Starting a turn on conversation B
     * overwrote the only reference to conversation A's job, so a later
     * `cancel(B)`/teardown cancelled A's job (or vice versa) and the victim
     * conversation never settled. Concurrent map: written from `send` (caller
     * thread) and read/removed from job-completion callbacks and cancel
     * coroutines on Dispatchers.IO.
     */
    private val turnRegistry = IrohTurnRegistry()
    private val connectionGeneration = atomic(0L)
    @Volatile
    private var resubscribeJob: Job? = null

    override fun hasActiveChatTurn(conversationId: String): Boolean =
        turnRegistry.hasActiveTurn(IrohConversationKey(conversationId))

    override val hasAnyActiveChatTurn: Boolean
        get() = turnRegistry.hasAnyActiveTurn

    internal data class ActiveTurnSnapshot(
        val turnId: String,
        val runId: String,
        val hasTerminal: Boolean,
        val isTerminalCompleted: Boolean,
    )

    internal fun activeTurnSnapshot(conversationId: String): ActiveTurnSnapshot? {
        val s = turnRegistry.snapshotForTest(IrohConversationKey(conversationId)) ?: return null
        return ActiveTurnSnapshot(
            turnId = s.turnId,
            runId = s.runId,
            hasTerminal = s.hasTerminal,
            isTerminalCompleted = s.isTerminalCompleted,
        )
    }

    internal fun activeSendJob(conversationId: String): Job? = turnRegistry.getSendJob(IrohConversationKey(conversationId))
    internal fun activeTurnsCount(): Int = turnRegistry.activeTurnsCount()
    internal fun activeSendJobsCount(): Int = turnRegistry.activeSendJobsCount()

    // letta-mobile-53k65.8: Generation-bound observer ingestion collaborator.
    private val observerIngestor = IrohObserverIngestor(
        IrohObserverIngestor.Dependencies(
            scope = scope,
            turnRegistry = turnRegistry,
            connectionGeneration = { connectionGeneration.value },
            emit = { emitBoth(it) },
            adminRpc = { request -> adminRpc(request.method, request.path, request.body) },
            recordFrameOwnership = ::recordFrameOwnership,
        ),
    )

    /** Test/wiring visibility: subagent correlator and observer state. */
    internal val subagentCorrelator: SubagentCorrelator get() = observerIngestor.subagentCorrelator
    internal val viewedConversationId: String? get() = observerIngestor.viewedConversationId
    internal val viewedMessageListPath: String? get() = observerIngestor.viewedMessageListPath
    internal val isObserverIngesting: Boolean get() = observerIngestor.isIngesting

    private var explicitConfig: IrohConnectConfig? = null

    private val irohDialer = IrohDialer(
        scope = scope,
        secretKeyStore = secretKeyStore,
        onConnectionLost = { reason, handle -> supervisor.onConnectionLostAsync(reason, handle) },
        onCloseResources = ::handleCloseResources,
    )

    // Explicit type: this field and `livenessProbe` reference each other through
    // their lambdas, which defeats type inference.
    private val supervisor: IrohConnectionSupervisor = IrohConnectionSupervisor(
        scope = scope,
        configProvider = { explicitConfig ?: activeConfigProvider() },
        dialer = { config -> testDialer?.invoke(config) ?: dialConnection(config) },
        onStateChanged = ::handleSupervisorStateChanged,
    )

    private fun handleCloseResources(reason: String) {
        turnRegistry.allSendJobEntries().forEach { (conversationId, _) ->
            val job = turnRegistry.removeSendJob(IrohConversationKey(conversationId)) ?: return@forEach
            val turn = turnRegistry.getActiveTurn(IrohConversationKey(conversationId))
            if (turn != null && !turn.hasTerminal) {
                Telemetry.event(
                    "IrohTransport", "turn.torn_down_nonterminal",
                    "reason" to reason,
                    "conversationId" to conversationId,
                    "turnId" to turn.turnId,
                    "runId" to turn.runId,
                )
            }
            runCatching { job.cancel() }
        }
    }

    private suspend fun dialConnection(config: IrohConnectConfig): IrohConnectionHandle {
        val forcedUrl = forcedIrohUrl.takeIf { it.isNotBlank() } ?: DEBUG_FORCE_IROH_URL.takeIf { it.isNotBlank() }
        return irohDialer.dial(
            config = config,
            effectiveUrlOverride = forcedUrl,
            onConnecting = {
                _state.value = ChannelTransportState.Connecting()
                onConnect()
            },
        )
    }

    private fun handleSupervisorStateChanged(supervisorState: IrohConnectionState) {
        _state.value = supervisorState.toChannelTransportState()
        if (supervisorState is IrohConnectionState.Ready) {
            handleReadyState(supervisorState)
        } else {
            handleNonReadyState(supervisorState)
        }
    }

    private fun handleReadyState(supervisorState: IrohConnectionState.Ready) {
        val generation = connectionGeneration.incrementAndGet()
        notifyRedialIfTurnActive()
        observerIngestor.start(IrohObserverIngestor.ObserverConnection(supervisorState.handle, generation))
        observerIngestor.reSubscribeViewedConversation(generation)
        livenessProbe.start(supervisorState.handle)
    }

    private fun handleNonReadyState(supervisorState: IrohConnectionState) {
        connectionGeneration.incrementAndGet()
        if (supervisorState is IrohConnectionState.Degraded && supervisorState.reason != "config_changed") {
            turnRegistry.rememberInterruptedTurns()
        } else if (supervisorState is IrohConnectionState.Degraded) {
            turnRegistry.clearInterruptedTurns()
        }
        val reason = "state:${supervisorState::class.simpleName}"
        observerIngestor.stop(reason)
        livenessProbe.stop(reason)
    }

    // letta-mobile-53k65.10: Generation-scoped Admin RPC executor and retry state.
    private val adminRpcExecutor = IrohAdminRpcExecutor(
        IrohAdminRpcExecutor.Dependencies(
            supervisor = supervisor,
            connectionGeneration = { connectionGeneration.value },
            onRequestObserved = observerIngestor::observeAdminRequest,
        ),
    )

    private val cronRpcClient = IrohCronRpcClient(
        adminRpc = { method, path, body -> adminRpc(method, path, body) },
    )

    private val subagentRpcClient = IrohSubagentRpcClient(
        readyHandle = { supervisor.ready() },
        currentScope = { observerIngestor.currentSubagentScope() },
        adminRpc = { method, path, body -> adminRpc(method, path, body) },
    )

    /**
     * letta-mobile-wxy4s: application-level connection liveness. QUIC state alone
     * cannot detect a black-holed peer — the transport's unacked keepalive datagram
     * keeps resetting the idle timer — so [IrohLivenessProbe] periodically opens a
     * fresh bidi stream instead. See that class for the full root cause.
     */
    private val livenessProbe = IrohLivenessProbe(
        intervalMs = livenessProbeIntervalMs,
        timeoutMs = livenessProbeTimeoutMs,
        failuresToDeclareDead = livenessProbeFailuresToDeclareDead,
        maxDetectionMs = livenessMaxDetectionMs,
        millisSinceLastProofOfLife = { adminRpcExecutor.millisSinceLastProofOfLife() },
        youngInFlightAdminRpcCount = {
            adminRpcExecutor.youngInFlightAdminRpcCount(graceMs = livenessCongestionGraceMs)
        },
        reportConnectionLost = { reason, handle -> supervisor.onConnectionLostAsync(reason, handle) },
    )

    /** Test/wiring visibility: is the liveness probe currently armed? */
    internal val isLivenessProbeArmed: Boolean get() = livenessProbe.isArmed

    override fun probeNow() = livenessProbe.probeNow()

    /**
     * letta-mobile-or40x: recovery is announced PER CONVERSATION. Every
     * interrupted conversation, plus every still-nonterminal live turn that has
     * no interrupted snapshot, gets its own [RedialWhileTurnActive]. The
     * pre-or40x version could only ever announce ONE conversation because it
     * read single global slots.
     */
    private fun notifyRedialIfTurnActive() {
        val announced = mutableSetOf<String>()
        turnRegistry.interruptedTurnsSnapshot().forEach { recovery ->
            announced += recovery.conversationId
            if (_redialWhileTurnActive.tryEmit(recovery)) {
                turnRegistry.removeInterruptedTurn(recovery)
            }
        }
        turnRegistry.activeTurnsSnapshot().forEach { turn ->
            if (turn.conversationId in announced || turn.hasTerminal) return@forEach
            _redialWhileTurnActive.tryEmit(
                RedialWhileTurnActive(
                    agentId = turn.agentId,
                    conversationId = turn.conversationId,
                    turnId = turn.turnId,
                    runId = turn.runId,
                ),
            )
        }
    }

    private fun rememberInterruptedTurns() {
        turnRegistry.rememberInterruptedTurns()
    }

    private fun clearInterruptedTurn(conversationId: String) {
        turnRegistry.removeInterruptedTurn(IrohConversationKey(conversationId))
    }

    private fun recordFrameOwnership(observation: IrohObserverIngestor.FrameObservation) {
        val conversationId = observation.conversationId
        val localTurn = observation.localTurn
        val result = turnRegistry.recordFrameOwnership(IrohFrameOwnershipObservation(IrohConversationKey(conversationId), localTurn))
        if (result is IrohTurnRegistry.FrameOwnershipResult.Switched) {
            Telemetry.event(
                "IrohObserver", "ingest.ownership_switched",
                "conversationId" to conversationId,
                "from" to result.from,
                "to" to result.to,
                "turnId" to (localTurn?.turnId ?: ""),
                "otherActiveConversations" to otherActiveConversationsLabel(conversationId),
            )
        }
    }

    /** Comma-joined ids of live nonterminal turns other than [conversationId]. */
    private fun otherActiveConversationsLabel(conversationId: String): String =
        turnRegistry.concurrentTurns(excludingConversation = IrohConversationKey(conversationId))
            .joinToString(",") { it.conversationId }

    /** Test/wiring visibility: current generation admin RPC retry state. */
    internal val adminRpcRetryState get() = adminRpcExecutor.currentRetryState()
    internal fun adminRpcRetryStateFor(generation: Long) = adminRpcExecutor.retryStateFor(generation)

    override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) {
        explicitConfig = IrohConnectConfig(
            baseShimUrl = baseShimUrl,
            token = token,
            deviceId = deviceId,
            clientVersion = clientVersion,
        )
        Telemetry.event(
            "IrohTrace", "transport.connect.begin",
            "baseShimUrl" to baseShimUrl,
            "forced" to DEBUG_FORCE_IROH_URL.isNotBlank(),
            "state" to state.value::class.simpleName,
        )
        supervisor.refreshConfig()
        val handle = supervisor.ready()
        Telemetry.event("IrohTrace", "transport.connect.done", "state" to "connected", "sessionId" to handle.sessionId)
    }

    override fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String?,
        contentParts: JsonArray?,
        startNewConversation: Boolean,
    ): Boolean {
        Telemetry.event(
            "IrohTrace", "transport.send.called",
            "agentId" to agentId,
            "conversationId" to conversationId,
            "textLength" to text.length,
            "state" to state.value::class.simpleName,
        )
        val turnId = "iroh-turn-${UUID.randomUUID()}"
        val initialRunId = "iroh-run-${UUID.randomUUID()}"
        val token = IrohTurnToken(conversationId, connectionGeneration.value, turnId)
        val startResult = turnRegistry.tryStart(
            IrohTurnStartRequest(token, initialRunId, agentId),
        )
        if (startResult is IrohTryStartResult.Busy) {
            Telemetry.event(
                "IrohTransport", "send.rejected_same_conversation_busy",
                "conversationId" to conversationId,
                "activeTurnId" to startResult.activeTurn.turnId,
                "rejectedTurnId" to turnId,
            )
            scope.launch {
                emitBoth(
                    ServerFrame.Error(
                        id = frameId("error"),
                        ts = nowIso(),
                        code = "iroh_turn_engine_busy",
                        message = "a turn is already active for this conversation",
                        conversationId = conversationId,
                        turnId = turnId,
                        runId = initialRunId,
                    ),
                )
                emitBoth(
                    ServerFrame.TurnDone(
                        id = frameId("turn_done"),
                        ts = nowIso(),
                        turnId = turnId,
                        runId = initialRunId,
                        status = "failed",
                    ),
                )
            }
            return true
        }
        val turn = (startResult as IrohTryStartResult.Started).turn
        // SENSING (a): a turn is starting for THIS conversation while another
        // conversation still has a nonterminal turn in flight.
        val concurrent = turnRegistry.concurrentTurns(excludingConversation = IrohConversationKey(conversationId))
        if (concurrent.isNotEmpty()) {
            Telemetry.event(
                "IrohTransport", "turn.concurrent_start",
                "conversationId" to conversationId,
                "turnId" to turnId,
                "concurrentConversations" to concurrent.joinToString(",") { it.conversationId },
                "concurrentTurnIds" to concurrent.joinToString(",") { it.turnId },
            )
        }
        val sendJob = scope.launch {
            Telemetry.event("IrohTrace", "transport.send.job_start", "turnId" to turnId, "runId" to turn.runId)
            val handle = runCatching { supervisor.ready() }.getOrElse { error ->
                Telemetry.event("IrohTransport", "turn.ready_failed", "error" to (error.message ?: error.toString()), "class" to error::class.simpleName)
                emitTurnFrame(
                    turn,
                    ServerFrame.Error(
                        id = frameId("error"),
                        ts = nowIso(),
                        code = "iroh_connection_not_ready",
                        message = error.message ?: error.toString(),
                        conversationId = conversationId,
                        turnId = turnId,
                        runId = turn.runId,
                    ),
                )
                emitTurnFrame(
                    turn,
                    ServerFrame.TurnDone(
                        id = frameId("turn_done"),
                        ts = nowIso(),
                        turnId = turnId,
                        runId = turn.runId,
                        status = "failed",
                    ),
                )
                return@launch
            }
            val engine = handle.turnEngine ?: error("Iroh send requested without turn engine")
            if (engine.isBusy(agentId, conversationId)) {
                val owner = engine.activeTurnOwnerFor(agentId, conversationId)
                val ownerAcquiredAtMs = owner?.acquiredAtMs
                Telemetry.event(
                    "IrohTransport", "turn.busy",
                    "turnId" to turnId,
                    "runId" to turn.runId,
                    "sendAgentId" to agentId,
                    "sendConversationId" to conversationId,
                    "sendOtid" to otid,
                    "ownerRunId" to owner?.runId,
                    "ownerRuntimeId" to owner?.runtimeId,
                    "ownerAgentId" to owner?.agentId,
                    "ownerConversationId" to owner?.conversationId,
                    "ownerAcquiredAtMs" to ownerAcquiredAtMs,
                    "ownerHeldForMs" to ownerAcquiredAtMs?.let { System.currentTimeMillis() - it },
                    "ownerLastTerminal" to owner?.lastTerminal,
                    "ownerLastTerminalSource" to owner?.lastTerminalSource,
                    "ownerLastTerminalAtMs" to owner?.lastTerminalAtMs,
                    "ownerLastTerminalSeq" to owner?.lastTerminalSeq,
                    "ownerLastTerminalScopeMatched" to owner?.lastTerminalScopeMatched,
                    "ownerSettleDeadlineMs" to owner?.settleDeadlineMs,
                    "ownerWatchdogDeadlineMs" to owner?.watchdogDeadlineMs,
                    "ownerProcessRole" to owner?.processRole,
                    "ownerReleaseReason" to owner?.releaseReason,
                    "otherBusyKeys" to engine.busyRuntimeKeys()
                        .filter { it.conversationId != conversationId || it.agentId != agentId }
                        .joinToString(",") { it.toString() },
                )
                emitTurnFrame(
                    turn,
                    ServerFrame.Error(
                        id = frameId("error"),
                        ts = nowIso(),
                        code = "iroh_turn_engine_busy",
                        message = "Iroh App Server turn engine is already busy.",
                        conversationId = conversationId,
                        turnId = turnId,
                        runId = turn.runId,
                    ),
                )
                emitTurnFrame(
                    turn,
                    ServerFrame.TurnDone(
                        id = frameId("turn_done"),
                        ts = nowIso(),
                        turnId = turnId,
                        runId = turn.runId,
                        status = "failed",
                    ),
                )
                return@launch
            }
            emitTurnFrame(
                turn,
                ServerFrame.TurnStarted(
                    id = frameId("turn_started"),
                    ts = nowIso(),
                    agentId = agentId,
                    conversationId = conversationId,
                    turnId = turnId,
                    runId = turn.runId,
                ),
            )
            runCatching {
                engine.runTurn(
                    TurnCommand(
                        backendId = BackendId("iroh-app-server"),
                        runtimeId = RuntimeId("iroh:${handle.sessionId}"),
                        agentId = AgentId(agentId),
                        conversationId = ConversationId(conversationId),
                        input = TurnInput.UserMessage(
                            localMessageId = otid ?: frameId("local"),
                            text = text,
                            contentPartsJson = contentParts?.toString(),
                        ),
                    ),
                ).collect { draft ->
                    draft.runId?.value?.let { realRunId ->
                        if (turn.promoteRunId(realRunId)) {
                            emitBoth(
                                ServerFrame.TurnStarted(
                                    id = frameId("turn_started"),
                                    ts = nowIso(),
                                    agentId = agentId,
                                    conversationId = conversationId,
                                    turnId = turnId,
                                    runId = realRunId,
                                ),
                            )
                        }
                    }
                    emitDraft(draft, turn).forEach { emitTurnFrame(turn, it) }
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    Telemetry.event("IrohTransport", "turn.cancelled", "turnId" to turnId, "runId" to turn.runId)
                    return@onFailure
                }
                Telemetry.event("IrohTransport", "turn.failed", "error" to (error.message ?: error.toString()), "class" to error::class.simpleName)
                emitTurnFrame(
                    turn,
                    ServerFrame.Error(
                        id = frameId("error"),
                        ts = nowIso(),
                        code = "iroh_app_server_error",
                        message = error.message ?: error.toString(),
                        conversationId = conversationId,
                        turnId = turnId,
                        runId = turn.runId,
                    ),
                )
                emitTurnFrame(
                    turn,
                    ServerFrame.TurnDone(
                        id = frameId("turn_done"),
                        ts = nowIso(),
                        turnId = turnId,
                        runId = turn.runId,
                        status = "failed",
                    ),
                )
            }
        }
        turn.job = sendJob
        turnRegistry.registerSendJob(IrohSendJobRegistration(IrohConversationKey(conversationId), sendJob))
        sendJob.invokeOnCompletion {
            val removed = turnRegistry.finish(turn.token)
            if (removed) {
                if (!turn.hasTerminal) {
                    Telemetry.event(
                        "IrohTransport", "turn.abandoned_nonterminal",
                        "conversationId" to conversationId,
                        "turnId" to turn.turnId,
                        "runId" to turn.runId,
                        "otherActiveConversations" to otherActiveConversationsLabel(conversationId),
                    )
                }
            } else {
                Telemetry.event(
                    "IrohTransport", "turn.completion_after_eviction",
                    "conversationId" to conversationId,
                    "turnId" to turn.turnId,
                    "hasTerminal" to turn.hasTerminal,
                    "currentTurnId" to (turnRegistry.getActiveTurn(IrohConversationKey(conversationId))?.turnId ?: ""),
                )
            }
            turnRegistry.unregisterSendJob(IrohSendJobRegistration(IrohConversationKey(conversationId), sendJob))
        }
        return true
    }

    /**
     * Emits a turn frame through the single exactly-one-terminal guard shared by
     * the streaming send job and [cancel]. Only the first [ServerFrame.TurnDone]
     * for a turn is forwarded; the loser is dropped. This holds no matter which
     * side (server terminal or synthetic cancel) reaches the terminal first.
     */
    private suspend fun emitTurnFrame(turn: IrohActiveTurn, frame: ServerFrame) {
        if (frame is ServerFrame.TurnDone) {
            if (!turnRegistry.publishTerminal(IrohTerminalPublication(turn, frame.status, "engine"))) {
                Telemetry.event(
                    "IrohTrace", "transport.turn_done.duplicate_skipped",
                    "turnId" to turn.turnId,
                    "runId" to frame.runId,
                    "status" to frame.status,
                )
                return
            }
            emitBoth(frame)
            return
        }
        emitBoth(frame)
    }

    private fun emitDraft(
        draft: RuntimeEventDraft,
        turn: IrohActiveTurn,
    ): List<ServerFrame> {
        val agentId = turn.agentId
        val conversationId = turn.conversationId
        val turnId = turn.turnId
        // T5 canonical ids: the first server frame carrying the real run id
        // promotes the turn off its synthetic `iroh-run-*` placeholder. Once
        // promoted, TurnStarted is re-emitted with the real run id and EVERY
        // subsequent frame (including the terminal TurnDone) carries it, so the
        // reducer merges synthetic-live and letta-msg-* rows on run id alone —
        // no otid/semantic fallback required.
        val realRunId = draft.runId?.value?.takeIf { it.isNotBlank() }
        val promoted = realRunId != null && turn.promoteRunId(realRunId)
        val effectiveRunId = turn.runId
        Telemetry.event(
            "IrohTrace", "transport.emitDraft",
            "payload" to (draft.payload::class.simpleName ?: ""),
            "runId" to effectiveRunId,
            "promoted" to promoted,
        )
        val promotionFrames: List<ServerFrame> = if (promoted) {
            Telemetry.event(
                "IrohTransport", "turn.run_id_promoted",
                "turnId" to turnId,
                "runId" to effectiveRunId,
            )
            listOf(
                ServerFrame.TurnStarted(
                    id = frameId("turn_started"),
                    ts = nowIso(),
                    agentId = agentId,
                    conversationId = conversationId,
                    turnId = turnId,
                    runId = effectiveRunId,
                ),
            )
        } else {
            emptyList()
        }
        return promotionFrames + payloadToServerFrames(
            payload = draft.payload,
            agentId = agentId,
            conversationId = conversationId,
            turnId = turnId,
            runId = effectiveRunId,
        )
    }

    /**
     * Shared payload -> [ServerFrame] projection used by BOTH the initiator send
     * path ([emitDraft]) and the passive OBSERVER ingestion loop
     * ([observeStreamFrames]).
     *
     * Extracting it guarantees the observer produces byte-identical frame shapes
     * to the initiator — the ONLY difference between the two paths is who supplies
     * the (agentId, conversationId, turnId, runId) context, and for
     * [RuntimeEventPayload.RemoteStreamFrame] even those are read from the wire
     * envelope first (context is only a fallback). This is the letta-mobile-r3i1z
     * "identical shape" contract: same mapper, same defaults, same output.
     */
    private fun payloadToServerFrames(
        payload: RuntimeEventPayload,
        agentId: String,
        conversationId: String,
        turnId: String,
        runId: String,
    ): List<ServerFrame> = RuntimeEventServerFrameMapper.map(
        payload = payload,
        context = RuntimeEventServerFrameMapper.Context(
            agentId = agentId,
            conversationId = conversationId,
            turnId = turnId,
            runId = runId,
        ),
    )

    private fun frameMessageId(frame: ServerFrame): String? = when (frame) {
        is ServerFrame.AssistantMessage -> frame.id
        is ServerFrame.ReasoningMessage -> frame.id
        is ServerFrame.ToolCallMessage -> frame.id
        is ServerFrame.ToolReturnMessage -> frame.id
        is ServerFrame.UserMessage -> frame.id
        else -> null
    }

    private fun frameConversationId(frame: ServerFrame): String? = when (frame) {
        is ServerFrame.AssistantMessage -> frame.conversationId
        is ServerFrame.ReasoningMessage -> frame.conversationId
        is ServerFrame.ToolCallMessage -> frame.conversationId
        is ServerFrame.ToolReturnMessage -> frame.conversationId
        is ServerFrame.UserMessage -> frame.conversationId
        else -> null
    }

    /**
     * letta-mobile-or40x: cancel HONORS ITS ARGUMENT. Only [conversationId]'s own
     * turn and send job are touched. The pre-or40x implementation was keyed in
     * name only — it read the single global `activeTurn`/`activeSendJob` and so
     * aborted and cancelled whichever conversation happened to occupy the slot.
     * That is the reported "cancelling one conversation froze the other".
     */
    override fun cancel(conversationId: String): Boolean {
        val turn = turnRegistry.getActiveTurn(IrohConversationKey(conversationId))
        if (turn == null) {
            clearInterruptedTurn(conversationId)
            // Nothing streaming ON THIS CONVERSATION: preserve the "cancel always
            // yields a terminal" contract so the UI can never get stuck streaming,
            // but there is no run to abort server-side — and, critically, no OTHER
            // conversation's job may be cancelled here.
            Telemetry.event(
                "IrohTransport", "cancel.no_active_turn",
                "conversationId" to conversationId,
                "otherActiveConversations" to otherActiveConversationsLabel(conversationId),
            )
            turnRegistry.removeSendJob(IrohConversationKey(conversationId))?.cancel()
            scope.launch {
                emitBoth(
                    ServerFrame.TurnDone(
                        id = frameId("cancelled"),
                        ts = nowIso(),
                        turnId = "cancelled-${UUID.randomUUID()}",
                        runId = "cancelled-${UUID.randomUUID()}",
                        status = "cancelled",
                    ),
                )
            }
            return true
        }
        Telemetry.event(
            "IrohTransport", "cancel.begin",
            "conversationId" to conversationId,
            "turnId" to turn.turnId,
            "runId" to turn.runId,
        )
        scope.launch {
            // Guard against stale ActiveTurn race: if send(A) was called twice
            // quickly and this cancel captured the old ActiveTurn, aborting would
            // target the NEW turn. Only proceed if this is still the active turn.
            if (turnRegistry.getActiveTurn(IrohConversationKey(conversationId)) !== turn) return@launch
            // 1. Ask the server to abort the active run so it emits its own
            //    authoritative terminal (and, per 8s45p, closes open tool_calls).
            //    A still-synthetic run id means the real run id has not streamed
            //    yet — pass null so the server aborts whatever run is active for
            //    the runtime.
            runCatching {
                val handle = supervisor.ready()
                // letta-mobile-8xxzv: keyed abort — a cancel for THIS conversation
                // must be addressed to THIS conversation's runtime scope.
                handle.turnEngine?.abort(
                    agentId = turn.agentId,
                    conversationId = turn.conversationId,
                    runId = turn.runId.takeUnless { it.isIrohSyntheticRunId() },
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Telemetry.event(
                    "IrohTransport", "cancel.abort_failed",
                    "turnId" to turn.turnId,
                    "error" to (error.message ?: error.toString()),
                    "class" to error::class.simpleName,
                )
            }
            // 2. Bounded wait for the server terminal to flow through the normal
            //    streaming path (emitTurnFrame claims the single terminal).
            val serverTerminalStatus = withTimeoutOrNull(serverTerminalWaitMs.milliseconds) {
                turn.terminalReached.await()
            }
            // 3. Fallback: only if the server never produced a terminal, synthesize
            //    a cancelled one — routed through the SAME guard so exactly one
            //    terminal is ever emitted for the turn.
            if (serverTerminalStatus == null && !turn.hasTerminal) {
                if (turnRegistry.publishTerminal(IrohTerminalPublication(turn, "cancelled", "cancel_synthetic"))) {
                    Telemetry.event(
                        "IrohTransport", "cancel.synthetic_terminal",
                        "turnId" to turn.turnId,
                        "runId" to turn.runId,
                    )
                    val cancelFrame = ServerFrame.TurnDone(
                        id = frameId("cancelled"),
                        ts = nowIso(),
                        turnId = turn.turnId,
                        runId = turn.runId,
                        status = "cancelled",
                    )
                    emitBoth(cancelFrame)
                }
            }
            // 4. Terminal settled — tear down THIS conversation's streaming job
            //    only. Keyed removal: another conversation's in-flight job is
            //    structurally unreachable from here.
            turn.job?.cancel()
            turn.job?.let { turnRegistry.unregisterSendJob(IrohSendJobRegistration(IrohConversationKey(conversationId), it)) }
            if (!turnRegistry.finish(turn.token)) {
                Telemetry.event(
                    "IrohTransport", "cancel.turn_already_replaced",
                    "conversationId" to conversationId,
                    "turnId" to turn.turnId,
                    "currentTurnId" to (turnRegistry.getActiveTurn(IrohConversationKey(conversationId))?.turnId ?: ""),
                )
            }
        }
        return true
    }
    override fun bye(): Boolean = true
    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Failed
    override fun subscribe(runId: String, cursor: Long): Boolean = false

    override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse =
        adminRpcExecutor.execute(AdminRpcRequest(method, path, body))

    override suspend fun disconnect() {
        connectionGeneration.incrementAndGet()
        turnRegistry.clear()
        adminRpcExecutor.clear()
        observerIngestor.reset()
        observerIngestor.stop("disconnect")
        livenessProbe.stop("disconnect")
        supervisor.disconnect("disconnect")
        _state.value = ChannelTransportState.Disconnected(1000, "disconnected")
    }

    private fun IrohConnectionState.toChannelTransportState(): ChannelTransportState = when (this) {
        IrohConnectionState.Disconnected -> ChannelTransportState.Idle
        IrohConnectionState.Dialing,
        IrohConnectionState.Handshaking -> ChannelTransportState.Connecting()
        is IrohConnectionState.Ready -> ChannelTransportState.Connected(
            serverId = "iroh-app-server",
            sessionId = handle.sessionId,
            deviceId = handle.config.deviceId,
            a2uiEnabled = false,
            a2uiCatalog = null,
            canonicalLiveTransport = "iroh",
        )
        is IrohConnectionState.Degraded -> ChannelTransportState.Disconnected(0, reason, willReconnect = true)
        is IrohConnectionState.AuthFailed -> ChannelTransportState.Disconnected(4001, reason, isAuthFailure = true, willReconnect = false)
        IrohConnectionState.Closed -> ChannelTransportState.Disconnected(1000, "closed")
    }

    override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long): ServerFrame.CronListResponse =
        cronRpcClient.sendCronList(agentId, conversationId, timeoutMs)

    override suspend fun sendCronAdd(agentId: String, name: String, description: String, prompt: String, recurring: Boolean, cron: String?, every: String?, at: String?, timezone: String?, conversationId: String?, timeoutMs: Long): ServerFrame.CronAddResponse =
        cronRpcClient.sendCronAdd(
            CronAddRequest(agentId, name, description, prompt, recurring, cron, every, at, timezone, conversationId, timeoutMs),
        )

    override suspend fun sendCronGet(taskId: String, timeoutMs: Long): ServerFrame.CronGetResponse =
        cronRpcClient.sendCronGet(taskId, timeoutMs)

    override suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse =
        cronRpcClient.sendCronDelete(taskId, timeoutMs)

    override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long): ServerFrame.CronDeleteAllResponse =
        cronRpcClient.sendCronDeleteAll(agentId, timeoutMs)

    override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long): ServerFrame.SubagentListResponse =
        subagentRpcClient.sendSubagentList(all, timeoutMs)

    override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long): ServerFrame.SubagentTodosResponse =
        subagentRpcClient.sendSubagentTodos(toolCallId, timeoutMs)

    private fun frameId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    private fun nowIso(): String = java.time.Instant.now().toString()

    companion object {
        const val IROH_URL_PREFIX = "iroh://"
        // letta-mobile-m6oa1.3: informational `reason` vocabulary on the
        // SubagentsUpdated push. Mirrors the shim's (§13.4) `started` / `completed`
        // strings the repository fold treats as informational (it keys terminal
        // detection off `subagent.status`, not this reason).
        /** letta-mobile-or40x: frame-ownership path labels for SENSING (c). */
        private const val OWNERSHIP_ENGINE = "engine"
        private const val OWNERSHIP_OBSERVER = "observer"

        internal const val SUBAGENT_REASON_STARTED = "started"
        internal const val SUBAGENT_REASON_COMPLETED = "completed"
        // Bounded window to let the server's own terminal (from abort) arrive
        // before falling back to a synthetic cancelled TurnDone.
        internal const val SERVER_TERMINAL_WAIT_MS = 3_000L
        internal const val SUBAGENT_RPC_CAPABILITY = "subagent_registry_v1"
        // letta-mobile-wxy4s: liveness probe cadence lives on IrohLivenessProbe.
        internal const val LIVENESS_PROBE_INTERVAL_MS = IrohLivenessProbe.INTERVAL_MS
        internal const val LIVENESS_PROBE_TIMEOUT_MS = IrohLivenessProbe.TIMEOUT_MS
        internal const val LIVENESS_PROBE_FAILURES_TO_DECLARE_DEAD = IrohLivenessProbe.FAILURES_TO_DECLARE_DEAD
        // Debug override for local Iroh testing. MUST stay blank in committed
        // code — a non-blank value forces EVERY backend through Iroh regardless
        // of the active config (breaks REST/local-runtime selection). Set it
        // only in a throwaway local build when dialing a hand-run wrapper.
        private const val DEBUG_FORCE_IROH_URL = ""
        fun shouldUseIroh(url: String?): Boolean = DEBUG_FORCE_IROH_URL.isNotBlank() || isIrohUrl(url)

        internal val READ_ONLY_ADMIN_RPC_METHODS = setOf(
            "message.list",
            "message.get",
            "tool_return.get",
            "conversation.list",
            "goal.get",
            "health.check",
            // #822 review: idempotent agent reads issued right after connect
            // (chat-screen load + conversation-list name resolution). Retrying
            // these on a closed/timed-out connection over the stream-per-request
            // (chunk-capable) path is safe — unlike the legacy control fallback,
            // which they must stay OFF (see isLegacyFallbackSafeAdminRpcMethod).
            "agent.get",
            "agent.list",
            "agent.count",
            "agent.context",
            "subagent.list",
            "subagent.todos",
            "schedule.get",
            "schedule.list",
            "skill.list",
            "skill.list_agent",
            "slash_command.list",
            "slash_command.list_agent",
            "tool.get",
            "tool.list",
            "block.get",
            "block.list",
            "block.list_agent",
            "project.beadsRemoteStatus",
            "project.get",
            "project.list",
        )

        /**
         * Handles bare `iroh://`, `https://iroh://` (corrupted saved config), etc.
         *
         * letta-mobile-lgns8.10.4.1: delegates to the commonMain
         * [com.letta.mobile.data.model.isIrohBackendUrl] so there is exactly ONE
         * implementation of this classification. Four independent copies used to
         * exist (here, ShimBackendDetector, ChatSendCoordinator,
         * AdminChatViewModel) and they were free to drift apart.
         */
        fun isIrohUrl(url: String?): Boolean =
            com.letta.mobile.data.model.isIrohBackendUrl(url)

        /**
         * Strips transport-scheme noise off an iroh backend URL and returns the bare
         * dialable address/ticket. Accepts the same corrupted-config forms
         * [isIrohUrl] accepts (`https://iroh://…`, `http://iroh://…`, leading
         * whitespace) so classification and normalization can never disagree.
         */
        fun normalizeIrohAddress(url: String): String =
            url.trimStart()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix(IROH_URL_PREFIX)
                .trim()
    }
}

/**
 * A client-synthesized run id placeholder used before the server's real run id
 * has streamed. This TRANSPORT-LOCAL predicate governs abort/promote behavior
 * and is INTENTIONALLY kept narrow (`iroh-run-` only), separate from the shared
 * reconcile/stream-reduction classifier ([isIrohSyntheticRunId] in
 * TimelineStreamReducer.kt).
 *
 * letta-mobile-j98r5.1: it is only ever evaluated against an [ActiveTurn] run
 * id, which is always born `iroh-run-${UUID}` in `send()` and only ever
 * promoted to a REAL server run id — never to an observer id. The observer
 * placeholder `iroh-observer-run-*` is stamped solely in the passive projection
 * path (`ingestObserverFrame`, which is skipped while a turn is engine-owned)
 * and is emitted straight to the timeline; it never enters an ActiveTurn nor
 * this predicate. Broadening it here would be dead code and would risk coupling
 * observer classification to transport abort timing, so it stays separate.
 */
internal fun String.isIrohSyntheticRunId(): Boolean = startsWith("iroh-run-")
