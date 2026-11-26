package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.attr
import com.letta.mobile.data.controller.node.iroh.errors.AdminRpcClientError
import com.letta.mobile.data.controller.node.iroh.stream.AdminRpcStreamedHandler
import com.letta.mobile.data.controller.node.iroh.timing.AdminRpcPhaseTimings
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.data.transport.iroh.AdminRpcErrors
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

/**
 * Refuses the call with [message] for the remote client: the router's catch path returns
 * success:false carrying [message]. Use it only for safe, deliberate text; any other exception
 * a handler lets escape answers [ADMIN_RPC_INTERNAL_ERROR] instead.
 */
fun adminError(message: String): Nothing = throw AdminRpcClientError(message)

/** An owner lookup that takes longer than this answers no owner (a denial), never a hung request. */
private const val OWNER_LOOKUP_TIMEOUT_MS = 2_000L

/** Bound on the router's resolved-owner cache; past it the cache starts over rather than growing without limit. */
private const val MAX_KNOWN_OWNERS = 4_096

/** The fixed failure text for every handler exception that is not a deliberate client refusal. */
internal const val ADMIN_RPC_INTERNAL_ERROR = "Internal error"

/**
 * Routes incoming admin RPC calls to registered handler functions.
 *
 * Each domain bead registers its handlers here. The [IrohNodeConnection] calls
 * [dispatch] when it receives an "admin_rpc" control frame.
 *
 * Handler signature: `suspend (JsonObject?) -> JsonElement`
 * - Input: the RPC params (may be null)
 * - Output: the JSON result to send back in admin_rpc_response
 *
 * @param controller The App Server controller for runtime management
 * @param json JSON instance for parsing
 */
data class AdminRpcRequestContext(
    val authenticated: Boolean,
    val authorizedConversationIds: Set<String>? = null,
    /**
     * Agents a paired peer may address; null = every
     * agent. Enforced by [AdminRpcRouter.dispatch] for every method via
     * [AgentScopeGuard], so no handler can forget it.
     */
    val authorizedAgentIds: Set<String>? = null,
    /** Iroh endpoint identity established by the serving connection, never request parameters. */
    val authenticatedEndpointId: String? = null,
    /**
     * Whether the serving connection authenticated a paired device by its stored pairing record
     * (the human principal of the secret request routes). False for bootstrap sessions (bearer token, static allowlist,
     * anonymous test mode), which is how a host-local process holding an admin token is told apart
     * from a person's device. Only the serving connection sets it.
     */
    val pairedDevice: Boolean = false,
    /** Transport encoding negotiated with this peer, independent of authorization grants. */
    val supportsFrameParts: Boolean = false,
    /** The capabilities the serving connection granted this session, as it verified them; empty unless it set them. */
    val grantedCapabilities: Set<String> = emptySet(),
) {
    fun canAccessConversation(conversationId: String): Boolean =
        authenticated && (authorizedConversationIds == null || conversationId in authorizedConversationIds)

    companion object {
        val Authenticated = AdminRpcRequestContext(authenticated = true)
        val Unauthenticated = AdminRpcRequestContext(authenticated = false, authorizedConversationIds = emptySet())
    }
}

/** Bundles dispatch inputs so the router API stays free of multi-string arity. */
data class AdminRpcInvocation(
    val requestId: String,
    val method: String,
    val params: JsonObject?,
    val context: AdminRpcRequestContext = AdminRpcRequestContext.Authenticated,
) {
    /** Never the params' values (a call may carry a secret, as `secret.request.submit` does): only their names. */
    override fun toString(): String =
        "AdminRpcInvocation(requestId=$requestId, method=$method, paramNames=${params?.keys}, context=$context)"
}

class AdminRpcRouter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val handlers = mutableMapOf<String, suspend (JsonObject?, AdminRpcRequestContext) -> JsonElement>()
    private val streamedHandlers = mutableMapOf<String, AdminRpcStreamedHandler>()

    /**
     * The agent that owns a conversation, from the host's own records (never from request parameters): given the
     * conversation id a request names, the owner, or null when the host cannot say. Null denies an
     * agent-scoped peer. [ConversationAdminHandlers] installs the App Server lookup; the default knows no owner.
     */
    internal var conversationOwner: suspend (ConversationId) -> String? = { null }

    /** Owners already resolved: a conversation never changes agent, so a known owner is kept (unknowns never are). */
    private val knownOwners = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Methods whose handler proves the owning agent of the conversation it is given itself (the feed journal's
     * recorded owner, the canvas mount authority's host record), failing closed with its own refusal wording, so the
     * central owner check in [preauthorize] leaves them to it. Only routes whose handler does so may add to it, at
     * registration. A transport observes such a call of an agent-scoped peer only once it succeeded.
     */
    internal val handlerOwnedConversationScope: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    val methodCount: Int
        get() = handlers.size

    val registeredMethods: Set<String>
        get() = handlers.keys.toSet()

    /**
     * Registers a handler for the given method name.
     * Method names should use dot-notation: "agent.list", "conversation.get", etc.
     */
    fun register(method: String, handler: suspend (JsonObject?) -> JsonElement) {
        registerScoped(method) { params, _ -> handler(params) }
    }

    fun registerScoped(
        method: String,
        handler: suspend (JsonObject?, AdminRpcRequestContext) -> JsonElement,
    ) {
        handlers[method] = handler
        Telemetry.event("AdminRpc", "handler.registered", "method" attr method)
    }

    /**
     * Registers the streamed method [streamed] serves: on a request bi-stream of its own,
     * [AdminRpcStreamServer] hands it to [streamed], which may write stream frames before the final
     * response. Anywhere else (the control channel, a direct dispatch) [elsewhere] answers, normally with
     * a refusal.
     */
    internal fun registerStreamed(
        streamed: AdminRpcStreamedHandler,
        elsewhere: suspend (JsonObject?, AdminRpcRequestContext) -> JsonElement,
    ) {
        registerScoped(streamed.method, elsewhere)
        streamedHandlers[streamed.method] = streamed
    }

    /**
     * Whether a transport may observe [invocation] (register its connection as the viewer of the conversation it
     * names) BEFORE dispatch, so that no turn frame of a conversation being hydrated is missed. Callable only once
     * [preauthorize] passed. True only when the call is already authorized for every conversation it names on the
     * context as it was before any viewer change: the peer may read each one (its conversation-content scope), and for
     * an agent-scoped peer [preauthorize] (not the handler) proved each one's owner. Otherwise the transport dispatches
     * first and observes only once the handler answered successfully, so viewing a conversation can never grant
     * access to it and a refused call never holds even a transient subscription.
     */
    internal fun mayObserveBeforeDispatch(invocation: AdminRpcInvocation): Boolean {
        val context = invocation.context
        if (context.authorizedAgentIds != null && invocation.method in handlerOwnedConversationScope) return false
        return AgentScopeGuard.conversationIdsOf(invocation.params).all(context::canAccessConversation)
    }

    /**
     * Dispatches a preauthorized [invocation] through [dispatch] (the transport's own coroutine context) and lets the
     * transport observe it: [observe] registers the connection as the call's viewer and answers how to settle that,
     * or null when it observed nothing. It runs before dispatch when [mayObserveBeforeDispatch] allows, otherwise only
     * once the handler answered successfully. The handler always runs on the invocation's own context, as it was
     * before any viewer change. [settle] applies the outcome, and a failure when dispatch throws or is cancelled.
     */
    internal suspend fun dispatchObserving(
        invocation: AdminRpcInvocation,
        observe: suspend () -> (suspend (Boolean) -> Unit)?,
        dispatch: suspend (AdminRpcInvocation) -> String,
        settle: suspend ((suspend (Boolean) -> Unit)?, Boolean) -> Unit,
    ): String {
        val observesFirst = mayObserveBeforeDispatch(invocation)
        val early = if (observesFirst) observe() else null
        var answered = false
        val response = try {
            dispatch(invocation).also { answered = true }
        } finally {
            // Thrown or cancelled: an observation made first is rolled back before the failure propagates.
            if (!answered) settle(early, false)
        }
        val succeeded = !isAdminRpcFailureEnvelope(response)
        val late = if (!observesFirst && succeeded) observe() else null
        settle(early ?: late, succeeded)
        return response
    }

    /** The streamed handler of [invocation]'s method, or null when it answers with a single response. */
    internal fun streamedHandler(invocation: AdminRpcInvocation): AdminRpcStreamedHandler? = streamedHandlers[invocation.method]

    fun requireNonEmpty(minMethods: Int = 1): AdminRpcRouter {
        require(handlers.size >= minMethods) {
            "Admin RPC router has ${handlers.size} registered methods; expected at least $minMethods"
        }
        return this
    }

    /**
     * Capabilities a host feature behind this router
     * enables beyond its methods (for example the subagent registry push),
     * advertised to clients on auth.
     */
    @Volatile
    var featureCapabilities: Set<String> = emptySet()

    fun copyHandlersFrom(other: AdminRpcRouter): AdminRpcRouter {
        handlers.clear()
        handlers.putAll(other.handlers)
        streamedHandlers.clear()
        streamedHandlers.putAll(other.streamedHandlers)
        featureCapabilities = other.featureCapabilities
        conversationOwner = other.conversationOwner
        handlerOwnedConversationScope.clear()
        handlerOwnedConversationScope.addAll(other.handlerOwnedConversationScope)
        return requireNonEmpty(other.methodCount)
    }

    /**
     * Checks whether [invocation] may reach its handler without invoking it or
     * changing connection state. Returns the same failure response [dispatch]
     * would return, or null when handler dispatch may proceed.
     *
     * Transport paths use this before attaching connection-local observation
     * state. For an agent-scoped peer it also resolves the owning agent of every
     * conversation the request names: a conversation owned
     * outside the scope, or whose owner the host cannot resolve, is denied here,
     * so no conversation-addressed handler can forget it.
     */
    internal suspend fun preauthorize(invocation: AdminRpcInvocation): String? {
        val handler = handlers[invocation.method]
        if (handler == null) {
            Telemetry.event("AdminRpc", "method.not_found", "method" attr invocation.method)
            return encodeFailure(invocation.requestId, AdminRpcErrors.unknownMethod(invocation.method))
        }
        if (isAgentScopeDenied(invocation)) {
            Telemetry.event("AdminRpc", "authz.agent_scope_denied", "method" attr invocation.method)
            return encodeFailure(invocation.requestId, AgentScopeGuard.DENIED)
        }
        if (!conversationsInScope(invocation.method, invocation.params, invocation.context)) {
            Telemetry.event("AdminRpc", "authz.conversation_owner_denied", "method" attr invocation.method)
            return encodeFailure(invocation.requestId, AgentScopeGuard.DENIED)
        }
        return null
    }

    private fun isAgentScopeDenied(invocation: AdminRpcInvocation): Boolean {
        val scope = invocation.context.authorizedAgentIds
        val scopedBeads = invocation.method.startsWith("beads.") && !scope.isNullOrEmpty()
        return scopedBeads || !AgentScopeGuard.allowsParams(scope, invocation.params)
    }

    /**
     * Whether every conversation a request or runtime frame ([params]) names is owned by an agent in the peer's scope
     * ([context]). Unscoped peers (owner, desktop, A2A) pass without a lookup. A conversation already in the
     * connection's bounded content scope (the one it views) needs none either: a connection only comes to view a
     * conversation through a request this check (or the owning handler) authorized. The bare `default` conversation is
     * per agent, so its owner is the one agent the frame names (already checked against the scope); without one it is
     * denied. Any other id is looked up; a lookup that fails or times out answers no owner, which denies.
     */
    internal suspend fun conversationsInScope(method: String, params: JsonObject?, context: AdminRpcRequestContext): Boolean {
        val scope = context.authorizedAgentIds ?: return true
        if (method in handlerOwnedConversationScope) return true
        val namedAgent = AgentScopeGuard.agentIdsOf(params).singleOrNull()
        return AgentScopeGuard.conversationIdsOf(params).all { conversation ->
            val owner = when {
                context.authorizedConversationIds?.contains(conversation) == true -> return@all true
                conversation == IdFormats.DEFAULT_CONVERSATION_ID -> namedAgent
                else -> knownOwners[conversation] ?: lookUpOwner(conversation)
            }
            if (owner == null) Telemetry.event("AdminRpc", "authz.conversation_owner_unresolved", "method" attr method)
            owner != null && owner in scope
        }
    }

    /** [conversationOwner] within [OWNER_LOOKUP_TIMEOUT_MS]; a failure or timeout answers null (a denial), a hit is kept. */
    private val lookUpOwner: suspend (String) -> String? = { conversation ->
        try {
            withTimeoutOrNull(OWNER_LOOKUP_TIMEOUT_MS.milliseconds) { conversationOwner(ConversationId(conversation)) }?.also { owner ->
                if (knownOwners.size >= MAX_KNOWN_OWNERS) knownOwners.clear()
                knownOwners[conversation] = owner
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Dispatches an RPC call to the registered handler.
     * Returns the JSON response frame or an error frame if the method is unknown
     * or the handler throws.
     */
    suspend fun dispatch(invocation: AdminRpcInvocation): String {
        preauthorize(invocation)?.let { return it }
        // Handler registration completes before the connection starts serving.
        // [preauthorize] above is the single source of the unknown-method check.
        val handler = checkNotNull(handlers[invocation.method])
        val agentScope = invocation.context.authorizedAgentIds
        val startedNs = System.nanoTime()
        val phases = AdminRpcPhaseTimings()
        return try {
            val result = AgentScopeGuard.filterResult(
                agentScope,
                invocation.method,
                withContext(phases) { handler(invocation.params, invocation.context) },
            )
            json.encodeToString(
                kotlinx.serialization.serializer(),
                buildJsonObject {
                    put("type", "admin_rpc_response")
                    put("request_id", invocation.requestId)
                    put("success", true)
                    put("result", result)
                },
            ).also { response -> reportIfSlow(invocation, startedNs, response.length, phases) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            reportHandlerError(invocation.method, e)
            encodeFailure(invocation.requestId, clientFacingMessage(e))
        }
    }

    /** Records a failed handler by exception class and category only: never its message, a path or an id. */
    private fun reportHandlerError(method: String, error: Exception) {
        Telemetry.event(
            "AdminRpc",
            "handler.error",
            "method" attr method,
            "class" attr error::class.simpleName,
            "category" attr if (error is AdminRpcClientError) "client_refusal" else "internal",
        )
    }

    private fun encodeFailure(requestId: String, message: String): String =
        json.encodeToString(
            kotlinx.serialization.serializer(),
            buildJsonObject {
                put("type", "admin_rpc_response")
                put("request_id", requestId)
                put("success", false)
                put("error", message)
            },
        )
}

/**
 * The failure text a remote client may see for [error]: the message of a deliberate
 * [AdminRpcClientError], else [ADMIN_RPC_INTERNAL_ERROR]. Exception messages of any other type can
 * carry host paths or internal state, so they never cross the wire.
 */
internal fun clientFacingMessage(error: Exception): String =
    (error as? AdminRpcClientError)?.message ?: ADMIN_RPC_INTERNAL_ERROR

/** Whether a router response is a failure envelope that must roll observer state back. */
internal fun isAdminRpcFailureEnvelope(response: String): Boolean =
    runCatching {
        Json.parseToJsonElement(response).jsonObject["success"]?.jsonPrimitive?.content == "false"
    }.getOrDefault(false)
