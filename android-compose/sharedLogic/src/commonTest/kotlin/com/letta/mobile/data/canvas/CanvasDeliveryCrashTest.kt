package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.CanvasOpId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.68: an op committed locally must reach the host even when the process dies
 * before [CanvasSyncTransport.publish] runs. The app has already joined the host once (so a
 * reconnect does NOT re-upload the whole local log); the crash is simulated by a transport that
 * throws at a chosen point, after which a fresh client over the same durable stores reconnects.
 */
class CanvasDeliveryCrashTest {

    private class ProcessDied(point: CrashPoint) : IllegalStateException("process died at $point")

    private enum class CrashPoint { BEFORE_RESERVE, BEFORE_PUBLISH }

    /** The relay client, except the process dies at [crashAt]. */
    private class CrashingTransport(private val inner: CanvasRelayClient, private val crashAt: CrashPoint) :
        CanvasSyncTransport by inner {
        override suspend fun reserveDelivery(canvasId: CanvasId, opIds: Collection<CanvasOpId>) {
            if (crashAt == CrashPoint.BEFORE_RESERVE) throw ProcessDied(crashAt)
            inner.reserveDelivery(canvasId, opIds)
        }

        override suspend fun publish(canvasId: CanvasId, op: CanvasOp) {
            if (crashAt == CrashPoint.BEFORE_PUBLISH) throw ProcessDied(crashAt)
            inner.publish(canvasId, op)
        }
    }

    private val topic = CanvasRelayProtocol.conversationTopic(ConversationId("conv-1"))

    private fun note(id: String, lamport: Long) = CanvasOp.AddElementOp(
        id, CanvasSession.LOCAL_USER_ACTOR_ID, lamport, "el-$id", """{"id":"el-$id","type":"Text","text":"$id"}""",
    )

    /** An app that has synced with [host] once, then lost its connection. */
    private suspend fun TestApp.syncedThenOffline(host: CanvasRelayHost, settle: () -> Unit): TestApp = apply {
        connect(host)
        settle()
        disconnect()
    }

    private suspend fun crashingSession(app: TestApp, crashAt: CrashPoint): CanvasSession =
        CanvasSession.getOrCreateForConversation(
            app.documents,
            ConversationId("conv-1"),
            CanvasConversationOptions(opLog = app.opLog, syncTransport = CrashingTransport(app.client, crashAt)),
        )

    private fun TestApp.restarted(): TestApp =
        TestApp(name, scope, opLog, InMemoryCanvasDeliveryStore(delivery.snapshot), documents)

    @Test
    fun anOpWrittenButNeverPublishedReachesTheHostAfterRestart() = runTest {
        val relayStore = InMemoryCanvasRelayStore()
        val host = CanvasRelayHost(relayStore, hostId = { "host-1" })
        val phone = TestApp("phone", backgroundScope).open("conv-1").syncedThenOffline(host) { runCurrent() }

        val crashing = crashingSession(phone, CrashPoint.BEFORE_PUBLISH)
        assertFailsWith<ProcessDied> { crashing.applyLocal(note("op-crash", 7)) }
        assertTrue(phone.scene().contains("el-op-crash"), "the crash point is after the document write")
        assertEquals(emptyList(), phone.opLog.unsettled(phone.canvasId), "the op is settled: journal recovery will not see it")

        val restarted = phone.restarted().open("conv-1")
        restarted.connect(host)
        runCurrent()

        assertTrue("op-crash" in relayStore.readAfter(topic, 0L).map { it.op.opId }, "the committed op never reached the host")
        assertTrue(restarted.delivery.queued(topic).isEmpty(), "the host's ack clears the queue")
    }

    @Test
    fun anOpLoggedButNeverQueuedIsQueuedByRecoveryAndReachesTheHost() = runTest {
        val relayStore = InMemoryCanvasRelayStore()
        val host = CanvasRelayHost(relayStore, hostId = { "host-1" })
        val phone = TestApp("phone", backgroundScope).open("conv-1").syncedThenOffline(host) { runCurrent() }

        val crashing = crashingSession(phone, CrashPoint.BEFORE_RESERVE)
        assertFailsWith<ProcessDied> { crashing.applyLocal(note("op-logged", 8)) }
        assertEquals(listOf("op-logged"), phone.opLog.unsettled(phone.canvasId).map { it.opId })
        assertTrue(phone.delivery.queued(topic).isEmpty(), "the crash point is before the queueing")

        val restarted = phone.restarted().open("conv-1")
        assertTrue(restarted.scene().contains("el-op-logged"), "recovery folds the logged op into the document")
        restarted.connect(host)
        runCurrent()

        assertTrue("op-logged" in relayStore.readAfter(topic, 0L).map { it.op.opId }, "the recovered op never reached the host")
        assertTrue(restarted.delivery.queued(topic).isEmpty(), "the host's ack clears the queue")
    }
}
