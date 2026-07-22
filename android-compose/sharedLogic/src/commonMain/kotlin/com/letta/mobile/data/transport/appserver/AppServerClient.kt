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
}
