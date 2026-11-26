package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.stream.AdminRpcStreamOutcome
import com.letta.mobile.data.controller.node.iroh.stream.AdminRpcStreamedCall
import com.letta.mobile.data.controller.node.iroh.stream.AdminRpcStreamedHandler
import com.letta.mobile.data.controller.node.link.NodeCallExchange
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The bounds the admin server applies to every call lane, whatever carrier it arrived on, driven through a fake call
 * lane (no bytes, no network): an oversized response becomes the `response_too_large` envelope for a peer without
 * `frame_part`, a streamed frame never grows past one frame, and a framing violation on one lane is answered with an
 * error envelope on that lane while the next lane is still served.
 */
class AdminRpcCallLaneBoundsTest {
    @Test
    fun anOversizedResponseBecomesTheTooLargeEnvelopeForAPeerWithoutFrameParts() = runTest {
        val server = server(router { register("big.read") { JsonPrimitive("x".repeat(LIMIT * 2)) } })
        val lane = FakeLane(request("big-1", "big.read"))
        server.handleExchange(lane)
        val response = lane.only()
        assertEquals("big-1", response["request_id"]?.jsonPrimitive?.content)
        assertEquals(false, response["success"]?.jsonPrimitive?.boolean)
        assertTrue("too large" in response["error"]?.jsonPrimitive?.content.orEmpty(), "$response")
        assertTrue(lane.finished, "the lane is finished")
    }

    @Test
    fun anOversizedResponseGoesWholeToAPeerWithFrameParts() = runTest {
        val server = server(router { register("big.read") { JsonPrimitive("x".repeat(LIMIT * 2)) } }, frameParts = true)
        val lane = FakeLane(request("big-2", "big.read"))
        server.handleExchange(lane)
        assertEquals(true, lane.only()["success"]?.jsonPrimitive?.boolean, "the carrier splits it, the server does not cut it")
    }

    @Test
    fun aStreamedFrameNeverGrowsPastOneFrame() = runTest {
        val router = router {
            registerStreamed(OversizedStream, elsewhere = { _, _ -> JsonPrimitive("refused") })
        }
        val lane = FakeLane(request("s-1", OversizedStream.method))
        server(router, frameParts = true).handleExchange(lane)
        lane.written.forEach { frame ->
            assertTrue(frame.encodeToByteArray().size <= LIMIT, "a streamed lane frame of ${frame.length} bytes")
        }
        assertTrue(lane.finished)
    }

    @Test
    fun aViolationOnOneLaneIsAnsweredThereAndTheNextLaneIsServed() = runTest {
        val server = server(router { register("health.check") { JsonPrimitive("ok") } })
        val broken = FakeLane(failure = IrohFrameCodec.ProtocolException("bad frame_part header"))
        server.handleExchange(broken)
        assertEquals(false, broken.only()["success"]?.jsonPrimitive?.boolean, "the violation is answered on its own lane")
        assertTrue(broken.finished)

        val next = FakeLane(request("h-1", "health.check"))
        server.handleExchange(next)
        assertEquals(true, next.only()["success"]?.jsonPrimitive?.boolean, "the connection still serves")
    }

    private fun server(router: AdminRpcRouter, frameParts: Boolean = false) = AdminRpcStreamServer(
        router = router,
        authenticated = AtomicBoolean(true),
        limits = AdminRpcStreamLimits(maxFrameBytes = LIMIT, maxReassembledBytes = LIMIT * 8),
        peerSupportsFrameParts = { frameParts },
    )

    private fun router(setup: AdminRpcRouter.() -> Unit) = AdminRpcRouter().apply(setup)

    /** A streamed method whose one event is larger than a frame. */
    private object OversizedStream : AdminRpcStreamedHandler {
        override val method = "voice.oversized"
        override val cancelledError = "cancelled"

        override suspend fun serve(call: AdminRpcStreamedCall): AdminRpcStreamOutcome {
            call.emit(buildJsonObject { put("blob", "x".repeat(LIMIT * 2)) })
            return AdminRpcStreamOutcome.Success(JsonPrimitive("done"))
        }
    }

    /** One call lane: the request (or a failure) in, every frame the server writes recorded. */
    private class FakeLane(request: String? = null, private val failure: Exception? = null) : NodeCallExchange {
        private val incoming = Channel<String>(Channel.UNLIMITED).apply {
            request?.let { trySend(it) }
            close()
        }
        val written = mutableListOf<String>()
        var finished = false

        override suspend fun next(): String? {
            failure?.let { throw it }
            return incoming.receiveCatching().getOrNull()
        }

        override suspend fun write(frame: String) {
            written += frame
        }

        override suspend fun respond(response: String) {
            written += response
        }

        override suspend fun finish() {
            finished = true
        }

        fun only(): JsonObject = Json.parseToJsonElement(written.single()).jsonObject
    }

    private companion object {
        const val LIMIT = 4_096

        fun request(requestId: String, method: String) = buildJsonObject {
            put("type", "admin_rpc")
            put("request_id", requestId)
            put("method", method)
        }.toString()
    }
}
