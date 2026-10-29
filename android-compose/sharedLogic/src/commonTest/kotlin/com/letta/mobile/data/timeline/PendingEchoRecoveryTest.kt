package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Recovery stays portable and retains failed rows across session recreation. */
class PendingEchoRecoveryTest {
    @Test fun exhaustedHistoryRetiresLostSentEchoAndKeepsOneFailedRowStableAcrossReopens() = runTest {
        val scope = TimelineScope("exhaustion-recovery", "conversation", "agent")
        val store = InMemoryTimelineStore()
        val pending = CanonicalPendingLocalStore(store)
        val stale = CanonicalPendingLocalStore.Record("stale-otid", "not sent", emptyList(), "2020-01-01T00:00:00Z")
        pending.save(scope, stale)
        pending.mark(scope, stale.otid, CanonicalPendingLocalStore.Delivery.Sent)
        val session = CanonicalTimelineSession(store, ExhaustedHistoryTransport, scope, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        assertEquals(listOf(CanonicalPendingLocalStore.Delivery.Sent), session.pending.value.map { it.delivery })
        assertEquals(TimelineEnginePageOutcome.Applied, session.loadOlder(selection))
        assertEquals(listOf(CanonicalPendingLocalStore.Delivery.Failed), session.pending.value.map { it.delivery })
        assertEquals(listOf(true), session.pending.value.map { it.toRenderItem().message.isSendFailed })
        assertIs<TimelineEngineOpen.Opened>(session.open())
        assertEquals(listOf("stale-otid"), session.pending.value.map { it.otid })
        assertEquals(listOf(CanonicalPendingLocalStore.Delivery.Failed), session.pending.value.map { it.delivery })
    }

}

private object ExhaustedHistoryTransport : TimelineTransport {
    override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
        TimelineRemotePageAdapter.fromMessages(request, emptyList(), hasMore = false)
    override suspend fun sendConversationMessage(conversationId: ConversationId, request: MessageCreateRequest): Flow<LettaMessage> =
        error("unexpected send")
    override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> = error("unexpected stream")
    override suspend fun listConversationMessages(
        conversationId: ConversationId, limit: Int?, after: String?, order: String?,
    ): List<LettaMessage> = error("legacy hydration")
    override suspend fun listAgentMessages(
        agentId: AgentId, limit: Int?, order: String?, conversationId: ConversationId?,
    ): List<LettaMessage> = error("legacy hydration")
}
