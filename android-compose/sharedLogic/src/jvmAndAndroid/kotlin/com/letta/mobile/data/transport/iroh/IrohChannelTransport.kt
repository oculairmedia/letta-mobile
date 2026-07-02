package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
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
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private val connectionMutex = Mutex()
    private var endpoint: Endpoint? = null
    private var appServerTransport: IrohAppServerTransport? = null
    private var turnEngine: AppServerTurnEngine? = null
    private var connectedTicket: String? = null
    private var activeSendJob: kotlinx.coroutines.Job? = null

    override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = connectionMutex.withLock {
        val effectiveUrl = forcedIrohUrl.takeIf { it.isNotBlank() }
            ?: DEBUG_FORCE_IROH_URL.takeIf { it.isNotBlank() }
            ?: baseShimUrl.trimStart().removePrefix("https://").removePrefix("http://")
        val ticket = effectiveUrl.removePrefix(IROH_URL_PREFIX).takeIf { it != effectiveUrl && it.isNotBlank() }
            ?: error("IrohChannelTransport requires backend URL iroh://<EndpointTicket>.")
        com.letta.mobile.util.Telemetry.event(
            "IrohTrace", "transport.connect.begin",
            "baseShimUrl" to baseShimUrl,
            "forced" to DEBUG_FORCE_IROH_URL.isNotBlank(),
            "ticketLength" to ticket.length,
            "connectedTicketMatches" to (connectedTicket == ticket),
            "hasEngine" to (turnEngine != null),
            "state" to state.value::class.simpleName,
        )
        if (connectedTicket == ticket && turnEngine != null && state.value is ChannelTransportState.Connected) {
            com.letta.mobile.util.Telemetry.event("IrohTrace", "transport.connect.reuse", "sessionId" to ticket.hashCode().toString())
            return@withLock
        }
        closeCurrentConnection("reconnect")
        _state.value = ChannelTransportState.Connecting()
        onConnect()
        val localEndpoint = runCatching {
            Endpoint.bind(
                EndpointOptions(relayMode = RelayMode.Companion.defaultMode())
            )
        }.onFailure { t ->
            com.letta.mobile.util.Telemetry.event("IrohTransport", "bind.failed", "error" to (t.message ?: t.toString()), "class" to t::class.simpleName)
            _state.value = ChannelTransportState.Disconnected(0, "bind_failed: ${t.message}")
            return@withLock
        }.getOrThrow()
        val transport = IrohAppServerTransportAdapter(localEndpoint).createTransport(
            endpoint = AppServerEndpoint(scheme = "iroh", address = ticket),
            scope = scope,
        ) as IrohAppServerTransport
        endpoint = localEndpoint
        appServerTransport = transport
        connectedTicket = ticket
        val appServerClient = DefaultAppServerClient(transport)
        if (token.isNotBlank()) {
            val auth = appServerClient.auth(
                com.letta.mobile.data.transport.appserver.AppServerCommand.Auth(
                    requestId = "auth-${UUID.randomUUID()}",
                    token = token,
                ),
            )
            if (!auth.success) {
                closeCurrentConnection("auth_failed")
                _state.value = ChannelTransportState.Disconnected(4401, auth.error ?: "auth_failed", isAuthFailure = true)
                error(auth.error ?: "Iroh auth failed")
            }
        }
        turnEngine = AppServerTurnEngine(
            client = appServerClient,
            clientInfo = com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo(
                name = "letta-mobile-android-iroh",
                version = clientVersion,
            ),
        )
        // Await actual QUIC connection before marking Connected. If the
        // handshake fails, the state stays Disconnected and the caller
        // can retry rather than getting a false "Connected" signal.
        runCatching {
            transport.awaitConnectionReady()
        }.onFailure { t ->
            com.letta.mobile.util.Telemetry.event("IrohTransport", "connect.failed", "error" to (t.message ?: t.toString()))
            closeCurrentConnection("connect_failed")
            _state.value = ChannelTransportState.Disconnected(0, "connect_failed: ${t.message}")
            return@withLock
        }
        _state.value = ChannelTransportState.Connected(serverId = "iroh-app-server", sessionId = connectedTicket.hashCode().toString(), deviceId = deviceId, a2uiEnabled = false, a2uiCatalog = null, canonicalLiveTransport = "iroh")
        com.letta.mobile.util.Telemetry.event("IrohTrace", "transport.connect.done", "state" to "connected", "sessionId" to connectedTicket.hashCode().toString())
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
            "hasEngine" to (turnEngine != null),
            "engineBusy" to (turnEngine?.isBusy ?: false),
        )
        val engine = turnEngine ?: return false
        if (engine.isBusy) return false
        val runId = "iroh-run-${UUID.randomUUID()}"
        val turnId = "iroh-turn-${UUID.randomUUID()}"
        val sendJob = scope.launch {
            com.letta.mobile.util.Telemetry.event("IrohTrace", "transport.send.job_start", "turnId" to turnId, "runId" to runId)
            emitBoth(
                ServerFrame.TurnStarted(
                    id = frameId("turn_started"),
                    ts = nowIso(),
                    agentId = agentId,
                    conversationId = conversationId,
                    turnId = turnId,
                    runId = runId,
                ),
            )
            runCatching {
                engine.runTurn(
                    TurnCommand(
                        backendId = BackendId("iroh-app-server"),
                        runtimeId = RuntimeId("iroh:${connectedTicket.hashCode()}"),
                        agentId = AgentId(agentId),
                        conversationId = ConversationId(conversationId),
                        input = TurnInput.UserMessage(
                            localMessageId = otid ?: frameId("local"),
                            text = text,
                            contentPartsJson = contentParts?.toString(),
                        ),
                    ),
                ).collect { draft -> emitDraft(draft, agentId, conversationId, turnId, runId) }
            }.onFailure { error ->
                com.letta.mobile.util.Telemetry.event("IrohTransport", "turn.failed", "error" to (error.message ?: error.toString()), "class" to error::class.simpleName)
                emitBoth(
                    ServerFrame.Error(
                        id = frameId("error"),
                        ts = nowIso(),
                        code = "iroh_app_server_error",
                        message = error.message ?: error.toString(),
                        conversationId = conversationId,
                        turnId = turnId,
                        runId = runId,
                    ),
                )
                emitBoth(
                    ServerFrame.TurnDone(
                        id = frameId("turn_done"),
                        ts = nowIso(),
                        turnId = turnId,
                        runId = runId,
                        status = "failed",
                    ),
                )
            }
        }
        activeSendJob = sendJob
        return true
    }

    private suspend fun emitDraft(
        draft: com.letta.mobile.runtime.RuntimeEventDraft,
        agentId: String,
        conversationId: String,
        turnId: String,
        runId: String,
    ) {
        com.letta.mobile.util.Telemetry.event(
            "IrohTrace", "transport.emitDraft",
            "payload" to (draft.payload::class.simpleName ?: ""),
            "runId" to runId,
        )
        when (val payload = draft.payload) {
            is RuntimeEventPayload.RemoteStreamFrame -> emitBoth(
                ServerFrame.AssistantMessage(
                    id = payload.messageId ?: payload.frameId,
                    ts = nowIso(),
                    agentId = agentId,
                    conversationId = conversationId,
                    turnId = turnId,
                    runId = runId,
                    // The UI maps AssistantMessage.content as a BARE string. The mapper sets
                    // RemoteStreamFrame.body to the raw stream_delta JSON envelope, so extract
                    // the actual assistant text out of it; otherwise the chat renders a JSON blob.
                    content = extractAssistantText(payload.body),
                ),
            )
            // Non-chat App Server frames (update_device_status, update_queue,
            // update_subagent_state, etc.) are side-channel runtime events, not
            // assistant text. Do not fold them into the chat timeline.
            is RuntimeEventPayload.ExternalTransportFrame -> Unit
            is RuntimeEventPayload.RunLifecycleChanged -> when (payload.status) {
                RuntimeRunStatus.Completed -> emitBoth(ServerFrame.TurnDone(id = frameId("turn_done"), ts = nowIso(), turnId = turnId, runId = runId, status = "completed"))
                RuntimeRunStatus.Failed -> {
                    com.letta.mobile.util.Telemetry.event("IrohTransport", "turn.lifecycle_failed", "reason" to (payload.reason ?: ""))
                    emitBoth(ServerFrame.TurnDone(id = frameId("turn_done"), ts = nowIso(), turnId = turnId, runId = runId, status = "failed"))
                }
                RuntimeRunStatus.Cancelled -> emitBoth(ServerFrame.TurnDone(id = frameId("turn_done"), ts = nowIso(), turnId = turnId, runId = runId, status = "cancelled"))
                RuntimeRunStatus.Started, RuntimeRunStatus.Running -> Unit
            }
            else -> Unit
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
    override fun bye(): Boolean = true
    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Failed
    override fun subscribe(runId: String, cursor: Long): Boolean = false

    override suspend fun disconnect() = connectionMutex.withLock {
        closeCurrentConnection("disconnect")
        _state.value = ChannelTransportState.Disconnected(1000, "disconnected")
    }

    private suspend fun closeCurrentConnection(reason: String) {
        com.letta.mobile.util.Telemetry.event(
            "IrohTrace", "transport.closeCurrent",
            "reason" to reason,
            "hasTransport" to (appServerTransport != null),
            "hasEndpoint" to (endpoint != null),
            "hasEngine" to (turnEngine != null),
        )
        runCatching { activeSendJob?.cancel() }
        activeSendJob = null
        runCatching { appServerTransport?.close() }
        appServerTransport = null
        runCatching { endpoint?.shutdown() }
        runCatching { endpoint?.close() }
        endpoint = null
        turnEngine = null
        connectedTicket = null
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

    /**
     * Extracts the bare assistant text from a stream_delta body. The App Server
     * mapper passes the raw stream_delta JSON envelope as the body
     * (`{"type":"stream_delta",...,"delta":{"content":"..."}}`). The mobile chat
     * UI renders [ServerFrame.AssistantMessage.content] as a plain string, so the
     * actual text must be unwrapped here. Falls back to the raw body when it isn't
     * a recognizable envelope (already-plain text or a different shape).
     */
    private fun extractAssistantText(body: String): String {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) return body
        return runCatching {
            val obj = lenientJson.parseToJsonElement(body).jsonObject
            val delta = obj["delta"]?.jsonObject ?: obj
            delta["content"]?.jsonPrimitive?.contentOrNull
                ?: delta["text"]?.jsonPrimitive?.contentOrNull
                ?: delta["message"]?.jsonPrimitive?.contentOrNull
                ?: obj["content"]?.jsonPrimitive?.contentOrNull
                ?: body
        }.getOrDefault(body)
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        const val IROH_URL_PREFIX = "iroh://"
        // Debug override for local Iroh testing. MUST stay blank in committed
        // code — a non-blank value forces EVERY backend through Iroh regardless
        // of the active config (breaks REST/local-runtime selection). Set it
        // only in a throwaway local build when dialing a hand-run wrapper.
        private const val DEBUG_FORCE_IROH_URL = ""
        fun shouldUseIroh(url: String?): Boolean = DEBUG_FORCE_IROH_URL.isNotBlank() || isIrohUrl(url)

        fun isIrohUrl(url: String?): Boolean {
            // Handle bare iroh://, https://iroh:// (corrupted saved config), etc.
            if (url == null) return false
            val stripped = url.trimStart().removePrefix("https://").removePrefix("http://")
            return stripped.startsWith(IROH_URL_PREFIX)
        }
    }
}