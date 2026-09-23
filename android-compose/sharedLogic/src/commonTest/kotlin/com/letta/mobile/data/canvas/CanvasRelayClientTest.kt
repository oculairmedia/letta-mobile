package com.letta.mobile.data.canvas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A connection between one app and a [CanvasRelayHost], in memory. While [hold] is set, what the host
 * sends waits here, so a test can decide when the app reads it.
 */
internal class TestConnection private constructor(override val hostId: String) : CanvasRelayConnection {
    private lateinit var session: CanvasRelayHost.Session
    private val inbox = Channel<CanvasRelayMessage?>(Channel.UNLIMITED)
    private val held = mutableListOf<CanvasRelayMessage>()
    var hold = false
    var failSends = false
    val sent = mutableListOf<CanvasRelayMessage>()

    override suspend fun send(message: CanvasRelayMessage) {
        if (failSends) error("network unavailable")
        sent += message
        session.receive(message)
    }

    override suspend fun receive(): CanvasRelayMessage? = inbox.receive()

    private fun fromHost(message: CanvasRelayMessage) {
        if (hold) held += message else inbox.trySend(message)
    }

    /** Everything the host sent while [hold] was set, now readable. */
    fun release() {
        hold = false
        held.forEach { inbox.trySend(it) }
        held.clear()
    }

    /** The network drops: the app's side ends, and so does the host's. */
    suspend fun drop() {
        inbox.trySend(null)
        session.close()
    }

    companion object {
        suspend fun to(host: CanvasRelayHost, origin: String, hostId: String = "host-1"): TestConnection =
            TestConnection(hostId).also { connection -> connection.session = host.connect(origin) { connection.fromHost(it) } }
    }
}

/**
 * One app: its own document store, op log and durable delivery record, a [CanvasRelayClient], and
 * a session on the conversation's canvas - as Android and desktop each hold them.
 */
internal class TestApp(
    val name: String,
    val scope: CoroutineScope,
    val opLog: CanvasOpLog = InMemoryCanvasOpLog(),
    val delivery: InMemoryCanvasDeliveryStore = InMemoryCanvasDeliveryStore(),
    val documents: CanvasDocumentStore = InMemoryCanvasDocumentStore(),
) {
    val client = CanvasRelayClient(opLog, delivery, topicOf = { documents.relayTopicOf(it) })
    lateinit var session: CanvasSession
    var connection: TestConnection? = null
    private var running: Job? = null
    private var syncing: Job? = null

    suspend fun open(conversationId: String): TestApp = apply {
        session = CanvasSession.getOrCreateForConversation(
            documents,
            conversationId,
            CanvasConversationOptions(opLog = opLog, syncTransport = client),
        )
        syncing = session.startSync(scope)
    }

    suspend fun connect(host: CanvasRelayHost): TestConnection {
        client.expectHost(true)
        val next = TestConnection.to(host, name)
        connection = next
        running = scope.launch { client.run(next) }
        return next
    }

    suspend fun disconnect() {
        connection?.drop()
        running?.join()
        connection = null
    }

    val canvasId: CanvasId get() = session.canvasId
    suspend fun scene(): String = documents.get(canvasId)!!.sceneJson
    suspend fun digest(): String = CanvasSceneDigest.of(scene())
    fun health(): CanvasSyncHealth = client.health(canvasId).value
    suspend fun edit(op: CanvasOp) = session.applyLocal(op)
    suspend fun opIds(): List<String> = opLog.getOps(canvasId, 0L).map { it.opId }
}

private fun note(id: String, lamport: Long, text: String = id) =
    CanvasOp.AddElementOp(id, CanvasSession.LOCAL_USER_ACTOR_ID, lamport, "el-$id", """{"id":"el-$id","type":"Text","text":"$text"}""")

class CanvasOfflineQueueTest {

    @Test
    fun publishFailureKeepsDurableQueuedOp() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" })
        val phone = TestApp("phone", backgroundScope).open("conv-1")
        val connection = phone.connect(host)
        runCurrent()
        connection.failSends = true

        phone.edit(note("op-offline", 5))
        runCurrent()
        assertEquals(listOf("op-offline"), phone.delivery.queued(CanvasRelayProtocol.conversationTopic("conv-1")))
        assertTrue("op-offline" in phone.opIds(), "the edit is in the local log before anything else")

        // The app restarts: a new client over the same durable stores still has it queued...
        phone.disconnect()
        val restarted = TestApp("phone", backgroundScope, phone.opLog, InMemoryCanvasDeliveryStore(phone.delivery.snapshot), phone.documents)
            .open("conv-1")
        runCurrent()
        assertEquals(CanvasSyncHealth.LocalOnly("Not connected to an Iroh host; this canvas stays on this device"), restarted.health())
        restarted.client.expectHost(true)
        runCurrent()
        assertEquals(CanvasSyncHealth.OfflineQueued(1), restarted.health())

        // ...uploads it on reconnect, and only the host's ack clears it.
        restarted.connect(host)
        runCurrent()
        assertTrue(restarted.delivery.queued(CanvasRelayProtocol.conversationTopic("conv-1")).isEmpty())
        assertEquals(CanvasSyncHealth.Synced, restarted.health())
    }
}

class CanvasRelayConvergenceTest {

    @Test
    fun duplicateAndReplayAreIdempotent() = runTest {
        val store = InMemoryCanvasRelayStore()
        val host = CanvasRelayHost(store, hostId = { "host-1" })
        val phone = TestApp("phone", backgroundScope).open("conv-1")
        val desktop = TestApp("desktop", backgroundScope).open("conv-1")
        phone.connect(host)
        desktop.connect(host)
        runCurrent()

        val op = note("op-1", 3)
        phone.edit(op)
        phone.client.publish(phone.canvasId, op) // a duplicate publish
        runCurrent()
        // Reconnect: the app replays its queue and catches up again.
        phone.disconnect()
        phone.delivery.enqueue(CanvasRelayProtocol.conversationTopic("conv-1"), listOf("op-1"))
        phone.connect(host)
        desktop.disconnect()
        desktop.connect(host)
        runCurrent()

        assertEquals(1L, store.head(CanvasRelayProtocol.conversationTopic("conv-1")), "one op on the host")
        assertEquals(listOf("op-1"), desktop.opIds(), "one op, applied once, on the other app")
        assertEquals(phone.digest(), desktop.digest())
    }

    @Test
    fun concurrentJitterConvergesAcrossClients() = runTest {
        val seed = 20260923
        val random = Random(seed)
        val store = InMemoryCanvasRelayStore()
        val host = CanvasRelayHost(store, hostId = { "host-1" })
        val phone = TestApp("phone", backgroundScope).open("conv-jitter")
        val desktop = TestApp("desktop", backgroundScope).open("conv-jitter")
        phone.connect(host)
        desktop.connect(host)
        runCurrent()

        // 20 ops from each app, jittered, many on the same elements at equal Lamports.
        val sentBy = mutableMapOf<String, String>()
        val jobs = listOf(phone, desktop).flatMap { app ->
            (1..20).map { i ->
                val delayMs = random.nextLong(0, 40)
                val opId = "${app.name}-$i"
                val element = "shared-${i % 5}"
                sentBy[opId] = app.name
                launch {
                    delay(delayMs)
                    app.edit(
                        CanvasOp.UpdateElementOp(opId, CanvasSession.LOCAL_USER_ACTOR_ID, i.toLong(), element, """{"id":"$element","text":"$opId"}"""),
                    )
                }
            }
        }
        jobs.forEach { it.join() }
        runCurrent()

        val topic = CanvasRelayProtocol.conversationTopic("conv-jitter")
        assertEquals(40L, store.head(topic), "seed=$seed: every op once on the host")
        for (app in listOf(phone, desktop)) {
            val ids = app.opIds()
            assertEquals(0, ids.size - ids.toSet().size, "seed=$seed: duplicates on ${app.name}")
            assertEquals(emptySet(), sentBy.keys - ids.toSet(), "seed=$seed: missing on ${app.name}")
            assertEquals(CanvasSyncHealth.Synced, app.health(), "seed=$seed: ${app.name}")
            assertEquals(40L, app.client.relayView(app.canvasId).hostCursor, "seed=$seed: ${app.name} at the host cursor")
        }
        assertEquals(phone.digest(), desktop.digest(), "seed=$seed: both apps hold the same scene")
    }
}

class CanvasRelayGenerationTest {

    @Test
    fun staleAckCannotClearSuccessorQueue() = runTest {
        val topic = CanvasRelayProtocol.conversationTopic("conv-1")
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" })
        val phone = TestApp("phone", backgroundScope).open("conv-1")
        val first = phone.connect(host)
        runCurrent()

        // The first connection sends an op; the host's ack for it is on its way, not yet read.
        first.hold = true
        phone.edit(note("op-1", 1))
        runCurrent()
        assertEquals(listOf("op-1"), phone.delivery.queued(topic))

        // A successor connection takes over (a reconnect) and has not heard from the host yet.
        val second = TestConnection.to(host, "phone").apply { hold = true }
        backgroundScope.launch { phone.client.run(second) }
        runCurrent()

        // The first connection's ack arrives now: it belongs to a superseded generation.
        first.release()
        runCurrent()
        assertEquals(listOf("op-1"), phone.delivery.queued(topic), "a stale ack cleared the successor's queue")
        assertIs<CanvasSyncHealth.Connecting>(phone.health())

        // The successor's own upload and ack settle it.
        second.release()
        runCurrent()
        assertTrue(phone.delivery.queued(topic).isEmpty())
        assertEquals(CanvasSyncHealth.Synced, phone.health())
    }
}

class CanvasSyncBarrierTest {

    @Test
    fun syncedRequiresUploadAndCatchUpBarrier() = runTest {
        val topic = CanvasRelayProtocol.conversationTopic("conv-1")
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" })
        val desktop = TestApp("desktop", backgroundScope).open("conv-1")
        desktop.connect(host)
        runCurrent()
        (1..3).forEach { desktop.edit(note("desktop-$it", it.toLong())) }
        runCurrent()

        // The phone edited offline, then reconnects with catch-up and acks held back.
        val phone = TestApp("phone", backgroundScope).open("conv-1")
        phone.client.expectHost(true)
        phone.edit(note("phone-offline", 9))
        runCurrent()
        assertEquals(CanvasSyncHealth.OfflineQueued(1), phone.health())
        val connection = TestConnection.to(host, "phone").apply { hold = true }
        backgroundScope.launch { phone.client.run(connection) }
        runCurrent()
        assertEquals(CanvasSyncHealth.Connecting, phone.health(), "connected is not synced")

        connection.release()
        runCurrent()
        assertTrue(phone.delivery.queued(topic).isEmpty())
        assertEquals(CanvasSyncHealth.Synced, phone.health())
        assertEquals(desktop.digest(), phone.digest())
    }
}

class CanvasCrossClientE2ETest {

    @Test
    fun androidAndDesktopConvergeAfterDisconnect() = runTest {
        val topic = CanvasRelayProtocol.conversationTopic("conv-e2e")
        val store = InMemoryCanvasRelayStore()
        val host = CanvasRelayHost(store, hostId = { "host-1" })
        val android = TestApp("android", backgroundScope).open("conv-e2e")
        val desktop = TestApp("desktop", backgroundScope).open("conv-e2e")
        android.connect(host)
        desktop.connect(host)
        runCurrent()

        // Both ways, live.
        android.edit(note("from-android", 1))
        desktop.edit(note("from-desktop", 1))
        runCurrent()
        assertEquals(android.digest(), desktop.digest())

        // Android drops off; it says so and queues its edits while desktop keeps editing.
        android.disconnect()
        runCurrent()
        assertEquals(CanvasSyncHealth.OfflineQueued(0), android.health())
        android.edit(note("android-offline-1", 2))
        android.edit(note("android-offline-2", 3))
        desktop.edit(note("desktop-while-away", 2))
        runCurrent()
        assertEquals(CanvasSyncHealth.OfflineQueued(2), android.health())

        // Back online: everything converges, and both agree on canvas, cursor and scene.
        android.connect(host)
        runCurrent()
        val head = store.head(topic)
        assertEquals(5L, head)
        for (app in listOf(android, desktop)) {
            assertEquals(CanvasSyncHealth.Synced, app.health(), app.name)
            val view = app.client.relayView(app.canvasId)
            assertEquals(head, view.hostCursor, "${app.name} cursor")
            assertEquals("host-1", view.hostId)
        }
        assertEquals(android.client.relayView(android.canvasId).canonicalId, desktop.client.relayView(desktop.canvasId).canonicalId)
        assertEquals(android.digest(), desktop.digest())
    }
}
