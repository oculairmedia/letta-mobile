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
        return presenter.project(timeline, presenter.olderPrefixFor(conv), ChatUiState(), isActiveRunStreaming = false)
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

    @Test
    fun presentWiresProjectionRunActiveFromProjectionNotFromServerLocalPending() {
        // letta-mobile-dir4k wiring regression: the mask in
        // ChatStreamingPresencePolicy derives `effectiveTurnInFlight =
        // turnInFlight && projectionRunActive`. PR #1117 collapsed this mask
        // to a no-op by reading `projection.anyLettaServerLocalPending` for
        // BOTH `anyServerLocalPending` AND `projectionRunActive` in
        // ChatTimelinePresenter.kt — same source for both inputs means the
        // mask was always equivalent to its left operand.
        //
        // The fix-forward reads `projectionRunActive` from a new
        // `TimelineProjection.anyRunActive` field, which the projection
        // computes from `projectRunActivity` over the just-projected live
        // messages. Today the projector computes these two booleans to
        // the same value (a run is active iff it has a Local+SENDING
        // message, which is the only thing that makes
        // `anyLettaServerLocalPending` true), so a behavioural test against
        // a real projection cannot distinguish the two wirings — and PR
        // #1117's existing unit tests passed against the broken code,
        // which is exactly how the bug shipped.
        //
        // To prove the wire is correct, this test constructs a
        // TimelineProjection where the two fields are FORCED to disagree
        // (`anyLettaServerLocalPending = false`, `anyRunActive = true`).
        // Today's projector cannot produce this shape from real inputs
        // (a run is active iff it has a Local+SENDING message, which is
        // the only thing that makes `anyLettaServerLocalPending` true —
        // so the two booleans are conflated by construction), but the
        // wire at the call site MUST split them so the mask can defend
        // the user-visible "stuck Thinking" symptom once the two facts
        // are allowed to mean different things (the entire reason for
        // splitting them in the first place).
        //
        // With turnInFlight=true and the divergent fixture, the
        // discriminator is on `isStreaming`:
        //   * Bug-state (`projectionRunActive = projection.anyLettaServerLocalPending`):
        //     mask = true && false = false → effectiveTurnInFlight=false
        //     → isStreaming falls through to anyServerLocalPending(false)
        //     = false. PRESENCE CLEARS, even though projection says a run
        //     is active. The transport's stale `turnInFlight=true` is
        //     suppressed, AND the projection's "run active" is also
        //     discarded — net effect: presence clears when it should
        //     stay.
        //   * Fix-state (`projectionRunActive = projection.anyRunActive`):
        //     mask = true && true = true → effectiveTurnInFlight=true
        //     → isStreaming = true. PRESENCE HELD, the projection's
        //     "run active" decision wins.
        //
        // This is the regression test that catches a re-introduction of
        // the structural no-op: if anyone reverts the presenter's wire
        // back to `projectionRunActive = projection.anyLettaServerLocalPending`,
        // the divergent fixture's `isStreaming` flips to false and the
        // assert below fails. Today the test exercises a shape the real
        // projector cannot produce, which is the honest limitation;
        // the durable defence is the wire itself, and the test is the
        // tripwire when it gets rewired wrong.
        val presenter = ChatTimelinePresenter()
        val projection = projectionOf(presenter)
        // Sanity: a server-confirmed-only projection has both fields false.
        // If either field ever changes meaning, this test fails LOUDLY
        // rather than passing against the wrong shape.
        assertFalse(
            projection.anyLettaServerLocalPending,
            "fixture invariant: server-confirmed-only timeline must have no Local-pending",
        )
        assertFalse(
            projection.anyRunActive,
            "fixture invariant: server-confirmed-only timeline must have no active run",
        )
        // Construct a divergent fixture: anyLettaServerLocalPending=false
        // simulates the transport-side count having been settled (no
        // Local pending entry), while anyRunActive=true simulates the
        // projection-layer fact that a run is still unresolved (e.g. its
        // final Confirmed never landed but the run's `isActive` has not
        // flipped to false). This is the shape the wire MUST split —
        // and today the projection's `projectRunActivity` cannot produce
        // it, but the wire at the call site must be correct for when
        // the run-state side of the projection learns to track
        // turn-state independently.
        val divergentProjection = projection.copy(
            anyLettaServerLocalPending = false,
            anyRunActive = true,
            tailIsAssistant = true,
        )

        val presentation = presenter.present(
            projection = divergentProjection,
            signals = ChatPresenceSignals(
                replyStreaming = false,
                clientModeStreamInFlight = false,
                a2uiThinkingActive = false,
                duplicateInitialMessageInFlight = false,
                turnInFlight = true,
            ),
            previousIsStreaming = false,
            previousIsAgentTyping = false,
        )
        // isStreaming: with the divergent fixture
        // (anyServerLocalPending=false, anyRunActive=true,
        // tailIsAssistant=true) and turnInFlight=true, the two wirings
        // produce OPPOSITE results:
        //   bug-state:  effectiveTurnInFlight = true && false = false
        //               → isStreaming = anyServerLocalPending(false)
        //               = false. PRESENCE CLEARS, projection's "run
        //               active" lost.
        //   fix-state:  effectiveTurnInFlight = true && true = true
        //               → isStreaming = true. PRESENCE HELD, projection
        //               wins.
        // The discriminating assertion below fails against any reversion
        // of the presenter's wire to
        // `projectionRunActive = projection.anyLettaServerLocalPending`.
        assertTrue(
            presentation.isStreaming,
            "project.anyRunActive=true must hold streaming presence " +
                "even when projection.anyLettaServerLocalPending=false — " +
                "proves the mask reads anyRunActive, not " +
                "anyLettaServerLocalPending.",
        )
    }
}
