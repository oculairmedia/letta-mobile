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
import kotlinx.serialization.json.JsonArray
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
) : IChannelTransport {
    private val _state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Idle)
    override val state: StateFlow<ChannelTransportState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 64)
    override val events: SharedFlow<ServerFrame> = _events.asSharedFlow()

    private val _frameEvents = MutableSharedFlow<TransportFrameEvent>(extraBufferCapacity = 64)
    override val frameEvents: SharedFlow<TransportFrameEvent> = _frameEvents.asSharedFlow()

    private var endpoint: Endpoint? = null
    private var turnEngine: AppServerTurnEngine? = null
    private var connectedTicket: String? = null

    override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) {
        val ticket = baseShimUrl.removePrefix(IROH_URL_PREFIX).takeIf { it != baseShimUrl && it.isNotBlank() }
            ?: error("IrohChannelTransport requires backend URL iroh://<EndpointTicket>.")
        disconnect()
        _state.value = ChannelTransportState.Connecting()
        val localEndpoint = Endpoint.bind(EndpointOptions())
        val transport = IrohAppServerTransportAdapter(localEndpoint).createTransport(
            endpoint = AppServerEndpoint(scheme = "iroh", address = ticket),
            scope = scope,
        )
        endpoint = localEndpoint
        connectedTicket = ticket
        turnEngine = AppServerTurnEngine(
            client = DefaultAppServerClient(transport),
            clientInfo = com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo(
                name = "letta-mobile-android-iroh",
                version = clientVersion,
            ),
        )
        _state.value = ChannelTransportState.Connected(serverId = "iroh-app-server", sessionId = connectedTicket.hashCode().toString(), deviceId = deviceId, a2uiEnabled = false, a2uiCatalog = null, canonicalLiveTransport = "iroh")
    }

    override fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String?,
        contentParts: JsonArray?,
        startNewConversation: Boolean,
    ): Boolean {
        val engine = turnEngine ?: return false
        val runId = "iroh-run-${UUID.randomUUID()}"
        val turnId = "iroh-turn-${UUID.randomUUID()}"
        scope.launch {
            _events.emit(
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
                        ),
                    ),
                ).collect { draft -> emitDraft(draft, agentId, conversationId, turnId, runId) }
            }.onFailure { error ->
                _events.emit(
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
                _events.emit(
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
        return true
    }

    private suspend fun emitDraft(
        draft: com.letta.mobile.runtime.RuntimeEventDraft,
        agentId: String,
        conversationId: String,
        turnId: String,
        runId: String,
    ) {
        when (val payload = draft.payload) {
            is RuntimeEventPayload.RemoteStreamFrame -> _events.emit(
                ServerFrame.AssistantMessage(
                    id = payload.messageId ?: payload.frameId,
                    ts = nowIso(),
                    agentId = agentId,
                    conversationId = conversationId,
                    turnId = turnId,
                    runId = runId,
                    content = payload.body,
                ),
            )
            is RuntimeEventPayload.ExternalTransportFrame -> _events.emit(
                ServerFrame.AssistantMessage(
                    id = payload.transportMessageId ?: payload.frameId,
                    ts = nowIso(),
                    agentId = agentId,
                    conversationId = conversationId,
                    turnId = turnId,
                    runId = runId,
                    content = payload.body,
                ),
            )
            is RuntimeEventPayload.RunLifecycleChanged -> when (payload.status) {
                RuntimeRunStatus.Completed -> _events.emit(ServerFrame.TurnDone(id = frameId("turn_done"), ts = nowIso(), turnId = turnId, runId = runId, status = "completed"))
                RuntimeRunStatus.Failed -> _events.emit(ServerFrame.TurnDone(id = frameId("turn_done"), ts = nowIso(), turnId = turnId, runId = runId, status = "failed"))
                RuntimeRunStatus.Cancelled -> _events.emit(ServerFrame.TurnDone(id = frameId("turn_done"), ts = nowIso(), turnId = turnId, runId = runId, status = "cancelled"))
                RuntimeRunStatus.Started, RuntimeRunStatus.Running -> Unit
            }
            else -> Unit
        }
    }

    override fun cancel(conversationId: String): Boolean = false
    override fun bye(): Boolean = true
    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Failed
    override fun subscribe(runId: String, cursor: Long): Boolean = false

    override suspend fun disconnect() {
        runCatching { endpoint?.shutdown() }
        runCatching { endpoint?.close() }
        endpoint = null
        turnEngine = null
        _state.value = ChannelTransportState.Disconnected(1000, "disconnected")
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
        fun isIrohUrl(url: String?): Boolean = url?.startsWith(IROH_URL_PREFIX) == true
    }
}
