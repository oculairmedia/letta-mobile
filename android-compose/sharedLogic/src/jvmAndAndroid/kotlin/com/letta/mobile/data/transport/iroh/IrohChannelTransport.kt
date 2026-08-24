package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.a2ui.A2uiAction
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
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.TurnInput
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
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
import com.letta.mobile.data.transport.iroh.IrohTransportSupport.string

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
            "messageId" to IrohTransportSupport.frameMessageId(frame),
            "conversationId" to IrohTransportSupport.frameConversationId(frame),
        )
        IrohTransportSupport.frameFlowContent(frame)?.let { (key, type, content) ->
            IrohFrameFlowDiagnostics.record("gate1.emit", key, type, content)
        }
        framePublisher.publish(frame)
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

    override fun hasActiveChatTurn(conversationId: String): Boolean =
        turnRegistry.hasActiveTurn(IrohConversationId(conversationId))

    override val hasAnyActiveChatTurn: Boolean
        get() = turnRegistry.hasAnyActiveTurn

    internal data class ActiveTurnSnapshot(
        val turnId: String,
        val runId: String,
        val hasTerminal: Boolean,
        val isTerminalCompleted: Boolean,
    )

    internal fun activeTurnSnapshot(conversationId: String): ActiveTurnSnapshot? {
        val s = turnRegistry.snapshotForTest(IrohConversationId(conversationId)) ?: return null
        return ActiveTurnSnapshot(
            turnId = s.turnId.value,
            runId = s.runId.value,
            hasTerminal = s.hasTerminal,
            isTerminalCompleted = s.isTerminalCompleted,
        )
    }

    internal fun activeSendJob(conversationId: String): Job? = turnRegistry.getSendJob(IrohConversationId(conversationId))
    internal fun activeTurnsCount(): Int = turnRegistry.activeTurnsCount()
    internal fun activeSendJobsCount(): Int = turnRegistry.activeSendJobsCount()

    /**
     * letta-mobile-m6oa1.1: the Kotlin App Server's own Agent-tool_call
     * correlation reducer — the Kotlin analogue of the shim's
     * `ingestParentFrame`. Fed additively from [ingestObserverFrame] as the
     * observer path decodes the parent run's frames. STRICTLY dispatch +
     * return correlation; identity-from-body (m6oa1.3) and lifecycle/terminal
     * nuance (m6oa1.4) are out of scope. Confined to the single-threaded
     * observer collector, so the reducer's non-synchronized map is safe.
     */
    private val subagentCorrelator = SubagentCorrelator()

    /**
     * letta-mobile-m6oa1.3: the correlator revision last PUBLISHED as a
     * [ServerFrame.SubagentsUpdated]. Emission is gated on this so idempotent
     * re-observes (which the pure reducer already no-ops on, leaving
     * [SubagentCorrelator.revision] unchanged) do NOT spam the event flow with
     * duplicate snapshots. Only advanced by the single-threaded observer
     * collector, so a plain field is safe (same confinement as the reducer).
     */
    private var lastEmittedSubagentRevision: Long = 0L

    private var explicitConfig: IrohConnectConfig? = null
    // Explicit type: this field and `livenessProbe` reference each other through
    // their lambdas, which defeats type inference.
    private val supervisor: IrohConnectionSupervisor = IrohConnectionSupervisor(
        scope = scope,
        configProvider = { explicitConfig ?: activeConfigProvider() },
        dialer = { config -> testDialer?.invoke(config) ?: dial(config) },
        onStateChanged = ::handleSupervisorStateChange,
    )

    private fun handleSupervisorStateChange(state: IrohConnectionState) {
        _state.value = state.toChannelTransportState()
        if (state is IrohConnectionState.Ready) {
            notifyRedialIfTurnActive()
            connectionSession.onReady(state.handle)
            livenessProbe.start(state.handle)
        } else {
            connectionSession.onNotReady()
            updateInterruptedTurns(state)
            livenessProbe.stop("state:${state::class.simpleName}")
        }
    }

    private fun updateInterruptedTurns(state: IrohConnectionState) {
        when (state) {
            is IrohConnectionState.Degraded -> {
                if (state.reason == "config_changed") turnRegistry.clearInterruptedTurns()
                else turnRegistry.rememberInterruptedTurns()
            }
            else -> Unit
        }
    }

    // letta-mobile-53k65.10: Generation-scoped Admin RPC executor and retry state.
    private val adminRpcExecutor = IrohAdminRpcExecutor(
        supervisor = supervisor,
        connectionGeneration = ::currentConnectionGeneration,
        recordViewedConversation = { method, path ->
            if (method == "message.list") {
                IrohViewedConversation.fromMessageListPath(path)?.let(connectionSession::recordViewedConversation)
            }
        },
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
        // Attribution is MANDATORY (r3i1z): an unattributed loss report landing
        // after a redial destroys the healthy NEW handle.
        reportConnectionLost = { reason, handle -> supervisor.onConnectionLostAsync(reason, handle) },
    )

    /** Test/wiring visibility: is the liveness probe currently armed? */
    internal val isLivenessProbeArmed: Boolean get() = livenessProbe.isArmed

    override fun probeNow() = livenessProbe.probeNow()


    // letta-mobile-r3i1z: OBSERVER INGESTION.
    //
    // Every fanned-out frame for a conversation this client is a registered viewer
    // of already ARRIVES on the transport's stream channel (IrohAppServerTransport
    // .streamFrames == streamFrameFlow). But nothing consumed that flow unless a
    // LOCAL turn's engine.runTurn was active — so frames for turns this client did
    // NOT initiate were dropped and a passive observer rendered nothing. This
    // long-lived collector fixes that: while connected it continuously ingests
    // stream_delta frames into the SAME _events/_frameEvents seam the initiator
    // uses, so observer frames reduce identically.
    private val observerMapper = AppServerRuntimeEventMapper()

    // Own generation-bound observer and viewer re-subscription work in a typed
    // session so stale handles cannot mutate a successor connection.
    private val connectionSession = IrohConnectionSession(
        scope = scope,
        ingestObserverFrame = { received ->
            observerIngestor.ingestObserverFrame(ObserverFrameRequest(received))
        },
        resubscribe = { conversation ->
            adminRpc(method = "message.list", path = conversation.messageListPath, body = null)
        },
    )

    private val observerIngestor: IrohObserverIngestor by lazy {
        IrohObserverIngestor(
            scope = scope,
            turnRegistry = turnRegistry,
            connectionGeneration = ::currentConnectionGeneration,
            emitBoth = ::emitBoth,
            adminRpc = { method, path, body -> adminRpc(method, path, body) },
            recordFrameOwnership = ::recordFrameOwnership,
        )
    }

    private fun currentConnectionGeneration(): Long = connectionSession.currentGeneration()

    private val turnDispatcher = IrohTurnDispatcher(
        IrohTurnDispatcherDependencies(
            scope = scope,
            registry = turnRegistry,
            ready = supervisor::ready,
            emitTurnFrame = ::emitTurnFrame,
            emitDraft = ::emitDraft,
            emitBoth = ::emitBoth,
            currentGeneration = connectionSession::currentGeneration,
        ),
    )

    /**
     * Ingest ONE fanned-out stream frame the observer path owns.
     *
     * DUAL-INGEST GUARD (letta-mobile-h30cy hazard): the engine's runTurn ALSO
     * collects this exact SharedFlow (via client.events = merge(control, stream))
     * while a local turn is active — both collectors therefore see every frame.
     * To keep exactly ONE consumer per frame, the observer collector SKIPS any
     * frame whose conversation has a live local turn: the engine OWNS frames for
     * its own conversation while that conversation's turn runs. The observer OWNS
     * a frame only when NO local turn is active for that frame's conversation.
     *
     * letta-mobile-or40x — THE INVARIANT IS PER CONVERSATION. Ownership is decided
     * by looking up the frame's own conversation_id in [turnRegistry], keyed
     * by conversationId. It is therefore airtight per conversation: for a given
     * conversation a frame is engine-owned XOR observer-owned (no overlap), every
     * stream_delta is owned by exactly one side (no gap), and — critically — that
     * answer CANNOT change mid-stream because of activity on some OTHER
     * conversation. Before or40x this compared against a single process-wide
     * `activeTurn`, so starting a turn on conversation B evicted conversation A
     * and silently flipped A's still-streaming frames from engine-owned to
     * observer-owned (double-emitting them). Any residual flip is now reported
     * via `ingest.ownership_switched` rather than absorbed.
     */
    private suspend fun ingestObserverFrame(received: AppServerReceivedFrame) {
        val streamDelta = received.frame as? AppServerInboundFrame.StreamDelta ?: return
        val engineScope = engineOwnedProjectionScope(streamDelta)
        if (engineScope != null) {
            recordEngineOwnedObserverFrame(engineScope, received)
            return
        }
        ingestPassiveObserverFrame(streamDelta, received)
    }

    private suspend fun recordEngineOwnedObserverFrame(
        scope: ObserverProjectionScope,
        received: AppServerReceivedFrame,
    ) {
        Telemetry.event(
            "IrohObserver", "ingest.skip_engine_owned",
            "conversationId" to scope.conversationId,
            "turnId" to scope.localTurn.turnId,
        )
        projectEngineOwnedObserverDelta(scope, received)
    }

    private suspend fun ingestPassiveObserverFrame(
        streamDelta: AppServerInboundFrame.StreamDelta,
        received: AppServerReceivedFrame,
    ) {
        val conversationId = streamDelta.runtime.conversationId
        val agentId = streamDelta.runtime.agentId
        if (isRetiredObserverFrame(streamDelta, conversationId)) return
        correlateAgentFrame(streamDelta).forEach { emitBoth(it) }
        emitObserverProjection(streamDelta, received, agentId, conversationId)
    }

    private fun isRetiredObserverFrame(
        streamDelta: AppServerInboundFrame.StreamDelta,
        conversationId: String,
    ): Boolean {
        val delta = streamDelta.delta as? JsonObject
        val runId = delta?.string("run_id") ?: delta?.string("runId")
        if (runId == null || !turnRegistry.isRetiredRun(IrohRunId(runId))) return false
        Telemetry.event(
            "IrohObserver", "ingest.skip_already_retired",
            "conversationId" to conversationId,
            "runId" to runId,
        )
        return true
    }

    private suspend fun emitObserverProjection(
        streamDelta: AppServerInboundFrame.StreamDelta,
        received: AppServerReceivedFrame,
        agentId: String,
        conversationId: String,
    ) {
        val command = IrohTransportSupport.observerTurnCommand(agentId, conversationId)
        observerMapper.map(command, received).forEach { draft ->
            val frames = payloadToServerFrames(
                payload = draft.payload,
                agentId = draft.agentId?.value ?: agentId,
                conversationId = draft.conversationId?.value ?: conversationId,
                turnId = "iroh-observer-turn-$conversationId",
                runId = draft.runId?.value ?: "iroh-observer-run-$conversationId",
            )
            frames.forEach { emitBoth(it) }
        }
    }

    private fun engineOwnedProjectionScope(
        streamDelta: AppServerInboundFrame.StreamDelta,
    ): ObserverProjectionScope? {
        val conversationId = streamDelta.runtime.conversationId
        val localTurn = turnRegistry.getActiveTurn(IrohConversationId(conversationId))
        recordFrameOwnership(conversationId, localTurn)
        return localTurn?.let {
            ObserverProjectionScope(streamDelta.runtime.agentId, conversationId, it)
        }
    }

    /**
     * letta-mobile-dir4k: When the observer sees a frame for a conversation
     * whose local turn is still active, the engine path already owns it. The
     * observer must not re-emit it. But the projection is still worth running
     * in one specific case: if the projection carries a `TurnDone` for the
     * LOCAL turn id, the engine path's terminal `emitTurnFrame` will not run
     * (race / engine collect already returned / frame was dropped) and we must
     * retire the `ActiveTurn` ourselves — otherwise the composer keeps
     * showing "Thinking…" indefinitely. The engine owns the emit slot (see
     * [emitTurnFrame]'s exactly-once guard), so we retire using
     * [retireActiveTurn] without re-emitting the frame. Anything else is
     * engine-owned and we drop it as before.
     */
    private suspend fun projectEngineOwnedObserverDelta(
        scope: ObserverProjectionScope,
        received: AppServerReceivedFrame,
    ) {
        val command = IrohTransportSupport.observerTurnCommand(scope.agentId, scope.conversationId)
        val projectedFrames = observerMapper.map(command, received).flatMap { draft ->
            payloadToServerFrames(
                payload = draft.payload,
                agentId = draft.agentId?.value ?: scope.agentId,
                conversationId = draft.conversationId?.value ?: scope.conversationId,
                turnId = scope.localTurn.turnId,
                runId = draft.runId?.value ?: scope.localTurn.runId,
            )
        }
        val terminal = projectedFrames.firstOrNull { it is ServerFrame.TurnDone }
        if (terminal is ServerFrame.TurnDone) {
            emitTerminalFrame(scope.localTurn, terminal, IrohTerminalSource.Observer)
        }
    }

    /**
     * letta-mobile-dir4k: bundle the local conversation context that drives an
     * observer-side projection. Keeps [projectEngineOwnedObserverDelta]'s arg
     * count under the CodeScene "max 4 function args" threshold so the
     * extraction stays the kind of helper a reviewer approves on first read.
     */
    private data class ObserverProjectionScope(
        val agentId: String,
        val conversationId: String,
        val localTurn: IrohActiveTurn,
    )

    /**
     * letta-mobile-m6oa1.1 / m6oa1.3: decode ONE observer StreamDelta and, when
     * it is a parent `Agent` tool_call dispatch or its matching tool_return,
     * feed the [subagentCorrelator]. All other frames are ignored. Parsing is
     * defensive (the whole body is wrapped in [runCatching]) so the correlation
     * tap can never disturb the projection path — on any failure it returns an
     * empty list and the projection continues unaffected.
     *
     * m6oa1.3 (consumer wiring): this is where the previously WRITE-ONLY
     * correlator becomes OBSERVABLE. After mutating the reducer, if the
     * reducer's observable state advanced ([SubagentCorrelator.revision] moved
     * past [lastEmittedSubagentRevision]), it RETURNS a
     * [ServerFrame.SubagentsUpdated] carrying the changed [SubagentEntry], a
     * fresh full snapshot, and the informational [reason] (`started` on
     * dispatch, `completed` on return) — matching the exact frame shape the
     * repository's `observePushEvents` fold already consumes via
     * `mergeSnapshot(frame.subagentsActive, terminal = frame.subagent)`. It does
     * NOT emit itself: the pure reducer decides WHAT to publish; the suspend
     * caller [ingestObserverFrame] performs the [emitBoth]. When nothing
     * observable changed (idempotent re-observe, unknown-id return, non-Agent
     * tool_call) it returns an empty list — revision-gating suppresses any
     * duplicate push.
     *
     * Frame shapes (mirrors [AppServerTurnEngine.extractToolCallId] / the
     * mapper): the tool_call_id is `delta.tool_call.tool_call_id` (dispatch) or
     * `delta.tool_call_id` (return); the tool name is `delta.tool_call.name`;
     * the arguments are `delta.tool_call.arguments`; the parent runId is
     * `delta.run_id`.
     */
    private fun correlateAgentFrame(
        streamDelta: AppServerInboundFrame.StreamDelta,
    ): List<ServerFrame> = runCatching {
        val delta = streamDelta.delta as? JsonObject ?: return@runCatching emptyList()
        val messageType = delta.string("message_type") ?: return@runCatching emptyList()
        val toolCall = delta["tool_call"]?.jsonObject
        val parent = ParentContext(
            agentId = streamDelta.runtime.agentId,
            conversationId = streamDelta.runtime.conversationId,
            runId = delta.string("run_id"),
        )
        val changedToolCallId: String
        val reason: String
        when (messageType) {
            "tool_call_message", "approval_request_message" -> {
                // Only the parent `Agent` dispatch is in scope.
                val name = toolCall?.string("name") ?: return@runCatching emptyList()
                if (name != "Agent") return@runCatching emptyList()
                val toolCallId = toolCall.string("tool_call_id")
                    ?: delta.string("tool_call_id") ?: return@runCatching emptyList()
                val arguments = toolCall["arguments"]?.toString()
                    ?: delta["arguments"]?.toString()
                subagentCorrelator.onAgentDispatch(toolCallId, arguments, parent)
                changedToolCallId = toolCallId
                reason = SUBAGENT_REASON_STARTED
            }
            "tool_return_message" -> {
                // Returns don't carry the tool name; correlate purely by id.
                // onAgentReturn ignores ids it never recorded as an Agent
                // dispatch, so passing every return id here is safe — a
                // non-Agent tool's return simply no-ops (revision unchanged).
                val toolCallId = toolCall?.string("tool_call_id")
                    ?: delta.string("tool_call_id") ?: return@runCatching emptyList()
                subagentCorrelator.onAgentReturn(toolCallId, parent)
                changedToolCallId = toolCallId
                reason = SUBAGENT_REASON_COMPLETED
            }
            else -> return@runCatching emptyList()
        }
        buildSubagentsUpdatedIfChanged(changedToolCallId, reason)
    }.getOrElse { emptyList() }

    /**
     * letta-mobile-m6oa1.3: REVISION-GATED projection of the correlator into a
     * [ServerFrame.SubagentsUpdated]. Returns the frame only when the reducer's
     * [SubagentCorrelator.revision] advanced past the last published revision —
     * so an idempotent re-observe (which leaves the revision untouched) yields
     * NO frame and never spams the flow. The changed entry is looked up from
     * the fresh snapshot by [changedToolCallId]; the snapshot is the same list
     * the shim-shaped frame carried, so the repository fold reduces identically.
     */
    private fun buildSubagentsUpdatedIfChanged(
        changedToolCallId: String,
        reason: String,
    ): List<ServerFrame> {
        val revision = subagentCorrelator.revision
        if (revision == lastEmittedSubagentRevision) return emptyList()
        lastEmittedSubagentRevision = revision
        val snapshot = subagentCorrelator.snapshot()
        val changed = snapshot.firstOrNull { it.toolCallId == changedToolCallId }
        val nowIso = IrohTransportSupport.nowIso()
        return listOf(
            ServerFrame.SubagentsUpdated(
                id = IrohTransportSupport.frameId("subagents_updated"),
                ts = nowIso,
                reason = reason,
                subagent = changed,
                subagentsActive = snapshot,
                at = nowIso,
            ),
        )
    }

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
                turnRegistry.removeInterruptedTurn(IrohConversationId(recovery.conversationId), recovery)
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

    private fun recordFrameOwnership(conversationId: String, localTurn: IrohActiveTurn?) {
        val result = turnRegistry.recordFrameOwnership(IrohConversationId(conversationId), localTurn)
        if (result is IrohTurnRegistry.FrameOwnershipResult.Switched) {
            Telemetry.event(
                "IrohObserver", "ingest.ownership_switched",
                "conversationId" to conversationId,
                "from" to result.from,
                "to" to result.to,
                "turnId" to (localTurn?.turnId ?: ""),
                "otherActiveConversations" to IrohTransportSupport.otherActiveConversationsLabel(turnRegistry, conversationId),
            )
        }
    }

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

    private suspend fun dial(config: IrohConnectConfig): IrohConnectionHandle {
        val effectiveUrl = forcedIrohUrl.takeIf { it.isNotBlank() }
            ?: DEBUG_FORCE_IROH_URL.takeIf { it.isNotBlank() }
            ?: config.baseShimUrl
        if (!isIrohUrl(effectiveUrl)) {
            error("IrohChannelTransport requires backend URL iroh://<EndpointTicket>.")
        }
        val ticket = normalizeIrohAddress(effectiveUrl).takeIf { it.isNotBlank() }
            ?: error("IrohChannelTransport requires backend URL iroh://<EndpointTicket>.")
        _state.value = ChannelTransportState.Connecting()
        onConnect()
        val secretKey = secretKeyStore.loadOrCreate()
        val localEndpoint = runCatching {
            Endpoint.bind(
                EndpointOptions(relayMode = RelayMode.defaultMode(), secretKey = secretKey)
            )
        }.onFailure { t ->
            Telemetry.event("IrohTransport", "bind.failed", "error" to (t.message ?: t.toString()), "class" to t::class.simpleName)
        }.getOrThrow()
        var transport: IrohAppServerTransport? = null
        // letta-mobile-r3i1z: attribute this connection's loss reports to the
        // handle produced by THIS dial. A dead transport reports loss up to
        // twice (close watcher + reader exit) and the second report can land
        // after the supervisor has already redialed; attribution lets the
        // supervisor drop such stale reports instead of tearing down the
        // healthy redialed connection (and its observer-ingestion collector).
        val dialedHandle = java.util.concurrent.atomic.AtomicReference<IrohConnectionHandle?>(null)
        return runCatching {
            transport = IrohAppServerTransportAdapter(
                endpoint = localEndpoint,
                onConnectionLost = { reason -> supervisor.onConnectionLostAsync(reason, dialedHandle.get()) },
            ).createTransport(
                endpoint = AppServerEndpoint(scheme = "iroh", address = ticket),
                scope = scope,
            ) as IrohAppServerTransport
            val appServerClient = DefaultAppServerClient(transport!!)
            // The auth exchange doubles as the Iroh transport handshake: it
            // advertises client capabilities (frame_part chunked-frame
            // reassembly) so the server may split >1MiB frames instead of
            // failing them. Send it even with a blank token — servers without
            // a required token still ack and record capabilities.
            val auth = appServerClient.auth(
                AppServerCommand.Auth(
                    requestId = "auth-${UUID.randomUUID()}",
                    token = config.token,
                    capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
                ),
            )
            if (!auth.success && config.token.isNotBlank()) {
                throw IrohAuthFailure(auth.error ?: "Iroh auth failed")
            }
            Telemetry.event(
                "IrohTransport", "auth.negotiated",
                "success" to auth.success,
                "serverCapabilities" to (auth.capabilities ?: emptyList()).sorted().joinToString(","),
            )
            // Preflight stays on the Iroh *node* / wrapper turn engine (WS to
            // App Server). Client-side preflight would send agent_retrieve /
            // conversation_messages_list as typed control frames; the node only
            // accepts auth/runtime_start/input/admin_rpc/sync/abort.
            transport!!.awaitConnectionReady()
            val (engine, eventRouter) = buildIrohTurnEngine(
                client = appServerClient,
                clientVersion = config.clientVersion,
                routerScope = scope,
            )
            IrohConnectionHandle(
                config = config,
                ticket = ticket,
                sessionId = ticket.hashCode().toString(),
                transport = transport,
                turnEngine = engine,
                serverCapabilities = auth.capabilities?.toSet(),
                close = { reason ->
                    eventRouter.detach()
                    closeIrohResources(reason, transport, localEndpoint)
                },
            ).also { handle -> dialedHandle.set(handle) }
        }.getOrElse { error ->
            closeIrohResources("dial_failed", transport, localEndpoint)
            throw error
        }
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
        return turnDispatcher.submit(
            IrohTurnSubmission(
                agentId = agentId,
                conversationId = conversationId,
                input = TurnInput.UserMessage(
                    localMessageId = otid ?: IrohTransportSupport.frameId("local"),
                    text = text,
                    contentPartsJson = contentParts?.toString(),
                ),
            ),
        )
    }

    /**
     * Emits a turn frame through the single exactly-one-terminal guard shared by
     * the streaming send job and [cancel]. Only the first [ServerFrame.TurnDone]
     * for a turn is forwarded; the loser is dropped. This holds no matter which
     * side (server terminal or synthetic cancel) reaches the terminal first.
     */
    private suspend fun emitTurnFrame(turn: IrohActiveTurn, frame: ServerFrame) {
        if (frame is ServerFrame.TurnDone) {
            if (!emitTerminalFrame(turn, frame, IrohTerminalSource.Engine)) {
                Telemetry.event(
                    "IrohTrace", "transport.turn_done.duplicate_skipped",
                    "turnId" to turn.turnId,
                    "runId" to frame.runId,
                    "status" to frame.status,
                )
            }
            return
        }
        emitBoth(frame)
    }

    /**
     * Claims terminal ownership first, then publishes and retires as one
     * non-cancellable operation. Registry inactivity therefore means the winner's
     * frame is already observable, rather than merely reserved for later emission.
     */
    private suspend fun emitTerminalFrame(
        turn: IrohActiveTurn,
        frame: ServerFrame.TurnDone,
        source: IrohTerminalSource,
    ): Boolean {
        val publication = IrohTerminalPublication(
            turn = turn,
            status = IrohTerminalStatus(frame.status),
            source = source,
        )
        if (!turnRegistry.claimTerminal(publication)) return false
        emitClaimedTerminal(publication, frame)
        return true
    }

    private suspend fun emitClaimedTerminal(
        publication: IrohTerminalPublication,
        frame: ServerFrame.TurnDone,
    ) = withContext(NonCancellable) {
        emitBoth(frame)
        turnRegistry.retireClaimed(publication)
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
        val promoted = realRunId != null && turnRegistry.promoteRunId(
            IrohRunPromotion(turn.token, IrohRunId(realRunId)),
        )
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
                    id = IrohTransportSupport.frameId("turn_started"),
                    ts = IrohTransportSupport.nowIso(),
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

    /**
     * letta-mobile-or40x: cancel HONORS ITS ARGUMENT. Only [conversationId]'s own
     * turn and send job are touched. The pre-or40x implementation was keyed in
     * name only — it read the single global `activeTurn`/`activeSendJob` and so
     * aborted and cancelled whichever conversation happened to occupy the slot.
     * That is the reported "cancelling one conversation froze the other".
     */
    override fun cancel(conversationId: String): Boolean {
        val turn = turnRegistry.getActiveTurn(IrohConversationId(conversationId))
        if (turn == null) return cancelWithoutActiveTurn(conversationId)
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
            if (turnRegistry.getActiveTurn(IrohConversationId(conversationId)) !== turn) return@launch
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
            if (serverTerminalStatus == null) {
                val cancelFrame = ServerFrame.TurnDone(
                    id = IrohTransportSupport.frameId("cancelled"),
                    ts = IrohTransportSupport.nowIso(),
                    turnId = turn.turnId,
                    runId = turn.runId,
                    status = "cancelled",
                )
                if (emitTerminalFrame(turn, cancelFrame, IrohTerminalSource.CancelSynthetic)) {
                    Telemetry.event(
                        "IrohTransport", "cancel.synthetic_terminal",
                        "turnId" to turn.turnId,
                        "runId" to turn.runId,
                    )
                }
            }
            // 4. Terminal settled — tear down THIS conversation's streaming job
            //    only. Keyed removal: another conversation's in-flight job is
            //    structurally unreachable from here.
            turn.job?.cancel()
            turn.job?.let { turnRegistry.unregisterSendJob(IrohSendJobRegistration(IrohConversationId(conversationId), it)) }
            if (!turnRegistry.finish(turn.token)) {
                Telemetry.event(
                    "IrohTransport", "cancel.turn_already_replaced",
                    "conversationId" to conversationId,
                    "turnId" to turn.turnId,
                    "currentTurnId" to (turnRegistry.getActiveTurn(IrohConversationId(conversationId))?.turnId ?: ""),
                )
            }
        }
        return true
    }

    private fun cancelWithoutActiveTurn(conversationId: String): Boolean {
        turnRegistry.removeInterruptedTurn(IrohConversationId(conversationId))
        Telemetry.event(
            "IrohTransport", "cancel.no_active_turn",
            "conversationId" to conversationId,
            "otherActiveConversations" to IrohTransportSupport.otherActiveConversationsLabel(turnRegistry, conversationId),
        )
        turnRegistry.removeSendJob(IrohConversationId(conversationId))?.cancel()
        return false
    }

    override fun bye(): Boolean = true
    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Failed
    override fun subscribe(runId: String, cursor: Long): Boolean = false

    override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse =
        adminRpcExecutor.execute(method, path, body)

    override suspend fun disconnect() {
        connectionSession.stopAndJoin()
        // Claim terminal ownership before cancellation: a cancelled send job can
        // synchronously remove its turn from the registry in its completion handler.
        // Claiming first makes disconnect the deterministic terminal winner.
        val disconnectOwnedTurns = turnRegistry.claimDisconnectTerminals()
        turnRegistry.cancelSendJobs()
        disconnectOwnedTurns.forEach { turn ->
            val terminal = ServerFrame.TurnDone(
                id = IrohTransportSupport.frameId("cancelled"),
                ts = IrohTransportSupport.nowIso(),
                turnId = turn.turnId,
                runId = turn.runId,
                status = "cancelled",
            )
            emitClaimedTerminal(
                IrohTerminalPublication(
                    turn = turn,
                    status = IrohTerminalStatus(terminal.status),
                    source = IrohTerminalSource.Disconnect,
                ),
                terminal,
            )
        }
        turnRegistry.clear()
        adminRpcExecutor.clear()
        subagentCorrelator.reset()
        lastEmittedSubagentRevision = 0L
        livenessProbe.stop("disconnect")
        supervisor.disconnect("disconnect")
        _state.value = ChannelTransportState.Disconnected(1000, "disconnected")
    }

    private suspend fun closeIrohResources(reason: String, transport: IrohAppServerTransport?, endpoint: Endpoint?) {
        Telemetry.event(
            "IrohTrace", "transport.closeCurrent",
            "reason" to reason,
            "hasTransport" to (transport != null),
            "hasEndpoint" to (endpoint != null),
        )
        // letta-mobile-or40x: a full teardown legitimately cancels EVERY
        // conversation's turn — the connection those turns stream over is gone.
        // Do it explicitly over all keyed entries (not via one global slot), and
        // report each nonterminal casualty (SENSING b) so a teardown that eats an
        // in-flight turn is never silent again.
        turnRegistry.allSendJobEntries().forEach { registration ->
            val conversationId = registration.conversationId
            val job = turnRegistry.removeSendJob(conversationId) ?: return@forEach
            val turn = turnRegistry.getActiveTurn(conversationId)
            if (turn != null && !turn.hasTerminal) {
                Telemetry.event(
                    "IrohTransport", "turn.torn_down_nonterminal",
                    "reason" to reason,
                    "conversationId" to conversationId.value,
                    "turnId" to turn.turnId,
                    "runId" to turn.runId,
                )
            }
            runCatching { job.cancel() }
        }
        runCatching { transport?.close() }
        runCatching { endpoint?.shutdown() }
        runCatching { endpoint?.close() }
    }

    /**
     * lgns8.22.3: one inbound collector per dial generation; turns subscribe via
     * fanout instead of collecting [AppServerClient.events] directly.
     */
    private fun buildIrohTurnEngine(
        client: DefaultAppServerClient,
        clientVersion: String,
        routerScope: CoroutineScope,
    ): Pair<AppServerTurnEngine, AppServerRuntimeEventRouter> {
        val eventRouter = AppServerRuntimeEventRouter()
        eventRouter.attach(routerScope, client.events)
        val engine = AppServerTurnEngine(
            client = client,
            clientInfo = AppServerRuntimeStartClientInfo(
                name = "letta-mobile-android-iroh",
                version = clientVersion,
            ),
            permissionMode = AppServerPermissionMode.Unrestricted,
            turnContextPreflight = TurnContextPreflight.None,
            eventRouter = eventRouter,
        )
        return engine to eventRouter
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

    // lgns8: cron scheduling over admin_rpc. The native CronAdminHandlers already
    // serve cron.list/add/get/delete/delete_all against the live App Server; the
    // Iroh transport just bridges the IChannelTransport surface onto those methods
    // (dispatch is by method name — CRON_ADMIN_PATH is a stable cosmetic hint,
    // cron is native-only with no proxy fallback). Field mapping mirrors the native
    // AppServerCommand.CronAdd contract: a one-off `at` maps to `scheduled_for`;
    // there is no native interval (`every`) field, so an every-only add reaches the
    // handler without a `cron` and is rejected there with a clear error rather than
    // silently dropped.
    override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long): ServerFrame.CronListResponse {
        val requestId = "iroh-cron-list-${UUID.randomUUID()}"
        return cronInvoke(
            op = "cron.list",
            requestId = requestId,
            timeoutMs = timeoutMs,
            body = buildJsonObject {
                agentId?.let { put("agent_id", it) }
                conversationId?.let { put("conversation_id", it) }
            },
            mapSuccess = { result ->
                val decoded = subagentJson.decodeFromJsonElement<CronListRpcResult>(result)
                ServerFrame.CronListResponse(id = IrohTransportSupport.frameId("cron_list"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = true, tasks = decoded.tasks)
            },
            onFailure = ::cronListFailure,
        )
    }

    override suspend fun sendCronAdd(agentId: String, name: String, description: String, prompt: String, recurring: Boolean, cron: String?, every: String?, at: String?, timezone: String?, conversationId: String?, timeoutMs: Long): ServerFrame.CronAddResponse {
        val requestId = "iroh-cron-add-${UUID.randomUUID()}"
        return cronInvoke(
            op = "cron.add",
            requestId = requestId,
            timeoutMs = timeoutMs,
            body = buildJsonObject {
                put("agent_id", agentId)
                put("name", name)
                put("description", description)
                put("prompt", prompt)
                put("recurring", recurring)
                cron?.let { put("cron", it) }
                timezone?.let { put("timezone", it) }
                conversationId?.let { put("conversation_id", it) }
                // Native contract has no `every`; a one-off time is `scheduled_for`.
                at?.let { put("scheduled_for", it) }
            },
            mapSuccess = { result ->
                val decoded = subagentJson.decodeFromJsonElement<CronMutationRpcResult>(result)
                ServerFrame.CronAddResponse(id = IrohTransportSupport.frameId("cron_add"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = true, task = decoded.task, warning = decoded.warning)
            },
            onFailure = ::cronAddFailure,
        )
    }

    override suspend fun sendCronGet(taskId: String, timeoutMs: Long): ServerFrame.CronGetResponse {
        val requestId = "iroh-cron-get-${UUID.randomUUID()}"
        return cronInvoke(
            op = "cron.get",
            requestId = requestId,
            timeoutMs = timeoutMs,
            body = buildJsonObject { put("task_id", taskId) },
            mapSuccess = { result ->
                val decoded = subagentJson.decodeFromJsonElement<CronMutationRpcResult>(result)
                ServerFrame.CronGetResponse(id = IrohTransportSupport.frameId("cron_get"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = true, task = decoded.task)
            },
            onFailure = ::cronGetFailure,
        )
    }

    override suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse {
        val requestId = "iroh-cron-delete-${UUID.randomUUID()}"
        return cronInvoke(
            op = "cron.delete",
            requestId = requestId,
            timeoutMs = timeoutMs,
            body = buildJsonObject { put("task_id", taskId) },
            mapSuccess = { _ ->
                ServerFrame.CronDeleteResponse(id = IrohTransportSupport.frameId("cron_delete"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = true)
            },
            onFailure = ::cronDeleteFailure,
        )
    }

    override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long): ServerFrame.CronDeleteAllResponse {
        val requestId = "iroh-cron-delete-all-${UUID.randomUUID()}"
        return cronInvoke(
            op = "cron.delete_all",
            requestId = requestId,
            timeoutMs = timeoutMs,
            body = buildJsonObject { put("agent_id", agentId) },
            mapSuccess = { result ->
                val decoded = subagentJson.decodeFromJsonElement<CronDeleteAllRpcResult>(result)
                ServerFrame.CronDeleteAllResponse(id = IrohTransportSupport.frameId("cron_delete_all"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = true, count = decoded.deleted)
            },
            onFailure = ::cronDeleteAllFailure,
        )
    }
    override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long): ServerFrame.SubagentListResponse {
        val requestId = "iroh-subagent-list-${UUID.randomUUID()}"
        val scope = currentSubagentScope()
            ?: return subagentListFailure(requestId, "subagent scope unavailable; hydrate a conversation first")
        return invokeScopedRpc(
            requestId = requestId,
            timeoutMs = timeoutMs,
            labels = ScopedRpcLabels(
                unsupported = SUBAGENT_RPC_UNSUPPORTED,
                timedOut = "subagent.list timed out",
                failed = "subagent.list failed",
            ),
            call = {
                callScopedSubagentRpc(
                    method = "subagent.list",
                    scope = scope,
                    body = buildJsonObject { put("all", all) }.toString(),
                )
            },
            mapSuccess = { result ->
                val decoded = subagentJson.decodeFromJsonElement<SubagentListRpcResult>(result)
                ServerFrame.SubagentListResponse(
                    id = IrohTransportSupport.frameId("subagent_list"),
                    ts = IrohTransportSupport.nowIso(),
                    requestId = requestId,
                    success = true,
                    subagents = decoded.subagents,
                )
            },
            onFailure = ::subagentListFailure,
        )
    }

    override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long): ServerFrame.SubagentTodosResponse {
        val requestId = "iroh-subagent-todos-${UUID.randomUUID()}"
        val scope = currentSubagentScope()
            ?: return subagentTodosFailure(requestId, "subagent scope unavailable; hydrate a conversation first")
        return invokeScopedRpc(
            requestId = requestId,
            timeoutMs = timeoutMs,
            labels = ScopedRpcLabels(
                unsupported = SUBAGENT_RPC_UNSUPPORTED,
                timedOut = "subagent.todos timed out",
                failed = "subagent.todos failed",
            ),
            call = {
                callScopedSubagentRpc(
                    method = "subagent.todos",
                    scope = scope,
                    body = buildJsonObject { put("tool_call_id", toolCallId) }.toString(),
                )
            },
            mapSuccess = { result ->
                val decoded = subagentJson.decodeFromJsonElement<SubagentTodosRpcResult>(result)
                ServerFrame.SubagentTodosResponse(
                    id = IrohTransportSupport.frameId("subagent_todos"),
                    ts = IrohTransportSupport.nowIso(),
                    requestId = requestId,
                    success = true,
                    found = decoded.found,
                    subagent = decoded.subagent,
                    todos = decoded.todos,
                    todosFound = decoded.todosFound,
                )
            },
            onFailure = ::subagentTodosFailure,
        )
    }

    private data class ScopedRpcLabels(
        val unsupported: String,
        val timedOut: String,
        val failed: String,
    )

    private suspend fun <T> invokeScopedRpc(
        requestId: String,
        timeoutMs: Long,
        labels: ScopedRpcLabels,
        call: suspend () -> AppServerInboundFrame.AdminRpcResponse?,
        mapSuccess: (JsonElement) -> T,
        onFailure: (String, String) -> T,
    ): T = try {
        withTimeoutOrNull(timeoutMs.milliseconds) {
            val response = call() ?: return@withTimeoutOrNull onFailure(requestId, labels.unsupported)
            if (!response.success) return@withTimeoutOrNull onFailure(requestId, response.error ?: labels.failed)
            val result = response.result ?: return@withTimeoutOrNull onFailure(requestId, "${labels.failed}: no result")
            mapSuccess(result)
        } ?: onFailure(requestId, labels.timedOut)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(requestId, error.message ?: labels.failed)
    }

    private suspend fun callScopedSubagentRpc(
        method: String,
        scope: SubagentRpcScope,
        body: String,
    ): AppServerInboundFrame.AdminRpcResponse? {
        val handle = supervisor.ready()
        val advertised = handle.serverCapabilities
        if (advertised != null && SUBAGENT_RPC_CAPABILITY !in advertised) return null
        val scopedBody = buildJsonObject {
            put("conversation_id", scope.conversationId)
            scope.agentId?.let { put("agent_id", it) }
            subagentJson.parseToJsonElement(body).jsonObject.forEach { (key, value) -> put(key, value) }
        }.toString()
        val response = adminRpc(method, "/v1/conversations/${scope.conversationId}/subagents", scopedBody)
        return response.takeUnless {
            !it.success && AdminRpcErrors.isUnknownMethod(it.error)
        }
    }

    private fun currentSubagentScope(): SubagentRpcScope? {
        val conversationId = connectionSession.currentViewedConversationId() ?: return null
        val agentId = turnRegistry.getActiveTurn(IrohConversationId(conversationId.value))?.agentId
        return SubagentRpcScope(conversationId.value, agentId)
    }

    private fun subagentListFailure(requestId: String, error: String) = ServerFrame.SubagentListResponse(
        id = IrohTransportSupport.frameId("subagent_list"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun subagentTodosFailure(requestId: String, error: String) = ServerFrame.SubagentTodosResponse(
        id = IrohTransportSupport.frameId("subagent_todos"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = false, error = error,
    )

    /** Shared scoped-RPC labels for the cron.* bridge methods (op = the admin_rpc method). */
    private fun cronLabels(op: String) = ScopedRpcLabels(
        unsupported = CRON_RPC_UNSUPPORTED,
        timedOut = "$op timed out",
        failed = "$op failed",
    )

    /**
     * Shared cron.* bridge invocation: run [op] over admin_rpc with [body] and map
     * the result / typed failure. Collapses the per-method invokeScopedRpc + labels
     * + adminRpc boilerplate so each sendCron* is just its body + result mapping.
     */
    private suspend fun <T> cronInvoke(
        op: String,
        requestId: String,
        timeoutMs: Long,
        body: JsonObject,
        mapSuccess: (JsonElement) -> T,
        onFailure: (String, String) -> T,
    ): T = invokeScopedRpc(
        requestId = requestId,
        timeoutMs = timeoutMs,
        labels = cronLabels(op),
        call = { adminRpc(method = op, path = CRON_ADMIN_PATH, body = body.toString()) },
        mapSuccess = mapSuccess,
        onFailure = onFailure,
    )

    private fun cronListFailure(requestId: String, error: String) = ServerFrame.CronListResponse(
        id = IrohTransportSupport.frameId("cron_list"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronAddFailure(requestId: String, error: String) = ServerFrame.CronAddResponse(
        id = IrohTransportSupport.frameId("cron_add"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronGetFailure(requestId: String, error: String) = ServerFrame.CronGetResponse(
        id = IrohTransportSupport.frameId("cron_get"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronDeleteFailure(requestId: String, error: String) = ServerFrame.CronDeleteResponse(
        id = IrohTransportSupport.frameId("cron_delete"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronDeleteAllFailure(requestId: String, error: String) = ServerFrame.CronDeleteAllResponse(
        id = IrohTransportSupport.frameId("cron_delete_all"), ts = IrohTransportSupport.nowIso(), requestId = requestId, success = false, error = error,
    )

    @Serializable
    private data class SubagentListRpcResult(val subagents: List<SubagentEntry> = emptyList())

    @Serializable
    private data class SubagentTodosRpcResult(
        val found: Boolean = false,
        val subagent: SubagentEntry? = null,
        val todos: List<SubagentTodo> = emptyList(),
        @SerialName("todos_found") val todosFound: Boolean = false,
    )

    private data class SubagentRpcScope(val conversationId: String, val agentId: String?)

    @Serializable
    private data class CronListRpcResult(val tasks: List<CronTask> = emptyList())

    @Serializable
    private data class CronMutationRpcResult(
        val found: Boolean = false,
        val task: CronTask? = null,
        val warning: String? = null,
    )

    @Serializable
    private data class CronDeleteAllRpcResult(val deleted: Long = 0L)

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
        private const val SUBAGENT_RPC_UNSUPPORTED = "subagent registry is unavailable on this Iroh node"
        private const val CRON_RPC_UNSUPPORTED = "cron scheduling is unavailable on this Iroh node"
        // Cron dispatch is by admin_rpc method name; this path is a stable cosmetic
        // hint (cron is native-only, no proxy fallback consumes it).
        private const val CRON_ADMIN_PATH = "/v1/cron"
        private val subagentJson = Json { ignoreUnknownKeys = true }
        // letta-mobile-34xoj: admin_rpc retry thresholds
        private const val ADMIN_RPC_FAILURE_THRESHOLD = 3
        private const val STREAM_IDLE_THRESHOLD_MS = 30_000L
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
