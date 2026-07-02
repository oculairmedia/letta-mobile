package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
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
import kotlinx.serialization.json.booleanOrNull
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
    private val requiredBearerToken: String? = null,
    private val allowedPeerIds: Set<String> = emptySet(),
) {
    private val handledClientMessageIds = LinkedHashSet<String>()
    private var authenticated: Boolean = requiredBearerToken.isNullOrBlank()

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
            IrohFrameCodec.readAll(
                recvStream = recvStream,
                maxFrameBytes = MAX_FRAME_BYTES,
            ) { frameJson ->
                Telemetry.event("IrohNode", "control.recv", "bytes" to frameJson.length)
                try {
                    val response = handleControlFrame(frameJson, streamSend, sendStream)
                    if (response != null) {
                        IrohFrameCodec.write(sendStream, response, MAX_FRAME_BYTES)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (error: Exception) {
                    Telemetry.event(
                        "IrohNode", "control.frame_failed",
                        "error" to (error.message ?: error.toString()),
                        "class" to error::class.simpleName,
                    )
                }
            }
        } finally {
            runCatching { sendStream.finish() }
        }
    }

    private suspend fun handleControlFrame(
        frameJson: String,
        streamSend: SendStream,
        controlSend: SendStream,
    ): String? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val element = json.parseToJsonElement(frameJson)
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            val requestId = obj["request_id"]?.jsonPrimitive?.content

            Telemetry.event("IrohNode", "control.frame", "type" to type, "requestId" to requestId, "agentId" to obj["agent_id"]?.jsonPrimitive?.content)
            when (type) {
                "auth" -> handleAuth(obj, requestId)
                "runtime_start" -> ifAuthorized(requestId) { handleRuntimeStart(obj, requestId) }
                "input" -> ifAuthorized(requestId) {
                    handleInput(frameJson, streamSend)
                    null
                }
                "admin_rpc" -> ifAuthorized(requestId) {
                    val method = obj["method"]?.jsonPrimitive?.content
                    if (method == null || requestId == null) {
                        """{"type":"admin_rpc_response","request_id":"$requestId","success":false,"error":"method and request_id are required"}"""
                    } else {
                        adminRpcRouter.dispatch(requestId, method, obj["params"]?.jsonObject)
                    }
                }
                "sync" -> ifAuthorized(requestId) {
                    if (requestId == null) {
                        """{"type":"sync_response","success":false,"error":"request_id is required"}"""
                    } else {
                        try {
                            val agentId = obj["agent_id"]?.jsonPrimitive?.content
                            val conversationId = obj["conversation_id"]?.jsonPrimitive?.content
                            if (agentId == null || conversationId == null) {
                                """{"type":"sync_response","request_id":"$requestId","success":false,"error":"agent_id and conversation_id required for sync"}"""
                            } else {
                                val runtime = com.letta.mobile.data.transport.appserver.AppServerRuntimeScope(
                                    agentId = agentId, conversationId = conversationId
                                )
                                val recoverApprovals = obj["recover_approvals"]?.jsonPrimitive?.booleanOrNull ?: false
                                val forceDeviceStatus = obj["force_device_status"]?.jsonPrimitive?.booleanOrNull ?: false
                                val response = controller.sync(runtime, recoverApprovals, forceDeviceStatus)
                                val success = response.success
                                val error = response.error
                                if (success) {
                                    """{"type":"sync_response","request_id":"$requestId","success":true}"""
                                } else {
                                    """{"type":"sync_response","request_id":"$requestId","success":false,"error":"${error?.replace("\"", "\\\"") ?: "sync failed"}"}"""
                                }
                            }
                        } catch (e: Exception) {
                            """{"type":"sync_response","request_id":"$requestId","success":false,"error":"${e.message?.replace("\"", "\\\"") ?: "sync error"}"}"""
                        }
                    }
                }
                else -> """{"type":"error","message":"Unknown command type: $type"}"""
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            """{"type":"error","message":"Failed to parse frame: ${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    private fun handleAuth(
        obj: kotlinx.serialization.json.JsonObject,
        requestId: String?,
    ): String {
        if (requestId == null) return """{"type":"auth_response","success":false,"error":"request_id is required"}"""
        val expected = requiredBearerToken
        val provided = obj["token"]?.jsonPrimitive?.contentOrNull
        authenticated = expected.isNullOrBlank() || provided == expected
        return if (authenticated) {
            Telemetry.event("IrohNode", "auth.ok")
            """{"type":"auth_response","request_id":"$requestId","success":true}"""
        } else {
            Telemetry.event("IrohNode", "auth.failed", "reason" to "invalid_token")
            """{"type":"auth_response","request_id":"$requestId","success":false,"error":"invalid_token"}"""
        }
    }

    private inline fun ifAuthorized(requestId: String?, block: () -> String?): String? =
        if (authenticated) {
            block()
        } else {
            val id = requestId ?: ""
            Telemetry.event("IrohNode", "auth.required", "requestId" to id)
            """{"type":"error","request_id":"$id","message":"unauthorized"}"""
        }

    private suspend fun handleRuntimeStart(
        obj: kotlinx.serialization.json.JsonObject,
        requestId: String?,
    ): String {
        val agentId = obj["agent_id"]?.jsonPrimitive?.content
        val conversationId = obj["conversation_id"]?.jsonPrimitive?.content
        val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull
        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull?.let { name ->
            when (name.lowercase()) {
                "standard" -> AppServerPermissionMode.Standard
                "acceptedits" -> AppServerPermissionMode.AcceptEdits
                "memory" -> AppServerPermissionMode.Memory
                else -> AppServerPermissionMode.Unrestricted
            }
        } ?: AppServerPermissionMode.Unrestricted

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
                    mode = mode,
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
        val userMsg = (input.payload as? AppServerInputPayload.CreateMessage)
            ?.messages
            ?.firstOrNull { it.role == "user" }
        val contentParts = userMsg?.content as? kotlinx.serialization.json.JsonArray
        val text = userMsg?.content
            ?.let { (it as? JsonPrimitive)?.contentOrNull ?: extractTextFromContentParts(contentParts) ?: it.toString() }
            ?: ""
        val clientMsgId = userMsg?.clientMessageId
        if (clientMsgId != null && !handledClientMessageIds.add(clientMsgId)) {
            Telemetry.event(
                "IrohNode", "input.duplicate_ignored",
                "clientMessageId" to clientMsgId,
                "agentId" to input.runtime.agentId,
                "conversationId" to input.runtime.conversationId,
            )
            return
        }
        if (handledClientMessageIds.size > MAX_HANDLED_CLIENT_MESSAGE_IDS) {
            val iterator = handledClientMessageIds.iterator()
            repeat(HANDLED_CLIENT_MESSAGE_IDS_TRIM_COUNT) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:${input.runtime.agentId}:${input.runtime.conversationId}"),
            agentId = AgentId(input.runtime.agentId),
            conversationId = ConversationId(input.runtime.conversationId),
            input = TurnInput.UserMessage(
                localMessageId = clientMsgId ?: "iroh-${UUID.randomUUID()}",
                text = text,
                contentPartsJson = contentParts?.toString(),
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

    private fun extractTextFromContentParts(parts: kotlinx.serialization.json.JsonArray?): String? =
        parts?.firstOrNull { part ->
            runCatching { part.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }.getOrDefault(false)
        }?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

    private suspend fun writeDraftAsStreamDelta(
        streamSend: SendStream,
        runtime: com.letta.mobile.data.transport.appserver.AppServerRuntimeScope,
        payload: RuntimeEventPayload,
    ) {
        when (payload) {
            // DefaultAppServerController already gives us raw App Server wire
            // frames here (usually stream_delta). Forward them unchanged so we
            // preserve message_type, runtime metadata, and terminal semantics.
            is RuntimeEventPayload.RemoteStreamFrame -> writeRawFrame(streamSend, payload.body)
            is RuntimeEventPayload.ExternalTransportFrame -> writeRawFrame(streamSend, payload.body)
            is RuntimeEventPayload.RunLifecycleChanged -> if (payload.status == RuntimeRunStatus.Completed) {
                writeStreamDelta(
                    streamSend = streamSend,
                    runtime = runtime,
                    delta = buildJsonObject {
                        put("message_type", "stop_reason")
                        put("stop_reason", payload.reason ?: "end_turn")
                    },
                )
            } else if (payload.status == RuntimeRunStatus.Failed) {
                writeStreamDelta(
                    streamSend = streamSend,
                    runtime = runtime,
                    delta = buildJsonObject {
                        put("message_type", "error_message")
                        put("message", payload.reason ?: "turn failed")
                    },
                )
            }
            else -> Unit
        }
    }

    private suspend fun writeRawFrame(
        streamSend: SendStream,
        rawFrame: String,
    ) {
        Telemetry.event(
            "IrohNode", "stream.write",
            "bytes" to rawFrame.length,
            "type" to frameType(rawFrame),
            "snippet" to rawFrame.take(180),
        )
        IrohFrameCodec.write(streamSend, rawFrame, MAX_FRAME_BYTES)
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
        Telemetry.event(
            "IrohNode", "stream.write",
            "bytes" to frame.length,
            "type" to frameType(frame),
            "snippet" to frame.take(180),
        )
        IrohFrameCodec.write(streamSend, frame, MAX_FRAME_BYTES)
    }

    private fun frameType(rawFrame: String): String? = runCatching {
        AppServerProtocol.json.parseToJsonElement(rawFrame).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private suspend fun serveStreamReadiness(biStream: BiStream) {
        val recvStream = biStream.recv()
        runCatching { recvStream.read(1u) }
    }

    private companion object {
        var nextEventSeq: Long = 1L
        const val MAX_FRAME_BYTES = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES
        const val MAX_HANDLED_CLIENT_MESSAGE_IDS = 512
        const val HANDLED_CLIENT_MESSAGE_IDS_TRIM_COUNT = 128
    }
}
