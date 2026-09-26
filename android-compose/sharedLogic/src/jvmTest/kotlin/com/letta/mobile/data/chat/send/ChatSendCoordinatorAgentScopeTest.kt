package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.headless.HeadlessTimelineStore
import com.letta.mobile.data.transport.AgentScopeWire
import com.letta.mobile.data.transport.WireTurn
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ztuog: on device, after visiting three agents every delta reached every agent's
 * coordinator, which dropped foreign ones frame by frame (`ws.event.foreignAgentDropped` ×1000 in
 * two hours). Delivery is now keyed by agent over the one shared transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendCoordinatorAgentScopeTest {
    private val wire = AgentScopeWire()

    @Test
    fun twoCoordinatorsOnOneTransportEachReceiveOnlyTheirAgentsFrames() = runTest {
        val chatA = Chat(backgroundScope, AGENT_A)
        val chatB = Chat(backgroundScope, AGENT_B)
        runCurrent()

        wire.fullTurn(WireTurn(AGENT_A, "turn-a1"))
        wire.fullTurn(WireTurn(AGENT_B, "turn-b1"))
        wire.fullTurn(WireTurn(AGENT_A, "turn-a2"))
        runCurrent()

        assertEquals(listOf("msg-turn-a1", "msg-turn-a2"), chatA.ingestedIds())
        assertEquals(listOf("msg-turn-b1"), chatB.ingestedIds())
        assertEquals(setOf("conv-${AGENT_A.value}"), chatA.ingestedConversations())
        assertEquals(setOf("conv-${AGENT_B.value}"), chatB.ingestedConversations())
        assertEquals(0, foreignDrops(), "the defensive gate must not fire in normal use")
    }

    @Test
    fun deselectingAnAgentStopsDeliveryAndReselectingResumesIt() = runTest {
        val chatA = Chat(backgroundScope, AGENT_A)
        val chatB = Chat(backgroundScope, AGENT_B)
        runCurrent()

        chatA.coordinator.eventSubscription.select()
        wire.fullTurn(WireTurn(AGENT_B, "turn-b1"))
        runCurrent()
        assertEquals(emptyList(), chatB.ingestedIds(), "an unselected, idle agent's coordinator receives nothing")
        assertEquals(0, wire.bridge.agentScopes.liveSubscriberCount(AGENT_B))

        chatB.coordinator.eventSubscription.select()
        runCurrent()
        wire.fullTurn(WireTurn(AGENT_B, "turn-b2"))
        wire.fullTurn(WireTurn(AGENT_A, "turn-a1"))
        runCurrent()
        assertEquals(listOf("msg-turn-b2"), chatB.ingestedIds(), "re-selection re-attaches")
        assertEquals(emptyList(), chatA.ingestedIds(), "the agent it replaced detached")
        assertEquals(0, foreignDrops())
    }

    private fun foreignDrops(): Int = Telemetry.events.value.count {
        it.name == "ws.event.foreignAgentDropped" && it.attrs["boundAgentId"] in setOf(AGENT_A.value, AGENT_B.value)
    }

    private inner class Chat(scope: CoroutineScope, agent: AgentId) {
        private val timeline = RecordingTimelineWriter()
        val coordinator = ChatSendCoordinator(
            scope = scope,
            agentId = agent.value,
            activeConfig = { LettaConfig("iroh", LettaConfig.Mode.SELF_HOSTED, "iroh://node@host:4501", accessToken = null) },
            wsChatBridge = wire.bridge,
            timelineRepository = timeline,
            conversationRepository = inert(),
            ui = inert(),
            clearComposerAfterSend = {},
            activeConversationId = { "conv-${agent.value}" },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersion = { "test" },
            otidGenerator = { "otid-${agent.value}" },
        )

        fun ingestedIds(): List<String> = timeline.ingested.map { it.second.id }.distinct()

        fun ingestedConversations(): Set<String> = timeline.ingested.map { it.first }.toSet()
    }

    /** Records every live ingest; everything else goes to a real in-memory timeline. */
    private class RecordingTimelineWriter(
        store: HeadlessTimelineStore = HeadlessTimelineStore(),
    ) : TimelineExternalTransportWriter by store {
        val ingested = CopyOnWriteArrayList<Pair<String, LettaMessage>>()

        override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String) {
            ingested += conversationId to message
        }
    }

    private companion object {
        val AGENT_A = AgentId("scope-agent-a")
        val AGENT_B = AgentId("scope-agent-b")

        /** A collaborator these tests never exercise: every call answers false, zero or nothing. */
        inline fun <reified T : Any> inert(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                else -> Unit.takeIf { method.returnType == Void.TYPE || method.returnType.isAssignableFrom(Unit::class.java) }
            }
        } as T
    }
}
