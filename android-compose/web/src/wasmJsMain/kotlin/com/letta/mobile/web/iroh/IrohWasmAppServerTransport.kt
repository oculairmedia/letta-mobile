package com.letta.mobile.web.iroh

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

internal class IrohWasmAppServerTransport private constructor(
    private val bridge: IrohWasmBridge,
    parentScope: CoroutineScope,
) : AppServerTransport {
    private val transportJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + transportJob)
    private val control = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER)
    private val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER)
    private val connected = MutableStateFlow(true)
    private val failure = MutableStateFlow<String?>(null)
    private var controlPump: Job? = null
    private var streamPump: Job? = null
    private var closed = false

    override val controlFrames: Flow<AppServerReceivedFrame> = control.onSubscription { startControlPump() }
    override val streamFrames: Flow<AppServerReceivedFrame> = stream.onSubscription { startStreamPump() }
    override val isConnected: Flow<Boolean> = connected.asStateFlow()
    internal val failureReason: StateFlow<String?> = failure.asStateFlow()

    private fun startControlPump() {
        if (controlPump == null) {
            controlPump = scope.launch { pump(AppServerChannel.Control, bridge::pollControl, control) }
        }
    }

    private fun startStreamPump() {
        if (streamPump == null) {
            streamPump = scope.launch { pump(AppServerChannel.Stream, bridge::pollStream, stream) }
        }
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
        var pollIntervalMs = MIN_POLL_INTERVAL_MS
        try {
            while (currentCoroutineContext().isActive && connected.value) {
                val raw = poll()
                if (raw != null) {
                    destination.emit(AppServerProtocol.decodeFrame(raw, channel))
                    pollIntervalMs = MIN_POLL_INTERVAL_MS
                    continue
                }
                when (bridge.state()) {
                    "connected" -> {
                        delay(pollIntervalMs.milliseconds)
                        pollIntervalMs = (pollIntervalMs * 2).coerceAtMost(MAX_POLL_INTERVAL_MS)
                    }
                    "error" -> error(bridge.error() ?: "Iroh App Server stream failed")
                    else -> return
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure.value = error.message ?: "Iroh App Server stream failed"
        } finally {
            connected.value = false
        }
    }

    companion object {
        private const val FRAME_BUFFER = 64
        private const val MIN_POLL_INTERVAL_MS = 10L
        private const val MAX_POLL_INTERVAL_MS = 100L

        suspend fun connect(ticket: String, scope: CoroutineScope): IrohWasmAppServerTransport =
            IrohWasmAppServerTransport(IrohWasmBridge.connect(ticket), scope)
    }
}
