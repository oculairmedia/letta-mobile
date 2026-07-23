package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Typed client for one Letta Code App Server process.
 *
 * The upstream App Server exposes one writable control channel per process plus
 * a receive-only stream channel. Use one direct client/transport as the control
 * owner for a runtime process; multi-client remote access needs an external
 * fanout/arbitration layer instead of several clients writing to the same
 * process.
 */
interface AppServerClient {
    val events: Flow<AppServerReceivedFrame>
    val isConnected: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(true)

    suspend fun auth(command: AppServerCommand.Auth): AppServerInboundFrame.AuthResponse =
        AppServerInboundFrame.AuthResponse(requestId = command.requestId, success = true)

    suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse

    suspend fun input(command: AppServerCommand.Input)

    suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse

    suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse

    suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse

    suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse)

    // Runtime-native admin operations (lgns8.7). Defaults throw so existing
    // fakes keep compiling; real transports override via the request registry.

    suspend fun agentList(command: AppServerCommand.AgentList): AppServerInboundFrame.AgentListResponse =
        throw UnsupportedOperationException("agent_list is not supported by this client")

    suspend fun agentRetrieve(command: AppServerCommand.AgentRetrieve): AppServerInboundFrame.AgentRetrieveResponse =
        throw UnsupportedOperationException("agent_retrieve is not supported by this client")

    suspend fun agentCreate(command: AppServerCommand.AgentCreate): AppServerInboundFrame.AgentCreateResponse =
        throw UnsupportedOperationException("agent_create is not supported by this client")

    suspend fun agentUpdate(command: AppServerCommand.AgentUpdate): AppServerInboundFrame.AgentUpdateResponse =
        throw UnsupportedOperationException("agent_update is not supported by this client")

    suspend fun agentDelete(command: AppServerCommand.AgentDelete): AppServerInboundFrame.AgentDeleteResponse =
        throw UnsupportedOperationException("agent_delete is not supported by this client")

    suspend fun conversationList(command: AppServerCommand.ConversationList): AppServerInboundFrame.ConversationListResponse =
        throw UnsupportedOperationException("conversation_list is not supported by this client")

    suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve): AppServerInboundFrame.ConversationRetrieveResponse =
        throw UnsupportedOperationException("conversation_retrieve is not supported by this client")

    suspend fun conversationCreate(command: AppServerCommand.ConversationCreate): AppServerInboundFrame.ConversationCreateResponse =
        throw UnsupportedOperationException("conversation_create is not supported by this client")

    suspend fun conversationUpdate(command: AppServerCommand.ConversationUpdate): AppServerInboundFrame.ConversationUpdateResponse =
        throw UnsupportedOperationException("conversation_update is not supported by this client")

    suspend fun conversationMessagesList(
        command: AppServerCommand.ConversationMessagesList,
    ): AppServerInboundFrame.ConversationMessagesListResponse =
        throw UnsupportedOperationException("conversation_messages_list is not supported by this client")
}

class DefaultAppServerClient(
    private val transport: AppServerTransport,
    requestTimeoutMs: Long = AppServerRequestRegistry.DEFAULT_REQUEST_TIMEOUT_MS,
    parentScope: CoroutineScope? = null,
) : AppServerClient {
    private val registry = AppServerRequestRegistry(
        controlFrames = transport.controlFrames,
        timeoutMs = requestTimeoutMs,
    )

    override val events: Flow<AppServerReceivedFrame> = transport.mergedFrames()
    override val isConnected: Flow<Boolean> = transport.isConnected

    init {
        // Start the registry's inbound router if a scope is provided.
        // When parentScope is null (e.g. in unit tests using FakeAppServerTransport),
        // the caller is responsible for starting the registry.
        parentScope?.let {
            registry.startRouting(it)
            it.launch {
                transport.isConnected.dropWhile { it }.collect {
                    registry.failAll(CancellationException("transport disconnected"))
                    return@collect
                }
            }
        }
    }

    override suspend fun auth(command: AppServerCommand.Auth): AppServerInboundFrame.AuthResponse =
        registry.request(
            requestId = command.requestId,
            response = { it as? AppServerInboundFrame.AuthResponse },
            send = { transport.sendControl(command) },
        )

    override suspend fun runtimeStart(
        command: AppServerCommand.RuntimeStart,
    ): AppServerInboundFrame.RuntimeStartResponse =
        registry.request(
            requestId = command.requestId,
            response = { it as? AppServerInboundFrame.RuntimeStartResponse },
            send = { transport.sendControl(command) },
        )

    override suspend fun input(command: AppServerCommand.Input) {
        transport.sendControl(command)
    }

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse {
        val requestId = requireNotNull(command.requestId) {
            "sync requires request_id when using response correlation."
        }
        return registry.request(
            requestId = requestId,
            response = { it as? AppServerInboundFrame.SyncResponse },
            send = { transport.sendControl(command) },
        )
    }

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
        val requestId = requireNotNull(command.requestId) {
            "abort_message requires request_id when using response correlation."
        }
        return registry.request(
            requestId = requestId,
            response = { it as? AppServerInboundFrame.AbortMessageResponse },
            send = { transport.sendControl(command) },
        )
    }

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
        registry.request(
            requestId = command.requestId,
            response = { it as? AppServerInboundFrame.AdminRpcResponse },
            send = { transport.sendControl(command) },
        )

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
        transport.sendControl(command)
    }

    override suspend fun agentList(command: AppServerCommand.AgentList): AppServerInboundFrame.AgentListResponse =
        registry.request(command.requestId, { it as? AppServerInboundFrame.AgentListResponse }) { transport.sendControl(command) }

    override suspend fun agentRetrieve(command: AppServerCommand.AgentRetrieve): AppServerInboundFrame.AgentRetrieveResponse =
        registry.request(command.requestId, { it as? AppServerInboundFrame.AgentRetrieveResponse }) { transport.sendControl(command) }

    override suspend fun agentCreate(command: AppServerCommand.AgentCreate): AppServerInboundFrame.AgentCreateResponse =
        registry.request(command.requestId, { it as? AppServerInboundFrame.AgentCreateResponse }) { transport.sendControl(command) }

    override suspend fun agentUpdate(command: AppServerCommand.AgentUpdate): AppServerInboundFrame.AgentUpdateResponse =
        registry.request(command.requestId, { it as? AppServerInboundFrame.AgentUpdateResponse }) { transport.sendControl(command) }

    override suspend fun agentDelete(command: AppServerCommand.AgentDelete): AppServerInboundFrame.AgentDeleteResponse =
        registry.request(command.requestId, { it as? AppServerInboundFrame.AgentDeleteResponse }) { transport.sendControl(command) }

    override suspend fun conversationList(command: AppServerCommand.ConversationList): AppServerInboundFrame.ConversationListResponse =
        registry.request(command.requestId, { it as? AppServerInboundFrame.ConversationListResponse }) { transport.sendControl(command) }

    override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve): AppServerInboundFrame.ConversationRetrieveResponse =
        registry.request(command.requestId, { it as? AppServerInboundFrame.ConversationRetrieveResponse }) { transport.sendControl(command) }

    override suspend fun conversationCreate(command: AppServerCommand.ConversationCreate): AppServerInboundFrame.ConversationCreateResponse =
        registry.request(command.requestId, { it as? AppServerInboundFrame.ConversationCreateResponse }) { transport.sendControl(command) }

    override suspend fun conversationUpdate(command: AppServerCommand.ConversationUpdate): AppServerInboundFrame.ConversationUpdateResponse =
        registry.request(command.requestId, { it as? AppServerInboundFrame.ConversationUpdateResponse }) { transport.sendControl(command) }

    override suspend fun conversationMessagesList(
        command: AppServerCommand.ConversationMessagesList,
    ): AppServerInboundFrame.ConversationMessagesListResponse =
        registry.request(command.requestId, { it as? AppServerInboundFrame.ConversationMessagesListResponse }) { transport.sendControl(command) }
}
