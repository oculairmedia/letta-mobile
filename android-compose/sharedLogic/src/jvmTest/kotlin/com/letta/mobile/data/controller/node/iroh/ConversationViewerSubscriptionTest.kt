package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
/**
 * eaczz.3 (S3-T): a connection subscribes to a conversation on BOTH signals —
 * runtime_start (initiator/sender) AND admin_rpc message.list (passive observer
 * hydrate) — with the Option A de-scope rule: a connection views only its
 * most-recently started/hydrated conversation.
 *
 * Two independent seams are exercised:
 *  - [ConversationViewerSubscription]: the de-scope bookkeeping the connection
 *    delegates to (runtime_start(convA) then message.list(convB) => now B, not A).
 *  - [AdminRpcStreamServer.onMethodObserved]: the wrinkle wiring — admin_rpc runs
 *    on its OWN BiStream, so the message.list->viewer association is made at the
 *    accept layer via this hook, not in the shared static handler.
 */
class ConversationViewerSubscriptionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class CapturingSink : ViewerFrameSink {
        override suspend fun writeAll(bytes: ByteArray) {}
    }

    private fun viewer(connectionId: String): IrohViewerHandle = IrohViewerHandle(
        connectionId = connectionId,
        sink = CapturingSink(),
        eventSeq = IrohEventSeqAllocator.newConnectionSeq(),
        streamWriteMutex = Mutex(),
        frameParts = { false },
        maxFrameBytes = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
    )

    @Test
    fun runtimeStartThenMessageListDeScopesToTheNewConversation() = runTest {
        val registry = ConnectionRegistry()
        val connViewer = viewer("conn-A")
        val subscription = ConversationViewerSubscription(registry, connViewer)

        // Signal 1: runtime_start(convA) — the initiator/sender path.
        subscription.subscribe("convA")
        assertEquals("convA", subscription.currentConversation)
        assertEquals(setOf<ViewerHandle>(connViewer), registry.viewersFor("convA"))
        assertTrue(registry.viewersFor("convB").isEmpty())

        // Signal 2: message.list(convB) — the observer hydrate path. De-scope
        // rule: convA is unregistered, convB registered.
        subscription.subscribe("convB")
        assertEquals("convB", subscription.currentConversation)
        assertTrue(registry.viewersFor("convA").isEmpty(), "convA must be de-scoped")
        assertEquals(setOf<ViewerHandle>(connViewer), registry.viewersFor("convB"))
        assertEquals(1, registry.conversationCount(), "connection views exactly one conversation")
    }

    @Test
    fun reSubscribingToSameConversationIsIdempotent() = runTest {
        val registry = ConnectionRegistry()
        val connViewer = viewer("conn-A")
        val subscription = ConversationViewerSubscription(registry, connViewer)

        subscription.subscribe("convA")
        subscription.subscribe("convA")
        subscription.subscribe("convA")

        assertEquals("convA", subscription.currentConversation)
        assertEquals(setOf<ViewerHandle>(connViewer), registry.viewersFor("convA"))
        assertEquals(1, registry.conversationCount())
    }

    @Test
    fun reSubscribeReAddsViewerAfterAnExternalUnregister() = runTest {
        // Regression: a passive viewer (mobile) that was evicted from the registry
        // by a fan-out dead-viewer drop or a redial teardown unregisterAll (both
        // key on NodeId) must be RE-ADDED by its next same-conversation signal
        // (message.list poll). Previously `subscribe` short-circuited on the
        // `viewed` cache and never re-registered, so the viewer stayed absent from
        // realtime fan-out forever ("desktop -> mobile not realtime").
        val registry = ConnectionRegistry()
        val v = viewer("conn-A")
        val subscription = ConversationViewerSubscription(registry, v)

        subscription.subscribe("convA")
        assertEquals(setOf<ViewerHandle>(v), registry.viewersFor("convA"))

        // Simulate the external eviction (fan-out drop / redial unregisterAll).
        registry.unregister("convA", v)
        assertTrue(registry.viewersFor("convA").isEmpty())

        // A repeat message.list signal for the SAME conversation must re-register.
        subscription.subscribe("convA")
        assertEquals(
            setOf<ViewerHandle>(v),
            registry.viewersFor("convA"),
            "a same-conversation signal must heal a dropped registration",
        )
    }

    @Test
    fun unrelatedConnectionIsNotRegistered() = runTest {
        val registry = ConnectionRegistry()
        val a = viewer("conn-A")
        val b = viewer("conn-B")
        val subA = ConversationViewerSubscription(registry, a)
        // conn-B never subscribes to anything.
        val subB = ConversationViewerSubscription(registry, b)

        subA.subscribe("convA")

        assertEquals(setOf<ViewerHandle>(a), registry.viewersFor("convA"))
        assertFalse(
            registry.viewersFor("convA").any { it.connectionId == "conn-B" },
            "an unrelated connection must not appear in the viewer set",
        )
        assertEquals(null, subB.currentConversation)
    }

    @Test
    fun twoViewersOfTheSameConversationCoexist() = runTest {
        val registry = ConnectionRegistry()
        val a = viewer("conn-A")
        val b = viewer("conn-B")
        ConversationViewerSubscription(registry, a).subscribe("convA")
        ConversationViewerSubscription(registry, b).subscribe("convA")

        assertEquals(setOf<ViewerHandle>(a, b), registry.viewersFor("convA"))
    }

    // ---- The admin_rpc message.list connection-association wrinkle ----

    @Test
    fun adminRpcMessageListInvokesTheObserverHookWithConversationId() = runTest {
        // Drives the ACTUAL AdminRpcStreamServer accept/dispatch path (admin_rpc
        // runs on its own BiStream) and asserts the per-connection observer hook
        // fires for message.list carrying conversation_id — the seam that lets
        // the connection subscribe itself as a viewer of the hydrated conversation.
        val registry = ConnectionRegistry()
        val connViewer = viewer("conn-A")
        val subscription = ConversationViewerSubscription(registry, connViewer)

        val router = AdminRpcRouter().apply {
            register("message.list") { kotlinx.serialization.json.JsonPrimitive("[]") }
        }
        val server = AdminRpcStreamServer(
            router = router,
            authenticated = AtomicBoolean(true),
            firstFrameTimeoutMs = 1_000,
            onMethodObserved = { method, params ->
                if (method == "message.list") {
                    val convId = params?.get("conversation_id")?.jsonPrimitive?.content
                    if (!convId.isNullOrEmpty()) subscription.subscribe(convId)
                }
            },
        )

        val stream = FakeBiStream()
        val job = with(server) { launch { handleStream(stream) } }
        stream.sendFrame(
            """{"type":"admin_rpc","request_id":"r1","method":"message.list","params":{"conversation_id":"convB"}}""",
        )
        val response = stream.awaitFrame()
        job.join()

        assertEquals("admin_rpc_response", response["type"]?.jsonPrimitive?.content)
        assertTrue(response["success"]?.jsonPrimitive?.content == "true")
        // The hook subscribed the connection as a viewer of convB.
        assertEquals("convB", subscription.currentConversation)
        assertEquals(setOf<ViewerHandle>(connViewer), registry.viewersFor("convB"))
    }

    @Test
    fun observerHookThatThrowsDoesNotBreakTheRpc() = runTest {
        val router = AdminRpcRouter().apply {
            register("message.list") { kotlinx.serialization.json.JsonPrimitive("[]") }
        }
        val server = AdminRpcStreamServer(
            router = router,
            authenticated = AtomicBoolean(true),
            firstFrameTimeoutMs = 1_000,
            onMethodObserved = { _, _ -> throw RuntimeException("boom") },
        )
        val stream = FakeBiStream()
        val job = with(server) { launch { handleStream(stream) } }
        stream.sendFrame(
            """{"type":"admin_rpc","request_id":"r1","method":"message.list","params":{"conversation_id":"convB"}}""",
        )
        val response = stream.awaitFrame()
        job.join()

        // A throwing hook must never break the request — dispatch still succeeds.
        assertEquals("admin_rpc_response", response["type"]?.jsonPrimitive?.content)
        assertTrue(response["success"]?.jsonPrimitive?.content == "true")
    }

    // ---- Fakes (mirror AdminRpcStreamServerTest patterns) ----

    private suspend fun FakeBiStream.sendFrame(frame: String) {
        recv.chunks.send(IrohFrameCodec.encodeFrame(frame))
    }

    private suspend fun FakeBiStream.awaitFrame() = withTimeout(1.seconds) {
        var result: kotlinx.serialization.json.JsonObject? = null
        while (result == null) {
            val decoder = IrohFrameCodec.Decoder()
            result = send.writes.flatMap { decoder.feed(it) }.firstOrNull()
                ?.let { json.parseToJsonElement(it).jsonObject }
            if (result == null) delay(1.milliseconds)
        }
        result
    }

    private class FakeBiStream(
        val recv: FakeRecvStream = FakeRecvStream(),
        val send: FakeSendStream = FakeSendStream(),
    ) : AdminRpcBiStream {
        override fun recv(): AdminRpcRecvStream = recv
        override fun send(): AdminRpcSendStream = send
    }

    private class FakeRecvStream : AdminRpcRecvStream {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun read(maxBytes: UInt): ByteArray = chunks.receive()
    }

    private class FakeSendStream : AdminRpcSendStream {
        val writes = mutableListOf<ByteArray>()
        override suspend fun writeAll(bytes: ByteArray) { writes += bytes }
        override suspend fun finish() {}
    }
}
