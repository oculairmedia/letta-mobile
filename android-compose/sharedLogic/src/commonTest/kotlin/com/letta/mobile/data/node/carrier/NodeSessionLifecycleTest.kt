package com.letta.mobile.data.node.carrier

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The session's own lifecycle rules: no link outlives a failed open or its router, and a resume never truncates silently. */
class NodeSessionLifecycleTest {
    private val request = NodeOpenRequest(LoopbackNodeCarrier.ADDRESS, NodeCredential.Token("contract-admin-token"))

    @Test
    fun anOpenCancelledWhileAwaitingAuthClosesTheLink() = onLoopback { carrier, scope, node ->
        val opening = scope.async { NodeSession.open(carrier, request, scope) }
        node.authReceived.await()
        opening.cancelAndJoin()
        assertEquals(NodeLinkClosure.NORMAL, node.closure.await(), "the dialled link is closed, not left without an owner")
    }

    @Test
    fun cancellingTheRoutingScopeClosesTheLink() = onLoopback(answerAuth = true) { carrier, scope, node ->
        val routing = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = NodeSession.open(carrier, request, routing)
        routing.cancel()
        assertEquals(NodeLinkClosure.NORMAL, node.closure.await(), "the router closes the link as a detach")
        assertEquals(NodeLinkClosure.NORMAL, session.closure.first { it != null })
        scope.cancel()
    }

    @Test
    fun aResumeThatNeverCatchesUpFailsIncomplete() = onLoopback(answerAuth = true, endlessFeed = true) { carrier, scope, _ ->
        val session = NodeSession.open(carrier, request, scope)
        val failure = runCatching { session.resumeFeed(ConversationId("conv-1"), AgentId("agent-a"), null) }.exceptionOrNull()
        assertEquals(
            NodeCarrierFailure.Incomplete("feed.subscribe", NodeSession.MAX_RESUME_PAGES),
            (failure as? NodeCarrierException)?.failure,
            "failed with $failure",
        )
        session.close()
    }

    private fun onLoopback(
        answerAuth: Boolean = false,
        endlessFeed: Boolean = false,
        body: suspend (NodeCarrier, CoroutineScope, LifecycleNode) -> Unit,
    ) = runTest(timeout = TIMEOUT) {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val node = LifecycleNode(answerAuth, endlessFeed)
            try {
                withTimeout(TIMEOUT) { body(LoopbackNodeCarrier(node, scope), scope, node) }
            } finally {
                scope.cancel()
            }
        }
    }

    private companion object {
        val TIMEOUT = 20.seconds
    }
}

/** A node that records its link's closure, answers auth only when told to, and can answer every feed read with more. */
private class LifecycleNode(private val answerAuth: Boolean, private val endlessFeed: Boolean) : NodePeerAcceptor {
    val authReceived = CompletableDeferred<Unit>()
    val closure = CompletableDeferred<NodeLinkClosure>()

    override suspend fun serve(link: NodePeerLink) {
        try {
            coroutineScope {
                launch { link.calls.collect { lane -> launch { answerFeed(lane) } } }
                link.control.collect { frame ->
                    authReceived.complete(Unit)
                    if (answerAuth) link.sendControl(authAccepted(frame.requestId.orEmpty()))
                }
            }
        } finally {
            closure.complete(link.closure.value ?: NodeLinkClosure.LOST)
        }
    }

    private suspend fun answerFeed(lane: NodeCallLane) {
        val call = lane.incoming.first()
        if (!endlessFeed) return
        lane.send(
            NodeFrame(
                buildJsonObject {
                    put("type", "admin_rpc_response")
                    put("request_id", call.requestId.orEmpty())
                    put("success", true)
                    putJsonObject("result") {
                        put("mode", "replay")
                        putJsonObject("cursor") {
                            put("epoch", 1)
                            put("seq", 1)
                        }
                        put("base_seq", 0)
                        put("head_seq", 1)
                        put("has_more", true)
                        put("entries", JsonArray(emptyList()))
                    }
                },
            ),
        )
        lane.finishSending()
    }

    private fun authAccepted(requestId: String) = NodeFrame(
        buildJsonObject {
            put("type", "auth_response")
            put("request_id", requestId)
            put("success", true)
            put("capabilities", JsonArray(emptyList()))
        },
    )
}
