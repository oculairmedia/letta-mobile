package com.letta.mobile.data.node.carrier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A streamed `admin_rpc` over a session: every frame of a multi-frame call in order, and a cancel when the caller stops early. */
class NodeSessionAdminRpcStreamTest {
    @Test
    fun everyStreamedFrameArrivesInOrderThenTheCallEnds() = onNode { session, _ ->
        val frames = session.adminRpcStream(STREAMED, null).toList()
        assertEquals(
            List(STREAM_FRAMES) { "admin_rpc_stream" } + "admin_rpc_response",
            frames.map { it.type },
        )
        assertEquals((0 until STREAM_FRAMES).map { "$it" }, frames.dropLast(1).map { it.json["seq"]?.jsonPrimitive?.content })
    }

    @Test
    fun aCallerThatStopsEarlyCancelsTheCallOnTheNode() = onNode { session, node ->
        val first = session.adminRpcStream(ENDLESS, null).take(1).toList()
        assertEquals(listOf("admin_rpc_stream"), first.map { it.type })
        val cancel = node.cancelled.await()
        assertEquals("admin_rpc_cancel", cancel.type)
        assertEquals(node.endlessRequestId.await(), cancel.requestId, "the cancel names the call it ends")
        node.sendHalfFinished.await()
    }

    private fun onNode(body: suspend (NodeSession, StreamingNode) -> Unit) = runTest(timeout = TIMEOUT) {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val node = StreamingNode()
                val carrier = InProcessNodeCarrier("stream-node", NodePeerIdentity("node"), node, scope)
                val session = NodeSession.open(carrier, NodeOpenRequest(carrier.address, NodeCredential.CarrierIdentity), scope)
                withTimeout(TIMEOUT) { body(session, node) }
            } finally {
                scope.cancel()
            }
        }
    }

    private companion object {
        const val STREAMED = "test.streamed"
        const val ENDLESS = "test.endless"
        const val STREAM_FRAMES = 3
        val TIMEOUT = 20.seconds
    }

    /** Accepts auth; [STREAMED] writes [STREAM_FRAMES] stream frames and a response, [ENDLESS] streams until cancelled. */
    private class StreamingNode : NodePeerAcceptor {
        val endlessRequestId = CompletableDeferred<String?>()
        val cancelled = CompletableDeferred<NodeFrame>()
        val sendHalfFinished = CompletableDeferred<Unit>()

        override suspend fun serve(link: NodePeerLink) = coroutineScope {
            launch { link.calls.collect { lane -> launch { answer(lane) } } }
            link.control.collect { frame ->
                if (frame.type == "auth") link.sendControl(authAccepted(frame.requestId.orEmpty()))
            }
        }

        private suspend fun answer(lane: NodeCallLane) {
            val request = lane.incoming.first()
            val requestId = request.requestId.orEmpty()
            when (request.json["method"]?.jsonPrimitive?.content) {
                STREAMED -> {
                    repeat(STREAM_FRAMES) { lane.send(frame("admin_rpc_stream", requestId) { put("seq", it) }) }
                    lane.send(frame("admin_rpc_response", requestId) { put("success", true) })
                    lane.finishSending()
                }
                ENDLESS -> {
                    endlessRequestId.complete(requestId)
                    lane.send(frame("admin_rpc_stream", requestId) { put("seq", 0) })
                    // The lane's incoming already yielded the request; what follows is the shell's cancel, then its end.
                    val rest = lane.incoming.toList()
                    rest.firstOrNull()?.let(cancelled::complete)
                    sendHalfFinished.complete(Unit)
                }
            }
        }

        private fun frame(type: String, requestId: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
            NodeFrame(
                buildJsonObject {
                    put("type", type)
                    put("request_id", requestId)
                    extra()
                },
            )

        private fun authAccepted(requestId: String) = NodeFrame(
            buildJsonObject {
                put("type", "auth_response")
                put("request_id", requestId)
                put("success", true)
                put("capabilities", JsonArray(emptyList()))
            },
        )
    }
}
