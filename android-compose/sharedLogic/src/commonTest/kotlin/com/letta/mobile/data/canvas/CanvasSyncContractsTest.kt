package com.letta.mobile.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class CanvasSyncHealthTest {

    @Test
    fun loopbackCanNeverReportSynced() {
        val loopback = LoopbackCanvasSyncTransport()
        assertIs<CanvasSyncHealth.LocalOnly>(loopback.health(CanvasId("c")).value)
        for (claim in listOf(CanvasSyncHealth.Synced, CanvasSyncHealth.Connecting, CanvasSyncHealth.OfflineQueued(0))) {
            assertFailsWith<IllegalStateException>("loopback must refuse $claim") { loopback.localHealth.report(claim) }
        }
        assertIs<CanvasSyncHealth.LocalOnly>(loopback.health(CanvasId("c")).value)
    }

    @Test
    fun aTransportThatSaysNothingIsLocalOnly() {
        val bare = object : CanvasSyncTransport {
            override suspend fun publish(canvasId: CanvasId, op: CanvasOp) = Unit
            override fun subscribe(canvasId: CanvasId) = kotlinx.coroutines.flow.emptyFlow<CanvasOp>()
        }
        assertIs<CanvasSyncHealth.LocalOnly>(bare.health(CanvasId("c")).value)
    }
}

class CanvasRelayProtocolTest {

    @Test
    fun everyMessageRoundTrips() {
        val op = CanvasOp.AddElementOp("op-1", "user", 3L, "e1", """{"id":"e1"}""")
        val presence = CanvasPresence("peer", "Desktop", "#ff0000", 1f, 2f)
        val messages = listOf(
            CanvasRelayMessage.Join("conversation:c", "canvas-1", afterCursor = 7),
            CanvasRelayMessage.Publish("conversation:c", op),
            CanvasRelayMessage.Leave("conversation:c"),
            CanvasRelayMessage.Presence("conversation:c", presence),
            CanvasRelayMessage.Joined("conversation:c", "canvas-1", "host", 9),
            CanvasRelayMessage.Op("conversation:c", 9, "peer-a", op),
            CanvasRelayMessage.CaughtUp("conversation:c", 9),
            CanvasRelayMessage.Ack("conversation:c", "op-1", 9, duplicate = true),
            CanvasRelayMessage.Rejected("conversation:c", "op-1", "no"),
            CanvasRelayMessage.PresenceRelayed("conversation:c", presence),
            CanvasRelayMessage.PresenceGone("conversation:c", "peer"),
            CanvasRelayMessage.Refused("unsupported"),
        )
        for (message in messages) {
            val decoded = assertIs<CanvasRelayDecoded.Message>(CanvasRelayProtocol.decode(CanvasRelayProtocol.encode(message)))
            assertEquals(message, decoded.message)
        }
    }

    @Test
    fun anUnknownVersionIsRefusedNotGuessed() {
        val future = CanvasRelayProtocol.encode(CanvasRelayMessage.Leave("t")).replace("\"v\":1", "\"v\":2")
        assertEquals(CanvasRelayDecoded.UnsupportedVersion(2), CanvasRelayProtocol.decode(future))
        assertIs<CanvasRelayDecoded.Malformed>(CanvasRelayProtocol.decode("""{"message":{"type":"leave","topic":"t"}}"""))
        assertIs<CanvasRelayDecoded.Malformed>(CanvasRelayProtocol.decode("""{"v":1,"message":{"type":"teleport"}}"""))
    }
}

class CanvasOpOrderTest {

    @Test
    fun twoDevicesWritingAtAnEqualLamportConvergeWhateverTheArrivalOrder() {
        // Every device edits as the same user actor with its own Lamport clock.
        val fromPhone = CanvasOp.UpdateElementOp("op-phone", "local-user", 5L, "e1", """{"id":"e1","text":"phone"}""")
        val fromDesktop = CanvasOp.UpdateElementOp("op-desktop", "local-user", 5L, "e1", """{"id":"e1","text":"desktop"}""")
        val base = CanvasOpProjector.project("", listOf(CanvasOp.AddElementOp("op-0", "local-user", 1L, "e1", """{"id":"e1"}""")))
        val phoneFirst = CanvasOpProjector.project(base, listOf(fromPhone, fromDesktop))
        val desktopFirst = CanvasOpProjector.project(base, listOf(fromDesktop, fromPhone))
        assertEquals(phoneFirst, desktopFirst)
        assertEquals(CanvasSceneDigest.of(phoneFirst), CanvasSceneDigest.of(desktopFirst))
    }

    @Test
    fun theOrderIsLamportThenActorThenOpId() {
        val ops = listOf(
            CanvasOp.RemoveElementOp("b", "z", 2, "e"),
            CanvasOp.RemoveElementOp("a", "z", 2, "e"),
            CanvasOp.RemoveElementOp("c", "a", 2, "e"),
            CanvasOp.RemoveElementOp("d", "z", 1, "e"),
        )
        assertEquals(listOf("d", "c", "a", "b"), ops.sortedWith(CanvasOpOrder).map { it.opId })
    }

    @Test
    fun theDigestTellsScenesApart() {
        assertEquals(CanvasSceneDigest.of("{}"), CanvasSceneDigest.of("{}"))
        assertNotEquals(CanvasSceneDigest.of("{}"), CanvasSceneDigest.of("{ }"))
        assertEquals(16, CanvasSceneDigest.of("x").length)
    }
}
