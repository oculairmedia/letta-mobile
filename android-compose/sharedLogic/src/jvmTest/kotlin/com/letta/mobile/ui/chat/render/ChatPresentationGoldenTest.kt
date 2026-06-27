package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.a2ui.A2uiMessage
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
 * Golden, end-to-end scenarios through the shared [ChatTimelinePresenter] — the
 * regression net for the chat-consolidation epic (letta-mobile-9ejia). Where
 * [ChatTimelinePresenterTest] covers the project→present *composition* and
 * [ChatTimelineProjectorTest] covers the four projection outcomes in isolation,
 * this pins the *observable presentation* for realistic conversation shapes that
 * both Android and desktop now feed through this one core:
 *
 *  - an A2UI-only assistant reply (the "thinking stuck" hazard): the visible
 *    message list ends with the user, yet [ChatPresentation.tailIsAssistant] must
 *    report true so a host can clear its thinking indicator;
 *  - a mixed text + A2UI reply: prose survives, the block is stripped to history;
 *  - a streaming reveal that settles: presence is "working" mid-stream and clears
 *    to idle once the reply lands.
 *
 * These are intentionally asserted on the neutral [ChatPresentation] (not platform
 * state) so the contract is locked once for every host.
 */
class ChatPresentationGoldenTest {
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

    private fun ChatTimelinePresenter.projectLive(events: List<TimelineEvent>, version: Long): TimelineProjection =
        project(
            Timeline(conversationId = conv, events = events.toPersistentList(), stablePrefixVersion = version),
            olderPrefixFor(conv),
            ChatUiState(),
        )

    /** Signals for a settled, non-streaming, server-mode turn (the common idle case). */
    private fun idleSignals(a2uiThinkingActive: Boolean = false) = ChatPresenceSignals(
        replyStreaming = false,
        clientModeStreamInFlight = false,
        a2uiThinkingActive = a2uiThinkingActive,
        duplicateInitialMessageInFlight = false,
    )

    private val a2uiCreateSurfaceBlock =
        "<a2ui-json>[{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"s1\",\"catalogId\":\"basic\"}}]</a2ui-json>"

    @Test
    fun a2uiOnlyReplyDropsVisibleMessageButStillReportsAssistantTail() {
        val presenter = ChatTimelinePresenter()
        val projection = presenter.projectLive(
            listOf(
                confirmed(TimelineMessageType.USER, "show me", "u1", 1.0),
                confirmed(TimelineMessageType.ASSISTANT, a2uiCreateSurfaceBlock, "a1", 2.0),
            ),
            version = 1,
        )
        val presentation = presenter.present(
            projection = projection,
            signals = idleSignals(),
            previousIsStreaming = true,
            previousIsAgentTyping = true,
        )

        // The a2ui-only reply strips to empty text, so it leaves the visible list —
        // which now ends with the *user* message.
        assertEquals(1, presentation.messages.size)
        assertEquals("user", presentation.messages.last().role)
        // …yet the timeline tail is the assistant event, so a host can clear its
        // "thinking" indicator. This is the exact fact the desktop thinking-clear
        // (and Android's a2uiResponseArrived) rely on.
        assertTrue(presentation.tailIsAssistant)
        // The block is decoded into A2UI history rather than rendered as text.
        assertEquals(1, presentation.a2uiMessages.size)
        assertTrue(presentation.a2uiMessages.first() is A2uiMessage.CreateSurface)
        // Idle, server-mode, tail-is-assistant → not working.
        assertFalse(presentation.isStreaming)
        assertFalse(presentation.isAgentTyping)
    }

    @Test
    fun mixedTextAndA2uiReplyKeepsProseAndExtractsBlock() {
        val presenter = ChatTimelinePresenter()
        val projection = presenter.projectLive(
            listOf(
                confirmed(TimelineMessageType.USER, "show me", "u1", 1.0),
                confirmed(TimelineMessageType.ASSISTANT, "Here you go\n$a2uiCreateSurfaceBlock", "a1", 2.0),
            ),
            version = 1,
        )
        val presentation = presenter.present(projection, idleSignals(), previousIsStreaming = false, previousIsAgentTyping = false)

        // Prose survives as a visible assistant message; the block is stripped out.
        assertEquals(2, presentation.messages.size)
        assertEquals("assistant", presentation.messages.last().role)
        assertEquals("Here you go", presentation.messages.last().content)
        assertTrue(presentation.tailIsAssistant)
        assertEquals(1, presentation.a2uiMessages.size)
    }

    @Test
    fun streamingRevealIsWorkingThenSettlesToIdle() {
        val presenter = ChatTimelinePresenter()

        // Mid-stream: a partial reply is landing while the reply stream is active.
        val streaming = presenter.present(
            projection = presenter.projectLive(
                listOf(
                    confirmed(TimelineMessageType.USER, "hi", "u1", 1.0),
                    confirmed(TimelineMessageType.ASSISTANT, "Hel", "a1", 2.0),
                ),
                version = 7,
            ),
            signals = ChatPresenceSignals(
                replyStreaming = true,
                clientModeStreamInFlight = false,
                a2uiThinkingActive = false,
                duplicateInitialMessageInFlight = false,
            ),
            previousIsStreaming = false,
            previousIsAgentTyping = false,
        )
        assertTrue(streaming.isStreaming)
        assertTrue(streaming.isAgentTyping)

        // Same tail grows then the stream ends → replace-tail, settles to idle.
        val settledProjection = presenter.projectLive(
            listOf(
                confirmed(TimelineMessageType.USER, "hi", "u1", 1.0),
                confirmed(TimelineMessageType.ASSISTANT, "Hello", "a1", 2.0),
            ),
            version = 7,
        )
        assertEquals(ChatMessageListChange.ReplaceTail, settledProjection.messageListChange)
        val settled = presenter.present(
            projection = settledProjection,
            signals = idleSignals(),
            previousIsStreaming = streaming.isStreaming,
            previousIsAgentTyping = streaming.isAgentTyping,
        )
        assertEquals("Hello", settled.messages.last().content)
        assertFalse(settled.isStreaming)
        assertFalse(settled.isAgentTyping)
    }
}
