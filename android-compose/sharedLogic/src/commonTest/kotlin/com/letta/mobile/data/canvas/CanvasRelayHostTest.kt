package com.letta.mobile.data.canvas

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** An app connected to a [CanvasRelayHost] in a test: everything the host sent it, in order. */
internal class RecordingApp(val origin: String) {
    val received = mutableListOf<CanvasRelayMessage>()
    lateinit var session: CanvasRelayHost.Session

    suspend fun connect(host: CanvasRelayHost): RecordingApp = apply { session = host.connect(origin) { received += it } }

    suspend fun send(message: CanvasRelayMessage) = session.receive(message)

    inline fun <reified T : CanvasRelayMessage> of(): List<T> = received.filterIsInstance<T>()
}

internal fun addOp(id: String, lamport: Long = 1L, actor: String = "local-user") =
    CanvasOp.AddElementOp(id, actor, lamport, "el-$id", """{"id":"el-$id"}""")

class CanvasRelayBindingTest {

    /** Any second, different binding for a topic is a bug: every joiner must get the first one. */
    private class SingleBindingStore(private val delegate: CanvasRelayStore = InMemoryCanvasRelayStore()) : CanvasRelayStore by delegate {
        private val first = mutableMapOf<String, CanvasId>()
        override suspend fun bind(topic: String, proposed: CanvasId): CanvasId {
            val bound = delegate.bind(topic, proposed)
            val earlier = first.getOrPut(topic) { bound }
            if (earlier != bound) error("second canvas binding")
            return bound
        }
    }

    @Test
    fun concurrentFirstJoinReturnsOneCanvasId() = runTest {
        val host = CanvasRelayHost(SingleBindingStore(), hostId = { "host-1" })
        val apps = (1..16).map { RecordingApp("device-$it").connect(host) }
        apps.mapIndexed { i, app ->
            async { app.send(CanvasRelayMessage.Join("conversation:shared", proposedCanvasId = "canvas-from-device-$i")) }
        }.awaitAll()
        val bound = apps.map { it.of<CanvasRelayMessage.Joined>().single().canvasId }.toSet()
        assertEquals(1, bound.size, "every app must open one canvas: $bound")
        assertTrue(apps.all { it.of<CanvasRelayMessage.Joined>().single().hostId == "host-1" })
    }
}

class CanvasRelayHostTest {

    @Test
    fun anOpIsAcknowledgedOnceDurableAndFannedOutToTheOthersOnly() = runTest {
        val store = InMemoryCanvasRelayStore()
        val host = CanvasRelayHost(store, hostId = { "host" })
        val phone = RecordingApp("phone").connect(host)
        val desktop = RecordingApp("desktop").connect(host)
        phone.send(CanvasRelayMessage.Join("conversation:c", "canvas-1"))
        desktop.send(CanvasRelayMessage.Join("conversation:c", "canvas-1"))

        phone.send(CanvasRelayMessage.Publish("conversation:c", addOp("op-1")))

        assertEquals(listOf(CanvasRelayMessage.Ack("conversation:c", "op-1", 1, duplicate = false)), phone.of<CanvasRelayMessage.Ack>())
        assertEquals(1L, store.head("conversation:c"), "acknowledged means already in the store")
        assertTrue(phone.of<CanvasRelayMessage.Op>().isEmpty(), "the sender does not get its own op back")
        val fanned = desktop.of<CanvasRelayMessage.Op>().single()
        assertEquals(1L, fanned.cursor)
        assertEquals("phone", fanned.origin, "origin is who the host received it from")
    }

    @Test
    fun aDuplicatePublishIsAcknowledgedAgainButNeverFannedOutTwice() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" })
        val phone = RecordingApp("phone").connect(host)
        val desktop = RecordingApp("desktop").connect(host)
        listOf(phone, desktop).forEach { it.send(CanvasRelayMessage.Join("conversation:c", "canvas-1")) }
        repeat(3) { phone.send(CanvasRelayMessage.Publish("conversation:c", addOp("op-1"))) }
        assertEquals(listOf(false, true, true), phone.of<CanvasRelayMessage.Ack>().map { it.duplicate })
        assertEquals(1, desktop.of<CanvasRelayMessage.Op>().size)
    }

    @Test
    fun joiningCatchesUpInCursorOrderThenSaysSo() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" })
        val writer = RecordingApp("writer").connect(host)
        writer.send(CanvasRelayMessage.Join("conversation:c", "canvas-1"))
        (1..5).forEach { writer.send(CanvasRelayMessage.Publish("conversation:c", addOp("op-$it", it.toLong()))) }

        val late = RecordingApp("late").connect(host)
        late.send(CanvasRelayMessage.Join("conversation:c", "canvas-other", afterCursor = 2))
        val kinds = late.received.map { it::class.simpleName }
        assertEquals(listOf("Joined", "Op", "Op", "Op", "CaughtUp"), kinds)
        assertEquals(listOf(3L, 4L, 5L), late.of<CanvasRelayMessage.Op>().map { it.cursor })
        assertEquals(5L, late.of<CanvasRelayMessage.CaughtUp>().single().cursor)
        assertEquals("canvas-1", late.of<CanvasRelayMessage.Joined>().single().canvasId, "the first binding stands")
    }

    @Test
    fun publishingToATopicNotJoinedIsRejected() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" })
        val app = RecordingApp("app").connect(host)
        app.send(CanvasRelayMessage.Publish("conversation:c", addOp("op-1")))
        assertEquals("not joined", app.of<CanvasRelayMessage.Rejected>().single().reason)
    }

    @Test
    fun anAppSendingHostMessagesIsRefusedAndDropped() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" })
        val app = RecordingApp("app").connect(host)
        app.send(CanvasRelayMessage.Ack("conversation:c", "op-1", 1))
        assertIs<CanvasRelayMessage.Refused>(app.received.single())
        assertTrue(app.session.closed)
    }
}

class CanvasRelayIsolationTest {

    @Test
    fun concurrentConversationsDoNotCross() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" })
        val a1 = RecordingApp("device-1").connect(host)
        val a2 = RecordingApp("device-2").connect(host)
        val b1 = RecordingApp("device-1").connect(host)
        val b2 = RecordingApp("device-2").connect(host)
        a1.send(CanvasRelayMessage.Join("conversation:a", "canvas-x"))
        a2.send(CanvasRelayMessage.Join("conversation:a", "canvas-x"))
        b1.send(CanvasRelayMessage.Join("conversation:b", "canvas-x"))
        b2.send(CanvasRelayMessage.Join("conversation:b", "canvas-x"))

        (1..8).map { i ->
            async {
                a1.send(CanvasRelayMessage.Publish("conversation:a", addOp("a-$i", i.toLong())))
                b1.send(CanvasRelayMessage.Publish("conversation:b", addOp("b-$i", i.toLong())))
                a1.send(CanvasRelayMessage.Presence("conversation:a", CanvasPresence("cursor", "A", "#f00", 1f, 1f)))
            }
        }.awaitAll()

        assertEquals((1..8).map { "a-$it" }.toSet(), a2.of<CanvasRelayMessage.Op>().map { it.op.opId }.toSet())
        assertEquals((1..8).map { "b-$it" }.toSet(), b2.of<CanvasRelayMessage.Op>().map { it.op.opId }.toSet())
        assertTrue(a2.received.none { it is CanvasRelayMessage.Op && it.topic != "conversation:a" })
        assertTrue(b2.of<CanvasRelayMessage.PresenceRelayed>().isEmpty(), "presence on a never reaches b")
        assertTrue(b2.received.none { it is CanvasRelayMessage.Op && it.op.opId.startsWith("a-") })
    }
}

class CanvasRelayPresenceTest {

    @Test
    fun disconnectExpiresPresenceAndRejectsSpoofedPeer() = runTest {
        var now = 1_000L
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" }, clock = { now }, presenceTtlMs = 10_000L)
        val phone = RecordingApp("phone-node").connect(host)
        val desktop = RecordingApp("desktop-node").connect(host)
        listOf(phone, desktop).forEach { it.send(CanvasRelayMessage.Join("conversation:c", "canvas-1")) }

        // The phone claims to be the desktop's cursor; the host scopes it to the phone.
        phone.send(CanvasRelayMessage.Presence("conversation:c", CanvasPresence("desktop-node/cursor", "Desktop", "#00f", 3f, 4f)))
        val seen = desktop.of<CanvasRelayMessage.PresenceRelayed>().single().presence
        assertEquals("phone-node/desktop-node/cursor", seen.peerId)
        assertFalse(seen.peerId.startsWith("desktop-node/"), "no app can speak as another device")

        // Disconnecting takes the phone's cursors away for everyone.
        phone.session.close()
        assertEquals(listOf("phone-node/desktop-node/cursor"), desktop.of<CanvasRelayMessage.PresenceGone>().map { it.peerId })

        // And a cursor nobody refreshes expires.
        desktop.send(CanvasRelayMessage.Presence("conversation:c", CanvasPresence("cursor", "Desktop", "#00f", 1f, 1f)))
        val watcher = RecordingApp("watcher").connect(host)
        watcher.send(CanvasRelayMessage.Join("conversation:c", "canvas-1"))
        now += 10_001L
        host.reapPresence()
        assertEquals(listOf("desktop-node/cursor"), watcher.of<CanvasRelayMessage.PresenceGone>().map { it.peerId })
    }
}
