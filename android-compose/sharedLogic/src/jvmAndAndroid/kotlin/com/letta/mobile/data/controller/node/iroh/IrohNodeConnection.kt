package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.TurnInputAcknowledgement
import com.letta.mobile.data.runtime.isTurnAlreadyActiveMessage
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.letta.mobile.util.Telemetry

import kotlin.time.Duration.Companion.milliseconds

/** Converts ordinary operation failures while preserving structured cancellation. */
private suspend fun <T> recoverUnlessCancelled(
    action: suspend () -> T,
    recover: (Exception) -> T,
): T = try {
    action()
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    recover(error)
}

/**
 * Handles a single Iroh connection serving the App Server protocol.
 *
 * Accepts two bi-directional streams (control + stream) and routes incoming
 * App Server v2 frames to the provided controller.
 */
class IrohNodeConnection(
    private val connection: Connection,
    private val controller: AppServerController,
    /**
     * Router for admin RPC calls. Domain handlers register here.
     * Defaults to an empty router — methods register on the same instance
     * before connections arrive (e.g. in IrohNodeEndpoint.start).
     */
    private val adminRpcRouter: AdminRpcRouter = AdminRpcRouter(),
    /**
     * REQUIRED authentication policy. Connections only start
     * pre-authenticated when the policy explicitly says so (anonymous
     * test-only mode or an already-vetted peer allowlist) — never because a
     * parameter was omitted.
     */
    private val authPolicy: IrohAuthPolicy,
    /**
     * Bearer verifier: constant-time comparison + per-NodeId rate
     * limiting. Endpoints pass ONE shared instance to every connection so
     * redialing cannot reset a peer's failure budget.
     */
    private val authVerifier: IrohBearerAuthVerifier = IrohBearerAuthVerifier(authPolicy),
    /**
     * Pairing service: peers whose NodeId is already paired
     * authenticate by transport identity alone; unpaired peers may redeem a
     * one-time invite via an `invite:<secret>` auth token. Null disables
     * pairing entirely.
     */
    private val pairingService: IrohPairingService? = null,
    private val remoteEndpointId: String = "",
    // eaczz.1: shared per-endpoint registry mapping conversationId -> viewers,
    // so a turn on one connection fans out to every connection viewing the same
    // conversation. Null in legacy/test constructions that don't use fanout.
    private val connectionRegistry: ConnectionRegistry? = null,
    /**
     * Server-process-scoped duplicate-suppression for client message ids. Shared
     * across connections so a redial re-delivery of the same turn does not
     * double-run. Injectable for tests.
     */
    private val clientMessageDedupe: ProcessScopedClientMessageDedupe = SHARED_CLIENT_MESSAGE_DEDUPE,
    /**
     * Server-process-scoped store of terminal frames dropped when a channel died
     * mid-turn, replayed on redial re-send. Injectable for tests.
     */
    private val parkedTerminals: ParkedTerminalStore = SHARED_PARKED_TERMINALS,
    /**
     * Node-owned home of this connection's turns. A turn outlives its
     * initiator's connection (it is detached, not cancelled), so it must not be a child of it.
     */
    private val turnHost: NodeTurnHost = NodeTurnHost.SHARED,
    /** The host's stream-to-stored identity join; null leaves rows unjoined. */
    private val turnIdentity: com.letta.mobile.data.runtime.TurnIdentityLedger? = null,
) {
    // Per-connection, strictly-monotonic event_seq with a disjoint process-scoped
    // base — replaces the shared mutable companion var that raced across
    // connections/threads (P3).
    private val eventSeq = IrohEventSeqAllocator.newConnectionSeq()
    private val streamWriteMutex = Mutex()
    private lateinit var observerWrites: ObserverWriteQueue

    // letta-mobile-qygvv.12: control responses and relayed turns' input_accepted acks share
    // the control stream, so writes to it are serialized.
    private val controlWriteMutex = Mutex()
    private var controlSend: SendStream? = null

    /** A client's `remove_queue_item` / `resume_queue`, forwarded upstream. */
    private val queueControlRelay = IrohQueueControlRelay(controller)

    private suspend fun writeControl(frame: String) {
        val send = controlSend ?: return
        controlWriteMutex.withLock {
            IrohFrameCodec.write(send, frame, MAX_FRAME_BYTES, allowFrameParts = peerSupportsFrameParts())
        }
    }
    /**
     * Auth + grants for this connection (/.6,): pre-authenticated
     * only when the policy vouches for THIS NodeId or it is paired; a revoked
     * pairing tears the connection down. See [PeerSessionAuth].
     */
    private val sessionAuth = PeerSessionAuth(
        policy = authPolicy,
        verifier = authVerifier,
        pairing = pairingService,
        remoteId = remoteEndpointId,
        onRevoked = { runCatching { connection.close(4403L, "peer_revoked".encodeToByteArray()) } },
    )
    private val authenticated: AtomicBoolean get() = sessionAuth.authenticated

    /** Whether this peer has authenticated; side protocols from the same peer are gated on it. */
    val isAuthenticated: Boolean get() = authenticated.get()

    /** The peer's pairing was revoked — deauthenticate and close now. */
    fun revokeSession() = sessionAuth.revoke()

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
    private var viewerRegistration: ViewerRegistration? = null

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
            // Agent pushes reach only authenticated peers allowed to read agents (chat.read, the
            // agent.list capability), the same gate admin_rpc applies per method.
            agentEventsGate = {
                authenticated.get() && IrohPeerCapabilities.isAllowed(effectiveCapabilities(), IrohPeerCapabilities.CHAT_READ)
            },
            // Conversation pushes name conversations, so they reach only peers whose conversation
            // scope is unrestricted (the same rule conversation.list scoping applies, lgns8.12).
            conversationEventsGate = {
                val capabilities = effectiveCapabilities()
                authenticated.get() &&
                    IrohPeerCapabilities.isAllowed(capabilities, IrohPeerCapabilities.CHAT_READ) &&
                    IrohPeerCapabilities.conversationScope(capabilities, viewedConversationId = null) == null
            },
        )
        selfViewer = handle
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
            val viewer = ensureSelfViewer(streamSend)
            connectionRegistry?.let { registry ->
                val registration = registry.claim(viewer)
                viewerRegistration = registration
                viewerSubscription = ConversationViewerSubscription(registry, registration)
            }

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.event(
                "IrohNode", "connection.error",
                "remoteEndpointId" to remoteEndpointId,
                "error" to (e.message ?: e.toString()),
                "class" to e::class.simpleName,
            )
        } finally {
            // Release only this connection generation. An overlapped reconnect
            // may already own the same endpoint identity and must survive stale close.
            viewerRegistration?.let { registration ->
                runCatching { connectionRegistry?.release(registration) }
            }
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
            // P0.4: enforce the SAME per-method capability matrix the control
            // channel uses (handleControlAdminRpc). Returns the missing capability
            // to deny, or null to allow — evaluated against the peer's CURRENT
            // effective capabilities at dispatch time.
            capabilityGate = { method ->
                val required = IrohPeerCapabilities.forAdminMethod(method)
                if (IrohPeerCapabilities.isAllowed(effectiveCapabilities(), required)) null else required
            },
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
            // lgns8.12: conversation-content scope follows peer capabilities —
            // manage/admin peers are unrestricted (null); lesser peers are
            // bounded to the conversation they are viewing.
            authorizedConversationIds = IrohPeerCapabilities.conversationScope(
                capabilities = effectiveCapabilities(),
                viewedConversationId = viewerSubscription?.currentConversation,
            ),
            authorizedAgentIds = agentScope(),
        )

    private fun agentScope(): Set<String>? = sessionAuth.agentScope()

    /**
     * A runtime frame (runtime_start, input, sync, abort, queue
     * controls) naming an agent outside the peer's scope is denied before any
     * upstream side effect. Returns the denial frame, or null to proceed.
     */
    private fun agentScopeViolation(frame: JsonObject): String? {
        val type = frame["type"]?.jsonPrimitive?.contentOrNull
        if (type !in AGENT_ADDRESSED_FRAME_TYPES || !authenticated.get()) return null
        if (AgentScopeGuard.allowsParams(agentScope(), frame)) return null
        Telemetry.event("IrohNode", "authz.agent_scope_denied", "remoteEndpointId" to remoteEndpointId, "type" to type)
        val requestId = frame["request_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return """{"type":"error","request_id":"$requestId","message":"forbidden","reason":"agent_scope"}"""
    }

    /** Control-channel admin_rpc: parse + dispatch with scoped auth context. */
    private suspend fun handleControlAdminRpc(obj: JsonObject): String {
        val requestId = obj["request_id"]?.jsonPrimitive?.content
        val method = obj["method"]?.jsonPrimitive?.content
        if (method == null || requestId == null) {
            return """{"type":"admin_rpc_response","request_id":"$requestId","success":false,"error":"method and request_id are required"}"""
        }
        // d6e8g.6: per-method capability gate BEFORE dispatch — a denial must
        // have no proxy side effects. Unknown methods require admin.full.
        val requiredCapability = IrohPeerCapabilities.forAdminMethod(method)
        if (!IrohPeerCapabilities.isAllowed(effectiveCapabilities(), requiredCapability)) {
            Telemetry.event(
                "IrohNode", "authz.denied",
                "remoteEndpointId" to remoteEndpointId,
                "method" to method,
                "capability" to requiredCapability,
            )
            return """{"type":"admin_rpc_response","request_id":"$requestId","success":false,"error":"forbidden","capability":"$requiredCapability"}"""
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
            ?: return """{"type":"sync_response","success":false,"error":"request_id is required"}"""
        return recoverUnlessCancelled(action = {
            val agentId = obj["agent_id"]?.jsonPrimitive?.content
            val conversationId = obj["conversation_id"]?.jsonPrimitive?.content
            if (agentId == null || conversationId == null) {
                """{"type":"sync_response","request_id":"$requestId","success":false,"error":"agent_id and conversation_id required for sync"}"""
            } else {
                val runtime = AppServerRuntimeScope(
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
        }, recover = { error ->
            val message = error.message?.replace("\"", "\\\"") ?: "sync error"
            """{"type":"sync_response","request_id":"$requestId","success":false,"error":"$message"}"""
        })
    }

    private suspend fun serveControlChannel(
        biStream: BiStream,
        streamSend: SendStream,
    ) = coroutineScope {
        val sendStream = biStream.send()
        controlSend = sendStream
        val activeTurnJobs = LinkedHashSet<Job>()
        val activeTurnJobsMutex = Mutex()
        observerWrites = ObserverWriteQueue(this)

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
                    if (response != null) writeControl(response)
                } catch (ce: CancellationException) {
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
            agentScopeViolation(obj)?.let { return it }
            when (type) {
                "auth" -> handleAuth(obj, requestId)
                "runtime_start" -> ifAuthorized(requestId) {
                    ifCapable(requestId, IrohPeerCapabilities.forProtocolCommand("runtime_start")) {
                        handleRuntimeStart(obj, requestId)
                    }
                }
                "input" -> ifAuthorized(requestId) {
                    ifCapable(requestId, IrohPeerCapabilities.forProtocolCommand("input")) {
                    launchInputJob(
                        frameJson = frameJson,
                        streamSend = streamSend,
                        activeTurnJobs = activeTurnJobs,
                        activeTurnJobsMutex = activeTurnJobsMutex,
                    )
                    null
                    }
                }
                "admin_rpc" -> ifAuthorized(requestId) { handleControlAdminRpc(obj) }
                "sync" -> ifAuthorized(requestId) {
                    ifCapable(requestId, IrohPeerCapabilities.forProtocolCommand("sync")) { handleControlSync(obj) }
                }
                "abort_message" -> ifAuthorized(requestId) {
                    ifCapable(requestId, IrohPeerCapabilities.forProtocolCommand("abort_message")) {
                        handleAbort(frameJson, requestId)
                    }
                }
                "remove_queue_item", "resume_queue" -> ifAuthorized(requestId) {
                    ifCapable(requestId, IrohPeerCapabilities.forProtocolCommand(type.orEmpty())) {
                        queueControlRelay.handle(frameJson)
                    }
                }
                else -> """{"type":"error","message":"Unknown command type: $type"}"""
            }
        } catch (e: CancellationException) {
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
            val input = AppServerProtocol.json.decodeFromString(AppServerCommand.serializer(), frameJson)
            handleInput(input as AppServerCommand.Input, streamSend)
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
        val completed = withTimeoutOrNull(STREAM_JOB_DRAIN_TIMEOUT_MS.milliseconds) {
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
        obj: JsonObject,
        requestId: String?,
    ): String {
        if (requestId == null) return """{"type":"auth_response","success":false,"error":"request_id is required"}"""
        val decision = sessionAuth.authenticate(
            token = obj["token"]?.jsonPrimitive?.contentOrNull,
            peerName = obj["peer_name"]?.jsonPrimitive?.contentOrNull,
        )
        return when (decision) {
            is PeerSessionAuth.Decision.Accepted -> acceptAuth(requestId, obj, decision)
            is PeerSessionAuth.Decision.Rejected -> rejectAuth(requestId, decision.outcome)
        }
    }

    /** Remember the peer's transport capabilities and answer success. */
    private fun acceptAuth(requestId: String, obj: JsonObject, decision: PeerSessionAuth.Decision.Accepted): String {
        val advertised = (obj["capabilities"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()
        peerCapabilities.set(advertised)
        Telemetry.event(
            "IrohNode", "auth.ok",
            "remoteEndpointId" to remoteEndpointId,
            "method" to decision.method,
            "peerCapabilities" to advertised.sorted().joinToString(","),
        )
        val capabilities = advertisedCapabilities(adminRpcRouter)
        return buildJsonObject {
            put("type", "auth_response")
            put("request_id", requestId)
            put("success", true)
            if (decision.newlyPaired || decision.alreadyPaired) put("paired", true)
            if (decision.alreadyPaired) put("already_paired", true)
            put("capabilities", Json.encodeToJsonElement(capabilities))
        }.toString()
    }

    /**
     * Deny with a fixed reason label (never derived from the secret); repeated
     * failures on one connection close it, and the endpoint-wide verifier rate
     * limits the NodeId across redials.
     */
    private fun rejectAuth(requestId: String, outcome: IrohBearerAuthVerifier.Outcome): String {
        if (outcome is IrohBearerAuthVerifier.Outcome.RateLimited) {
            Telemetry.event("IrohNode", "auth.rate_limited", "remoteEndpointId" to remoteEndpointId)
            runCatching { connection.close(4429L, "auth_rate_limited".encodeToByteArray()) }
            return """{"type":"auth_response","request_id":"$requestId","success":false,"error":"rate_limited"}"""
        }
        val reason = (outcome as? IrohBearerAuthVerifier.Outcome.Denied)?.reason ?: "denied"
        val failures = authFailuresOnThisConnection.incrementAndGet()
        Telemetry.event(
            "IrohNode", "auth.failed",
            "remoteEndpointId" to remoteEndpointId,
            "reason" to reason,
            "connectionFailures" to failures,
        )
        if (failures >= IrohBearerAuthVerifier.MAX_FAILURES_BEFORE_CLOSE) {
            Telemetry.event("IrohNode", "auth.connection_closed", "remoteEndpointId" to remoteEndpointId)
            runCatching { connection.close(4401L, "auth_failures".encodeToByteArray()) }
        }
        return """{"type":"auth_response","request_id":"$requestId","success":false,"error":"$reason"}"""
    }

    private val authFailuresOnThisConnection = java.util.concurrent.atomic.AtomicInteger(0)

    /** Effective capabilities for this connection (see [PeerSessionAuth]). */
    private fun effectiveCapabilities(): Set<String> = sessionAuth.effectiveCapabilities()

    private fun capabilityDenied(requestId: String?, required: String): String {
        val id = requestId ?: ""
        Telemetry.event(
            "IrohNode", "authz.denied",
            "remoteEndpointId" to remoteEndpointId,
            "requestId" to id,
            "capability" to required,
        )
        return """{"type":"error","request_id":"$id","message":"forbidden","capability":"$required"}"""
    }

    private inline fun ifCapable(requestId: String?, required: String?, block: () -> String?): String? {
        if (required != null && !IrohPeerCapabilities.isAllowed(effectiveCapabilities(), required)) {
            return capabilityDenied(requestId, required)
        }
        return block()
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
        obj: JsonObject,
        requestId: String?,
    ): String {
        val agentId = obj["agent_id"]?.jsonPrimitive?.content
        val conversationId = obj["conversation_id"]?.jsonPrimitive?.content
        val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull
        val modeName = obj["mode"]?.jsonPrimitive?.contentOrNull
        val mode = modeName?.let(AppServerPermissionMode::fromWireValue)

        return if (requestId == null) {
            """{"type":"runtime_start_response","success":false,"error":"request_id is required"}"""
        } else if (agentId == null || conversationId == null) {
            """{"type":"runtime_start_response","request_id":"$requestId","success":false,"error":"agent_id and conversation_id are required"}"""
        } else if (modeName != null && mode == null) {
            """{"type":"runtime_start_response","request_id":"$requestId","success":false,"error":"unsupported permission mode"}"""
        } else {
            recoverUnlessCancelled(action = {
                val runtime = controller.startRuntime(
                    agentId = AgentId(agentId),
                    conversationId = ConversationId(conversationId),
                    cwd = cwd,
                    // letta-mobile-bzvro.40: no mode keeps the one the runtime already has (or the host
                    // default); a mode a client chose with permission_mode.set is never loosened here.
                    mode = mode,
                    recoverApprovals = false,
                    forceDeviceStatus = false,
                )
                // eaczz.3: the initiator/sender signal — a successful
                // runtime_start subscribes THIS connection to conversation_id so
                // its turn frames fan out to it (and, via S4, to co-viewers).
                registerAsViewer(runtime.scope.conversationId)
                """{"type":"runtime_start_response","request_id":"$requestId","success":true,"runtime":{"agent_id":"${runtime.scope.agentId}","conversation_id":"${runtime.scope.conversationId}"}}"""
            }, recover = { error ->
                """{"type":"runtime_start_response","request_id":"$requestId","success":false,"error":"${error.message?.replace("\"", "\\\"")}"}"""
            })
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
                AppServerInboundFrame.AbortMessageResponse(
                    requestId = requestId,
                    runtime = runtime ?: AppServerRuntimeScope("", ""),
                    aborted = false,
                    success = false,
                    error = e.message ?: e.toString(),
                ),
            )
        }
    }

    /**
     * Builds one turn fanout with initiator parking. The initiator's writes stay on this
     * connection's queue; observer writes use the node-owned queue so a detached turn
     *  still reaches the remaining viewers.
     */
    private fun createTurnFanout(
        input: AppServerCommand.Input,
        streamSend: SendStream,
        tracker: TurnFrameTracker?,
        clientMessageId: String?,
    ) = ConversationTurnFanout(
        conversationId = input.runtime.conversationId,
        runtime = input.runtime,
        viewersFor = { conversationId -> connectionRegistry?.viewersFor(conversationId) ?: emptySet() },
        initiatorViewer = ensureSelfViewer(streamSend),
        trackInitiatorFrame = { deltaJson -> tracker?.track(deltaJson) },
        unregisterViewer = { conversationId, viewer -> connectionRegistry?.unregister(conversationId, viewer) },
        observerWrites = turnHost.observerWrites,
        initiatorWrites = observerWrites,
        turnId = firstUserMessage(input)?.clientMessageId,
        identity = turnIdentity?.let { TurnIdentityBinding(it, input.runtime.conversationId, clientMessageId) },
    )

    private fun firstUserMessage(input: AppServerCommand.Input): AppServerInputMessage? =
        (input.payload as? AppServerInputPayload.CreateMessage)?.messages?.firstOrNull { it.role == "user" }

    private suspend fun handleInput(
        input: AppServerCommand.Input,
        streamSend: SendStream,
    ) {
        val userMsg = firstUserMessage(input)
        val contentParts = userMsg?.content as? JsonArray
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
            ackRedialResend(input)
            val parkedFrameSequence = parkedTerminals.takeParked(clientMsgId)
            if (parkedFrameSequence != null) {
                Telemetry.event(
                    "IrohNode", "input.duplicate_replayed_parked_frames",
                    "clientMessageId" to clientMsgId,
                    "agentId" to input.runtime.agentId,
                    "conversationId" to input.runtime.conversationId,
                )
                runCatching {
                    replayParkedDeltas(ensureSelfViewer(streamSend), input.runtime, parkedFrameSequence)
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
        // Mid-turn redial fix: the INITIATOR-ONLY parking record for this turn.
        val tracker = clientMsgId?.let { TurnFrameTracker() }
        val fanout = createTurnFanout(input, streamSend, tracker, clientMsgId)
        val echo = clientMsgId?.let { RelayedUserEcho(it, text, contentParts) }
        val turn = RelayedInputTurn(input, clientMsgId, fanout, relayedTurnProtocol(input, fanout, echo, ::writeControl), tracker)
        // letta-mobile-qygvv.3: the collector runs on the node-owned host. If this
        // connection closes first, the turn is detached, not cancelled: the server
        // turn keeps its approvals, external tools and other viewers, and its tail
        // is parked for this initiator's redial.
        turnHost.run(
            NodeTurn(
                conversationId = input.runtime.conversationId,
                clientMessageId = clientMsgId,
                fanout = fanout,
                tracker = tracker ?: TurnFrameTracker(),
                parkedTerminals = parkedTerminals,
            ) {
                relayAndSettle(command, turn)
            },
        )
    }

    /** Runs the relayed turn, then joins its messages to the stored rows. */
    private suspend fun relayAndSettle(command: TurnCommand, turn: RelayedInputTurn) {
        var rejectedBusy = false
        try {
            relayTurn(controller, command, turn.protocol.onHost(turnHost)) { error ->
                rejectedBusy = isTurnAlreadyActiveMessage(error.message ?: error.toString())
                handleInputFailure(error, turn)
            }
            if (!rejectedBusy) turn.fanout.settleIdentity()
        } finally {
            if (!rejectedBusy) turn.fanout.abandonIdentity()
        }
    }

    /** A redial re-send still gets its ack; the original turn owns the input. */
    private suspend fun ackRedialResend(input: AppServerCommand.Input) {
        val requestId = input.requestId ?: return
        runCatching { writeControl(inputAcceptedFrame(requestId, input.runtime, TurnInputAcknowledgement.Started)) }
            .onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun handleInputFailure(error: Throwable, turn: RelayedInputTurn) {
        val errorText = error.message ?: error.toString()
        if (isTurnAlreadyActiveMessage(errorText)) {
            emitBusyRejectionToInitiator(turn.fanout, error)
            return
        }
        val wroteTerminal = runCatching {
            withContext(NonCancellable) {
                turn.fanout.flushOpenToolCalls()
                turn.fanout.emitErrorTerminal(errorText)
                turn.protocol.onTurnError(errorText)
            }
        }.isSuccess
        if (!wroteTerminal) {
            parkInterruptedTerminal(error, turn.fanout, turn.clientMsgId, turn.input, turn.tracker)
        }
        if (error is CancellationException) throw error
    }

    private suspend fun emitBusyRejectionToInitiator(fanout: ConversationTurnFanout, error: Throwable) {
        val errorText = error.message ?: error.toString()
        // Concurrent send while a turn is live: do NOT fan out error_message to
        // conversation viewers (they would map it onto the live turn). Still
        // deliver an initiator-only busy rejection so the submitting peer gets a
        // failure terminal for its own pending send.
        runCatching {
            withContext(NonCancellable) {
                fanout.emitInitiatorOnlyBusyRejection(errorText)
            }
        }
        Telemetry.event(
            "IrohNode", "stream.busy_rejected_initiator_only",
            "remoteEndpointId" to remoteEndpointId,
            "error" to errorText,
            "class" to error::class.simpleName,
            level = Telemetry.Level.WARN,
        )
    }

    private fun parkInterruptedTerminal(
        error: Throwable,
        fanout: ConversationTurnFanout,
        clientMsgId: String?,
        input: AppServerCommand.Input,
        tracker: TurnFrameTracker?,
    ) {
        Telemetry.event(
            "IrohNode", "stream.closed_before_terminal",
            "remoteEndpointId" to remoteEndpointId,
            "error" to (error.message ?: error.toString()),
            "class" to error::class.simpleName,
            level = Telemetry.Level.WARN,
        )
        if (clientMsgId == null || fanout.anyTerminalWritten) return
        tracker?.parkFrames(parkedTerminals, clientMsgId, NodeTurnHost.INTERRUPTED_TERMINAL)
        Telemetry.event(
            "IrohNode", "stream.frames_parked",
            "remoteEndpointId" to remoteEndpointId,
            "clientMessageId" to clientMsgId,
            "conversationId" to input.runtime.conversationId,
            "frameCount" to (tracker?.frameCount() ?: 0),
        )
    }

    private fun extractTextFromContentParts(parts: JsonArray?): String? =
        parts?.firstOrNull { part ->
            runCatching { part.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }.getOrDefault(false)
        }?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

    private suspend fun serveStreamReadiness(biStream: BiStream) {
        val recvStream = biStream.recv()
        runCatching { recvStream.read(1u) }
    }

    companion object {
        private val SUBAGENT_RPC_METHODS = setOf("subagent.list", "subagent.todos")

        /** Control frames that address an agent and so are subject to the peer's agent scope. */
        private val AGENT_ADDRESSED_FRAME_TYPES = setOf(
            "runtime_start", "input", "sync", "abort_message", "remove_queue_item", "resume_queue",
        )

        internal fun advertisedCapabilities(router: AdminRpcRouter): List<String> = buildList {
            // letta-mobile-by4vm.50: ordered event_seq is a property of the connection's write path,
            // not of any admin handler, so every host advertises it.
            addAll(listOf(IrohFrameCodec.FRAME_PART_CAPABILITY, ORDERED_EVENT_SEQ_CAPABILITY) + router.featureCapabilities)
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
