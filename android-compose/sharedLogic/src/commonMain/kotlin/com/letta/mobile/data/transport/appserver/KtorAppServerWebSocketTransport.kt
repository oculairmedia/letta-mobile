package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ktor-backed App Server transport.
 *
 * `bearerToken` is intentionally optional: loopback App Server runs omit WS
 * auth, while non-loopback/headless hosts should launch `letta app-server` with
 * `--ws-auth` and pass the matching bearer token here.
 */
class KtorAppServerWebSocketTransport(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    scope: CoroutineScope,
    private val bearerToken: String? = null,
    private val protocol: AppServerProtocol = AppServerProtocol,
) : AppServerTransport {
    private val controlCommandQueue = Channel<AppServerCommand>(Channel.BUFFERED)
    private val controlFrameFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
    private val streamFrameFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
    private val controlJob = scope.launch {
        runControlChannel()
    }
    private val streamJob = scope.launch {
        runReceiveOnlyChannel(AppServerChannel.Stream, streamFrameFlow)
    }

    override val controlFrames: Flow<AppServerReceivedFrame> = controlFrameFlow.asSharedFlow()
    override val streamFrames: Flow<AppServerReceivedFrame> = streamFrameFlow.asSharedFlow()

    private val isConnectedFlow = MutableStateFlow(true)
    override val isConnected: Flow<Boolean> = isConnectedFlow.asStateFlow()

    init {
        scope.launch {
            try {
                controlJob.join()
            } finally {
                isConnectedFlow.value = false
            }
        }
        scope.launch {
            try {
                streamJob.join()
            } finally {
                isConnectedFlow.value = false
            }
        }
    }

    override suspend fun sendControl(command: AppServerCommand) {
        controlCommandQueue.send(command)
    }

    suspend fun close() {
        controlCommandQueue.close()
        controlJob.cancelAndJoin()
        streamJob.cancelAndJoin()
    }

    private suspend fun runControlChannel() {
        httpClient.webSocket(urlString = appServerChannelUrl(baseUrl, AppServerChannel.Control), request = {
            bearerToken?.let(::bearerAuth)
        }) {
            val sender = launch {
                for (command in controlCommandQueue) {
                    send(Frame.Text(protocol.encodeCommand(command)))
                }
            }
            try {
                receiveFrames(AppServerChannel.Control, controlFrameFlow)
            } finally {
                sender.cancelAndJoin()
            }
        }
    }

    private suspend fun runReceiveOnlyChannel(
        channel: AppServerChannel,
        sink: MutableSharedFlow<AppServerReceivedFrame>,
    ) {
        httpClient.webSocket(urlString = appServerChannelUrl(baseUrl, channel), request = {
            bearerToken?.let(::bearerAuth)
        }) {
            receiveFrames(channel, sink)
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveFrames(
        channel: AppServerChannel,
        sink: MutableSharedFlow<AppServerReceivedFrame>,
    ) {
        for (frame in incoming) {
            if (frame is Frame.Text) {
                sink.emit(decodeFrame(frame.readText(), channel))
            }
        }
    }

    private fun decodeFrame(rawText: String, channel: AppServerChannel): AppServerReceivedFrame =
        runCatching {
            protocol.decodeFrame(rawText, channel)
        }.getOrElse { error ->
            val raw = buildJsonObject {
                put("type", "decode_error")
                put("raw", rawText)
                put("message", error.message ?: "Failed to decode App Server frame")
            }
            AppServerReceivedFrame(
                channel = channel,
                frame = AppServerInboundFrame.Unknown(type = "decode_error", raw = raw),
                raw = raw,
            )
        }

    private companion object {
        const val FRAME_BUFFER_CAPACITY = 64
    }
}

internal fun appServerChannelUrl(baseUrl: String, channel: AppServerChannel): String {
    val channelName = when (channel) {
        AppServerChannel.Control -> "control"
        AppServerChannel.Stream -> "stream"
    }
    return URLBuilder().takeFrom(baseUrl).apply {
        encodedPath = "/ws"
        parameters.clear()
        parameters.append("channel", channelName)
    }.buildString()
}
