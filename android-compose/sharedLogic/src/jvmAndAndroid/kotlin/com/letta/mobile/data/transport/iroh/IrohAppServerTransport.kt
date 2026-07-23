package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTransport
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.PathChangeCallback
import computer.iroh.PathEventCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlinx.serialization.json.put
import com.letta.mobile.util.Telemetry

import kotlin.time.Duration.Companion.milliseconds
/**
 * Iroh-backed App Server transport.
 *
 * Implements the [AppServerTransport] interface over QUIC connections via the
 * iroh-ffi binding. Models the App Server's dual-channel protocol (control + stream)
 * as two QUIC bidirectional streams over a single iroh connection.
 *
 * The control channel is used for sending commands and receiving responses with
 * request_id correlation. The stream channel is receive-only and carries streaming
 * events (stream_delta, update_loop_status, etc.).
 *
 * App Server v2 frames are sent/received UNCHANGED — this is pure transport,
 * not protocol transformation.
 *
 * @param endpoint The iroh endpoint to use for connections (must be bound)
 * @param remoteAddr The remote endpoint address (NodeID + optional relay/direct addrs)
 * @param alpn The ALPN protocol identifier (e.g., "/letta/appserver/0")
 * @param scope The coroutine scope for transport I/O jobs
 * @param protocol The App Server protocol codec (typically [AppServerProtocol])
 */
class IrohAppServerTransport(
    private val endpoint: Endpoint,
    private val remoteAddr: EndpointAddr,
    private val alpn: ByteArray,
    scope: CoroutineScope,
    private val protocol: AppServerProtocol = AppServerProtocol,
    private val onConnectionLost: (String) -> Unit = {},
) : AppServerTransport {
    private val controlCommandQueue = Channel<AppServerCommand>(Channel.BUFFERED)
    private val controlFrameFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
    private val streamFrameFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)

    /**
     * Tracks whether this transport still has a live QUIC connection. Starts
     * `true` (matches [AppServerTransport]'s default) and flips to `false` at
     * the two drop points: a reader loop exiting ([reportReaderExit]) or the
     * initial connect failing ([runControlChannel]). Lets desktop's heartbeat
     * gate (`client.isConnected.first()`) actually break on a QUIC drop
     * instead of spinning forever against the interface's `flowOf(true)`
     * default.
     */
    private val connected = MutableStateFlow(true)

    /**
     * Synchronous liveness read of the underlying QUIC connection. Flips false
     * only on a real reader-exit / [close] (lines that set `connected.value =
     * false`). Used by [IrohChannelTransport.adminRpc] to keep a single request's
     * failure from tearing down a connection that is actually still alive
     * (request isolation) — a per-request error (unimplemented method, this
     * request's own timeout) must never cancel every other in-flight read on the
     * shared connection.
     */
    val isConnectionAlive: Boolean get() = connected.value

    // Connection and streams, initialized in background jobs
    private lateinit var connection: Connection
    private lateinit var controlBiStream: BiStream
    private lateinit var streamBiStream: BiStream
    private var pathWatchJob: Job? = null
    private val readerExitReported = AtomicBoolean(false)
    private val legacyAdminRpcResponsesMutex = Mutex()
    private val legacyAdminRpcResponses = mutableMapOf<String, CompletableDeferred<AppServerInboundFrame.AdminRpcResponse>>()
    private val connectionReady = CompletableDeferred<Unit>()
    private val streamReady = CompletableDeferred<Unit>()

    /**
     * Whether the server advertised the `frame_part` capability in its
     * auth_response (sniffed off the control channel). Until then all
     * client-to-server frames stay plain, so pre-negotiation writes to old
     * servers can never emit framing the peer cannot decode.
     */
    private val serverSupportsFrameParts = AtomicBoolean(false)

    /**
     * Awaits the initial QUIC connection completion (success or failure).
     * The transport is not fully usable until this completes successfully.
     * Throws the connection error if the handshake failed.
     */
    suspend fun awaitConnectionReady() {
        connectionReady.await()
        streamReady.await()
    }

    // Catches any Iroh FFI exception that escapes per-coroutine error handling
    // (e.g. IrohError timed out from keepalive, stream reader, or accept loops).
    // Without this, an uncaught IrohException in a scope.launch propagates to
    // the thread's default uncaught exception handler and crashes the app.
    private val irohExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Telemetry.event("IrohTransport", "crash.caught", "error" to (throwable.message ?: throwable.toString()), "class" to throwable::class.simpleName)
    }

    private val controlJob = scope.launch(irohExceptionHandler) {
        runControlChannel()
    }

    private val streamJob = scope.launch(irohExceptionHandler) {
        connectionReady.await()
        try {
            runStreamChannel()
        } catch (t: Throwable) {
            if (!streamReady.isCompleted) streamReady.completeExceptionally(t)
            throw t
        }
    }

    // c0qm0 instrumentation: log WHY/WHEN the QUIC connection closes. If the
    // connection drops mid-conversation, this names the cause (idle, server
    // close, error) so the multi-turn churn can be diagnosed precisely.
    private val closeWatcher = scope.launch {
        if (runCatching { connectionReady.await() }.isFailure || !::connection.isInitialized) return@launch
        val activeConnection = connection
        val reason = runCatching { activeConnection.closed() }.getOrNull()
        val closeReason = IrohDiagnostics.closeReason(reason)
        Telemetry.event(
            "IrohTransport", "connection.closed",
            *(IrohDiagnostics.closeAttributes(activeConnection) + ("reason" to closeReason)).toTypedArray(),
        )
        onConnectionLost("connection_closed: $closeReason")
    }

    // c0qm0 keepalive: Iroh exposes no idle-timeout knob, so proactively keep the
    // QUIC connection warm by sending a tiny datagram on an interval. A datagram
    // on the wire resets the peer's idle timer and prevents the mid-conversation
    // drop that orphaned later turns' response streams. The server ignores these
    // (it never reads datagrams); they exist only to keep the path alive.
    private val keepAliveJob = scope.launch {
        if (runCatching { connectionReady.await() }.isFailure || !::connection.isInitialized) return@launch
        val activeConnection = connection
        while (true) {
            kotlinx.coroutines.delay(KEEPALIVE_INTERVAL_MS.milliseconds)
            val ok = runCatching { activeConnection.sendDatagram(KEEPALIVE_PAYLOAD) }.isSuccess
            if (!ok) {
                Telemetry.event("IrohTransport", "keepalive.stopped")
                break
            }
        }
    }

    override val controlFrames: Flow<AppServerReceivedFrame> = controlFrameFlow.asSharedFlow()
    override val streamFrames: Flow<AppServerReceivedFrame> = streamFrameFlow.asSharedFlow()
    override val isConnected: Flow<Boolean> get() = connected

    override suspend fun sendControl(command: AppServerCommand) {
        Telemetry.event("IrohTransport", "control.enqueue", "command" to command::class.simpleName)
        controlCommandQueue.send(command)
    }

    suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
        connectionReady.await()
        val requestId = "admin-${UUID.randomUUID()}"
        val params = adminRpcParams(path = path, body = body)
        return try {
            adminRpcOverStream(requestId, method, path, params)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (streamError: Throwable) {
            if (!method.isLegacyFallbackSafeAdminRpcMethod()) {
                Telemetry.event(
                    "IrohTransport", "admin_rpc.stream.fallback_skipped",
                    "method" to method,
                    "path" to path,
                    "requestId" to requestId,
                    "error" to (streamError.message ?: streamError.toString()),
                    "class" to streamError::class.simpleName,
                )
                throw streamError
            }
            Telemetry.event(
                "IrohTransport", "admin_rpc.stream.fallback_control",
                "method" to method,
                "path" to path,
                "requestId" to requestId,
                "error" to (streamError.message ?: streamError.toString()),
                "class" to streamError::class.simpleName,
            )
            adminRpcOverControl(requestId, method, params)
        }
    }

    private suspend fun adminRpcOverStream(
        requestId: String,
        method: String,
        path: String,
        params: JsonObject,
    ): AppServerInboundFrame.AdminRpcResponse = withTimeoutOrNull(ADMIN_RPC_TIMEOUT_MS.milliseconds) {
        val biStream = connection.openBi()
        val sendStream = biStream.send()
        var sendFinished = false
        Telemetry.event("IrohTransport", "admin_rpc.stream.open", "method" to method, "path" to path, "requestId" to requestId)
        try {
            val request = protocol.encodeCommand(AppServerCommand.AdminRpc(requestId = requestId, method = method, params = params))
            IrohFrameCodec.write(sendStream, request, MAX_FRAME_BYTES, allowFrameParts = serverSupportsFrameParts.get())
            sendStream.finish()
            sendFinished = true
            val rawResponse = IrohFrameCodec.readOne(biStream.recv(), MAX_FRAME_BYTES)
                ?: error("admin_rpc stream closed before response for method=$method path=$path")
            val response = decodeFrame(rawResponse, AppServerChannel.Control).frame
            val adminResponse = response as? AppServerInboundFrame.AdminRpcResponse
                ?: error("admin_rpc expected admin_rpc_response but received ${response.type}")
            Telemetry.event("IrohTransport", "admin_rpc.stream.complete", "method" to method, "path" to path, "requestId" to requestId, "success" to adminResponse.success.toString())
            adminResponse
        } catch (error: Throwable) {
            Telemetry.event("IrohTransport", "admin_rpc.stream.failed", "method" to method, "path" to path, "requestId" to requestId, "error" to (error.message ?: error.toString()), "class" to error::class.simpleName)
            throw error
        } finally {
            if (!sendFinished) {
                runCatching { sendStream.finish() }
            }
        }
    } ?: error("admin_rpc timed out for method=$method path=$path")

    private suspend fun adminRpcOverControl(
        requestId: String,
        method: String,
        params: JsonObject,
    ): AppServerInboundFrame.AdminRpcResponse {
        val pending = CompletableDeferred<AppServerInboundFrame.AdminRpcResponse>()
        legacyAdminRpcResponsesMutex.withLock { legacyAdminRpcResponses[requestId] = pending }
        return try {
            sendControl(AppServerCommand.AdminRpc(requestId = requestId, method = method, params = params))
            withTimeoutOrNull(ADMIN_RPC_TIMEOUT_MS.milliseconds) { pending.await() }
                ?: error("legacy admin_rpc timed out for method=$method")
        } finally {
            legacyAdminRpcResponsesMutex.withLock { legacyAdminRpcResponses.remove(requestId) }
        }
    }

    suspend fun close() {
        controlCommandQueue.close()
        runCatching { keepAliveJob.cancelAndJoin() }
        runCatching { pathWatchJob?.cancelAndJoin() }
        runCatching { closeWatcher.cancelAndJoin() }
        controlJob.cancelAndJoin()
        streamJob.cancelAndJoin()
        
        // Clean up streams and connection
        runCatching { controlBiStream.send().finish() }
        runCatching { streamBiStream.send().finish() }
        runCatching { connection.close() }
    }

    private suspend fun runControlChannel() = coroutineScope {
        // Establish connection and open control bi-stream
        try {
            connection = withTimeout(CONNECT_TIMEOUT_MS.milliseconds) {
                endpoint.connect(remoteAddr, alpn)
            }
            Telemetry.event("IrohTransport", "connect.ok", *IrohDiagnostics.connectionAttributes(connection).toTypedArray())
            controlBiStream = connection.openBi()
            Telemetry.event("IrohTransport", "control.opened")
            connectionReady.complete(Unit)
            pathWatchJob = launch { attachPathWatchers() }
        } catch (t: Throwable) {
            connected.value = false
            onConnectionLost("connect_failed: ${t.message ?: t.toString()}")
            Telemetry.event(
                "IrohTransport", "connect.failed",
                "remoteEndpointId" to IrohDiagnostics.endpointIdHex(remoteAddr.id().toBytes()),
                "closeReason" to "",
                "error" to (t.message ?: t.toString()),
                "class" to t::class.simpleName,
            )
            connectionReady.completeExceptionally(t)
            if (!streamReady.isCompleted) streamReady.completeExceptionally(t)
            return@coroutineScope
        }

        val sender = controlCommandQueue
        val receiver = controlBiStream.recv()
        val sendStream = controlBiStream.send()

        // Launch sender coroutine
        val senderJob = launch {
            for (command in sender) {
                val json = protocol.encodeCommand(command)
                val bytes = json.encodeToByteArray()
                Telemetry.event("IrohTransport", "control.write", "command" to command::class.simpleName, "bytes" to bytes.size)
                IrohFrameCodec.write(sendStream, json, allowFrameParts = serverSupportsFrameParts.get())
            }
        }

        try {
            // Read frames from control channel
            receiveFrames(receiver, AppServerChannel.Control, controlFrameFlow)
        } finally {
            senderJob.cancelAndJoin()
        }
    }

    private suspend fun runStreamChannel() {
        Telemetry.event("IrohTransport", "stream.channel_start", "alive" to true)
        // Open stream bi-stream (we only read from this, but QUIC requires bi-directional)
        streamBiStream = connection.openBi()
        Telemetry.event("IrohTransport", "stream.opened")
        
        // The stream channel is receive-only in the App Server protocol.
        // Open the send half and finish it to signal readiness without sending
        // any application frame.
        val s = streamBiStream.send()
        s.finish()
        Telemetry.event("IrohTransport", "stream.listening")
        if (!streamReady.isCompleted) streamReady.complete(Unit)
        
        receiveFrames(streamBiStream.recv(), AppServerChannel.Stream, streamFrameFlow)
    }

    private suspend fun receiveFrames(
        recvStream: computer.iroh.RecvStream,
        channel: AppServerChannel,
        sink: MutableSharedFlow<AppServerReceivedFrame>,
    ) {
        Telemetry.event("IrohTransport", "frame.reader_start", "channel" to channel.name)
        try {
            IrohFrameCodec.readAll(
                recvStream = recvStream,
                maxFrameBytes = MAX_FRAME_BYTES,
                chunkBytes = READ_CHUNK_SIZE,
            ) { json ->
                val frame = decodeFrame(json, channel)
                Telemetry.event("IrohTransport", "frame.recv", "channel" to channel.name, "type" to frame.frame.type)
                recordServerCapabilities(frame)
                if (channel == AppServerChannel.Control && completeLegacyAdminRpcIfPending(frame)) {
                    return@readAll
                }
                sink.emit(frame)
            }
        } catch (error: IrohFrameCodec.ProtocolException) {
            Telemetry.event(
                "IrohTransport", "frame.protocol_error",
                "channel" to channel.name,
                *(if (::connection.isInitialized) IrohDiagnostics.closeAttributes(connection) else emptyList()).toTypedArray(),
                "error" to (error.message ?: error.toString()),
            )
            throw error
        } catch (error: Throwable) {
            Telemetry.event(
                "IrohTransport", "frame.reader_error",
                "channel" to channel.name,
                "error" to (error.message ?: error.toString()),
                "class" to error::class.simpleName,
            )
            throw error
        } finally {
            reportReaderExit(channel, "reader_stopped:${channel.name}")
        }
    }

    private fun reportReaderExit(channel: AppServerChannel, reason: String) {
        if (!readerExitReported.compareAndSet(false, true)) return
        connected.value = false
        Telemetry.event(
            "IrohSupervisor", "reader.exit",
            "channel" to channel.name,
            "reason" to reason,
        )
        onConnectionLost(reason)
    }

    private fun attachPathWatchers() {
        val attrs = IrohDiagnostics.connectionAttributes(connection)
        // letta-mobile-34xoj: wrap watch calls in runCatching with retry telemetry
        // to diagnose "no Tokio runtime" failures after reconnect
        runCatching {
            connection.watchPaths(object : PathChangeCallback {
                override suspend fun onChange(paths: List<computer.iroh.PathSnapshot>) {
                    val summary = IrohDiagnostics.summarizePaths(paths)
                    Telemetry.event(
                        "IrohTransport", "paths.changed",
                        "remoteEndpointId" to (attrs.firstOrNull { it.first == "remoteEndpointId" }?.second ?: ""),
                        "selectedPathKind" to summary.selectedKind,
                        "selectedPathId" to summary.selectedPathId,
                        "pathCount" to summary.pathCount,
                        "relayPathCount" to summary.relayPathCount,
                        "directPathCount" to summary.directPathCount,
                        "rttMs" to summary.selectedRttMs,
                        "lostPackets" to summary.lostPackets,
                        "lostBytes" to summary.lostBytes,
                    )
                }
            })
            Telemetry.event("IrohTransport", "paths.watch.attached")
        }.onFailure { error ->
            Telemetry.event(
                "IrohTransport", "paths.watch_failed",
                "error" to (error.message ?: error.toString()),
                "class" to error::class.simpleName,
            )
        }
        runCatching {
            connection.watchPathEvents(object : PathEventCallback {
                override suspend fun onEvent(event: computer.iroh.PathEvent) {
                    Telemetry.event(
                        "IrohTransport", "path.event",
                        "remoteEndpointId" to (attrs.firstOrNull { it.first == "remoteEndpointId" }?.second ?: ""),
                        *IrohDiagnostics.pathEventAttributes(event).toTypedArray(),
                    )
                }
            })
            Telemetry.event("IrohTransport", "path_events.watch.attached")
        }.onFailure { error ->
            Telemetry.event(
                "IrohTransport", "path_events.watch_failed",
                "error" to (error.message ?: error.toString()),
                "class" to error::class.simpleName,
            )
        }
    }

    /**
     * letta-mobile-5purh follow-up: record the server's advertised transport
     * capabilities from its auth_response so subsequent client writes may use
     * frame_part chunking for >1MiB frames (e.g. user messages carrying
     * base64 image attachments). Only a successful auth_response counts.
     */
    private fun recordServerCapabilities(frame: AppServerReceivedFrame) {
        val auth = frame.frame as? AppServerInboundFrame.AuthResponse ?: return
        if (!auth.success) return
        val supported = IrohFrameCodec.FRAME_PART_CAPABILITY in auth.capabilities.orEmpty()
        val changed = serverSupportsFrameParts.getAndSet(supported) != supported
        if (changed) {
            Telemetry.event("IrohTransport", "capabilities.negotiated", "framePart" to supported)
        }
    }

    private suspend fun completeLegacyAdminRpcIfPending(frame: AppServerReceivedFrame): Boolean {
        val response = frame.frame as? AppServerInboundFrame.AdminRpcResponse ?: return false
        val pending = legacyAdminRpcResponsesMutex.withLock { legacyAdminRpcResponses.remove(response.requestId) }
        pending?.complete(response)
        return pending != null
    }

    /**
     * letta-mobile-k7yyc (P4 request isolation): the legacy control-channel
     * fallback muxes a response onto the SHARED control stream. That is only
     * ever safe for trivially-small, bounded payloads — a list-sized response
     * (message.list / *.list / a single large message or tool_return body) on
     * the control channel can starve or corrupt every other multiplexed frame.
     *
     * With #792 stream-per-request server support landed, every admin_rpc gets
     * its own bi-stream, so the fallback is legacy-only. We keep it ONLY for the
     * two tiny control-plane methods and let all list-sized reads hard-fail the
     * single request (see [adminRpc]) instead of degrading to the control
     * channel — failure stays isolated to the request, never the transport.
     */
    private fun String.isLegacyFallbackSafeAdminRpcMethod(): Boolean = when (this) {
        // #822 review: agent.get / agent.list are deliberately NOT here. They
        // return full Agent objects (system text, blocks, tools, metadata) that
        // can exceed the unchunked control-frame budget; falling those back onto
        // the shared control stream could take down unrelated traffic. Only small
        // fixed-size reads belong on the legacy fallback. Agent-read retry-on-
        // reconnect is handled by READ_ONLY_ADMIN_RPC_METHODS in
        // IrohChannelTransport instead (stream-per-request, chunk-capable).
        "health.check",
        "goal.get" -> true
        else -> false
    }

    private fun adminRpcParams(path: String, body: String?): JsonObject = buildJsonObject {
        parseAdminRpcPath(path).forEach { (key, value) -> put(key, value) }
        if (body != null) {
            runCatching { AppServerProtocol.json.parseToJsonElement(body).jsonObject }
                .getOrNull()
                ?.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun parseAdminRpcPath(path: String): Map<String, String> {
        val withoutPrefix = path.substringBefore('?').trim('/').split('/').filter { it.isNotBlank() }
        val values = mutableMapOf<String, String>()
        if (withoutPrefix.size >= 3 && withoutPrefix[0] == "v1" && withoutPrefix[1] == "conversations") {
            values["conversation_id"] = withoutPrefix[2]
            if (withoutPrefix.size >= 5 && withoutPrefix[3] == "messages") {
                values["message_id"] = withoutPrefix[4]
            }
        }
        path.substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter { it.isNotBlank() }
            .forEach { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', missingDelimiterValue = "")
                if (key.isNotBlank()) values[key] = value
            }
        return values
    }

    // protocol.decodeFrame is total: malformed frames surface as
    // AppServerInboundFrame.DecodeFailure (raw preserved, diagnostic bounded +
    // credential-redacted) instead of throwing, so a bad frame never tears down
    // the QUIC reader loop (letta-mobile-lgns8.4).
    private fun decodeFrame(rawText: String, channel: AppServerChannel): AppServerReceivedFrame =
        protocol.decodeFrame(rawText, channel)

    private companion object {
        const val FRAME_BUFFER_CAPACITY = 64
        const val READ_CHUNK_SIZE = 8192
        const val MAX_FRAME_BYTES = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES
        // c0qm0: keep the QUIC connection warm across turns. 15s is well under
        // typical QUIC idle timeouts (~30s) so the path never goes cold between
        // user messages.
        const val KEEPALIVE_INTERVAL_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 120_000L
        // 30s (was 15s): headroom for legitimately large admin_rpc reads on a
        // slow/relayed link — notably block.list, whose backend ignores
        // limit/offset so it can't be paged and returns the full set (~2 MB /
        // 1400+ blocks) in one response. agent.list is paged (see
        // IrohAdminRpcChatGateway.AGENT_LIST_PAGE_SIZE) so it no longer relies on
        // this. Kept bounded so a genuinely dead request still fails, not hangs.
        const val ADMIN_RPC_TIMEOUT_MS = 30_000L
        val KEEPALIVE_PAYLOAD = byteArrayOf(0)
    }
}
