package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.ConnectionRegistry
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.ViewerHandle
import com.letta.mobile.data.controller.node.iroh.ViewerRegistration
import com.letta.mobile.data.feed.client.FeedLink
import com.letta.mobile.data.feed.client.FeedReply
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One real host: the production journal, `feed.subscribe` route, connection registry and push, served through the real
 * [AdminRpcRouter] (capability matrix, scope guards) and shared by every [device]. A device that calls `feed.subscribe`
 * is registered as a viewer of the conversation it names and marked as reading the feed, exactly as
 * `IrohNodeConnection.observeAdminMethod` does for a real connection (IrohNodeConnection.kt, the `feed.subscribe`
 * branch), and a rejected call leaves it unregistered. Nothing here accepts what the real host would refuse.
 */
internal class RouterFeedHost(
    private val scope: CoroutineScope,
    private val directory: Path = Files.createTempDirectory("feed-host"),
    private val frameBudgetMs: Long = 16,
) {
    private var startedAt = EPOCH
    val registry = ConnectionRegistry()
    private val devices = mutableListOf<Device>()

    /** The `feed.subscribe` params the host answered, in order. */
    val reads = mutableListOf<JsonObject>()

    lateinit var journal: FeedJournalStore
        private set
    private lateinit var router: AdminRpcRouter
    private var pusher: FeedPusher? = null

    init {
        start(pushes = true)
    }

    private fun start(pushes: Boolean) {
        journal = FeedJournalStore(directory, FeedRetention()) { startedAt }
        router = AdminRpcRouter().also { FeedRoutes.forHost(journal, pushes).register(it) }
        pusher = FeedPusher(scope, journal, { registry.viewersFor(it.value) }, registry::isViewed, frameBudgetMs).takeIf { pushes }
    }

    /** The host restarted: a later epoch over the same journal files (the old journal and pusher are abandoned). */
    fun restart() {
        startedAt += EPOCH_STEP
        start(pushes = true)
    }

    /** A host that serves the feed to readers but does not push (an older build): no push capability, no push. */
    fun withoutPush(): RouterFeedHost = also { start(pushes = false) }

    /** Writes [script] to [conv]'s journal as a running turn would. */
    fun write(conv: Conv, script: TurnScript) {
        journal.write(conv, script)
    }

    /** A paired device: a link to this host that the test can cut and restore. */
    fun device(endpoint: String, agents: Set<String>? = null, conversations: Set<String>? = null): Device =
        Device(endpoint, agents, conversations).also { devices += it }

    inner class Device(
        private val endpoint: String,
        private val agents: Set<String>?,
        private val conversations: Set<String>?,
    ) : FeedLink, ViewerHandle {
        val connected = MutableStateFlow(true)
        private val pushFrames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
        private var epoch = 1
        private var readsFeed = false
        private var registration: ViewerRegistration? = null
        private var viewed: String? = null

        /** Every `feed_delta` frame the host wrote to this device. */
        val received = mutableListOf<JsonObject>()

        override val connectionId: String get() = endpoint
        override val hostCapabilities: Set<String> get() = if (connected.value) router.featureCapabilities else emptySet()
        override val connection: Flow<String?> get() = connected.map { up -> "session#$epoch".takeIf { up } }
        override val pushes: Flow<JsonObject> get() = pushFrames

        override suspend fun writeFrame(frame: String): Boolean {
            if (!connected.value) return false
            val parsed = Json.parseToJsonElement(frame).jsonObject
            received += parsed
            pushFrames.tryEmit(parsed)
            return true
        }

        override fun receivesFeedPushes(): Boolean = readsFeed

        override fun adminContext(): AdminRpcRequestContext? = context()

        private fun context() = AdminRpcRequestContext(
            authenticated = true,
            authenticatedEndpointId = endpoint,
            authorizedAgentIds = agents,
            authorizedConversationIds = viewed?.let { conversations?.plus(it) } ?: conversations,
            grantedCapabilities = IrohPeerCapabilities.CHAT_ROLE,
        )

        fun cut() {
            connected.value = false
        }

        fun restore() {
            epoch += 1
            connected.value = true
        }

        override suspend fun call(method: String, params: JsonObject): FeedReply {
            if (!connected.value) error("link is down")
            if (method == ConversationFeedWire.SUBSCRIBE) reads += params
            val conversation = params[ConversationFeedWire.CONVERSATION_ID]?.jsonPrimitive?.content
            val joined = if (method == ConversationFeedWire.SUBSCRIBE && conversation != null) observe(conversation) else false
            val response = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", method, params, context()))).jsonObject
            val ok = response.getValue("success").jsonPrimitive.boolean
            if (!ok && joined) leave()
            return FeedReply(ok, response["result"], response["error"]?.jsonPrimitive?.content)
        }

        /** The connection's observer signal: view [conversation] and be pushed its feed. */
        private suspend fun observe(conversation: String): Boolean {
            val held = registration ?: registry.claim(this).also { registration = it }
            viewed?.takeIf { it != conversation }?.let { registry.unregister(it, this) }
            viewed = conversation
            readsFeed = true
            return registry.register(conversation, held)
        }

        private suspend fun leave() {
            viewed?.let { registry.unregister(it, this) }
            viewed = null
            readsFeed = false
        }
    }

    private companion object {
        const val EPOCH = 1000L
        const val EPOCH_STEP = 1000L
    }
}
