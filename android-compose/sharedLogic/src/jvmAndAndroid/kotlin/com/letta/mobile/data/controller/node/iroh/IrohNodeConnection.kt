package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.iroh.IrohDiagnostics
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val remoteEndpointId: String = "",
) {
    private val handledClientMessageIds = LinkedHashSet<String>()
    private val streamWriteMutex = Mutex()
    private val authenticated = AtomicBoolean(requiredBearerToken.isNullOrBlank())

    /**
     * Transport capabilities advertised by the peer in its auth frame.
     * Empty until the peer authenticates with a `capabilities` array; frames
     * that need capability-gated encodings (frame_part chunking) must check
     * this before writing so old peers reject cleanly instead of corrupting.
     */
    private val peerCapabilities = java.util.concurrent.atomic.AtomicReference<Set<String>>(emptySet())

    private fun peerSupportsFrameParts(): Boolean =
        IrohFrameCodec.FRAME_PART_CAPABILITY in peerCapabilities.get()

    suspend fun serve() = coroutineScope {
        try {
            val controlBiStream = connection.acceptBi()
            Telemetry.event("IrohNode", "control.accepted", "remoteEndpointId" to remoteEndpointId)
            val streamBiStream = connection.acceptBi()
            Telemetry.event("IrohNode", "stream.accepted", "remoteEndpointId" to remoteEndpointId)
            val streamSend = streamBiStream.send()

            val controlJob = launch {
                serveControlChannel(controlBiStream, streamSend)
            }

            val streamJob = launch {
                serveStreamReadiness(streamBiStream)
            }

            val adminRpcAcceptJob = launch {
                acceptAdminRpcStreams()
            }

            controlJob.join()
            adminRpcAcceptJob.cancelAndJoin()
            streamJob.cancelAndJoin()
            runCatching { streamSend.finish() }
        } catch (e: Exception) {
            Telemetry.event(
                "IrohNode", "connection.error",
                "remoteEndpointId" to remoteEndpointId,
                "error" to (e.message ?: e.toString()),
                "class" to e::class.simpleName,
            )
        } finally {
            Telemetry.event(
                "IrohNode", "connection.closed",
                "remoteEndpointId" to remoteEndpointId,
                "closeReason" to IrohDiagnostics.closeReason(connection),
                *IrohDiagnostics.connectionStatsAttributes(runCatching { connection.stats() }.getOrNull()).toTypedArray(),
            )
            runCatching { connection.close() }
        }
    }

    private suspend fun acceptAdminRpcStreams() {
        val server = AdminRpcStreamServer(
            router = adminRpcRouter,
            authenticated = authenticated,
            remoteEndpointId = remoteEndpointId,
            maxActiveHandlers = MAX_CONCURRENT_ADMIN_RPC_STREAMS,
            firstFrameTimeoutMs = ADMIN_RPC_REQUEST_TIMEOUT_MS,
            maxFrameBytes = MAX_FRAME_BYTES,
            peerSupportsFrameParts = { peerSupportsFrameParts() },
        )
        server.serveAcceptLoop { connection.acceptBi().asAdminRpcBiStream() }
    }

    private suspend fun serveControlChannel(
        biStream: BiStream,
        streamSend: SendStream,
    ) = coroutineScope {
        val sendStream = biStream.send()
        val activeTurnJobs = LinkedHashSet<Job>()
        val activeTurnJobsMutex = Mutex()

        try {
            val recvStream = biStream.recv()
            IrohFrameCodec.readAll(
                recvStream = recvStream,
                maxFrameBytes = MAX_FRAME_BYTES,
            ) { frameJson ->
                Telemetry.event("IrohNode", "control.recv", "remoteEndpointId" to remoteEndpointId, "bytes" to frameJson.length)
                try {
                    val response = handleControlFrame(
                        frameJson = frameJson,
                        streamSend = streamSend,
                        activeTurnJobs = activeTurnJobs,
                        activeTurnJobsMutex = activeTurnJobsMutex,
                    )
                    if (response != null) {
                        IrohFrameCodec.write(sendStream, response, MAX_FRAME_BYTES, allowFrameParts = peerSupportsFrameParts())
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (error: Exception) {
                    Telemetry.event(
                        "IrohNode", "control.frame_failed",
                        "remoteEndpointId" to remoteEndpointId,
                        "error" to (error.message ?: error.toString()),
                        "class" to error::class.simpleName,
                    )
                }
            }
        } finally {
            drainTurnJobs(activeTurnJobs, activeTurnJobsMutex)
            runCatching { sendStream.finish() }
        }
    }

    private suspend fun CoroutineScope.handleControlFrame(
        frameJson: String,
        streamSend: SendStream,
        activeTurnJobs: LinkedHashSet<Job>,
        activeTurnJobsMutex: Mutex,
    ): String? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val element = json.parseToJsonElement(frameJson)
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            val requestId = obj["request_id"]?.jsonPrimitive?.content

            Telemetry.event("IrohNode", "control.frame", "remoteEndpointId" to remoteEndpointId, "type" to type, "requestId" to requestId, "agentId" to obj["agent_id"]?.jsonPrimitive?.content)
            when (type) {
                "auth" -> handleAuth(obj, requestId)
                "runtime_start" -> ifAuthorized(requestId) { handleRuntimeStart(obj, requestId) }
                "input" -> ifAuthorized(requestId) {
                    launchInputJob(
                        frameJson = frameJson,
                        streamSend = streamSend,
                        activeTurnJobs = activeTurnJobs,
                        activeTurnJobsMutex = activeTurnJobsMutex,
                    )
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
                "abort_message" -> ifAuthorized(requestId) { handleAbort(frameJson, requestId) }
                else -> """{"type":"error","message":"Unknown command type: $type"}"""
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            """{"type":"error","message":"Failed to parse frame: ${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    private suspend fun CoroutineScope.launchInputJob(
        frameJson: String,
        streamSend: SendStream,
        activeTurnJobs: LinkedHashSet<Job>,
        activeTurnJobsMutex: Mutex,
    ) {
        val job = launch(start = CoroutineStart.LAZY) {
            handleInput(frameJson, streamSend)
        }
        activeTurnJobsMutex.withLock { activeTurnJobs += job }
        job.invokeOnCompletion { cause ->
            launch {
                activeTurnJobsMutex.withLock { activeTurnJobs.remove(job) }
                Telemetry.event(
                    "IrohNode", "stream.job.done",
                    "remoteEndpointId" to remoteEndpointId,
                    "cancelled" to (cause is CancellationException),
                    "error" to (cause?.message ?: ""),
                    "class" to (cause?.let { it::class.simpleName } ?: ""),
                )
            }
        }
        Telemetry.event(
            "IrohNode", "stream.job.started",
            "remoteEndpointId" to remoteEndpointId,
            "activeJobs" to activeTurnJobs.size,
        )
        job.start()
    }

    private suspend fun drainTurnJobs(
        activeTurnJobs: LinkedHashSet<Job>,
        activeTurnJobsMutex: Mutex,
    ) {
        val jobs = activeTurnJobsMutex.withLock { activeTurnJobs.toList() }
        if (jobs.isEmpty()) return
        Telemetry.event(
            "IrohNode", "stream.jobs.drain",
            "remoteEndpointId" to remoteEndpointId,
            "activeJobs" to jobs.size,
        )
        val completed = withTimeoutOrNull(STREAM_JOB_DRAIN_TIMEOUT_MS) {
            jobs.joinAll()
            true
        } ?: false
        if (completed) return
        Telemetry.event(
            "IrohNode", "stream.jobs.drain_timeout",
            "remoteEndpointId" to remoteEndpointId,
            "activeJobs" to jobs.count { it.isActive },
            level = Telemetry.Level.WARN,
        )
        jobs.forEach { job ->
            job.cancel(CancellationException("control channel closed before stream job completed"))
        }
        jobs.joinAll()
    }

    private fun handleAuth(
        obj: kotlinx.serialization.json.JsonObject,
        requestId: String?,
    ): String {
        if (requestId == null) return """{"type":"auth_response","success":false,"error":"request_id is required"}"""
        val expected = requiredBearerToken
        val provided = obj["token"]?.jsonPrimitive?.contentOrNull
        val isAuthenticated = expected.isNullOrBlank() || provided == expected
        authenticated.set(isAuthenticated)
        return if (isAuthenticated) {
            val advertised = (obj["capabilities"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                .orEmpty()
            peerCapabilities.set(advertised)
            Telemetry.event(
                "IrohNode", "auth.ok",
                "remoteEndpointId" to remoteEndpointId,
                "peerCapabilities" to advertised.sorted().joinToString(","),
            )
            """{"type":"auth_response","request_id":"$requestId","success":true,"capabilities":["${IrohFrameCodec.FRAME_PART_CAPABILITY}"]}"""
        } else {
            val reason = if (provided.isNullOrBlank()) "missing_token" else "invalid_token"
            Telemetry.event("IrohNode", "auth.failed", "remoteEndpointId" to remoteEndpointId, "reason" to reason)
            """{"type":"auth_response","request_id":"$requestId","success":false,"error":"$reason"}"""
        }
    }

    private inline fun ifAuthorized(requestId: String?, block: () -> String?): String? =
        if (authenticated.get()) {
            block()
        } else {
            val id = requestId ?: ""
            Telemetry.event("IrohNode", "auth.required", "remoteEndpointId" to remoteEndpointId, "requestId" to id, "reason" to "missing_token")
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

    private suspend fun handleAbort(
        frameJson: String,
        requestId: String?,
    ): String {
        if (requestId == null) {
            return """{"type":"abort_message_response","success":false,"error":"request_id is required"}"""
        }
        return try {
            val abort = AppServerProtocol.json.decodeFromString(AppServerCommand.serializer(), frameJson) as AppServerCommand.AbortMessage
            val response = controller.abort(abort.runtime, abort.runId)
            AppServerProtocol.json.encodeToString(AppServerInboundFrame.serializer(), response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val runtime = runCatching {
                AppServerProtocol.json.decodeFromString(AppServerCommand.serializer(), frameJson) as AppServerCommand.AbortMessage
            }.getOrNull()?.runtime
            AppServerProtocol.json.encodeToString(
                AppServerInboundFrame.serializer(),
                com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AbortMessageResponse(
                    requestId = requestId,
                    runtime = runtime ?: com.letta.mobile.data.transport.appserver.AppServerRuntimeScope("", ""),
                    aborted = false,
                    success = false,
                    error = e.message ?: e.toString(),
                ),
            )
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
        var terminalWritten = false
        runCatching {
            controller.runTurn(command).collect { draft ->
                val payload = draft.payload
                if (terminalWritten && payload.isTerminalLifecycle()) {
                    Telemetry.event(
                        "IrohNode", "stream.terminal_duplicate_skipped",
                        "remoteEndpointId" to remoteEndpointId,
                        "agentId" to input.runtime.agentId,
                        "conversationId" to input.runtime.conversationId,
                    )
                    return@collect
                }
                terminalWritten = writeDraftAsStreamDelta(streamSend, input.runtime, payload) || terminalWritten
            }
        }.onFailure { error ->
            val wroteTerminal = runCatching {
                withContext(NonCancellable) {
                    writeStreamDelta(
                        streamSend = streamSend,
                        runtime = input.runtime,
                        delta = buildJsonObject {
                            put("message_type", "error_message")
                            put("message", error.message ?: error.toString())
                        },
                    )
                }
            }.isSuccess
            if (!wroteTerminal) {
                Telemetry.event(
                    "IrohNode", "stream.closed_before_terminal",
                    "remoteEndpointId" to remoteEndpointId,
                    "error" to (error.message ?: error.toString()),
                    "class" to error::class.simpleName,
                    level = Telemetry.Level.WARN,
                )
            }
            if (error is CancellationException) throw error
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
    ): Boolean {
        return when (payload) {
            // DefaultAppServerController already gives us raw App Server wire
            // frames here (usually stream_delta). Forward them unchanged so we
            // preserve message_type, runtime metadata, and terminal semantics.
            is RuntimeEventPayload.RemoteStreamFrame -> {
                writeRawFrame(streamSend, payload.body)
                rawFrameIsTerminal(payload.body)
            }
            is RuntimeEventPayload.ExternalTransportFrame -> {
                writeRawFrame(streamSend, payload.body)
                rawFrameIsTerminal(payload.body)
            }
            is RuntimeEventPayload.RunLifecycleChanged -> if (payload.status == RuntimeRunStatus.Completed) {
                writeStreamDelta(
                    streamSend = streamSend,
                    runtime = runtime,
                    delta = buildJsonObject {
                        put("message_type", "stop_reason")
                        put("stop_reason", payload.reason ?: "end_turn")
                    },
                )
                true
            } else if (payload.status == RuntimeRunStatus.Failed) {
                writeStreamDelta(
                    streamSend = streamSend,
                    runtime = runtime,
                    delta = buildJsonObject {
                        put("message_type", "error_message")
                        put("message", payload.reason ?: "turn failed")
                    },
                )
                true
            } else if (payload.status == RuntimeRunStatus.Cancelled) {
                writeStreamDelta(
                    streamSend = streamSend,
                    runtime = runtime,
                    delta = buildJsonObject {
                        put("message_type", "error_message")
                        put("message", payload.reason ?: "turn cancelled")
                        put("status", "cancelled")
                    },
                )
                true
            } else {
                false
            }
            else -> false
        }
    }

    private fun RuntimeEventPayload.isTerminalLifecycle(): Boolean =
        this is RuntimeEventPayload.RunLifecycleChanged &&
            (status == RuntimeRunStatus.Completed || status == RuntimeRunStatus.Failed || status == RuntimeRunStatus.Cancelled)

    private suspend fun writeRawFrame(
        streamSend: SendStream,
        rawFrame: String,
    ) {
        Telemetry.event(
            "IrohNode", "stream.write",
            "remoteEndpointId" to remoteEndpointId,
            *IrohDiagnostics.redactedFrameAttributes(frameType(rawFrame), rawFrame.length).toTypedArray(),
        )
        streamWriteMutex.withLock {
            IrohFrameCodec.write(streamSend, rawFrame, MAX_FRAME_BYTES, allowFrameParts = peerSupportsFrameParts())
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
        Telemetry.event(
            "IrohNode", "stream.write",
            "remoteEndpointId" to remoteEndpointId,
            *IrohDiagnostics.redactedFrameAttributes(frameType(frame), frame.length).toTypedArray(),
        )
        streamWriteMutex.withLock {
            IrohFrameCodec.write(streamSend, frame, MAX_FRAME_BYTES, allowFrameParts = peerSupportsFrameParts())
        }
    }

    private fun frameType(rawFrame: String): String? = runCatching {
        AppServerProtocol.json.parseToJsonElement(rawFrame).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun rawFrameIsTerminal(rawFrame: String): Boolean = runCatching {
        val obj = AppServerProtocol.json.parseToJsonElement(rawFrame).jsonObject
        val delta = obj["delta"]?.jsonObject ?: obj
        when (delta["message_type"]?.jsonPrimitive?.contentOrNull) {
            "stop_reason",
            "loop_error",
            "error_message" -> true
            else -> false
        }
    }.getOrDefault(false)

    private suspend fun serveStreamReadiness(biStream: BiStream) {
        val recvStream = biStream.recv()
        runCatching { recvStream.read(1u) }
    }

    private companion object {
        var nextEventSeq: Long = 1L
        const val MAX_FRAME_BYTES = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES
        const val MAX_HANDLED_CLIENT_MESSAGE_IDS = 512
        const val HANDLED_CLIENT_MESSAGE_IDS_TRIM_COUNT = 128
        const val STREAM_JOB_DRAIN_TIMEOUT_MS = 5_000L
        const val ADMIN_RPC_REQUEST_TIMEOUT_MS = 15_000L
        const val MAX_CONCURRENT_ADMIN_RPC_STREAMS = 16
    }
}
