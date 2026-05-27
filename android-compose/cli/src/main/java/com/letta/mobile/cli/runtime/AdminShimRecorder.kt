package com.letta.mobile.cli.runtime

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
import com.letta.mobile.data.transport.HelloFrame
import com.letta.mobile.data.transport.PongFrame
import com.letta.mobile.data.transport.SendMessageFrame
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import com.letta.mobile.data.transport.SubscribeFrame
import com.letta.mobile.data.transport.encodeJson
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class AdminShimRecorder {
    suspend fun record(
        baseUrl: String,
        token: String,
        agentId: String?,
        conversationId: String?,
        message: String?,
        attachments: List<MessageContentPart.Image>,
        runId: String?,
        cursor: Long,
        out: Path,
        timeoutMs: Long,
        deviceId: String,
        clientVersion: String,
    ): Int {
        Files.createDirectories(out.toAbsolutePath().parent)
        val writer = Files.newBufferedWriter(
            out,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        val done = CompletableDeferred<Unit>()
        val counter = FrameCounter()
        val shouldSendMessage = message != null || attachments.isNotEmpty()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(45, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/').toWsUrl() + "/shim/v1/mobile")
            .build()
        var socketRef: WebSocket? = null
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef = webSocket
                val hello = HelloFrame(
                    id = UUID.randomUUID().toString(),
                    ts = nowIso(),
                    token = token,
                    deviceId = deviceId,
                    clientVersion = clientVersion,
                ).encodeJson(CliJson)
                recordRaw(writer, counter.next(), "outbound", hello)
                webSocket.send(hello)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                recordRaw(writer, counter.next(), "inbound", text)
                val frame = runCatching {
                    CliJson.decodeFromString(ServerFrameSerializer, text)
                }.getOrNull()
                when (frame) {
                    is ServerFrame.Welcome -> {
                        if (runId != null) {
                            val subscribe = SubscribeFrame(
                                id = UUID.randomUUID().toString(),
                                ts = nowIso(),
                                runId = runId,
                                cursor = cursor,
                            ).encodeJson(CliJson)
                            recordRaw(writer, counter.next(), "outbound", subscribe)
                            webSocket.send(subscribe)
                        }
                        if (shouldSendMessage && agentId != null && conversationId != null) {
                            val text = message.orEmpty()
                            val send = SendMessageFrame(
                                id = UUID.randomUUID().toString(),
                                ts = nowIso(),
                                agentId = agentId,
                                conversationId = conversationId,
                                text = text,
                                otid = newCliOtid(),
                                contentParts = if (attachments.isEmpty()) {
                                    null
                                } else {
                                    buildContentParts(text, attachments).toJsonArray()
                                },
                            ).encodeJson(CliJson)
                            recordRaw(writer, counter.next(), "outbound", send)
                            webSocket.send(send)
                        }
                    }
                    is ServerFrame.Ping -> {
                        val pong = PongFrame(
                            id = UUID.randomUUID().toString(),
                            ts = nowIso(),
                        ).encodeJson(CliJson)
                        recordRaw(writer, counter.next(), "outbound", pong)
                        webSocket.send(pong)
                    }
                    is ServerFrame.TurnDone -> {
                        if (shouldSendMessage) {
                            webSocket.close(1000, "record complete")
                            done.complete(Unit)
                        }
                    }
                    is ServerFrame.SubscribeDone -> {
                        if (message == null && runId != null) {
                            webSocket.close(1000, "record complete")
                            done.complete(Unit)
                        }
                    }
                    is ServerFrame.Error -> {
                        if (frame.code == "invalid_token" || frame.code == "protocol_violation") {
                            done.completeExceptionally(IllegalStateException("${frame.code}: ${frame.message}"))
                        }
                    }
                    else -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Unit)
            }
        }
        try {
            client.newWebSocket(request, listener)
            try {
                withTimeout(timeoutMs) { done.await() }
            } catch (e: TimeoutCancellationException) {
                if (shouldSendMessage) throw e
            }
        } finally {
            socketRef?.close(1000, "record done")
            writer.flush()
            writer.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        return counter.value
    }

    private fun recordRaw(writer: BufferedWriter, index: Int, direction: String, raw: String) {
        val frame = runCatching { CliJson.parseToJsonElement(raw).jsonObjectOrNull() }.getOrNull()
        val obj = buildJsonObject {
            put("index", index)
            put("direction", direction)
            put("recordedAt", nowIso())
            if (frame != null) put("frame", frame) else put("raw", raw)
        }
        synchronized(writer) {
            writer.write(CliJson.encodeToString(JsonObject.serializer(), obj))
            writer.newLine()
            writer.flush()
        }
    }
}

private class FrameCounter {
    var value: Int = 0
        private set

    fun next(): Int {
        value += 1
        return value
    }
}

private fun String.toWsUrl(): String = when {
    startsWith("https://") -> "wss://" + removePrefix("https://")
    startsWith("http://") -> "ws://" + removePrefix("http://")
    startsWith("ws://") || startsWith("wss://") -> this
    else -> "wss://$this"
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
