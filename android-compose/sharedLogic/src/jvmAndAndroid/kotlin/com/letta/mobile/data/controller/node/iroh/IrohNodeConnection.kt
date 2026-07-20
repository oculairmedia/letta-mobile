package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
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
import kotlinx.serialization.json.JsonObject
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
    // eaczz.1: shared per-endpoint registry mapping conversationId -> viewers,
    // so a turn on one connection fans out to every connection viewing the same
    // conversation. Null in legacy/test constructions that don't use fanout.
    private val connectionRegistry: ConnectionRegistry? = null,
    /**
     * Server-process-scoped duplicate-suppression for client message ids. Shared
     * across connections so a redial re-delivery of the same turn does not
     * double-run (P3, 3wq5g). Injectable for tests.
     */
    private val clientMessageDedupe: ProcessScopedClientMessageDedupe = SHARED_CLIENT_MESSAGE_DEDUPE,
    /**
     * Server-process-scoped store of terminal frames dropped when a channel died
     * mid-turn, replayed on redial re-send (P3, q71yi). Injectable for tests.
     */
    private val parkedTerminals: ParkedTerminalStore = SHARED_PARKED_TERMINALS,
) {
    // Per-connection, strictly-monotonic event_seq with a disjoint process-scoped
    // base — replaces the shared mutable companion var that raced across
    // connections/threads (P3).
    private val eventSeq = IrohEventSeqAllocator.newConnectionSeq()
    private val streamWriteMutex = Mutex()
    private val authenticated = AtomicBoolean(requiredBearerToken.isNullOrBlank())
    
    /**
     * Mid-turn redial fix: thread-local storage for tracking the active turn's
     * clientMessageId and frames. Set at the start of handleInput, cleared when
     * the turn completes or fails. Used by writeStreamDelta to track frames for
     * parking if the stream dies mid-turn.
     */
    private val activeTurnTracking = ThreadLocal<ActiveTurnTracking?>()

    /**
     * Transport capabilities advertised by the peer in its auth frame.
     * Empty until the peer authenticates with a `capabilities` array; frames
     * that need capability-gated encodings (frame_part chunking) must check
     * this before writing so old peers reject cleanly instead of corrupting.
     */
    private val peerCapabilities = java.util.concurrent.atomic.AtomicReference<Set<String>>(emptySet())

    private fun peerSupportsFrameParts(): Boolean =
        IrohFrameCodec.FRAME_PART_CAPABILITY in peerCapabilities.get()

    /**
     * eaczz.3: this connection's single viewer handle onto its own turn/stream
     * BiStream. Built once when the stream opens in [serve] (it needs the
     * stream's [SendStream]) and reused for every conversation this connection
     * views. It reuses the connection's per-connection [eventSeq] and
     * [streamWriteMutex] so fanned-out frames keep this connection's monotonic
     * seq + serialized writes, exactly like the single-viewer path.
     */
    private var selfViewer: IrohViewerHandle? = null

    /**
     * eaczz.3: this connection's viewer subscription state (Option A de-scope
     * rule). Built alongside [selfViewer] once the turn stream opens; null when
     * there is no [connectionRegistry] (legacy/test constructions). Both
     * subscription signals — runtime_start and message.list — call
     * [registerAsViewer], which delegates the de-scope bookkeeping here.
     */
    private var viewerSubscription: ConversationViewerSubscription? = null

    /**
     * eaczz.3: build this connection's [selfViewer] once, bound to the turn
     * stream's [SendStream]. Idempotent — later calls return the existing handle
     * so every conversation this connection views shares ONE viewer identity
     * (so [ConnectionRegistry.unregisterAll] can drop them all on disconnect).
     */
    private fun ensureSelfViewer(streamSend: SendStream): IrohViewerHandle {
        selfViewer?.let { return it }
        val handle = IrohViewerHandle(
            connectionId = remoteEndpointId,
            sink = sendStreamSink(streamSend),
            eventSeq = eventSeq,
            streamWriteMutex = streamWriteMutex,
            frameParts = { peerSupportsFrameParts() },
            maxFrameBytes = MAX_FRAME_BYTES,
        )
        selfViewer = handle
        connectionRegistry?.let { registry ->
            viewerSubscription = ConversationViewerSubscription(registry, handle)
        }
        return handle
    }

    /**
     * eaczz.3: register this connection as the viewer of [conversationId],
     * applying the Option A de-scope rule (a new signal for a DIFFERENT
     * conversation unregisters the prior one first). No-op when there is no
     * registry / no live stream (subscription is null). Safe to call from either
     * signal path (control channel runtime_start, admin_rpc message.list)
     * concurrently.
     */
    private suspend fun registerAsViewer(conversationId: String) {
        val subscription = viewerSubscription ?: return
        val previous = subscription.currentConversation
        subscription.subscribe(conversationId)
        Telemetry.event(
            "IrohNode", "viewer.registered",
            "remoteEndpointId" to remoteEndpointId,
            "conversationId" to conversationId,
            "deregistered" to (previous?.takeIf { it != conversationId } ?: ""),
        )
    }

    suspend fun serve() = coroutineScope {
        try {
            val controlBiStream = connection.acceptBi()
            Telemetry.event("IrohNode", "control.accepted", "remoteEndpointId" to remoteEndpointId)
            val streamBiStream = connection.acceptBi()
            Telemetry.event("IrohNode", "stream.accepted", "remoteEndpointId" to remoteEndpointId)
            val streamSend = streamBiStream.send()
            // eaczz.3: build this connection's viewer handle now that its turn
            // stream is open, so both subscription signals (runtime_start on the
            // control channel, message.list on an admin_rpc stream) can register
            // it as a conversation viewer.
            ensureSelfViewer(streamSend)

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
            // eaczz.1: drop every viewer entry this connection registered so a
            // disconnected client stops receiving fanned-out frames.
            runCatching { connectionRegistry?.unregisterAll(remoteEndpointId) }
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
            requestContextProvider = ::currentAdminRpcRequestContext,
            // eaczz.3: the OBSERVER signal. admin_rpc runs on its own BiStream,
            // so the shared message.list handler has no connection identity —
            // associate it HERE, where remoteEndpointId + selfViewer are in
            // scope. When a connection hydrates conversation X via message.list,
            // register it as a viewer of X (de-scoping any prior conversation).
            onMethodObserved = { method, params ->
                if (method == "message.list") {
                    val conversationId = params
                        ?.get("conversation_id")
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (!conversationId.isNullOrEmpty()) {
                        registerAsViewer(conversationId)
                    }
                }
            },
        )
        server.serveAcceptLoop { connection.acceptBi().asAdminRpcBiStream() }
    }

    /**
     * Conversation-scoped auth context for both admin_rpc BiStreams and
     * control-channel admin_rpc frames. Empty authorized set when the peer
     * has not hydrated a conversation yet (denies scoped methods).
     */
    private fun currentAdminRpcRequestContext(): AdminRpcRequestContext =
        AdminRpcRequestContext(
            authenticated = authenticated.get(),
            authorizedConversationIds = viewerSubscription?.currentConversation
                ?.let(::setOf)
                ?: emptySet(),
        )

    /** Control-channel admin_rpc: parse + dispatch with scoped auth context. */
    private suspend fun handleControlAdminRpc(obj: JsonObject): String {
        val requestId = obj["request_id"]?.jsonPrimitive?.content
        val method = obj["method"]?.jsonPrimitive?.content
        if (method == null || requestId == null) {
            return """{"type":"admin_rpc_response","request_id":"$requestId","success":false,"error":"method and request_id are required"}"""
        }
        return adminRpcRouter.dispatch(
            AdminRpcInvocation(
                requestId = requestId,
                method = method,
                params = obj["params"]?.jsonObject,
                context = currentAdminRpcRequestContext(),
            ),
        )
    }

    /** Control-channel sync frame — kept out of [handleControlFrame] for complexity. */
    private suspend fun handleControlSync(obj: JsonObject): String {
        val requestId = obj["request_id"]?.jsonPrimitive?.content
        if (requestId == null) {
            return """{"type":"sync_response","success":false,"error":"request_id is required"}"""
        }
        return try {
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
                if (response.success) {
                    """{"type":"sync_response","request_id":"$requestId","success":true}"""
                } else {
                    val error = response.error?.replace("\"", "\\\"") ?: "sync failed"
                    """{"type":"sync_response","request_id":"$requestId","success":false,"error":"$error"}"""
                }
            }
        } catch (e: Exception) {
            val error = e.message?.replace("\"", "\\\"") ?: "sync error"
            """{"type":"sync_response","request_id":"$requestId","success":false,"error":"$error"}"""
        }
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
                // Chunked requests may only widen reassembly memory beyond a
                // single frame once the peer authenticated AND advertised
                // frame_part — the auth frame itself arrives on this stream,
                // so pre-auth peers stay bounded to MAX_FRAME_BYTES.
                maxReassembledBytesProvider = {
                    if (authenticated.get() && peerSupportsFrameParts()) {
                        IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES
                    } else {
                        MAX_FRAME_BYTES
                    }
                },
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
                "admin_rpc" -> ifAuthorized(requestId) { handleControlAdminRpc(obj) }
                "sync" -> ifAuthorized(requestId) { handleControlSync(obj) }
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
            val capabilities = advertisedCapabilities(adminRpcRouter)
            buildJsonObject {
                put("type", "auth_response")
                put("request_id", requestId)
                put("success", true)
                put("capabilities", Json.encodeToJsonElement(capabilities))
            }.toString()
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
                // eaczz.3: the initiator/sender signal — a successful
                // runtime_start subscribes THIS connection to conversation_id so
                // its turn frames fan out to it (and, via S4, to co-viewers).
                registerAsViewer(runtime.scope.conversationId)
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
        if (clientMsgId != null && !clientMessageDedupe.markHandled(clientMsgId)) {
            // Duplicate re-delivery (typically a redial re-send). If the previous
            // connection died before delivering this turn's frames we parked
            // them — replay them now so the client's turn resolves instead of
            // hanging forever (q71yi + mid-turn redial fix). Otherwise the original
            // turn is still in-flight or already completed, so drop silently.
            val parkedFrameSequence = parkedTerminals.takeParked(clientMsgId)
            if (parkedFrameSequence != null) {
                Telemetry.event(
                    "IrohNode", "input.duplicate_replayed_parked_frames",
                    "clientMessageId" to clientMsgId,
                    "agentId" to input.runtime.agentId,
                    "conversationId" to input.runtime.conversationId,
                )
                runCatching {
                    // Replay each parked frame (newline-separated)
                    parkedFrameSequence.split("\n").forEach { frameJson ->
                        if (frameJson.isNotBlank()) {
                            val delta = AppServerProtocol.json.parseToJsonElement(frameJson).jsonObject
                            writeStreamDelta(streamSend, input.runtime, delta)
                        }
                    }
                }.onFailure { replayError ->
                    // Could not deliver on this stream either — re-park so a
                    // later redial can still resolve the turn.
                    parkedTerminals.park(clientMsgId, parkedFrameSequence)
                    if (replayError is CancellationException) throw replayError
                }
            } else {
                Telemetry.event(
                    "IrohNode", "input.duplicate_ignored",
                    "clientMessageId" to clientMsgId,
                    "agentId" to input.runtime.agentId,
                    "conversationId" to input.runtime.conversationId,
                )
            }
            return
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
        // Mid-turn redial fix: set thread-local tracking so the INITIATOR-ONLY
        // parking hook can record frames for parking if the stream dies mid-turn.
        if (clientMsgId != null) {
            activeTurnTracking.set(ActiveTurnTracking(clientMessageId = clientMsgId))
        }
        // eaczz.4: the fanout core. Owns this turn's per-connection frame-shaping
        // state (cumulative text + open-tool_call tracking + terminal-dedup) and
        // publishes each cumulated+tagged delta body to EVERY viewer of the
        // conversation via each viewer's own writeBroadcastFrame — the initiator
        // (its selfViewer) is just one viewer in that set. Parking stays
        // INITIATOR-ONLY through [trackInitiatorFrame].
        val fanout = ConversationTurnFanout(
            conversationId = input.runtime.conversationId,
            runtime = input.runtime,
            remoteEndpointId = remoteEndpointId,
            viewersFor = { conv -> connectionRegistry?.viewersFor(conv) ?: emptySet() },
            initiatorViewer = ensureSelfViewer(streamSend),
            trackInitiatorFrame = { deltaJson ->
                // INITIATOR-ONLY: matches the pre-fanout writeStreamDelta, which
                // tracked the delta it was handed for redial replay. Never runs
                // per-observer.
                activeTurnTracking.get()?.tracker?.track(deltaJson)
            },
            // eaczz.6 fault isolation: drop a wedged/failed OBSERVER from the SAME
            // registry the fanout reads from, so the broadcaster stops writing to
            // a dead peer on later deltas. The initiator is never de-registered
            // here (it follows the parking path).
            unregisterViewer = { conv, viewer ->
                connectionRegistry?.unregister(conv, viewer)
            },
        )
        try {
            // eaczz.5: live user-echo fanout. Before the assistant stream, emit a
            // snapshot `user_message` delta so OBSERVERS see the sender's prompt
            // immediately, in order, ahead of the reply (today they only get it on
            // a later message.list reconcile — so the reply could appear first).
            // The initiator does NOT double-render: the echo carries the sender's
            // otid (== clientMsgId), which the reducer collapses against the
            // initiator's own optimistic Local row (idempotent snapshot, never
            // appended twice). No-op when there is no client_message_id (nothing
            // to key optimistic dedup on) — the fanout is best-effort regardless.
            if (clientMsgId != null) {
                runCatching {
                    fanout.broadcastUserEcho(
                        clientMessageId = clientMsgId,
                        text = text,
                        contentParts = contentParts,
                    )
                }
            }
            runCatching {
                controller.runTurn(command).collect { draft ->
                    val payload = draft.payload
                    if (fanout.anyTerminalWritten && fanout.isTerminalLifecycle(payload)) {
                        Telemetry.event(
                            "IrohNode", "stream.terminal_duplicate_skipped",
                            "remoteEndpointId" to remoteEndpointId,
                            "agentId" to input.runtime.agentId,
                            "conversationId" to input.runtime.conversationId,
                        )
                        return@collect
                    }
                    // Before a failure/cancel terminal, close any dangling tool_calls
                    // so the client never renders a tool_call without a return.
                    if (fanout.isFailureOrCancelLifecycle(payload)) {
                        fanout.flushOpenToolCalls()
                    }
                    fanout.onDraft(payload)
                }
            }.onFailure { error ->
                val wroteTerminal = runCatching {
                    withContext(NonCancellable) {
                        // Same dangling-tool_call guarantee on the exception path.
                        fanout.flushOpenToolCalls()
                        fanout.emitErrorTerminal(error.message ?: error.toString())
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
                    // Channel died before any terminal reached the client. Park the
                    // frames we've sent so far + a synthetic terminal, keyed by
                    // client_message_id so a redial re-send replays them instead of
                    // the client hanging on "Thinking…" (q71yi + mid-turn redial fix).
                    if (clientMsgId != null && !fanout.anyTerminalWritten) {
                        val tracking = activeTurnTracking.get()
                        tracking?.tracker?.parkFrames(parkedTerminals, clientMsgId, interruptedTerminalDelta().toString())
                        Telemetry.event(
                            "IrohNode", "stream.frames_parked",
                            "remoteEndpointId" to remoteEndpointId,
                            "clientMessageId" to clientMsgId,
                            "conversationId" to input.runtime.conversationId,
                            "frameCount" to (tracking?.tracker?.frameCount() ?: 0),
                        )
                    }
                }
                if (error is CancellationException) throw error
            }
        } finally {
            // Clear thread-local tracking when the turn completes or fails
            activeTurnTracking.remove()
        }
    }

    private fun interruptedTerminalDelta(): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("message_type", "error_message")
        put("message", "connection interrupted before the turn completed")
        put("status", "cancelled")
    }

    private fun extractTextFromContentParts(parts: kotlinx.serialization.json.JsonArray?): String? =
        parts?.firstOrNull { part ->
            runCatching { part.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }.getOrDefault(false)
        }?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

    /**
     * eaczz.4: retained ONLY for the redial parked-frame REPLAY path — a
     * reconnecting INITIATOR re-sends the same turn and we replay its parked
     * frame tail to ITS OWN stream (observers reconcile via message.list, so they
     * are not part of this replay). Live turn frames go through
     * [ConversationTurnFanout] now.
     */
    private suspend fun writeStreamDelta(
        streamSend: SendStream,
        runtime: com.letta.mobile.data.transport.appserver.AppServerRuntimeScope,
        delta: kotlinx.serialization.json.JsonObject,
    ) {
        // letta-mobile-h30cy: the Iroh stream forwards the App Server delta with
        // its raw provider id (letta-msg-*), but message.list later returns the
        // SAME reply with a DIFFERENT id namespace (ui-msg-*, null run) — the two
        // never share an identity field, so the mobile reconcile cannot dedupe the
        // streamed row against the disk-fetched copy and renders it twice. The
        // WS/HTTP shim paths already solve this by rewriting the streamed
        // assistant id to a stable `cm-stream-<otid>` (and reasoning to
        // `cm-reason-<otid>`), which mobile's optimistic-twin dedup collapses
        // against the disk copy. The Iroh serve path bypassed the shim, so it
        // never got this tag — THE reason Iroh dupes and HTTPS does not. Apply the
        // identical tagging here.
        val taggedDelta = tagStreamDeltaForOptimisticDedup(delta)
        val frame = buildJsonObject {
            put("type", "stream_delta")
            put("runtime", AppServerProtocol.json.encodeToJsonElement(runtime))
            put("event_seq", eventSeq.next())
            put("emitted_at", Instant.now().toString())
            put("idempotency_key", "iroh-delta-${UUID.randomUUID()}")
            put("delta", taggedDelta)
        }.toString()
        Telemetry.event(
            "IrohNode", "stream.write",
            "remoteEndpointId" to remoteEndpointId,
            *IrohDiagnostics.redactedFrameAttributes(frameType(frame), frame.length).toTypedArray(),
        )
        streamWriteMutex.withLock {
            IrohFrameCodec.write(streamSend, frame, MAX_FRAME_BYTES, allowFrameParts = peerSupportsFrameParts())
        }
        // Mid-turn redial fix: track the delta JSON for parking if stream dies
        activeTurnTracking.get()?.tracker?.track(delta.toString())
    }


    private fun frameType(rawFrame: String): String? = runCatching {
        AppServerProtocol.json.parseToJsonElement(rawFrame).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private suspend fun serveStreamReadiness(biStream: BiStream) {
        val recvStream = biStream.recv()
        runCatching { recvStream.read(1u) }
    }

    companion object {
        private val SUBAGENT_RPC_METHODS = setOf("subagent.list", "subagent.todos")

        internal fun advertisedCapabilities(router: AdminRpcRouter): List<String> = buildList {
            add(IrohFrameCodec.FRAME_PART_CAPABILITY)
            if (SUBAGENT_RPC_METHODS.all { it in router.registeredMethods }) {
                add(IrohChannelTransport.SUBAGENT_RPC_CAPABILITY)
            }
        }
        const val MAX_FRAME_BYTES = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES
        // Raised 5s -> 60s (P3): a heavy final turn (large tool_return, long
        // stop_reason flush) can legitimately still be draining when the control
        // channel closes; the old 5s cap cancelled those turns before their
        // terminal reached the client, stranding "Thinking…". Undelivered
        // terminals are additionally parked for redial replay (q71yi).
        const val STREAM_JOB_DRAIN_TIMEOUT_MS = 60_000L
        const val ADMIN_RPC_REQUEST_TIMEOUT_MS = 15_000L
        const val MAX_CONCURRENT_ADMIN_RPC_STREAMS = 16

        // Process-scoped so they persist across the per-connection lifecycle and
        // survive a redial (new IrohNodeConnection instance).
        internal val SHARED_CLIENT_MESSAGE_DEDUPE = ProcessScopedClientMessageDedupe()
        internal val SHARED_PARKED_TERMINALS = ParkedTerminalStore()
    }
}
