package com.letta.mobile.data.node.core

import com.letta.mobile.core.ffi.CatalogDocument
import com.letta.mobile.core.ffi.CatalogEvent
import com.letta.mobile.core.ffi.CatalogEventListener
import com.letta.mobile.core.ffi.CoreNode
import com.letta.mobile.core.ffi.MembershipListener
import com.letta.mobile.core.ffi.MembershipView
import com.letta.mobile.core.ffi.NodeEvent
import com.letta.mobile.core.ffi.NodeEventListener
import com.letta.mobile.core.ffi.NodeInfo
import com.letta.mobile.core.ffi.NodeOptions
import com.letta.mobile.core.ffi.PeerAddress
import com.letta.mobile.core.ffi.PlacementStatus
import com.letta.mobile.core.ffi.PlatformHooks
import com.letta.mobile.core.ffi.RuntimeHost
import com.letta.mobile.core.ffi.TaskHandle
import com.letta.mobile.core.ffi.Transport
import com.letta.mobile.data.model.AgentId
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * [CoreNodeHandle] over the generated bindings. The core's synchronous reads run on [blocking]
 * (never the caller's thread, which may be a UI thread); its suspend calls are awaited directly,
 * since the core runs them on the node's own runtime. Streams follow ffi-v1: the listener only
 * hands values to the flow's channel, and closing the flow cancels the core's task.
 */
class UniffiCoreNode(
    private val node: CoreNode,
    private val blocking: CoroutineContext = Dispatchers.IO,
) : CoreNodeHandle {
    override suspend fun start(): String = node.start()

    override suspend fun stop() = node.stop()

    override val isRunning: Boolean get() = node.isRunning()

    override suspend fun address(): PeerAddress = withContext(blocking) { node.address() }

    override suspend fun connect(peer: PeerAddress) = node.connect(peer)

    override suspend fun info(): NodeInfo = withContext(blocking) { node.info() }

    override suspend fun membership(): MembershipView = withContext(blocking) { node.membership() }

    override suspend fun catalogDocuments(): List<CatalogDocument> = node.catalogDocuments()

    override suspend fun catalogViewJson(url: String): String = node.catalogViewJson(url)

    override suspend fun placement(agent: AgentId): PlacementStatus? = node.placement(agent.value)

    override fun membershipViews(): Flow<MembershipView> = stream<MembershipView> { channel ->
        node.subscribeMembership(
            object : MembershipListener {
                override fun onView(view: MembershipView) {
                    channel.trySend(view)
                }
            },
        )
    }.conflate()

    override fun catalogEvents(): Flow<CatalogEvent> = stream<CatalogEvent> { channel ->
        node.subscribeCatalogEvents(
            object : CatalogEventListener {
                override fun onEvent(event: CatalogEvent) {
                    channel.trySend(event)
                }
            },
        )
    }.buffer(Channel.UNLIMITED)

    override fun nodeEvents(): Flow<NodeEvent> = stream<NodeEvent> { channel ->
        node.subscribeNodeEvents(
            object : NodeEventListener {
                override fun onEvent(event: NodeEvent) {
                    channel.trySend(event)
                }
            },
        )
    }.buffer(Channel.UNLIMITED)

    /** Frees the native node; stop it first. */
    override fun close() = node.close()

    /** A cold flow over one core stream: subscribes on collection, cancels the task when it ends. */
    private fun <T> stream(subscribe: (ProducerScope<T>) -> TaskHandle): Flow<T> = callbackFlow {
        val handle = withContext(blocking) { subscribe(this@callbackFlow) }
        awaitClose {
            handle.cancel()
            handle.close()
        }
    }

    companion object {
        /** A node with these options, transport and platform implementations; nothing runs until [start]. */
        fun create(
            options: NodeOptions,
            transport: Transport,
            host: RuntimeHost,
            hooks: PlatformHooks,
        ): UniffiCoreNode = UniffiCoreNode(CoreNode(options, transport, host, hooks))
    }
}
