package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolExecutionStatus
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
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
) : IChannelTransport {
    private val _state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Idle)
    override val state: StateFlow<ChannelTransportState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 64)
    override val events: SharedFlow<ServerFrame> = _events.asSharedFlow()

    private val _frameEvents = MutableSharedFlow<TransportFrameEvent>(extraBufferCapacity = 64)
    override val frameEvents: SharedFlow<TransportFrameEvent> = _frameEvents.asSharedFlow()

    /** Emit to both event flows so both direct consumers and
     *  WsChatBridge (via frameEvents) see each frame exactly once. */
    private suspend fun emitBoth(frame: ServerFrame) {
        Telemetry.event(
            "IrohGate", "gate1.emitBoth",
            "frame" to (frame::class.simpleName ?: ""),
            "messageId" to frameMessageId(frame),
            "conversationId" to frameConversationId(frame),
        )
        _events.emit(frame)
        _frameEvents.emit(TransportFrameEvent(frame = frame))
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
        onStateChanged = { supervisorState -> _state.value = supervisorState.toChannelTransportState() },
    )

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
            ?: config.baseShimUrl.trimStart().removePrefix("https://").removePrefix("http://")
        val ticket = effectiveUrl.removePrefix(IROH_URL_PREFIX).takeIf { it != effectiveUrl && it.isNotBlank() }
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
        return runCatching {
            transport = IrohAppServerTransportAdapter(
                endpoint = localEndpoint,
                onConnectionLost = { reason -> supervisor.onConnectionLostAsync(reason) },
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
            )
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
        return promotionFrames + when (val payload = draft.payload) {
            is RuntimeEventPayload.RemoteStreamFrame -> IrohStreamDeltaServerFrameMapper.map(
                payload = payload,
                context = IrohStreamDeltaServerFrameMapper.Context(
                    agentId = agentId,
                    conversationId = conversationId,
                    turnId = turnId,
                    runId = effectiveRunId,
                    timestamp = nowIso(),
                ),
            )
            // Non-chat App Server frames (update_device_status, update_queue,
            // update_subagent_state, etc.) are side-channel runtime events, not
            // assistant text. Do not fold them into the chat timeline.
            is RuntimeEventPayload.ExternalTransportFrame -> emptyList()
            is RuntimeEventPayload.ToolCallObserved -> listOf(
                ServerFrame.ToolCallMessage(
                    id = "toolcall-${payload.toolCallId.value}",
                    ts = nowIso(),
                    agentId = agentId,
                    conversationId = conversationId,
                    turnId = turnId,
                    runId = effectiveRunId,
                    toolCall = ToolCallPayload(
                        toolCallId = payload.toolCallId.value,
                        name = payload.toolName.value,
                        arguments = payload.argumentsJson ?: "{}",
                    ),
                    seq = null,
                ),
            )
            is RuntimeEventPayload.ToolReturnObserved -> listOf(
                ServerFrame.ToolReturnMessage(
                    id = "toolreturn-${payload.toolCallId.value}",
                    ts = nowIso(),
                    agentId = agentId,
                    conversationId = conversationId,
                    turnId = turnId,
                    runId = effectiveRunId,
                    toolCallId = payload.toolCallId.value,
                    status = if (payload.status == ToolExecutionStatus.Failed) "error" else "success",
                    toolReturn = JsonPrimitive(payload.body),
                ),
            )
            is RuntimeEventPayload.ApprovalRequested -> listOf(
                ServerFrame.ToolCallMessage(
                    type = "approval_request_message",
                    id = payload.request.approvalId.value,
                    ts = nowIso(),
                    agentId = agentId,
                    conversationId = conversationId,
                    turnId = turnId,
                    runId = effectiveRunId,
                    toolCall = ToolCallPayload(
                        toolCallId = payload.request.callId.value,
                        name = payload.request.toolName.value,
                        arguments = payload.request.argumentsPreview ?: "{}",
                    ),
                    seq = null,
                ),
            )
            is RuntimeEventPayload.RunLifecycleChanged -> when (payload.status) {
                RuntimeRunStatus.Completed -> listOf(ServerFrame.TurnDone(id = frameId("turn_done"), ts = nowIso(), turnId = turnId, runId = effectiveRunId, status = "completed"))
                RuntimeRunStatus.Failed -> {
                    com.letta.mobile.util.Telemetry.event("IrohTransport", "turn.lifecycle_failed", "reason" to (payload.reason ?: ""))
                    listOf(ServerFrame.TurnDone(id = frameId("turn_done"), ts = nowIso(), turnId = turnId, runId = effectiveRunId, status = "failed"))
                }
                RuntimeRunStatus.Cancelled -> listOf(ServerFrame.TurnDone(id = frameId("turn_done"), ts = nowIso(), turnId = turnId, runId = effectiveRunId, status = "cancelled"))
                RuntimeRunStatus.Started, RuntimeRunStatus.Running -> emptyList()
            }
            else -> emptyList()
        }
    }

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
        val first = supervisor.ready()
        return runCatching {
            first.adminRpc(method = method, path = path, body = body)
        }.getOrElse { firstError ->
            if (firstError is CancellationException) throw firstError
            if (!firstError.isConnectionLostClass()) throw firstError
            if (!method.isReadOnlyAdminRpcMethod()) throw firstError
            com.letta.mobile.util.Telemetry.event(
                "IrohTransport", "admin_rpc.retry_after_failure",
                "method" to method,
                "path" to path,
                "error" to (firstError.message ?: firstError.toString()),
                "class" to firstError::class.simpleName,
            )
            supervisor.onConnectionLost("admin_rpc_failed: ${firstError.message ?: firstError.toString()}")
            val retry = supervisor.ready()
            retry.adminRpc(method = method, path = path, body = body)
        }
    }

    private fun String.isReadOnlyAdminRpcMethod(): Boolean = this in READ_ONLY_ADMIN_RPC_METHODS

    override suspend fun disconnect() {
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
        )

        fun isIrohUrl(url: String?): Boolean {
            // Handle bare iroh://, https://iroh:// (corrupted saved config), etc.
            if (url == null) return false
            val stripped = url.trimStart().removePrefix("https://").removePrefix("http://")
            return stripped.startsWith(IROH_URL_PREFIX)
        }
    }
}

/**
 * A client-synthesized run id placeholder used before the server's real run id
 * has streamed. Mirrors the reducer's own `iroh-run-` recognition so both sides
 * agree on which ids are promotable placeholders vs canonical server run ids.
 */
private fun String.isIrohSyntheticRunId(): Boolean = startsWith("iroh-run-")
