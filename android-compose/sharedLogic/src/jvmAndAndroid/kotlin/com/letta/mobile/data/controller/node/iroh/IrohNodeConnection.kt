package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.SendStream
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import com.letta.mobile.util.Telemetry

/**
 * Handles a single Iroh connection serving the App Server protocol.
 *
 * Accepts two bi-directional streams (control + stream) and routes incoming
 * App Server v2 frames to the provided controller.
 */
class IrohNodeConnection(
    private val connection: Connection,
    private val controller: AppServerController,
    private val alpn: ByteArray,
    /**
     * Router for admin RPC calls. Domain handlers register here.
     * Defaults to an empty router — methods register on the same instance
     * before connections arrive (e.g. in IrohNodeEndpoint.start).
     */
    private val adminRpcRouter: AdminRpcRouter = AdminRpcRouter(),
) {
    suspend fun serve() = coroutineScope {
        try {
            val controlBiStream = connection.acceptBi()
            Telemetry.event("IrohNode", "control.accepted")
            val streamBiStream = connection.acceptBi()
            Telemetry.event("IrohNode", "stream.accepted")
            val streamSend = streamBiStream.send()

            val controlJob = launch {
                serveControlChannel(controlBiStream, streamSend)
            }

            val streamJob = launch {
                serveStreamReadiness(streamBiStream)
            }

            controlJob.join()
            streamJob.cancelAndJoin()
            runCatching { streamSend.finish() }
        } catch (e: Exception) {
            // Connection error - silently close
        } finally {
            runCatching { connection.close() }
        }
    }

    private suspend fun serveControlChannel(
        biStream: BiStream,
        streamSend: SendStream,
    ) = coroutineScope {
        val sendStream = biStream.send()

        try {
            val recvStream = biStream.recv()
            val buffer = mutableListOf<Byte>()

            while (true) {
                val chunk = runCatching {
                    recvStream.read(8192u)
                }.getOrNull() ?: break

                if (chunk.isEmpty()) break

                for (byte in chunk) {
                    if (byte == '\n'.code.toByte()) {
                        if (buffer.isNotEmpty()) {
                            val frameJson = buffer.toByteArray().decodeToString()
                            buffer.clear()
                            Telemetry.event("IrohNode", "control.recv", "bytes" to frameJson.length)

                            launch {
                                runCatching {
                                    val response = handleControlFrame(frameJson, streamSend)
                                    if (response != null) {
                                        sendStream.writeAll(response.toByteArray())
                                        sendStream.writeAll("\n".toByteArray())
                                    }
                                }
                            }
                        }
                    } else {
                        buffer.add(byte)
                    }
                }
            }
        } finally {
            runCatching { sendStream.finish() }
        }
    }

    private suspend fun handleControlFrame(
        frameJson: String,
        streamSend: SendStream,
    ): String? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val element = json.parseToJsonElement(frameJson)
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            val requestId = obj["request_id"]?.jsonPrimitive?.content

            Telemetry.event("IrohNode", "control.frame", "type" to type, "requestId" to requestId, "agentId" to obj["agent_id"]?.jsonPrimitive?.content)
            when (type) {
                "runtime_start" -> handleRuntimeStart(obj, requestId)
                "input" -> {
                    handleInput(frameJson, streamSend)
                    null
                }
                "admin_rpc" -> {
                    val method = obj["method"]?.jsonPrimitive?.content
                    if (method == null || requestId == null) {
                        """{"type":"admin_rpc_response","request_id":"$requestId","success":false,"error":"method and request_id are required"}"""
                    } else {
                        adminRpcRouter.dispatch(requestId, method, obj["params"]?.jsonObject)
                    }
                }
                "sync" -> {
                    if (requestId == null) {
                        """{"type":"sync_response","success":false,"error":"request_id is required"}"""
                    } else {
                        """{"type":"sync_response","request_id":"$requestId","success":false,"error":"sync not yet implemented in Iroh node"}"""
                    }
                }
                else -> """{"type":"error","message":"Unknown command type: $type"}"""
            }
        } catch (e: Exception) {
            """{"type":"error","message":"Failed to parse frame: ${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    private suspend fun handleRuntimeStart(
        obj: kotlinx.serialization.json.JsonObject,
        requestId: String?,
    ): String {
        val agentId = obj["agent_id"]?.jsonPrimitive?.content
        val conversationId = obj["conversation_id"]?.jsonPrimitive?.content
        val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull

        return if (requestId == null) {
            """{"type":"runtime_start_response","success":false,"error":"request_id is required"}"""
        } else if (agentId == null || conversationId == null) {
            """{"type":"runtime_start_response","request_id":"$requestId","success":false,"error":"agent_id and conversation_id are required"}"""
        } else {
            try {
                val runtime = controller.startRuntime(
                    agentId = AgentId(agentId),
                    conversationId = ConversationId(conversationId),
                    cwd = cwd,
                    mode = AppServerPermissionMode.Unrestricted,
                    recoverApprovals = false,
                    forceDeviceStatus = false,
                )
                """{"type":"runtime_start_response","request_id":"$requestId","success":true,"runtime":{"agent_id":"${runtime.scope.agentId}","conversation_id":"${runtime.scope.conversationId}"}}"""
            } catch (e: Exception) {
                """{"type":"runtime_start_response","request_id":"$requestId","success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }
        }
    }

    private suspend fun handleInput(
        frameJson: String,
        streamSend: SendStream,
    ) {
        val input = AppServerProtocol.json.decodeFromString(AppServerCommand.serializer(), frameJson) as AppServerCommand.Input
        val text = (input.payload as? AppServerInputPayload.CreateMessage)
            ?.messages
            ?.firstOrNull { it.role == "user" }
            ?.content
            ?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
            ?: ""
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:${input.runtime.agentId}:${input.runtime.conversationId}"),
            agentId = AgentId(input.runtime.agentId),
            conversationId = ConversationId(input.runtime.conversationId),
            input = TurnInput.UserMessage(
                localMessageId = "iroh-${UUID.randomUUID()}",
                text = text,
            ),
        )
        runCatching {
            controller.runTurn(command).collect { draft ->
                writeDraftAsStreamDelta(streamSend, input.runtime, draft.payload)
            }
        }.onFailure { error ->
            writeStreamDelta(
                streamSend = streamSend,
                runtime = input.runtime,
                delta = buildJsonObject {
                    put("message_type", "error_message")
                    put("message", error.message ?: error.toString())
                },
            )
        }
    }

    private suspend fun writeDraftAsStreamDelta(
        streamSend: SendStream,
        runtime: com.letta.mobile.data.transport.appserver.AppServerRuntimeScope,
        payload: RuntimeEventPayload,
    ) {
        when (payload) {
            is RuntimeEventPayload.RemoteStreamFrame -> writeStreamDelta(
                streamSend,
                runtime,
                buildJsonObject {
                    put("message_type", payload.messageType ?: "assistant_message")
                    put("id", payload.messageId ?: payload.frameId)
                    put("content", payload.body)
                },
            )
            is RuntimeEventPayload.ExternalTransportFrame -> writeStreamDelta(
                streamSend,
                runtime,
                buildJsonObject {
                    put("message_type", "assistant_message")
                    put("id", payload.transportMessageId ?: payload.frameId)
                    put("content", payload.body)
                },
            )
            is RuntimeEventPayload.RunLifecycleChanged -> if (payload.status == RuntimeRunStatus.Completed) {
                writeStreamDelta(
                    streamSend,
                    runtime,
                    buildJsonObject {
                        put("message_type", "stop_reason")
                        put("stop_reason", payload.reason ?: "end_turn")
                    },
                )
            } else if (payload.status == RuntimeRunStatus.Failed) {
                writeStreamDelta(
                    streamSend,
                    runtime,
                    buildJsonObject {
                        put("message_type", "error_message")
                        put("message", payload.reason ?: "turn failed")
                    },
                )
            }
            else -> Unit
        }
    }

    private suspend fun writeStreamDelta(
        streamSend: SendStream,
        runtime: com.letta.mobile.data.transport.appserver.AppServerRuntimeScope,
        delta: kotlinx.serialization.json.JsonObject,
    ) {
        val frame = buildJsonObject {
            put("type", "stream_delta")
            put("runtime", AppServerProtocol.json.encodeToJsonElement(runtime))
            put("event_seq", nextEventSeq++)
            put("emitted_at", Instant.now().toString())
            put("idempotency_key", "iroh-delta-${UUID.randomUUID()}")
            put("delta", delta)
        }.toString()
        Telemetry.event("IrohNode", "stream.write", "bytes" to frame.length)
        streamSend.writeAll(frame.toByteArray())
        streamSend.writeAll("\n".toByteArray())
    }

    private suspend fun serveStreamReadiness(biStream: BiStream) {
        val recvStream = biStream.recv()
        runCatching { recvStream.read(1u) }
    }

    private companion object {
        var nextEventSeq: Long = 1L
    }
}
