package com.letta.mobile.data.feed.client

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A previously persisted foreign delta must not reintroduce a child row during feed replay. */
class FeedRowScopeTest {
    @Test
    fun foreignIdentitiesAreDroppedButIdentityFreeParentRowsStillProject() {
        val projector = FeedRowProjector(AgentId("parent"), ConversationId("conversation"))
        listOf("agent_id", "session_id", "conversation_id").forEach { field ->
            assertTrue(projector.apply(entry(field)).isEmpty())
        }
        assertEquals(1, projector.apply(entry(null)).size)
    }

    private fun entry(identityField: String?) = FeedDelta(
        epoch = 1, seq = 1, kind = ConversationFeedWire.KIND_STREAM_DELTA, turn = "turn",
        payload = buildJsonObject {
            put("message_type", "assistant_message")
            put("id", "message-${identityField ?: "parent"}")
            put("content", "hello")
            put("turn_id", "turn")
            put("logical_message_id", "message-${identityField ?: "parent"}")
            identityField?.let { put(it, "child") }
        },
    )
}
