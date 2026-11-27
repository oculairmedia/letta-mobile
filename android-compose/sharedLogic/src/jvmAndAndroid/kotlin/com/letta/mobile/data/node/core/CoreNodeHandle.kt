package com.letta.mobile.data.node.core

import com.letta.mobile.core.ffi.CatalogDocument
import com.letta.mobile.core.ffi.CatalogEvent
import com.letta.mobile.core.ffi.MembershipView
import com.letta.mobile.core.ffi.NodeEvent
import com.letta.mobile.core.ffi.NodeInfo
import com.letta.mobile.core.ffi.PeerAddress
import com.letta.mobile.core.ffi.PlacementStatus
import com.letta.mobile.data.model.AgentId
import kotlinx.coroutines.flow.Flow

/**
 * One Baken node run by the Rust core (the `baken_core` library), as the app sees it. Every call
 * that touches the node suspends; every stream is cold, subscribing when collected and cancelling
 * the core's task when the collector goes away. Failures are `CoreException`s from the core.
 *
 * The app does not use this yet: which platforms switch from the Kotlin node to the core is a
 * per-platform decision taken when it is wired into their composition.
 */
interface CoreNodeHandle : AutoCloseable {
    /** Starts the node (key, store, runtime, transport) and returns its node id, hex. */
    suspend fun start(): String

    /** Stops the node; every stream of it completes. Stopping a stopped node does nothing. */
    suspend fun stop()

    /** Whether the node is running. */
    val isRunning: Boolean

    /** Where other nodes dial this one. */
    suspend fun address(): PeerAddress

    /** Keeps a session (agent logs and catalog) with [peer], redialling while the node runs. */
    suspend fun connect(peer: PeerAddress)

    /** node.info v1 for this node. */
    suspend fun info(): NodeInfo

    /** The membership view the node holds now. */
    suspend fun membership(): MembershipView

    /** The documents the node's catalog registry lists. */
    suspend fun catalogDocuments(): List<CatalogDocument>

    /** The typed view of one catalog document, as JSON. */
    suspend fun catalogViewJson(url: String): String

    /** Where [agent] lives by this node's copy of its log, and this node's input gate; null if unknown. */
    suspend fun placement(agent: AgentId): PlacementStatus?

    /** The membership view now, then one per change; conflated, so a slow collector sees the latest. */
    fun membershipViews(): Flow<MembershipView>

    /** Catalog events (violations, closed links). */
    fun catalogEvents(): Flow<CatalogEvent>

    /** The node's own events (stopping, foreground changes, network changes). */
    fun nodeEvents(): Flow<NodeEvent>
}
