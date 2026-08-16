package com.letta.mobile.data.chat.projection.fixture

import com.letta.mobile.data.a2ui.A2uiSurfaceManager
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
            ChatDisplayMode.entries.forEach { mode ->
                val actual = buildChatRenderModel(
                    messages = fixture.messages,
                    mode = mode,
                ).renderItems.toFixtureExpectation()

                assertEquals(fixture.expectedItems, actual, "${fixture.id}: $mode")
                assertEquals(
                    actual.map { it.key }.distinct(),
                    actual.map { it.key },
                    "${fixture.id}: $mode duplicate keys",
                )
            }
        }
    }

    @Test
    fun `tool fixture pins production timeline classification`() {
        val fixture = ToolApprovalFixture.fixture

        assertEquals(
            fixture.expectedToolStates,
            projectToolTimelineGroups(fixture.messages).flatMap { group -> group.calls.map { it.state } },
        )
    }

    @Test
    fun `a2ui envelope metadata links production surface to projected run and approval`() {
        val fixture = ToolApprovalFixture.fixture
        val event = assertNotNull(fixture.a2uiEvent)
        val manager = A2uiSurfaceManager()
        manager.apply(event)
        val surfaceId = event.messages.single().surfaceId
        val surface = assertNotNull(manager.surface(surfaceId))
        val rendered = buildChatRenderModel(fixture.messages, ChatDisplayMode.Interactive).renderItems

        assertEquals(event.conversationId, surface.conversationId)
        assertEquals(event.runId, surface.runId)
        assertEquals(event.requestId, surface.approvalRequestId)
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
