package com.letta.mobile.data.chat.projection.fixture

import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.chat.projection.buildChatRenderModel
import com.letta.mobile.data.chat.projection.projectToolTimelineGroups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatProjectionParityFixturesTest {

    @Test
    fun `canonical fixtures project to the shared semantic contract`() {
        ChatProjectionParityFixtures.projectionCases.forEach { fixture ->
            val actual = buildChatRenderModel(
                messages = fixture.messages,
                mode = ChatDisplayMode.Interactive,
            ).renderItems.toFixtureExpectation()

            assertEquals(fixture.expectedItems, actual, fixture.id)
            assertEquals(actual.map { it.key }.distinct(), actual.map { it.key }, "${fixture.id}: duplicate keys")
            assertEquals(
                fixture.expectedToolStates,
                projectToolTimelineGroups(fixture.messages).flatMap { group -> group.calls.map { it.state } },
                "${fixture.id}: tool timeline classification",
            )
        }
    }

    @Test
    fun `a2ui fixture shares authoritative conversation and run identity with chat projection`() {
        val fixture = ChatProjectionParityFixtures.projectionCases.single { it.expectedA2uiLink != null }
        val expected = assertNotNull(fixture.expectedA2uiLink)
        val surface = assertNotNull(fixture.a2uiSurface)
        val rendered = buildChatRenderModel(fixture.messages, ChatDisplayMode.Interactive).renderItems

        assertEquals(expected.surfaceId, surface.surfaceId)
        assertEquals(expected.conversationId, surface.conversationId)
        assertEquals(expected.runId, surface.runId)
        assertEquals(expected.approvalRequestId, surface.approvalRequestId)
        assertTrue(
            rendered.any { it is ChatRenderItem.RunBlock && it.runId == surface.runId },
            "${fixture.id}: A2UI surface must link to a rendered run",
        )
        assertTrue(
            fixture.messages.any { it.approvalRequest?.requestId == surface.approvalRequestId },
            "${fixture.id}: A2UI surface must link to the rendered approval request",
        )
    }

    @Test
    fun `incremental fixture changes match production classification and full projection`() {
        val sequence = ChatProjectionParityFixtures.transitionSequence
        val cache = IncrementalChatRenderItemsCache()
        var previous = emptyList<com.letta.mobile.data.model.UiMessage>()

        sequence.frames.forEach { frame ->
            val actualChange = if (previous.isEmpty()) {
                ChatMessageListChange.Full
            } else {
                ChatMessageListChange.compute(previous, frame.messages)
            }
            assertEquals(frame.expectedChange, actualChange, sequence.id)

            val incremental = cache.renderItems(
                messages = frame.messages,
                mode = ChatDisplayMode.Interactive,
                change = actualChange,
            )
            val full = buildChatRenderModel(
                messages = frame.messages,
                mode = ChatDisplayMode.Interactive,
            ).renderItems
            assertEquals(full, incremental, "${sequence.id}: incremental projection drift")
            previous = frame.messages
        }

        assertTrue(cache.incrementalBuildCount > 0, "${sequence.id}: fixture never exercised incremental path")
    }
}
