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
) : IChannelTransport, RedialAwareChannelTransport, LivenessProbingChannelTransport {
    private val _state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Idle)
    override val state: StateFlow<ChannelTransportState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 64)
    override val events: SharedFlow<ServerFrame> = _events.asSharedFlow()

    private val _frameEvents = MutableSharedFlow<TransportFrameEvent>(extraBufferCapacity = 64)
    override val frameEvents: SharedFlow<TransportFrameEvent> = _frameEvents.asSharedFlow()

    private val _redialWhileTurnActive = MutableSharedFlow<RedialWhileTurnActive>(extraBufferCapacity = 8)
    override val redialWhileTurnActive: SharedFlow<RedialWhileTurnActive> = _redialWhileTurnActive.asSharedFlow()

    /** Emit to both event flows so both direct consumers and
     *  WsChatBridge (via frameEvents) see each frame exactly once. */
    private suspend fun emitBoth(frame: ServerFrame) {
        // letta-mobile-34xoj: record stream activity to prevent premature reconnect
        adminRpcRetryState.recordStreamActivity()
        Telemetry.event(
            "IrohGate", "gate1.emitBoth",
            "frame" to (frame::class.simpleName ?: ""),
            "messageId" to frameMessageId(frame),
            "conversationId" to frameConversationId(frame),
        )
        frameFlowContent(frame)?.let { (key, type, content) ->
            IrohFrameFlowDiagnostics.record("gate1.emit", key, type, content)
        }
        _events.emit(frame)
        _frameEvents.emit(TransportFrameEvent(frame = frame))
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
    private val activeSendJobs = ConcurrentHashMap<String, Job>()

    /**
     * The turns currently being streamed, KEYED BY conversationId. Each holds the
     * promotable run id and the exactly-one-terminal guard so [cancel] can address
     * an `abort_message` to the real (server) run id and route a synthetic
     * cancelled terminal through the SAME guard the streaming path uses — a turn
     * can only ever emit one [ServerFrame.TurnDone], no matter which side reaches
     * it first.
     *
     * letta-mobile-or40x: this used to be ONE process-wide slot, which made every
     * per-turn invariant in this file (presence, engine/observer ownership,
     * cancellation targeting) silently global. A conversation is the unit of
     * ownership; the map makes that structural.
     */
    private val activeTurns = ConcurrentHashMap<String, ActiveTurn>()

    /**
     * Nonterminal turns whose connection died before their send job could emit a
     * terminal, KEYED BY conversationId. Closing the stale handle cancels those
     * jobs and drops them from [activeTurns], so retain only the immutable
     * identity needed to trigger the existing reconcile-and-settle path after
     * redial. Keyed writes AND keyed reads — the pre-or40x code wrote this
     * unkeyed but cleared it keyed by conversationId.
     */
    private val interruptedTurns = ConcurrentHashMap<String, RedialWhileTurnActive>()

    /**
     * letta-mobile-or40x SENSING: the last observed frame-ownership path
     * ("engine" / "observer") per conversation, so a mid-stream ownership FLIP —
     * the corruption that made conversation A's frames silently change consumer
     * once conversation B evicted A from the (formerly global) turn slot — is
     * reported instead of being absorbed. Cleared when a conversation's turn
     * reaches its terminal, so the legitimate engine -> observer transition at
     * end of turn is not reported as a flip.
     */
    private val frameOwnershipPath = ConcurrentHashMap<String, String>()

    override fun hasActiveChatTurn(conversationId: String): Boolean =
        activeTurns[conversationId]?.terminalReached?.isCompleted == false

    override val hasAnyActiveChatTurn: Boolean
        get() = activeTurns.values.any { !it.terminalReached.isCompleted }

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

    /**
     * Per-turn client state shared between the streaming send job and [cancel].
     * Guards are atomic because the send job (Dispatchers.IO) and a cancel
     * request race for the single terminal.
     */
    private class ActiveTurn(
        val turnId: String,
        initialRunId: String,
        val agentId: String,
        val conversationId: String,
    ) {
        private val runIdRef = atomic(initialRunId)
        private val terminalClaimed = atomic(false)
        /** Completes with the terminal status once the one terminal is emitted. */
        val terminalReached = CompletableDeferred<String>()
        @Volatile
        var job: Job? = null

        /** The canonical run id — the real server run id once promoted. */
        val runId: String get() = runIdRef.value

        /**
         * Promote a still-synthetic run id to the real server run id. Returns
         * true only on the first real promotion so the caller re-emits
         * TurnStarted exactly once.
         */
        fun promoteRunId(real: String): Boolean {
            if (real.isBlank() || real.isIrohSyntheticRunId()) return false
            while (true) {
                val current = runIdRef.value
                if (!current.isIrohSyntheticRunId() || current == real) return false
                if (runIdRef.compareAndSet(current, real)) return true
            }
        }

        /** Wins exactly once; the first terminal (server or synthetic) claims it. */
        fun claimTerminal(): Boolean = terminalClaimed.compareAndSet(expect = false, update = true)
        val hasTerminal: Boolean get() = terminalClaimed.value
    }

    private var explicitConfig: IrohConnectConfig? = null
    // Explicit type: this field and `livenessProbe` reference each other through
    // their lambdas, which defeats type inference.
    private val supervisor: IrohConnectionSupervisor = IrohConnectionSupervisor(
        scope = scope,
        configProvider = { explicitConfig ?: activeConfigProvider() },
        dialer = { config -> testDialer?.invoke(config) ?: dial(config) },
        onStateChanged = { supervisorState ->
            _state.value = supervisorState.toChannelTransportState()
            if (supervisorState is IrohConnectionState.Ready) {
                notifyRedialIfTurnActive()
                // letta-mobile-r3i1z: (re)start the passive observer ingestion loop
                // bound to THIS connection generation. Any prior collector (tied to
                // an older, now-dead flow) is cancelled first so a stale collector
                // never ingests from a torn-down transport.
                startObserverIngest(supervisorState.handle)
                // letta-mobile-r3i1z (A): on EVERY fresh Ready — including a silent
                // redial after a QUIC timeout — re-register this connection as a
                // viewer of the currently-viewed conversation. Server-side viewer
                // registration only fires on runtime_start (send) or message.list
                // (hydrate); a long-lived app that redials without doing either is
                // invisible to the fanout (viewerCount drops to just the initiator).
                // Re-issuing the hydrate's message.list both re-registers server-side
                // AND reconciles frames missed during the dead window.
                reSubscribeViewedConversation()
                // letta-mobile-wxy4s: arm the application-level liveness probe for
                // THIS connection generation. QUIC state alone cannot detect a
                // black-holed peer (the unacked keepalive datagram keeps resetting
                // the idle timer), so a periodic health.check over a FRESH bidi
                // stream is the only thing that actually tests the path.
                livenessProbe.start(supervisorState.handle)
            } else {
                // Snapshot turn identity before a degraded handle is closed and
                // its send jobs drop their entries from activeTurns. Intentional
                // disconnects and config replacement must not synthesize redial
                // recovery.
                if (supervisorState is IrohConnectionState.Degraded && supervisorState.reason != "config_changed") {
                    rememberInterruptedTurns()
                } else if (supervisorState is IrohConnectionState.Degraded) {
                    interruptedTurns.clear()
                }
                // Any non-Ready transition (Degraded/Disconnected/Closed/dialing)
                // stops observer ingestion. On redial a fresh Ready fires and the
                // collector restarts against the new handle above.
                stopObserverIngest("state:${supervisorState::class.simpleName}")
                // letta-mobile-wxy4s: the probe is pinned to a Ready handle; any
                // non-Ready transition disarms it. A fresh Ready re-arms it above.
                livenessProbe.stop("state:${supervisorState::class.simpleName}")
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
        millisSinceLastStream = { adminRpcRetryState.millisSinceLastStream() },
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
    private val observerGeneration = atomic(0)
    @Volatile
    private var observerJob: Job? = null

    private fun startObserverIngest(handle: IrohConnectionHandle) {
        val streamFrames = handle.effectiveObserverStreamFrames
        if (streamFrames == null) {
            // A Ready handle with no observable stream must STILL invalidate any
            // prior collector — a stale collector pinned to a superseded
            // connection's flow can never be left running (r3i1z redial gap).
            stopObserverIngest("no_observer_stream")
            Telemetry.event("IrohObserver", "ingest.unavailable", "sessionId" to handle.sessionId)
            return
        }
        // Bump the generation and cancel any prior collector: exactly one observer
        // collector is ever live, and it is pinned to this handle's session.
        val generation = observerGeneration.incrementAndGet()
        observerJob?.cancel()
        // Log at ARM time (synchronously), not inside the launched job: if a racing
        // teardown cancels the job before dispatch, telemetry still shows the
        // (re)start happened — the r3i1z redial diagnosis relied on this signal.
        Telemetry.event(
            "IrohObserver", "ingest.start",
            "sessionId" to handle.sessionId,
            "generation" to generation.toString(),
        )
        observerJob = scope.launch {
            runCatching {
                streamFrames.collect { received ->
                    // Guard against a stale collector that a redial has superseded.
                    if (observerGeneration.value != generation) return@collect
                    ingestObserverFrame(received)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Telemetry.event(
                    "IrohObserver", "ingest.failed",
                    "error" to (error.message ?: error.toString()),
                    "class" to error::class.simpleName,
                )
            }
        }
    }

    private fun stopObserverIngest(reason: String) {
        val job = observerJob ?: return
        observerJob = null
        // Invalidate the generation so an in-flight collect body drops its frame.
        observerGeneration.incrementAndGet()
        job.cancel()
        Telemetry.event("IrohObserver", "ingest.stop", "reason" to reason)
    }

    // letta-mobile-r3i1z (A): RE-SUBSCRIBE ON RECONNECT.
    //
    // The "currently viewed conversation" is learned from the transport's OWN
    // message.list admin_rpc traffic — the same hydrate that first registered
    // this connection as a server-side viewer (path /v1/conversations/<id>/...).
    // We record its (conversationId, path) and, on every fresh Ready, replay it.
    // No new callback/provider is needed: the timeline layer already routes its
    // hydrate through adminRpc(), so the transport already sees which
    // conversation is being viewed.
    @Volatile
    private var viewedConversationId: String? = null
    @Volatile
    private var viewedMessageListPath: String? = null

    /**
     * Records the currently-viewed conversation from a message.list hydrate so a
     * later reconnect can re-issue it. Called for every message.list adminRpc the
     * transport handles. Non-message.list reads (agent.list, health.check, …) do
     * not carry a viewed-conversation identity and are ignored.
     */
    private fun recordViewedConversationFrom(method: String, path: String) {
        if (method != "message.list") return
        val conversationId = conversationIdFromMessageListPath(path) ?: return
        // A conversation switch re-points the re-subscribe target (mirrors the
        // server's Option A de-scope rule). The freshest message.list wins.
        viewedConversationId = conversationId
        viewedMessageListPath = path
    }

    /**
     * Extracts the conversation id from a message.list path of the shape
     * `/v1/conversations/<id>/messages[?...]`. Returns null for any other shape.
     */
    private fun conversationIdFromMessageListPath(path: String): String? {
        val marker = "/v1/conversations/"
        val start = path.indexOf(marker)
        if (start < 0) return null
        val after = path.substring(start + marker.length)
        val id = after.substringBefore('/').substringBefore('?')
        return id.takeIf { it.isNotBlank() }
    }

    /**
     * On a fresh Ready, re-issue the recorded message.list for the viewed
     * conversation so the (possibly brand-new, redialed) connection re-registers
     * as a viewer server-side. Idempotent — fires on the FIRST Ready too, where
     * the normal open/hydrate already registers, so a duplicate hydrate is
     * harmless (message.list is read-only + the server viewer set is a Set).
     * Fire-and-forget on [scope]; failures are swallowed (a dead connection just
     * escalates through the normal admin_rpc retry path on the next real read).
     */
    private fun reSubscribeViewedConversation() {
        val path = viewedMessageListPath ?: return
        val conversationId = viewedConversationId
        scope.launch {
            Telemetry.event(
                "IrohObserver", "resubscribe.begin",
                "conversationId" to (conversationId ?: ""),
            )
            runCatching { adminRpc(method = "message.list", path = path, body = null) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Telemetry.event(
                        "IrohObserver", "resubscribe.failed",
                        "conversationId" to (conversationId ?: ""),
                        "error" to (error.message ?: error.toString()),
                        "class" to error::class.simpleName,
                    )
                }
        }
    }

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
     * by looking up the frame's own conversation_id in [activeTurns], a map keyed
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
        val conversationId = streamDelta.runtime.conversationId
        val agentId = streamDelta.runtime.agentId

        // DUAL-INGEST GUARD: if a local turn is active on THIS conversation, the
        // engine's collect already consumes+emits its frames — the observer must
        // not touch them. Frames for a conversation with no live local turn belong
        // to the observer. Keyed lookup: another conversation's turn is irrelevant.
        val localTurn = activeTurns[conversationId]
        recordFrameOwnership(conversationId, localTurn)
        if (localTurn != null) {
            Telemetry.event(
                "IrohObserver", "ingest.skip_engine_owned",
                "conversationId" to conversationId,
                "turnId" to localTurn.turnId,
            )
            return
        }

        // letta-mobile-m6oa1.1 / m6oa1.3: ADDITIVE tap — correlate parent
        // `Agent` tool_call dispatch/return frames into the subagent correlator
        // and, when the correlator's observable state advances, publish the
        // resulting SubagentsUpdated frame(s) through the SAME emitBoth seam the
        // repository's push-fold already consumes (observePushEvents). This does
        // NOT consume or alter the projection below; it only observes and then
        // publishes an additive push frame. The pure reducer decides WHAT to
        // emit (correlateAgentFrame stays side-effect-light); this suspend
        // caller does the actual emitBoth.
        correlateAgentFrame(streamDelta).forEach { emitBoth(it) }

        // Project via the EXACT initiator chain: raw StreamDelta -> RuntimeEventDraft
        // (AppServerRuntimeEventMapper, the same mapper engine.collect uses) ->
        // ServerFrame(s) (payloadToServerFrames, shared with emitDraft). The
        // observer supplies only fallback context; wire envelope ids win.
        val command = observerTurnCommand(agentId, conversationId)
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
        val nowIso = nowIso()
        return listOf(
            ServerFrame.SubagentsUpdated(
                id = frameId("subagents_updated"),
                ts = nowIso,
                reason = reason,
                subagent = changed,
                subagentsActive = snapshot,
                at = nowIso,
            ),
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun observerTurnCommand(agentId: String, conversationId: String): TurnCommand =
        TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh-observer"),
            agentId = AgentId(agentId),
            conversationId = ConversationId(conversationId),
            input = TurnInput.UserMessage(
                localMessageId = "iroh-observer-$conversationId",
                text = "",
            ),
        )

    /**
     * letta-mobile-or40x: recovery is announced PER CONVERSATION. Every
     * interrupted conversation, plus every still-nonterminal live turn that has
     * no interrupted snapshot, gets its own [RedialWhileTurnActive]. The
     * pre-or40x version could only ever announce ONE conversation because it
     * read single global slots.
     */
    private fun notifyRedialIfTurnActive() {
        val announced = mutableSetOf<String>()
        interruptedTurns.values.toList().forEach { recovery ->
            announced += recovery.conversationId
            if (_redialWhileTurnActive.tryEmit(recovery)) {
                interruptedTurns.remove(recovery.conversationId, recovery)
            }
        }
        activeTurns.values.toList().forEach { turn ->
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
        activeTurns.values.toList().forEach { turn ->
            if (turn.hasTerminal) return@forEach
            interruptedTurns[turn.conversationId] = RedialWhileTurnActive(
                agentId = turn.agentId,
                conversationId = turn.conversationId,
                turnId = turn.turnId,
                runId = turn.runId,
            )
        }
    }

    private fun clearInterruptedTurn(conversationId: String) {
        interruptedTurns.remove(conversationId)
    }

    /**
     * letta-mobile-or40x SENSING (c): report a mid-stream ownership FLIP for a
     * conversation. [localTurn] is the live turn for [conversationId] (engine
     * path) or null (observer path). A flip while the conversation's stream is
     * still running is exactly the corruption or40x fixes; it was previously
     * 100% silent.
     */
    private fun recordFrameOwnership(conversationId: String, localTurn: ActiveTurn?) {
        val path = if (localTurn != null) OWNERSHIP_ENGINE else OWNERSHIP_OBSERVER
        val previous = frameOwnershipPath.put(conversationId, path)
        if (previous != null && previous != path) {
            Telemetry.event(
                "IrohObserver", "ingest.ownership_switched",
                "conversationId" to conversationId,
                "from" to previous,
                "to" to path,
                "turnId" to (localTurn?.turnId ?: ""),
                "otherActiveConversations" to otherActiveConversationsLabel(conversationId),
            )
        }
    }

    /** Comma-joined ids of live nonterminal turns other than [conversationId]. */
    private fun otherActiveConversationsLabel(conversationId: String): String =
        activeTurns.values
            .filter { it.conversationId != conversationId && !it.hasTerminal }
            .joinToString(",") { it.conversationId }

    // letta-mobile-34xoj: track consecutive admin_rpc failures and last stream frame
    // time to decide retry-on-same-connection vs. escalate-to-reconnect.
    private val adminRpcRetryState = AdminRpcRetryState()
    private class AdminRpcRetryState {
        private val mutex = Mutex()
        @Volatile var consecutiveFailures = 0
        @Volatile var lastStreamFrameMs = System.currentTimeMillis()

        suspend fun recordFailure(): Int = mutex.withLock {
            consecutiveFailures += 1
            consecutiveFailures
        }

        suspend fun reset() = mutex.withLock {
            consecutiveFailures = 0
        }

        fun recordStreamActivity() {
            lastStreamFrameMs = System.currentTimeMillis()
        }

        fun millisSinceLastStream(): Long = System.currentTimeMillis() - lastStreamFrameMs
    }

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
        val runId = "iroh-run-${UUID.randomUUID()}"
        val turnId = "iroh-turn-${UUID.randomUUID()}"
        val turn = ActiveTurn(
            turnId = turnId,
            initialRunId = runId,
            agentId = agentId,
            conversationId = conversationId,
        )
        // letta-mobile-or40x: KEYED registration. Starting a turn on conversation
        // B must never displace conversation A's turn — only a new turn on the
        // SAME conversation supersedes the previous one, and that supersession is
        // reported (SENSING b) instead of being silently absorbed.
        val superseded = activeTurns.put(conversationId, turn)
        if (superseded != null && !superseded.hasTerminal) {
            Telemetry.event(
                "IrohTransport", "turn.superseded_nonterminal",
                "conversationId" to conversationId,
                "supersededTurnId" to superseded.turnId,
                "supersededRunId" to superseded.runId,
                "newTurnId" to turnId,
            )
        }
        // SENSING (a): a turn is starting for THIS conversation while another
        // conversation still has a nonterminal turn in flight. Legal after or40x
        // (that is the whole point), but it is the precondition of the reported
        // corruption, so it must be observable.
        val concurrent = activeTurns.values.filter { it.conversationId != conversationId && !it.hasTerminal }
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
            Telemetry.event("IrohTrace", "transport.send.job_start", "turnId" to turnId, "runId" to runId)
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
            // letta-mobile-8xxzv: SCOPED busy gate. The engine keys its turn
            // lease by {agentId, conversationId} — the App Server's own unit of
            // turn exclusion — so a live turn in ANOTHER conversation must not
            // fast-fail this send. Only a second turn into the SAME runtime is
            // rejected, which is the documented letta-code contract (one active
            // turn per {agent_id, conversation_id} runtime, parallel across them).
            if (engine.isBusy(agentId, conversationId)) {
                // letta-mobile-kyqdt: TELEMETRY-ONLY. Read the engine's owner
                // metadata (pure getter, no lock) so this busy rejection can
                // prove WHO holds the engine — the owning run/agent/conversation,
                // when it acquired the lock (+ how long ago), and its last-seen
                // terminal — alongside the incoming (rejected) send's identity.
                // letta-mobile-8xxzv: report the owner of the lease this send
                // actually collided with (our own key), not "whoever holds a
                // lease" — with concurrent turns those differ.
                val owner = engine.activeTurnOwnerFor(agentId, conversationId)
                val ownerAcquiredAtMs = owner?.acquiredAtMs
                Telemetry.event(
                    "IrohTransport", "turn.busy",
                    "turnId" to turnId,
                    "runId" to runId,
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
                    // letta-mobile-kyqdt: terminal DIAGNOSTICS so a busy rejection
                    // can prove the leading hypothesis — a terminal arrived but
                    // failed matches(scope). All pure reads of owner metadata.
                    "ownerLastTerminalSource" to owner?.lastTerminalSource,
                    "ownerLastTerminalAtMs" to owner?.lastTerminalAtMs,
                    "ownerLastTerminalSeq" to owner?.lastTerminalSeq,
                    "ownerLastTerminalScopeMatched" to owner?.lastTerminalScopeMatched,
                    "ownerSettleDeadlineMs" to owner?.settleDeadlineMs,
                    "ownerWatchdogDeadlineMs" to owner?.watchdogDeadlineMs,
                    "ownerProcessRole" to owner?.processRole,
                    "ownerReleaseReason" to owner?.releaseReason,
                    // Every OTHER runtime key running concurrently right now:
                    // proof that this rejection is same-key, not global.
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
        activeSendJobs[conversationId] = sendJob
        sendJob.invokeOnCompletion {
            // letta-mobile-or40x SENSING (b): the pre-or40x identity-checked clear
            // (`if (activeTurn === turn) activeTurn = null`) silently absorbed the
            // case where this turn had already been evicted by another turn. Keyed
            // removal makes that impossible across conversations, and the two
            // remaining outcomes are now BOTH reported.
            val removed = activeTurns.remove(conversationId, turn)
            if (removed) {
                frameOwnershipPath.remove(conversationId)
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
                    "currentTurnId" to (activeTurns[conversationId]?.turnId ?: ""),
                )
            }
            activeSendJobs.remove(conversationId, sendJob)
        }
        return true
    }

    /**
     * Emits a turn frame through the single exactly-one-terminal guard shared by
     * the streaming send job and [cancel]. Only the first [ServerFrame.TurnDone]
     * for a turn is forwarded; the loser is dropped. This holds no matter which
     * side (server terminal or synthetic cancel) reaches the terminal first.
     */
    private suspend fun emitTurnFrame(turn: ActiveTurn, frame: ServerFrame) {
        if (frame is ServerFrame.TurnDone) {
            if (!turn.claimTerminal()) {
                Telemetry.event(
                    "IrohTrace", "transport.turn_done.duplicate_skipped",
                    "turnId" to turn.turnId,
                    "runId" to frame.runId,
                    "status" to frame.status,
                )
                return
            }
            if (interruptedTurns[turn.conversationId]?.turnId == turn.turnId) {
                interruptedTurns.remove(turn.conversationId)
            }
            // The engine -> observer handover at end of turn is legitimate; drop
            // the recorded ownership path so it is not reported as a mid-stream
            // flip by [recordFrameOwnership].
            frameOwnershipPath.remove(turn.conversationId)
            emitBoth(frame)
            turn.terminalReached.complete(frame.status)
            return
        }
        emitBoth(frame)
    }

    private fun emitDraft(
        draft: RuntimeEventDraft,
        turn: ActiveTurn,
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
        val turn = activeTurns[conversationId]
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
            activeSendJobs.remove(conversationId)?.cancel()
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
                Telemetry.event(
                    "IrohTransport", "cancel.synthetic_terminal",
                    "turnId" to turn.turnId,
                    "runId" to turn.runId,
                )
                emitTurnFrame(
                    turn,
                    ServerFrame.TurnDone(
                        id = frameId("cancelled"),
                        ts = nowIso(),
                        turnId = turn.turnId,
                        runId = turn.runId,
                        status = "cancelled",
                    ),
                )
            }
            // 4. Terminal settled — tear down THIS conversation's streaming job
            //    only. Keyed removal: another conversation's in-flight job is
            //    structurally unreachable from here.
            turn.job?.cancel()
            turn.job?.let { activeSendJobs.remove(conversationId, it) }
            if (!activeTurns.remove(conversationId, turn)) {
                Telemetry.event(
                    "IrohTransport", "cancel.turn_already_replaced",
                    "conversationId" to conversationId,
                    "turnId" to turn.turnId,
                    "currentTurnId" to (activeTurns[conversationId]?.turnId ?: ""),
                )
            } else {
                frameOwnershipPath.remove(conversationId)
            }
        }
        return true
    }
    override fun bye(): Boolean = true
    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Failed
    override fun subscribe(runId: String, cursor: Long): Boolean = false

    override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
        // letta-mobile-r3i1z (A): learn the currently-viewed conversation from
        // the hydrate so a later reconnect can re-register this connection as a
        // server-side viewer with no user action. Recorded before the call so a
        // hydrate that only succeeds on retry/redial is still captured.
        recordViewedConversationFrom(method, path)
        // letta-mobile-34xoj: first attempt
        val first = supervisor.ready()
        val firstAttempt = runCatching {
            first.adminRpc(method = method, path = path, body = body)
        }
        if (firstAttempt.isSuccess) {
            adminRpcRetryState.reset()
            return firstAttempt.getOrThrow()
        }

        val firstError = firstAttempt.exceptionOrNull()!!
        if (firstError is CancellationException) throw firstError
        // k7yyc: a decode / frame-size (payload) error is isolated to THIS
        // request. It is NOT a transport fault, so never reconnect or close
        // the shared connection for it — a single oversized or garbled
        // list response must fail only its own request with the typed
        // error, never tear down streaming for every other request.
        if (firstError.isAdminRpcPayloadError()) throw firstError
        if (!firstError.isConnectionLostClass()) throw firstError
        if (!method.isReadOnlyAdminRpcMethod()) throw firstError

        // Request isolation: if the shared connection is STILL ALIVE, this read's
        // failure is isolated to THIS request (e.g. a method the node doesn't
        // implement, or this request's own 15s timeout), NOT a transport fault.
        // `isConnectionLostClass()` only inspects the error text — which for
        // per-request errors ("admin_rpc stream closed before response", "admin_rpc
        // timed out") matches "closed"/"stream"/"timeout" and looks connection-ish
        // even though the QUIC connection is fine. Escalating here would call
        // supervisor.onConnectionLost → close the shared connection → cancel every
        // OTHER in-flight admin_rpc read on it (e.g. a large concurrent agent.list),
        // which is exactly the desktop connect-burst teardown loop. A genuine drop
        // instead flips `connected` false (reader-exit/close, which reconnect
        // independently), so only fall through to retry/escalate when the
        // connection is actually dead.
        if (first.isConnectionAlive) {
            com.letta.mobile.util.Telemetry.event(
                "IrohTransport", "admin_rpc.request_isolated",
                "method" to method,
                "path" to path,
                "error" to (firstError.message ?: firstError.toString()),
                "class" to firstError::class.simpleName,
            )
            throw firstError
        }

        // letta-mobile-34xoj: an admin_rpc read timed out or failed with a
        // connection-like error. NEVER invalidate the live connection while
        // a turn is actively streaming — retry on the SAME connection.
        val failures = adminRpcRetryState.recordFailure()
        val idleMs = adminRpcRetryState.millisSinceLastStream()
        val shouldEscalate = failures >= ADMIN_RPC_FAILURE_THRESHOLD && idleMs > STREAM_IDLE_THRESHOLD_MS

        if (!shouldEscalate) {
            // Retry on the SAME connection (no supervisor invalidation)
            Telemetry.event(
                "IrohTransport", "admin_rpc.retry.same_connection",
                "method" to method,
                "path" to path,
                "error" to (firstError.message ?: firstError.toString()),
                "class" to firstError::class.simpleName,
                "consecutiveFailures" to failures.toString(),
                "idleMs" to idleMs.toString(),
            )
            return runCatching {
                // Re-use the SAME handle (no redial)
                first.adminRpc(method = method, path = path, body = body)
            }.getOrElse { retryError ->
                if (retryError is CancellationException) throw retryError
                // Second failure on same connection — now escalate
                Telemetry.event(
                    "IrohTransport", "admin_rpc.escalate.reconnect",
                    "method" to method,
                    "path" to path,
                    "error" to (retryError.message ?: retryError.toString()),
                    "class" to retryError::class.simpleName,
                    "consecutiveFailures" to (failures + 1).toString(),
                )
                supervisor.onConnectionLost("admin_rpc_failed_after_retry: ${retryError.message ?: retryError.toString()}", first)
                val newHandle = supervisor.ready()
                newHandle.adminRpc(method = method, path = path, body = body)
            }
        } else {
            // Escalate: connection is idle and multiple failures accumulated
            Telemetry.event(
                "IrohTransport", "admin_rpc.escalate.reconnect",
                "method" to method,
                "path" to path,
                "error" to (firstError.message ?: firstError.toString()),
                "class" to firstError::class.simpleName,
                "consecutiveFailures" to failures.toString(),
                "idleMs" to idleMs.toString(),
            )
            supervisor.onConnectionLost("admin_rpc_failed: ${firstError.message ?: firstError.toString()}", first)
            val retry = supervisor.ready()
            return retry.adminRpc(method = method, path = path, body = body)
        }
    }

    private fun String.isReadOnlyAdminRpcMethod(): Boolean = this in READ_ONLY_ADMIN_RPC_METHODS

    override suspend fun disconnect() {
        interruptedTurns.clear()
        stopObserverIngest("disconnect")
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
        activeSendJobs.keys.toList().forEach { conversationId ->
            val job = activeSendJobs.remove(conversationId) ?: return@forEach
            val turn = activeTurns[conversationId]
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
                ServerFrame.CronListResponse(id = frameId("cron_list"), ts = nowIso(), requestId = requestId, success = true, tasks = decoded.tasks)
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
                ServerFrame.CronAddResponse(id = frameId("cron_add"), ts = nowIso(), requestId = requestId, success = true, task = decoded.task, warning = decoded.warning)
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
                ServerFrame.CronGetResponse(id = frameId("cron_get"), ts = nowIso(), requestId = requestId, success = true, task = decoded.task)
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
                ServerFrame.CronDeleteResponse(id = frameId("cron_delete"), ts = nowIso(), requestId = requestId, success = true)
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
                ServerFrame.CronDeleteAllResponse(id = frameId("cron_delete_all"), ts = nowIso(), requestId = requestId, success = true, count = decoded.deleted)
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
                    id = frameId("subagent_list"),
                    ts = nowIso(),
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
                    id = frameId("subagent_todos"),
                    ts = nowIso(),
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
        val conversationId = viewedConversationId ?: return null
        val agentId = activeTurns[conversationId]?.agentId
        return SubagentRpcScope(conversationId, agentId)
    }

    private fun subagentListFailure(requestId: String, error: String) = ServerFrame.SubagentListResponse(
        id = frameId("subagent_list"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun subagentTodosFailure(requestId: String, error: String) = ServerFrame.SubagentTodosResponse(
        id = frameId("subagent_todos"), ts = nowIso(), requestId = requestId, success = false, error = error,
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
        id = frameId("cron_list"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronAddFailure(requestId: String, error: String) = ServerFrame.CronAddResponse(
        id = frameId("cron_add"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronGetFailure(requestId: String, error: String) = ServerFrame.CronGetResponse(
        id = frameId("cron_get"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronDeleteFailure(requestId: String, error: String) = ServerFrame.CronDeleteResponse(
        id = frameId("cron_delete"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun cronDeleteAllFailure(requestId: String, error: String) = ServerFrame.CronDeleteAllResponse(
        id = frameId("cron_delete_all"), ts = nowIso(), requestId = requestId, success = false, error = error,
    )

    private fun frameId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    private fun nowIso(): String = Instant.now().toString()

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
            "project.beadsRemoteStatus",
            "project.get",
            "project.list",
        )

        fun isIrohUrl(url: String?): Boolean {
            // Handle bare iroh://, https://iroh:// (corrupted saved config), etc.
            if (url == null) return false
            val stripped = url.trimStart().removePrefix("https://").removePrefix("http://")
            return stripped.startsWith(IROH_URL_PREFIX)
        }

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
private fun String.isIrohSyntheticRunId(): Boolean = startsWith("iroh-run-")
