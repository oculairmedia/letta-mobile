package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.RedialAwareChannelTransport
import com.letta.mobile.data.transport.api.RedialWhileTurnActive
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.runtime.AppServerTurnEngine
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
import kotlinx.coroutines.cancel
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
import kotlinx.serialization.json.JsonArray
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import java.time.Instant
import java.util.UUID

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
    private val testDialer: (suspend (IrohConnectConfig) -> IrohConnectionHandle)? = null,
    // Bounded window (ms) to await the server's own terminal after an abort
    // before synthesizing a cancelled terminal. Overridable so tests need not
    // wait the full production window.
    private val serverTerminalWaitMs: Long = SERVER_TERMINAL_WAIT_MS,
) : IChannelTransport, RedialAwareChannelTransport {
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

    private var activeSendJob: kotlinx.coroutines.Job? = null

    /**
     * The turn currently being streamed, if any. Holds the promotable run id and
     * the exactly-one-terminal guard so [cancel] can address an `abort_message`
     * to the real (server) run id and route a synthetic cancelled terminal
     * through the SAME guard the streaming path uses — the turn can only ever
     * emit one [ServerFrame.TurnDone], no matter which side reaches it first.
     */
    @Volatile
    private var activeTurn: ActiveTurn? = null

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
        fun claimTerminal(): Boolean = terminalClaimed.compareAndSet(false, true)
        val hasTerminal: Boolean get() = terminalClaimed.value
    }

    private var explicitConfig: IrohConnectConfig? = null
    private val supervisor = IrohConnectionSupervisor(
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
            } else {
                // Any non-Ready transition (Degraded/Disconnected/Closed/dialing)
                // stops observer ingestion. On redial a fresh Ready fires and the
                // collector restarts against the new handle above.
                stopObserverIngest("state:${supervisorState::class.simpleName}")
            }
        },
    )

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
    private val observerMapper = com.letta.mobile.data.runtime.AppServerRuntimeEventMapper()
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
     * frame whose conversation matches the currently-active local turn: the engine
     * OWNS frames for its own conversation while its turn runs. The observer OWNS
     * a frame only when NO local turn is active for that frame's conversation.
     * Ownership is decided per-frame by the frame's own conversation_id vs the
     * live activeTurn.conversationId — airtight (no overlap: a frame is engine-owned
     * XOR observer-owned; no gap: every stream_delta is owned by exactly one side).
     */
    private suspend fun ingestObserverFrame(received: AppServerReceivedFrame) {
        val streamDelta = received.frame as? AppServerInboundFrame.StreamDelta ?: return
        val conversationId = streamDelta.runtime.conversationId
        val agentId = streamDelta.runtime.agentId

        // DUAL-INGEST GUARD: if a local turn is active on THIS conversation, the
        // engine's collect already consumes+emits its frames — the observer must
        // not touch them. Frames for a DIFFERENT conversation than the active turn
        // (or when there is no active turn at all) belong to the observer.
        val localTurn = activeTurn
        if (localTurn != null && localTurn.conversationId == conversationId) {
            Telemetry.event(
                "IrohObserver", "ingest.skip_engine_owned",
                "conversationId" to conversationId,
                "turnId" to localTurn.turnId,
            )
            return
        }

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

    private fun notifyRedialIfTurnActive() {
        val turn = activeTurn ?: return
        if (turn.hasTerminal) return
        _redialWhileTurnActive.tryEmit(
            RedialWhileTurnActive(
                agentId = turn.agentId,
                conversationId = turn.conversationId,
                turnId = turn.turnId,
                runId = turn.runId,
            )
        )
    }

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
        com.letta.mobile.util.Telemetry.event(
            "IrohTrace", "transport.connect.begin",
            "baseShimUrl" to baseShimUrl,
            "forced" to DEBUG_FORCE_IROH_URL.isNotBlank(),
            "state" to state.value::class.simpleName,
        )
        supervisor.refreshConfig()
        val handle = supervisor.ready()
        com.letta.mobile.util.Telemetry.event("IrohTrace", "transport.connect.done", "state" to "connected", "sessionId" to handle.sessionId)
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
        val localEndpoint = runCatching {
            Endpoint.bind(
                EndpointOptions(relayMode = RelayMode.Companion.defaultMode())
            )
        }.onFailure { t ->
            com.letta.mobile.util.Telemetry.event("IrohTransport", "bind.failed", "error" to (t.message ?: t.toString()), "class" to t::class.simpleName)
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
                com.letta.mobile.data.transport.appserver.AppServerCommand.Auth(
                    requestId = "auth-${UUID.randomUUID()}",
                    token = config.token,
                    capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
                ),
            )
            if (!auth.success && config.token.isNotBlank()) {
                throw IrohAuthFailure(auth.error ?: "Iroh auth failed")
            }
            com.letta.mobile.util.Telemetry.event(
                "IrohTransport", "auth.negotiated",
                "success" to auth.success,
                "serverCapabilities" to (auth.capabilities ?: emptyList()).sorted().joinToString(","),
            )
            val engine = AppServerTurnEngine(
                client = appServerClient,
                clientInfo = com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo(
                    name = "letta-mobile-android-iroh",
                    version = config.clientVersion,
                ),
                permissionMode = com.letta.mobile.data.transport.appserver.AppServerPermissionMode.Unrestricted,
            )
            transport!!.awaitConnectionReady()
            IrohConnectionHandle(
                config = config,
                ticket = ticket,
                sessionId = ticket.hashCode().toString(),
                transport = transport,
                turnEngine = engine,
                close = { reason ->
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
        com.letta.mobile.util.Telemetry.event(
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
        activeTurn = turn
        val sendJob = scope.launch {
            com.letta.mobile.util.Telemetry.event("IrohTrace", "transport.send.job_start", "turnId" to turnId, "runId" to runId)
            val handle = runCatching { supervisor.ready() }.getOrElse { error ->
                com.letta.mobile.util.Telemetry.event("IrohTransport", "turn.ready_failed", "error" to (error.message ?: error.toString()), "class" to error::class.simpleName)
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
            if (engine.isBusy) {
                com.letta.mobile.util.Telemetry.event("IrohTransport", "turn.busy", "turnId" to turnId, "runId" to runId)
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
                    com.letta.mobile.util.Telemetry.event("IrohTransport", "turn.cancelled", "turnId" to turnId, "runId" to turn.runId)
                    return@onFailure
                }
                com.letta.mobile.util.Telemetry.event("IrohTransport", "turn.failed", "error" to (error.message ?: error.toString()), "class" to error::class.simpleName)
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
        sendJob.invokeOnCompletion {
            if (activeTurn === turn) activeTurn = null
        }
        activeSendJob = sendJob
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
            emitBoth(frame)
            turn.terminalReached.complete(frame.status)
            return
        }
        emitBoth(frame)
    }

    private fun emitDraft(
        draft: com.letta.mobile.runtime.RuntimeEventDraft,
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
        com.letta.mobile.util.Telemetry.event(
            "IrohTrace", "transport.emitDraft",
            "payload" to (draft.payload::class.simpleName ?: ""),
            "runId" to effectiveRunId,
            "promoted" to promoted,
        )
        val promotionFrames: List<ServerFrame> = if (promoted) {
            com.letta.mobile.util.Telemetry.event(
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

    override fun cancel(conversationId: String): Boolean {
        val turn = activeTurn
        if (turn == null) {
            // Nothing streaming: preserve the "cancel always yields a terminal"
            // contract so the UI can never get stuck streaming, but there is no
            // run to abort server-side.
            Telemetry.event("IrohTransport", "cancel.no_active_turn", "conversationId" to conversationId)
            activeSendJob?.cancel()
            activeSendJob = null
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
                handle.turnEngine?.abort(turn.runId.takeUnless { it.isIrohSyntheticRunId() })
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
            val serverTerminalStatus = withTimeoutOrNull(serverTerminalWaitMs) {
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
            // 4. Terminal settled — tear down the streaming job.
            turn.job?.cancel()
            if (activeSendJob === turn.job) activeSendJob = null
            if (activeTurn === turn) activeTurn = null
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

        // letta-mobile-34xoj: an admin_rpc read timed out or failed with a
        // connection-like error. NEVER invalidate the live connection while
        // a turn is actively streaming — retry on the SAME connection.
        val failures = adminRpcRetryState.recordFailure()
        val idleMs = adminRpcRetryState.millisSinceLastStream()
        val shouldEscalate = failures >= ADMIN_RPC_FAILURE_THRESHOLD && idleMs > STREAM_IDLE_THRESHOLD_MS

        if (!shouldEscalate) {
            // Retry on the SAME connection (no supervisor invalidation)
            com.letta.mobile.util.Telemetry.event(
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
                com.letta.mobile.util.Telemetry.event(
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
            com.letta.mobile.util.Telemetry.event(
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

    /**
     * k7yyc: true when [this] (or any cause in its chain) is a frame codec
     * decode/size rejection — a per-request payload fault that must fail only
     * the request, never trigger a transport reconnect.
     */
    private fun Throwable.isAdminRpcPayloadError(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is IrohFrameCodec.ProtocolException) return true
            current = current.cause
        }
        return false
    }

    override suspend fun disconnect() {
        stopObserverIngest("disconnect")
        supervisor.disconnect("disconnect")
        _state.value = ChannelTransportState.Disconnected(1000, "disconnected")
    }

    private suspend fun closeIrohResources(reason: String, transport: IrohAppServerTransport?, endpoint: Endpoint?) {
        com.letta.mobile.util.Telemetry.event(
            "IrohTrace", "transport.closeCurrent",
            "reason" to reason,
            "hasTransport" to (transport != null),
            "hasEndpoint" to (endpoint != null),
        )
        runCatching { activeSendJob?.cancel() }
        activeSendJob = null
        runCatching { transport?.close() }
        runCatching { endpoint?.shutdown() }
        runCatching { endpoint?.close() }
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

    private fun Throwable.isConnectionLostClass(): Boolean {
        val text = listOfNotNull(message, this::class.simpleName).joinToString(" ").lowercase()
        return listOf("closed", "timeout", "timed out", "reset", "broken pipe", "connection", "stream").any { it in text }
    }

    override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long): ServerFrame.CronListResponse =
        ServerFrame.CronListResponse(id = frameId("cron_list"), ts = nowIso(), requestId = "iroh-cron-list", success = false, error = "cron over iroh not implemented")
    override suspend fun sendCronAdd(agentId: String, name: String, description: String, prompt: String, recurring: Boolean, cron: String?, every: String?, at: String?, timezone: String?, conversationId: String?, timeoutMs: Long): ServerFrame.CronAddResponse =
        ServerFrame.CronAddResponse(id = frameId("cron_add"), ts = nowIso(), requestId = "iroh-cron-add", success = false, error = "cron over iroh not implemented")
    override suspend fun sendCronGet(taskId: String, timeoutMs: Long): ServerFrame.CronGetResponse =
        ServerFrame.CronGetResponse(id = frameId("cron_get"), ts = nowIso(), requestId = "iroh-cron-get", success = false, error = "cron over iroh not implemented")
    override suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse =
        ServerFrame.CronDeleteResponse(id = frameId("cron_delete"), ts = nowIso(), requestId = "iroh-cron-delete", success = false, error = "cron over iroh not implemented")
    override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long): ServerFrame.CronDeleteAllResponse =
        ServerFrame.CronDeleteAllResponse(id = frameId("cron_delete_all"), ts = nowIso(), requestId = "iroh-cron-delete-all", success = false, error = "cron over iroh not implemented")
    override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long): ServerFrame.SubagentListResponse =
        ServerFrame.SubagentListResponse(id = frameId("subagent_list"), ts = nowIso(), requestId = "iroh-subagent-list", success = false, error = "subagents over iroh not implemented")
    override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long): ServerFrame.SubagentTodosResponse =
        ServerFrame.SubagentTodosResponse(id = frameId("subagent_todos"), ts = nowIso(), requestId = "iroh-subagent-todos", success = false, error = "subagents over iroh not implemented")

    private fun frameId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    private fun nowIso(): String = Instant.now().toString()

    companion object {
        const val IROH_URL_PREFIX = "iroh://"
        // Bounded window to let the server's own terminal (from abort) arrive
        // before falling back to a synthetic cancelled TurnDone.
        internal const val SERVER_TERMINAL_WAIT_MS = 3_000L
        // letta-mobile-34xoj: admin_rpc retry thresholds
        private const val ADMIN_RPC_FAILURE_THRESHOLD = 3
        private const val STREAM_IDLE_THRESHOLD_MS = 30_000L
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
 * has streamed. Mirrors the reducer's own `iroh-run-` recognition so both sides
 * agree on which ids are promotable placeholders vs canonical server run ids.
 */
private fun String.isIrohSyntheticRunId(): Boolean = startsWith("iroh-run-")
