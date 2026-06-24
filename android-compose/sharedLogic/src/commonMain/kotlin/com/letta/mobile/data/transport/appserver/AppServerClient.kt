package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.flow.Flow

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

    suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse

    suspend fun input(command: AppServerCommand.Input)

    suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse

    suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse

    suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse)
}

class DefaultAppServerClient(
    private val transport: AppServerTransport,
    requestTimeoutMs: Long = AppServerRequestCorrelator.DEFAULT_REQUEST_TIMEOUT_MS,
) : AppServerClient {
    private val correlator = AppServerRequestCorrelator(
        controlFrames = transport.controlFrames,
        timeoutMs = requestTimeoutMs,
    )

    override val events: Flow<AppServerReceivedFrame> = transport.mergedFrames()

    override suspend fun runtimeStart(
        command: AppServerCommand.RuntimeStart,
    ): AppServerInboundFrame.RuntimeStartResponse =
        correlator.request(
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
        return correlator.request(
            requestId = requestId,
            response = { it as? AppServerInboundFrame.SyncResponse },
            send = { transport.sendControl(command) },
        )
    }

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
        val requestId = requireNotNull(command.requestId) {
            "abort_message requires request_id when using response correlation."
        }
        return correlator.request(
            requestId = requestId,
            response = { it as? AppServerInboundFrame.AbortMessageResponse },
            send = { transport.sendControl(command) },
        )
    }

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
        transport.sendControl(command)
    }
}
