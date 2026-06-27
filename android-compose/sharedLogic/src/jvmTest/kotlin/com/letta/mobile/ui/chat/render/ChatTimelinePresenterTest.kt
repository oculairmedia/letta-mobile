package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.timeline.MessageSource
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.parseTimelineInstant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Composition coverage for the shared presentation core: that [ChatTimelinePresenter.present]
 * wires the projection facts through [com.letta.mobile.data.chat.runtime.ChatStreamingPresencePolicy]
 * and carries the projected messages/list-change/A2UI into a neutral [ChatPresentation].
 * The projection and presence rules themselves are covered by their own tests.
 */
class ChatTimelinePresenterTest {
    private val conv = "conv-1"

    private fun confirmed(type: TimelineMessageType, content: String, serverId: String, position: Double) =
        TimelineEvent.Confirmed(
            position = position,
            otid = "server-$serverId",
            content = content,
            serverId = serverId,
            messageType = type,
            runId = null,
            stepId = null,
            date = parseTimelineInstant("2026-04-19T06:00:00Z"),
            toolCalls = persistentListOf(),
            approvalRequestId = null,
            approvalDecided = false,
            toolReturnContent = null,
            toolReturnIsError = false,
            toolReturnContentByCallId = persistentMapOf(),
            toolReturnIsErrorByCallId = persistentMapOf(),
            attachments = persistentListOf(),
            source = MessageSource.LETTA_SERVER,
        )

    private fun projectionOf(presenter: ChatTimelinePresenter): TimelineProjection {
        val timeline = Timeline(
            conversationId = conv,
            events = listOf(
                confirmed(TimelineMessageType.USER, "hi", "u1", 1.0),
                confirmed(TimelineMessageType.ASSISTANT, "hello", "a1", 2.0),
            ).toPersistentList(),
            stablePrefixVersion = 1,
        )
        return presenter.project(timeline, presenter.olderPrefixFor(conv), ChatUiState())
    }

    @Test
    fun presentDerivesStreamingAndCarriesProjection() {
        val presenter = ChatTimelinePresenter()
        val projection = projectionOf(presenter)
        val presentation = presenter.present(
            projection = projection,
            signals = ChatPresenceSignals(
                replyStreaming = true,
                clientModeStreamInFlight = false,
                a2uiThinkingActive = false,
                duplicateInitialMessageInFlight = false,
            ),
            previousIsStreaming = false,
            previousIsAgentTyping = false,
        )
        // replyStreaming → both flags true (the policy's reply-streaming branch).
        assertTrue(presentation.isStreaming)
        assertTrue(presentation.isAgentTyping)
        // Projection facts carried through.
        assertEquals(2, presentation.messages.size)
        assertEquals("hello", presentation.messages[1].content)
        assertEquals(ChatMessageListChange.Full, presentation.messageListChange)
        assertTrue(presentation.tailIsAssistant)
    }

    @Test
    fun presentHoldsPreviousFlagsDuringClientModeStream() {
        val presenter = ChatTimelinePresenter()
        val projection = projectionOf(presenter)
        val presentation = presenter.present(
            projection = projection,
            signals = ChatPresenceSignals(
                replyStreaming = true,
                clientModeStreamInFlight = true,
                a2uiThinkingActive = false,
                duplicateInitialMessageInFlight = false,
            ),
            previousIsStreaming = true,
            previousIsAgentTyping = false,
        )
        // client-mode in flight → the runtime owns the flags; previous held.
        assertTrue(presentation.isStreaming)
        assertFalse(presentation.isAgentTyping)
    }

    @Test
    fun zeroMessageOpenCloseCycle_limitation_heartbeatsOnlyAffectPresenceNotMessages() {
        val presenter = ChatTimelinePresenter()
        val projection = projectionOf(presenter)
        
        // Simulating a stream open heartbeat
        val openPresentation = presenter.present(
            projection = projection,
            signals = ChatPresenceSignals(
                replyStreaming = false,
                clientModeStreamInFlight = true,
                a2uiThinkingActive = false,
                duplicateInitialMessageInFlight = false,
            ),
            previousIsStreaming = true,
            previousIsAgentTyping = false,
        )
        assertTrue(openPresentation.isStreaming)
        assertFalse(openPresentation.isAgentTyping)
        assertEquals(2, openPresentation.messages.size)
        assertEquals(ChatMessageListChange.Full, openPresentation.messageListChange)

        // Simulating a stream close heartbeat
        val closePresentation = presenter.present(
            projection = projection,
            signals = ChatPresenceSignals(
                replyStreaming = false,
                clientModeStreamInFlight = false,
                a2uiThinkingActive = false,
                duplicateInitialMessageInFlight = false,
            ),
            previousIsStreaming = false,
            previousIsAgentTyping = false,
        )
        assertFalse(closePresentation.isStreaming)
        assertFalse(closePresentation.isAgentTyping)
        assertEquals(2, closePresentation.messages.size)
        assertEquals(ChatMessageListChange.Full, closePresentation.messageListChange)
    }
}
