package com.letta.mobile.data.canvas

import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.23: an agent draws while the person is in chat, the board closed. The app is
 * still joined to the canvas's topic; the op must reach the board, not be skipped.
 */
class CanvasRelayClosedBoardTest {
    private val topic = CanvasRelayProtocol.conversationTopic(CONVERSATION)

    private class Host {
        val store = InMemoryCanvasRelayStore()
        val relay = CanvasRelayHost(store, hostId = { HOST })
        private val backend = HostCanvasBackend(relay, store, InMemoryHostCanvasDirectory())
        private val caller = HostCanvasCaller(AGENT, CONVERSATION)

        /** The agent adds a text element through the host's own canvas backend. */
        suspend fun agentDraws(elementId: String): String {
            val entry = assertIs<HostCanvasAccess.Granted>(backend.conversation(caller, CONVERSATION, claim = true)).entry
            val element = buildJsonObject {
                put("id", elementId)
                put("type", "Text")
                put("text", "drawn by the agent")
                put("textTopLeft", "10.0,10.0")
            }
            val op = CanvasOp.AddElementOp("x", AGENT, 1L, elementId, element.toString())
            assertIs<HostCanvasPublish.Published>(backend.publish(caller, entry, listOf(op)))
            return store.readAfter(entry.topic, 0L).last().op.opId
        }
    }

    /** A phone with its board open and connected, then closed: still joined, no session applying. */
    private suspend fun TestScope.phoneWithClosedBoard(host: Host, scope: CoroutineScope, closedBoard: Boolean): TestApp {
        val phone = TestApp("phone", scope, closedBoard = closedBoard).open(CONVERSATION, agentId = AGENT)
        phone.connect(host.relay)
        runCurrent()
        phone.close()
        runCurrent()
        return phone
    }

    private suspend fun TestApp.cursor(): Long? = delivery.cursor(HOST, CanvasRelayProtocol.conversationTopic(CONVERSATION))

    private suspend fun TestApp.stored(): String = documents.get(CanvasId.forConversation(CONVERSATION))!!.sceneJson

    @Test
    fun anOpArrivingWhileTheBoardIsClosedIsOnTheBoardWhenItOpens() = runTest {
        val host = Host()
        val phone = phoneWithClosedBoard(host, backgroundScope, closedBoard = true)

        val opId = host.agentDraws("agent-text")
        runCurrent()

        assertTrue("drawn by the agent" in phone.stored(), "written into the stored canvas: ${phone.stored()}")
        assertEquals(host.store.head(topic), phone.cursor(), "applied, so the cursor moves past it")
        val reopened = CanvasSession.open(phone.documents, CanvasId.forConversation(CONVERSATION), CanvasConversationOptions(opLog = phone.opLog))!!
        assertTrue("drawn by the agent" in reopened.sceneJsonOrEmpty(), "the board opened later shows it")
        assertEquals(1, phone.opIds().count { it == opId })
    }

    @Test
    fun afterARelaunchTheConversationCanvasIsKeptCurrentWithNoBoardOpened() = runTest {
        val host = Host()
        val before = phoneWithClosedBoard(host, backgroundScope, closedBoard = true)
        before.disconnect()
        val relaunched = TestApp("phone", backgroundScope, before.opLog, before.delivery, before.documents, closedBoard = true)
        relaunched.connect(host.relay)
        runCurrent()

        host.agentDraws("agent-text")
        runCurrent()

        assertTrue("drawn by the agent" in relaunched.stored(), "joined on connect and applied: ${relaunched.stored()}")
    }

    @Test
    fun theCursorDoesNotMovePastAnOpNothingApplied() = runTest {
        val host = Host()
        val phone = phoneWithClosedBoard(host, backgroundScope, closedBoard = false)
        val before = phone.cursor()
        Telemetry.clear()

        val opId = host.agentDraws("agent-text")
        runCurrent()

        assertEquals(before, phone.cursor(), "the op was not applied, so it must not be skipped")
        val warning = Telemetry.snapshot().single { it.name == "op.unapplied" }
        assertEquals(Telemetry.Level.WARN, warning.level)
        assertEquals(opId, warning.attrs["opId"])
        assertEquals(topic, warning.attrs["topic"])
    }

    @Test
    fun aBoardOpenedLaterReceivesTheReplayExactlyOnce() = runTest {
        val host = Host()
        val phone = phoneWithClosedBoard(host, backgroundScope, closedBoard = false)
        val opId = host.agentDraws("agent-text")
        runCurrent()

        phone.open(CONVERSATION, agentId = AGENT)
        runCurrent()

        assertTrue("drawn by the agent" in phone.scene(), "replayed by the join: ${phone.scene()}")
        assertEquals(1, phone.opIds().count { it == opId }, "applied once")
        assertEquals(host.store.head(topic), phone.cursor(), "and the gap is closed")
    }

    @Test
    fun aBoardOpeningJustAfterAClosedBoardApplyTakesTheStoredCanvas() = runTest {
        val host = Host()
        val phone = phoneWithClosedBoard(host, backgroundScope, closedBoard = true)
        val options = CanvasConversationOptions(opLog = phone.opLog, syncTransport = phone.client)
        val stale = CanvasSession.open(phone.documents, CanvasId.forConversation(CONVERSATION), options)!!

        host.agentDraws("agent-text")
        runCurrent()
        val staleSync = stale.startSync(backgroundScope)
        runCurrent()

        assertTrue("drawn by the agent" in stale.sceneJsonOrEmpty(), "a session loaded before the apply catches up: ${stale.sceneJsonOrEmpty()}")
        staleSync?.cancel()
    }

    private companion object {
        const val HOST = "host-1"
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
    }
}
