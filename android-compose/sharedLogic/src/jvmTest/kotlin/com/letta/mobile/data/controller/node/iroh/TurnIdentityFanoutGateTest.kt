package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.ConversationRowsSource
import com.letta.mobile.data.runtime.RowIdentity
import com.letta.mobile.data.runtime.StoredRowRef
import com.letta.mobile.data.runtime.TurnIdentityLedger
import com.letta.mobile.data.runtime.TurnIdentityStore
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-r1xkl: a tool turn has several stop_reason frames; none of them holds `message.list` back. */
class TurnIdentityFanoutGateTest {
    private class MemoryStore : TurnIdentityStore {
        override suspend fun load(conversationId: String): Map<StoredRowRef, RowIdentity> = emptyMap()
        override suspend fun append(conversationId: String, entries: Map<StoredRowRef, RowIdentity>) = Unit
    }

    private fun stopReason(seq: Int) = RuntimeEventPayload.RemoteStreamFrame(
        frameId = "f-$seq",
        messageId = null,
        messageType = null,
        body = buildJsonObject {
            put("type", "stream_delta")
            put("event_seq", seq)
            put("idempotency_key", "k-$seq")
            put(
                "delta",
                buildJsonObject {
                    put("message_type", "stop_reason")
                    put("stop_reason", "requires_approval")
                },
            )
        }.toString(),
    )

    @Test
    fun enrichIsNotGatedAfterTheFirstOfTwoStopReasonFrames() = runTest {
        val ledger = TurnIdentityLedger(MemoryStore(), ConversationRowsSource { _, _ -> emptyList() }) { 0L }
        val fanout = ConversationTurnFanout(
            conversationId = "conv-1",
            runtime = AppServerRuntimeScope("agent-1", "conv-1"),
            viewersFor = { emptySet() },
            initiatorViewer = null,
            identity = TurnIdentityBinding(ledger, "conv-1", "cmid-1"),
        )

        fanout.onDraft(stopReason(1))
        fanout.onDraft(stopReason(2))
        ledger.enrich("conv-1", emptyList())

        assertEquals(0L, currentTime)
    }
}
