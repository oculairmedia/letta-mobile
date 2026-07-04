package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminRpcStreamServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun cancelledAdminStreamDoesNotCancelAcceptLoopOrSiblingStreams() = runTest {
        val server = AdminRpcStreamServer(router = router(), authenticated = AtomicBoolean(true), firstFrameTimeoutMs = 1_000)
        val accepted = Channel<AdminRpcBiStream?>(Channel.UNLIMITED)
        val cancelled = FakeBiStream(FailingRecvStream(CancellationException("reset")))
        val healthy = FakeBiStream()

        val acceptJob = launch { server.serveAcceptLoop { accepted.receive() } }
        accepted.send(cancelled)
        accepted.send(healthy)
        healthy.sendFrame(adminRpc("ok", "health.check"))
        healthy.closeRecv()
        accepted.send(null)

        val response = healthy.awaitFrame()
        acceptJob.join()

        assertEquals("admin_rpc_response", response["type"]?.jsonPrimitive?.content)
        assertEquals("ok", response["request_id"]?.jsonPrimitive?.content)
        assertTrue(response["success"]?.jsonPrimitive?.boolean == true)
        assertFalse(acceptJob.isCancelled)
    }

    @Test
    fun idleAdminStreamWithoutFirstFrameTimesOutAndReleasesHandlerResources() = runTest {
        val server = AdminRpcStreamServer(router = router(), authenticated = AtomicBoolean(true), firstFrameTimeoutMs = 10)
        val idle = FakeBiStream()

        val job = launchTracked(server, idle)
        withTimeout(1_000) { job.join() }

        assertEquals(0, server.activeHandlerCount)
        assertTrue(idle.send.finished)
        val response = idle.writtenFrames().single().let { json.parseToJsonElement(it).jsonObject }
        assertTrue(response["success"]?.jsonPrimitive?.boolean == false)
        assertTrue(response["error"]?.jsonPrimitive?.content.orEmpty().contains("closed before request"))
    }

    @Test
    fun perConnectionConcurrencyBoundLimitsActiveHandlers() = runTest {
        val server = AdminRpcStreamServer(
            router = router(),
            authenticated = AtomicBoolean(true),
            maxActiveHandlers = 2,
            firstFrameTimeoutMs = 5_000,
        )
        val streams = List(6) { FakeBiStream() }
        val jobs = streams.map { launchTracked(server, it) }

        withTimeout(1_000) {
            while (server.activeHandlerCount < 2) delay(1)
        }
        assertEquals(2, server.activeHandlerCount)
        assertTrue(server.maxObservedHandlerCount <= 2)

        streams.forEach { it.closeRecv() }
        jobs.forEach { it.join() }
        assertEquals(0, server.activeHandlerCount)
        assertTrue(server.maxObservedHandlerCount <= 2)
    }

    @Test
    fun adminRpcImmediatelyAfterSuccessfulAuthSeesAuthorizedTrue() = runTest {
        // Auth happens on the control channel; admin_rpc streams observe the
        // shared AtomicBoolean. A stream dispatched right after auth flips the
        // flag must see authorized=true (cross-stream visibility).
        val authenticated = AtomicBoolean(false)
        val router = AdminRpcRouter().apply {
            register("auth.visible") { JsonPrimitive(authenticated.get()) }
        }
        val server = AdminRpcStreamServer(
            router = router,
            authenticated = authenticated,
            firstFrameTimeoutMs = 1_000,
        )

        val before = FakeBiStream()
        val beforeJob = launchTracked(server, before)
        before.sendFrame(adminRpc("rpc-before", "auth.visible"))
        val beforeResponse = before.awaitFrame()
        beforeJob.join()
        assertTrue(beforeResponse["success"]?.jsonPrimitive?.boolean == false)
        assertEquals("unauthorized", beforeResponse["error"]?.jsonPrimitive?.content)

        authenticated.set(true) // control channel completed auth

        val after = FakeBiStream()
        val afterJob = launchTracked(server, after)
        after.sendFrame(adminRpc("rpc-after", "auth.visible"))
        val rpcResponse = after.awaitFrame()
        afterJob.join()

        assertEquals("admin_rpc_response", rpcResponse["type"]?.jsonPrimitive?.content)
        assertTrue(rpcResponse["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(rpcResponse["result"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun unauthorizedAdminRpcBeforeAuthReturnsUnauthorizedEnvelope() = runTest {
        val server = AdminRpcStreamServer(
            router = router(),
            authenticated = AtomicBoolean(false),
            firstFrameTimeoutMs = 1_000,
        )
        val stream = FakeBiStream()

        val job = launchTracked(server, stream)
        stream.sendFrame(adminRpc("rpc-unauth", "health.check"))
        stream.closeRecv()

        val response = stream.awaitFrame()
        job.join()

        assertEquals("admin_rpc_response", response["type"]?.jsonPrimitive?.content)
        assertEquals("rpc-unauth", response["request_id"]?.jsonPrimitive?.content)
        assertTrue(response["success"]?.jsonPrimitive?.boolean == false)
        assertEquals("unauthorized", response["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun failedErrorResponseWriteDoesNotCloseUnrelatedStreams() = runTest {
        val server = AdminRpcStreamServer(router = router(), authenticated = AtomicBoolean(true), firstFrameTimeoutMs = 1_000)
        val accepted = Channel<AdminRpcBiStream?>(Channel.UNLIMITED)
        val failingErrorStream = FakeBiStream(send = FakeSendStream(failWrites = true))
        val healthy = FakeBiStream()

        val acceptJob = launch { server.serveAcceptLoop { accepted.receive() } }
        accepted.send(failingErrorStream)
        accepted.send(healthy)
        failingErrorStream.sendFrame(adminRpc("bad", "missing.method"))
        failingErrorStream.closeRecv()
        healthy.sendFrame(adminRpc("good", "health.check"))
        healthy.closeRecv()
        accepted.send(null)

        val response = healthy.awaitFrame()
        acceptJob.join()

        assertEquals("good", response["request_id"]?.jsonPrimitive?.content)
        assertTrue(response["success"]?.jsonPrimitive?.boolean == true)
        assertFalse(acceptJob.isCancelled)
    }

    @Test
    fun oversizedResponseIsChunkedWhenPeerAdvertisedFramePart() = runTest {
        val bigResult = "x".repeat(1_500)
        val router = AdminRpcRouter().apply { register("big.get") { JsonPrimitive(bigResult) } }
        val server = AdminRpcStreamServer(
            router = router,
            authenticated = AtomicBoolean(true),
            firstFrameTimeoutMs = 1_000,
            maxFrameBytes = 256,
            peerSupportsFrameParts = { true },
        )
        val stream = FakeBiStream()

        val job = launchTracked(server, stream)
        stream.sendFrame(adminRpc("rpc-big", "big.get"))
        stream.closeRecv()

        val response = stream.awaitFrame()
        job.join()

        assertEquals("admin_rpc_response", response["type"]?.jsonPrimitive?.content)
        assertEquals("rpc-big", response["request_id"]?.jsonPrimitive?.content)
        assertTrue(response["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(bigResult, response["result"]?.jsonPrimitive?.content)
        assertTrue(stream.send.writes.size > 1, "response should span multiple frame_part writes")
        stream.send.writes.forEach { assertTrue(it.size <= 256 + 13, "each part stays within maxFrameBytes + headers") }
    }

    @Test
    fun oversizedResponseWithoutFramePartCapabilityReturnsTypedErrorEnvelope() = runTest {
        val bigResult = "x".repeat(1_500)
        val router = AdminRpcRouter().apply { register("big.get") { JsonPrimitive(bigResult) } }
        val server = AdminRpcStreamServer(
            router = router,
            authenticated = AtomicBoolean(true),
            firstFrameTimeoutMs = 1_000,
            maxFrameBytes = 256,
            // peerSupportsFrameParts defaults to false (capability-off peer)
        )
        val stream = FakeBiStream()

        val job = launchTracked(server, stream)
        stream.sendFrame(adminRpc("rpc-big", "big.get"))
        stream.closeRecv()

        val response = stream.awaitFrame()
        job.join()

        assertEquals("admin_rpc_response", response["type"]?.jsonPrimitive?.content)
        assertEquals("rpc-big", response["request_id"]?.jsonPrimitive?.content)
        assertTrue(response["success"]?.jsonPrimitive?.boolean == false)
        val error = response["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("frame_part" in error, "error should name the missing capability: $error")
        assertTrue("too large" in error, "error should name the size rejection: $error")
        assertEquals(1, stream.send.writes.size, "capability-off peer must receive a single plain frame")
    }

    @Test
    fun chunkedRequestFromUnauthenticatedPeerIsBoundedByMaxFrameBytes() = runTest {
        // A peer holding just the ticket (no/invalid token) must not be able
        // to pin more than one frame's worth of reassembly memory per stream.
        val server = AdminRpcStreamServer(
            router = router(),
            authenticated = AtomicBoolean(false),
            firstFrameTimeoutMs = 1_000,
            maxFrameBytes = 256,
        )
        val stream = FakeBiStream()

        val job = launchTracked(server, stream)
        stream.sendChunkedFrame(adminRpcWithPadding("rpc-preauth", "health.check", padTo = 600), maxFrameBytes = 256)
        stream.closeRecv()

        val response = stream.awaitFrame()
        job.join()

        assertTrue(response["success"]?.jsonPrimitive?.boolean == false)
        val error = response["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("too large" in error, "pre-auth chunked request must be rejected as too large: $error")
    }

    @Test
    fun chunkedRequestFromAuthenticatedFramePartPeerIsAccepted() = runTest {
        val server = AdminRpcStreamServer(
            router = router(),
            authenticated = AtomicBoolean(true),
            firstFrameTimeoutMs = 1_000,
            maxFrameBytes = 256,
            peerSupportsFrameParts = { true },
        )
        val stream = FakeBiStream()

        val job = launchTracked(server, stream)
        stream.sendChunkedFrame(adminRpcWithPadding("rpc-chunked", "health.check", padTo = 600), maxFrameBytes = 256)
        stream.closeRecv()

        val response = stream.awaitFrame()
        job.join()

        assertEquals("admin_rpc_response", response["type"]?.jsonPrimitive?.content)
        assertEquals("rpc-chunked", response["request_id"]?.jsonPrimitive?.content)
        assertTrue(response["success"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun responseBeyondLogicalFrameCapReturnsTypedErrorEnvelopeToFramePartPeer() = runTest {
        // Regression: FrameTooLargeException from encodeFrameParts used to
        // escape writeResponse, so the stream finished silently and the
        // client burned its full RPC timeout. It must get the same typed
        // envelope the capability-off path sends.
        val bigResult = "x".repeat(1_500)
        val router = AdminRpcRouter().apply { register("big.get") { JsonPrimitive(bigResult) } }
        val server = AdminRpcStreamServer(
            router = router,
            authenticated = AtomicBoolean(true),
            firstFrameTimeoutMs = 1_000,
            maxFrameBytes = 256,
            peerSupportsFrameParts = { true },
            maxReassembledBytes = 512,
        )
        val stream = FakeBiStream()

        val job = launchTracked(server, stream)
        stream.sendFrame(adminRpc("rpc-huge", "big.get"))
        stream.closeRecv()

        val response = stream.awaitFrame()
        job.join()

        assertEquals("admin_rpc_response", response["type"]?.jsonPrimitive?.content)
        assertEquals("rpc-huge", response["request_id"]?.jsonPrimitive?.content)
        assertTrue(response["success"]?.jsonPrimitive?.boolean == false)
        val error = response["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("too large" in error, "error should name the size rejection: $error")
        assertEquals(1, stream.send.writes.size, "fallback must be a single plain frame")
    }

    private fun TestScope.launchTracked(server: AdminRpcStreamServer, stream: AdminRpcBiStream): Job =
        with(server) { launchHandler(stream) }

    private fun router(): AdminRpcRouter = AdminRpcRouter().apply {
        register("health.check") { JsonPrimitive("ok") }
    }

    private fun adminRpc(requestId: String, method: String): String =
        """{"type":"admin_rpc","request_id":"$requestId","method":"$method","params":{}}"""

    private fun adminRpcWithPadding(requestId: String, method: String, padTo: Int): String {
        val base = """{"type":"admin_rpc","request_id":"$requestId","method":"$method","params":{"pad":"PAD"}}"""
        return base.replace("PAD", "p".repeat((padTo - base.length + 3).coerceAtLeast(1)))
    }

    private suspend fun FakeBiStream.sendFrame(frame: String) {
        recv.chunks.send(IrohFrameCodec.encodeFrame(frame))
    }

    private suspend fun FakeBiStream.sendChunkedFrame(frame: String, maxFrameBytes: Int) {
        IrohFrameCodec.encodeFrameParts(frame, maxFrameBytes).forEach { recv.chunks.send(it) }
    }

    private suspend fun FakeBiStream.closeRecv() {
        recv.chunks.send(ByteArray(0))
    }

    private suspend fun FakeBiStream.awaitFrame(): JsonObject = withTimeout(1_000) {
        var result: JsonObject? = null
        while (result == null) {
            result = writtenFrames().firstOrNull()?.let { json.parseToJsonElement(it).jsonObject }
            if (result == null) delay(1)
        }
        result
    }

    private fun FakeBiStream.writtenFrames(): List<String> {
        val decoder = IrohFrameCodec.Decoder()
        return send.writes.flatMap { decoder.feed(it) }
    }

    private class FakeBiStream(
        private val recvStream: AdminRpcRecvStream = FakeRecvStream(),
        val send: FakeSendStream = FakeSendStream(),
    ) : AdminRpcBiStream {
        val recv: FakeRecvStream get() = recvStream as FakeRecvStream
        override fun recv(): AdminRpcRecvStream = recvStream
        override fun send(): AdminRpcSendStream = send
    }

    private class FakeRecvStream : AdminRpcRecvStream {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun read(maxBytes: UInt): ByteArray = chunks.receive()
    }

    private class FailingRecvStream(private val failure: Exception) : AdminRpcRecvStream {
        override suspend fun read(maxBytes: UInt): ByteArray = throw failure
    }

    private class FakeSendStream(private val failWrites: Boolean = false) : AdminRpcSendStream {
        val writes = mutableListOf<ByteArray>()
        var finished = false
            private set

        override suspend fun writeAll(bytes: ByteArray) {
            if (failWrites) throw IOException("write failed")
            writes += bytes
        }

        override suspend fun finish() {
            finished = true
        }
    }
}
