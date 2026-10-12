package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.runtime.TurnRuntimeKey
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import com.letta.mobile.runtime.PermissionSuggestion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-bzvro.40: the host pushes what its engine parked to the viewers of each conversation,
 * clears it with an empty snapshot, and replays it (empty included) to a viewer that joins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingApprovalsPublisherTest {
    private val key = TurnRuntimeKey(AGENT, CONVERSATION)
    private val parked = PendingApprovalDetails(
        approvalId = "perm-call-1",
        toolCallId = "call-1",
        toolName = "Bash",
        suggestions = listOf(PermissionSuggestion("rule-1", "Allow npm test")),
    )
    private val source = MutableStateFlow<Map<TurnRuntimeKey, List<PendingApprovalDetails>>>(emptyMap())
    private val decoder = Json { ignoreUnknownKeys = true }

    private fun decode(frame: String) =
        (decoder.decodeFromString(ServerFrameSerializer, frame) as ServerFrame.PendingApprovalsUpdated).decode()

    private class RecordingViewer(override val connectionId: String) : ViewerHandle {
        val frames = CopyOnWriteArrayList<String>()
        override suspend fun writeFrame(frame: String): Boolean = frames.add(frame)
    }

    @Test
    fun parkAndAnswerEachPublishOneSnapshotToTheConversation() = runTest {
        val published = CopyOnWriteArrayList<Pair<String, String>>()
        val publisher = PendingApprovalsPublisher(backgroundScope, source, clock = { Instant.EPOCH })
        publisher.attach { conversationId, frame -> published += conversationId to frame; 1 }
        runCurrent()

        source.value = mapOf(key to listOf(parked))
        runCurrent()
        source.value = emptyMap()
        runCurrent()

        assertEquals(listOf(CONVERSATION, CONVERSATION), published.map { it.first })
        assertEquals(listOf(parked), decode(published[0].second).approvals)
        assertEquals(emptyList(), decode(published[1].second).approvals, "the answer clears the card")
        publisher.close()
    }

    @Test
    fun anUnchangedConversationIsNotRepublished() = runTest {
        val published = CopyOnWriteArrayList<String>()
        val publisher = PendingApprovalsPublisher(backgroundScope, source, clock = { Instant.EPOCH })
        publisher.attach { conversationId, _ -> published += conversationId; 1 }
        val other = TurnRuntimeKey(AGENT, "conv-2")

        source.value = mapOf(key to listOf(parked))
        runCurrent()
        source.value = mapOf(key to listOf(parked), other to listOf(parked.copy(approvalId = "perm-2", toolCallId = "call-2")))
        runCurrent()

        assertEquals(listOf(CONVERSATION, "conv-2"), published)
        publisher.close()
    }

    @Test
    fun aJoiningViewerGetsTheCurrentListEvenWhenEmpty() = runTest {
        val publisher = PendingApprovalsPublisher(backgroundScope, source, clock = { Instant.EPOCH })
        val connections = ConnectionRegistry()
        connections.addViewerJoinedListener { conversationId, viewer -> publisher.replayTo(conversationId, viewer) }
        val busy = RecordingViewer("endpoint-1")
        val idle = RecordingViewer("endpoint-2")
        source.value = mapOf(key to listOf(parked))

        connections.register(CONVERSATION, connections.claim(busy))
        connections.register("conv-idle", connections.claim(idle))

        assertEquals(listOf(parked), decode(busy.frames.single()).approvals)
        val emptied = decode(idle.frames.single())
        assertEquals("conv-idle", emptied.conversationId)
        assertEquals(emptyList(), emptied.approvals, "a reconnecting client learns which kept cards are gone")
        publisher.close()
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
    }
}
