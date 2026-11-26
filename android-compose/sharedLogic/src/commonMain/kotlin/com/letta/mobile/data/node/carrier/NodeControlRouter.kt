package com.letta.mobile.data.node.carrier

import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads a link's inbound frames once and routes them: a control frame whose `request_id` a pending [request] waits
 * for completes it; everything else goes to [unsolicited]. When the link ends, every pending request fails with the
 * link's closure. When the routing ends first (its scope was cancelled, or the inbound flow failed), the router
 * closes the link itself, so no link outlives its router.
 */
internal class NodeControlRouter private constructor(private val link: NodeLink) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<NodeFrame>>()
    private var ended = false
    private val others = Channel<NodeInbound>(Channel.UNLIMITED)

    /** Frames no request claimed, in arrival order. */
    val unsolicited: Flow<NodeInbound> = others.receiveAsFlow()

    suspend fun request(frame: NodeFrame): NodeFrame {
        val id = requireNotNull(frame.requestId) { "a control request needs a request_id" }
        val answer = CompletableDeferred<NodeFrame>()
        mutex.withLock {
            if (ended) throw closed()
            pending[id] = answer
        }
        try {
            link.sendControl(frame)
            return answer.await()
        } finally {
            // A failed send, a closed link or the caller giving up: drop this waiter (only if it is still ours), so a
            // late answer goes to the unsolicited frames instead of an abandoned waiter. The link stays open.
            withContext(NonCancellable) { mutex.withLock { if (pending[id] === answer) pending.remove(id) } }
        }
    }

    private suspend fun route(inbound: NodeInbound) {
        val waiter = inbound.frame.requestId
            ?.takeIf { inbound.lane == NodeLane.CONTROL }
            ?.let { id -> mutex.withLock { pending.remove(id) } }
        if (waiter != null) waiter.complete(inbound.frame) else others.trySend(inbound)
    }

    private suspend fun end() {
        val waiting = mutex.withLock {
            ended = true
            pending.values.toList().also { pending.clear() }
        }
        val failure = closed()
        waiting.forEach { it.completeExceptionally(failure) }
        others.close()
    }

    private fun closed() = NodeCarrierException(NodeCarrierFailure.Closed(link.closure.value ?: NodeLinkClosure.LOST))

    companion object {
        /** Starts routing [link]'s inbound frames in [scope] until the link closes. */
        fun start(link: NodeLink, scope: CoroutineScope): NodeControlRouter {
            val router = NodeControlRouter(link)
            // Undispatched, so the routing enters its try before start returns: even a scope that is already
            // cancelled runs the cleanup below, closing the link and failing every pending request.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Cancelled routing means the session's owner went away: close as a detach.
                var closure = NodeLinkClosure.NORMAL
                try {
                    runCatchingCancellable { link.inbound.collect { router.route(it) } }
                    // The inbound flow ended or failed without a cancel: the link is gone. Close is a no-op when the
                    // link already ended, which is the usual case.
                    closure = NodeLinkClosure.LOST
                } finally {
                    withContext(NonCancellable) {
                        runCatchingCancellable { link.close(closure) }
                        router.end()
                    }
                }
            }
            return router
        }
    }
}
