package com.letta.mobile.web.iroh

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class IrohWasmAppServerTransport private constructor(
    private val bridge: IrohWasmBridge,
    parentScope: CoroutineScope,
) : AppServerTransport {
    private val transportJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + transportJob)
    private val control = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER)
    private val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER)
    private val connected = MutableStateFlow(true)
    private var closed = false

    override val controlFrames: Flow<AppServerReceivedFrame> = control.asSharedFlow()
    override val streamFrames: Flow<AppServerReceivedFrame> = stream.asSharedFlow()
    override val isConnected: Flow<Boolean> = connected.asStateFlow()

    init {
        scope.launch { pump(AppServerChannel.Control, bridge::pollControl, control) }
        scope.launch { pump(AppServerChannel.Stream, bridge::pollStream, stream) }
    }

    override suspend fun sendControl(command: AppServerCommand) {
        check(connected.value) { "Iroh App Server transport is disconnected" }
        bridge.sendControl(AppServerProtocol.encodeCommand(command))
    }

    suspend fun close() {
        if (closed) return
        closed = true
        connected.value = false
        withContext(NonCancellable) {
            transportJob.cancelAndJoin()
            bridge.close()
        }
    }

    private suspend fun pump(
        channel: AppServerChannel,
        poll: () -> String?,
        destination: MutableSharedFlow<AppServerReceivedFrame>,
    ) {
        try {
            while (currentCoroutineContext().isActive && connected.value) {
                val raw = poll()
                if (raw != null) {
                    destination.emit(AppServerProtocol.decodeFrame(raw, channel))
                    continue
                }
                when (bridge.state()) {
                    "connected" -> delay(POLL_INTERVAL_MS)
                    "error" -> error(bridge.error() ?: "Iroh App Server stream failed")
                    else -> return
                }
            }
        } finally {
            connected.value = false
        }
    }

    companion object {
        private const val FRAME_BUFFER = 64
        private const val POLL_INTERVAL_MS = 10L

        suspend fun connect(ticket: String, scope: CoroutineScope): IrohWasmAppServerTransport =
            IrohWasmAppServerTransport(IrohWasmBridge.connect(ticket), scope)
    }
}
