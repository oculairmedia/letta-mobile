package com.letta.mobile.data.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Encapsulates low-level OkHttp WebSocket client requests, listener wiring,
 * thread-safe reference management, and active reconnect jobs.
 */
internal class WebSocketConnection(
    private val scope: CoroutineScope,
    private val json: kotlinx.serialization.json.Json,
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val socketRef = AtomicReference<WebSocket?>(null)
    private var reconnectJob: Job? = null
    private var lastConnectionConfig: ConnectionConfig? = null

    @Volatile
    var clientInitiatedClose: Boolean = false
        private set

    fun getSocket(): WebSocket? = socketRef.get()

    /**
     * Dispatches a string on the active socket. Returns true if successfully queued/sent.
     */
    fun send(text: String): Boolean {
        return socketRef.get()?.send(text) ?: false
    }

    /**
     * Dispatches a [ClientFrame] as JSON on the active socket.
     */
    fun sendFrame(frame: ClientFrame): Boolean {
        return send(frame.encodeJson(json))
    }

    /**
     * Establishes a new WebSocket connection, superseding any prior active socket.
     */
    fun connect(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
        listener: WebSocketListener,
    ) {
        lastConnectionConfig = ConnectionConfig(
            baseShimUrl = baseShimUrl,
            token = token,
            deviceId = deviceId,
            clientVersion = clientVersion,
        )

        teardown("reconnect")

        val wsUrl = baseShimUrl.trimEnd('/').toWsUrl() + "/shim/v1/mobile"
        val request = Request.Builder().url(wsUrl).build()

        socketRef.set(httpClient.newWebSocket(request, listener))
    }

    /**
     * Closes the active socket cleanly and cancels any reconnect operations.
     */
    fun disconnect(reason: String) {
        teardown(reason)
    }

    /**
     * Clears the client-initiated close flag and returns its previous value.
     */
    fun clearClientInitiatedClose(): Boolean {
        val wasClientClose = clientInitiatedClose
        clientInitiatedClose = false
        return wasClientClose
    }

    /**
     * Helper to tear down the current WebSocket socket and mark it as client-initiated.
     */
    fun teardown(reason: String): Boolean {
        val socket = socketRef.getAndSet(null)
        clientInitiatedClose = socket != null
        socket?.close(NORMAL_CLOSE, reason)
        reconnectJob?.cancel()
        return socket != null
    }

    /**
     * Closes the socket from the superseded checker.
     */
    fun compareAndSetSuperseded(expected: WebSocket): Boolean {
        val matched = socketRef.compareAndSet(expected, null)
        if (matched) {
            clientInitiatedClose = false
        }
        return matched
    }

    /**
     * Redials the last recorded connection configuration.
     */
    fun requestReconnect(
        reason: String,
        isConnecting: () -> Boolean,
        connectFn: suspend (baseShimUrl: String, token: String, deviceId: String, clientVersion: String) -> Unit,
    ) {
        val config = lastConnectionConfig
        if (config == null) {
            Log.w(TAG, "WS reconnect unavailable: no prior connection config")
            return
        }
        if (isConnecting()) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            Log.i(TAG, "redialing WS: $reason")
            connectFn(config.baseShimUrl, config.token, config.deviceId, config.clientVersion)
        }
    }

    private data class ConnectionConfig(
        val baseShimUrl: String,
        val token: String,
        val deviceId: String,
        val clientVersion: String,
    )

    companion object {
        private const val TAG = "WebSocketConnection"
        private const val NORMAL_CLOSE = 1000

        private fun String.toWsUrl(): String = when {
            startsWith("https://") -> "wss://" + removePrefix("https://")
            startsWith("http://") -> "ws://" + removePrefix("http://")
            startsWith("ws://") || startsWith("wss://") -> this
            else -> "wss://$this"
        }
    }
}
