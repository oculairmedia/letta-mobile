package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.deduplicateRenderKeys
import com.letta.mobile.data.chat.projection.groupMessagesForRender
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Freezes the 11 key tool lifecycle contracts across stream reduction,
 * history hydration, UI message projection, and render item grouping.
 *
 * All test function names in commonTest use camelCase identifiers (Rule 4).
 */
class ToolTimelineContractTest {

    @Test
    fun oneCallReceivingArgumentDeltasKeepsKeyStable() {
        val initialInput = TimelineReducerInput(
            prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
            frame = ToolTimelineFixtures.ArgumentDeltas.initialCall,
            pendingToolReturnsByCallId = persistentMapOf(),
        )
        val initialOutput = reduceStreamFrame(initialInput)
        val initialEvent = initialOutput.next.events.single() as TimelineEvent.Confirmed
        val initialUi = timelineEventToUiMessage(initialEvent)!!
        val initialItems = groupMessagesForRender(listOf(initialUi to GroupPosition.None))
        val initialKey = initialItems.single().key

        val deltaInput = TimelineReducerInput(
            prev = initialOutput.next,
            frame = ToolTimelineFixtures.ArgumentDeltas.updatedCall,
            pendingToolReturnsByCallId = initialOutput.updatedPendingToolReturnsByCallId,
        )
        val deltaOutput = reduceStreamFrame(deltaInput)
        val deltaEvent = deltaOutput.next.events.single() as TimelineEvent.Confirmed
        val deltaUi = timelineEventToUiMessage(deltaEvent)!!
        val deltaItems = groupMessagesForRender(listOf(deltaUi to GroupPosition.None))

        assertEquals("msg-tool-delta", initialEvent.serverId)
        assertEquals("msg-tool-delta", deltaEvent.serverId)
        assertEquals("""{"command":"ls""", initialEvent.toolCalls.single().arguments)
        assertEquals("""{"command":"ls -la"}""", deltaEvent.toolCalls.single().arguments)

        assertEquals("msg-tool-delta", initialUi.id)
        assertEquals("msg-tool-delta", deltaUi.id)

        assertEquals(initialKey, deltaItems.single().key)
    }

    @Test
    fun runningToSuccessTerminalValuesAreEquivalent() {
        // Live stream path
        val callOutput = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
                frame = ToolTimelineFixtures.RunningToSuccess.callFrame,
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        )
        val returnOutput = reduceStreamFrame(
            TimelineReducerInput(
                prev = callOutput.next,
                frame = ToolTimelineFixtures.RunningToSuccess.returnFrame,
                pendingToolReturnsByCallId = callOutput.updatedPendingToolReturnsByCallId,
            )
        )
        val liveEvent = returnOutput.next.events.single() as TimelineEvent.Confirmed

        // Hydrated path
        val hydrationResult = TimelineHydrationReducer.reduce(
            conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID,
            serverMessagesChronological = listOf(
                ToolTimelineFixtures.RunningToSuccess.callFrame,
                ToolTimelineFixtures.RunningToSuccess.returnFrame,
            ),
            timelineBeforeFetch = Timeline(ToolTimelineFixtures.TEST_CONVERSATION_ID),
            currentTimeline = Timeline(ToolTimelineFixtures.TEST_CONVERSATION_ID),
            diskRecords = emptyList(),
        )
        val hydratedEvent = hydrationResult.timeline.events.single() as TimelineEvent.Confirmed

        assertEquals("file content ok", liveEvent.toolReturnContentByCallId["call-succ-1"])
        assertEquals("file content ok", hydratedEvent.toolReturnContentByCallId["call-succ-1"])
        assertEquals(false, liveEvent.toolReturnIsErrorByCallId["call-succ-1"])
        assertEquals(false, hydratedEvent.toolReturnIsErrorByCallId["call-succ-1"])
        assertTrue(liveEvent.approvalDecided)
        assertTrue(hydratedEvent.approvalDecided)

        val liveUi = timelineEventToUiMessage(liveEvent)!!
        val hydratedUi = timelineEventToUiMessage(hydratedEvent)!!

        assertEquals("success", liveUi.toolCalls!!.single().status)
        assertEquals("success", hydratedUi.toolCalls!!.single().status)
        assertEquals("file content ok", liveUi.toolCalls!!.single().result)
        assertEquals("file content ok", hydratedUi.toolCalls!!.single().result)
    }

    @Test
    fun runningToExplicitErrorPreservesErrorStatus() {
        val callOutput = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
                frame = ToolTimelineFixtures.RunningToExplicitError.callFrame,
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        )
        val returnOutput = reduceStreamFrame(
            TimelineReducerInput(
                prev = callOutput.next,
                frame = ToolTimelineFixtures.RunningToExplicitError.returnFrame,
                pendingToolReturnsByCallId = callOutput.updatedPendingToolReturnsByCallId,
            )
        )
        val event = returnOutput.next.events.single() as TimelineEvent.Confirmed

        assertEquals(true, event.toolReturnIsErrorByCallId["call-err-1"])

        val ui = timelineEventToUiMessage(event)!!
        assertEquals("error", ui.toolCalls!!.single().status)
        assertEquals("permission denied", ui.toolCalls!!.single().result)
    }

    @Test
    fun approvalPendingToApprovedToRunningToSuccessLifecycle() {
        // Step 1: Approval Pending
        val step1 = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
                frame = ToolTimelineFixtures.ApprovalPendingApprovedRunningSuccess.requestFrame,
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        )
        val ev1 = step1.next.events.single() as TimelineEvent.Confirmed
        val ui1 = timelineEventToUiMessage(ev1)!!
        assertFalse(ev1.approvalDecided)
        assertNotNull(ui1.approvalRequest)
        assertNull(ui1.toolCalls!!.single().approvalDecision)
        assertNull(ui1.toolCalls!!.single().status)

        // Step 2: Approved
        val step2 = reduceStreamFrame(
            TimelineReducerInput(
                prev = step1.next,
                frame = ToolTimelineFixtures.ApprovalPendingApprovedRunningSuccess.responseFrame,
                pendingToolReturnsByCallId = step1.updatedPendingToolReturnsByCallId,
            )
        )
        val ev2 = step2.next.events.single() as TimelineEvent.Confirmed
        val ui2 = timelineEventToUiMessage(ev2)!!
        assertTrue(ev2.approvalDecided)
        assertNull(ui2.approvalRequest)
        assertEquals(UiToolApprovalDecision.Approved, ui2.toolCalls!!.single().approvalDecision)
        assertNull(ui2.toolCalls!!.single().status)

        // Step 3: Success
        val step3 = reduceStreamFrame(
            TimelineReducerInput(
                prev = step2.next,
                frame = ToolTimelineFixtures.ApprovalPendingApprovedRunningSuccess.returnFrame,
                pendingToolReturnsByCallId = step2.updatedPendingToolReturnsByCallId,
            )
        )
        val ev3 = step3.next.events.single() as TimelineEvent.Confirmed
        val ui3 = timelineEventToUiMessage(ev3)!!
        assertEquals("success", ui3.toolCalls!!.single().status)
        assertEquals("file deleted successfully", ui3.toolCalls!!.single().result)
        assertEquals(UiToolApprovalDecision.Approved, ui3.toolCalls!!.single().approvalDecision)
    }

    @Test
    fun rejectionMarksDecidedAndClearsApprovalRequest() {
        val step1 = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
                frame = ToolTimelineFixtures.Rejection.requestFrame,
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        )
        val step2 = reduceStreamFrame(
            TimelineReducerInput(
                prev = step1.next,
                frame = ToolTimelineFixtures.Rejection.responseFrame,
                pendingToolReturnsByCallId = step1.updatedPendingToolReturnsByCallId,
            )
        )
        val ev = step2.next.events.single() as TimelineEvent.Confirmed
        val ui = timelineEventToUiMessage(ev)!!

        // Decision clears the blocking approval request UI
        assertTrue(ev.approvalDecided)
        assertNull(ui.approvalRequest)

        // Note: Production TimelineEventToUiMessage projects `approvalDecided && approvalRequestId != null`
        // as UiToolApprovalDecision.Approved. This documents actual current contract.
        assertEquals(UiToolApprovalDecision.Approved, ui.toolCalls!!.single().approvalDecision)
    }

    @Test
    fun twoCallsArrivingIncrementallyPreservesKeys() {
        val step1 = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
                frame = ToolTimelineFixtures.TwoCallsArrivingIncrementally.call1Frame,
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        )
        val ev1 = step1.next.events.single() as TimelineEvent.Confirmed
        val ui1 = timelineEventToUiMessage(ev1)!!
        val key1 = groupMessagesForRender(listOf(ui1 to GroupPosition.None)).single().key

        val step2 = reduceStreamFrame(
            TimelineReducerInput(
                prev = step1.next,
                frame = ToolTimelineFixtures.TwoCallsArrivingIncrementally.call1And2Frame,
                pendingToolReturnsByCallId = step1.updatedPendingToolReturnsByCallId,
            )
        )
        val ev2 = step2.next.events.single() as TimelineEvent.Confirmed
        val ui2 = timelineEventToUiMessage(ev2)!!
        val key2 = groupMessagesForRender(listOf(ui2 to GroupPosition.None)).single().key

        assertEquals("msg-batch-inc", ev1.serverId)
        assertEquals("msg-batch-inc", ev2.serverId)
        assertEquals(1, ev1.toolCalls.size)
        assertEquals(2, ev2.toolCalls.size)
        assertEquals(key1, key2)

        val step3 = reduceStreamFrame(
            TimelineReducerInput(
                prev = step2.next,
                frame = ToolTimelineFixtures.TwoCallsArrivingIncrementally.return1Frame,
                pendingToolReturnsByCallId = step2.updatedPendingToolReturnsByCallId,
            )
        )
        val step4 = reduceStreamFrame(
            TimelineReducerInput(
                prev = step3.next,
                frame = ToolTimelineFixtures.TwoCallsArrivingIncrementally.return2Frame,
                pendingToolReturnsByCallId = step3.updatedPendingToolReturnsByCallId,
            )
        )
        val evFinal = step4.next.events.single() as TimelineEvent.Confirmed
        val uiFinal = timelineEventToUiMessage(evFinal)!!

        assertEquals("result 1", uiFinal.toolCalls!![0].result)
        assertEquals("result 2", uiFinal.toolCalls!![1].result)
    }

    @Test
    fun returnBeforeCallAttachesWhenCallArrives() {
        val earlyReturnOutput = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
                frame = ToolTimelineFixtures.ReturnBeforeCall.returnFrameEarly,
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        )
        // Return frame without call frame is buffered
        assertTrue(earlyReturnOutput.next.events.isEmpty())
        assertTrue(earlyReturnOutput.updatedPendingToolReturnsByCallId.containsKey("call-early-1"))

        val lateCallOutput = reduceStreamFrame(
            TimelineReducerInput(
                prev = earlyReturnOutput.next,
                frame = ToolTimelineFixtures.ReturnBeforeCall.callFrameLate,
                pendingToolReturnsByCallId = earlyReturnOutput.updatedPendingToolReturnsByCallId,
            )
        )
        val ev = lateCallOutput.next.events.single() as TimelineEvent.Confirmed

        // Buffer is consumed and attached
        assertTrue(lateCallOutput.updatedPendingToolReturnsByCallId.isEmpty())
        assertEquals("early output arrived first", ev.toolReturnContentByCallId["call-early-1"])
        assertTrue(ev.approvalDecided)
    }

    @Test
    fun liveVsHydratedHistoryTerminalEquivalence() {
        var timeline = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID)
        var pendingReturns = persistentMapOf<String, com.letta.mobile.data.model.ToolReturnMessage>()

        for (msg in ToolTimelineFixtures.LiveVsHydrated.messagesSequence) {
            val out = reduceStreamFrame(
                TimelineReducerInput(
                    prev = timeline,
                    frame = msg,
                    pendingToolReturnsByCallId = pendingReturns,
                )
            )
            timeline = out.next
            pendingReturns = out.updatedPendingToolReturnsByCallId
        }

        val hydrated = TimelineHydrationReducer.reduce(
            conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID,
            serverMessagesChronological = ToolTimelineFixtures.LiveVsHydrated.messagesSequence,
            timelineBeforeFetch = Timeline(ToolTimelineFixtures.TEST_CONVERSATION_ID),
            currentTimeline = Timeline(ToolTimelineFixtures.TEST_CONVERSATION_ID),
            diskRecords = emptyList(),
        ).timeline

        assertEquals(timeline.events.size, hydrated.events.size)
        val liveConfirmed = timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        val hydratedConfirmed = hydrated.events.filterIsInstance<TimelineEvent.Confirmed>()

        assertEquals(liveConfirmed.map { it.serverId }, hydratedConfirmed.map { it.serverId })
        assertEquals(liveConfirmed.map { it.content }, hydratedConfirmed.map { it.content })
        assertEquals(
            liveConfirmed.map { it.toolReturnContentByCallId },
            hydratedConfirmed.map { it.toolReturnContentByCallId },
        )
    }

    @Test
    fun reconnectReconcilePromotesRunIdAndPreservesIdentity() {
        val step1 = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
                frame = ToolTimelineFixtures.ReconnectReconcile.syntheticLiveFrame,
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        )
        val ev1 = step1.next.events.single() as TimelineEvent.Confirmed
        assertEquals("iroh-run-synthetic-101", ev1.runId)

        val step2 = reduceStreamFrame(
            TimelineReducerInput(
                prev = step1.next,
                frame = ToolTimelineFixtures.ReconnectReconcile.realReconciledFrame,
                pendingToolReturnsByCallId = step1.updatedPendingToolReturnsByCallId,
            )
        )
        val ev2 = step2.next.events.single() as TimelineEvent.Confirmed

        // Single row preserved, runId promoted to real server runId
        assertEquals(1, step2.next.events.size)
        assertEquals("msg-reconcile-1", ev2.serverId)
        assertEquals("run-real-server-101", ev2.runId)
        assertEquals("Full complete answer.", ev2.content)
    }

    @Test
    fun truncatedResultPreservesTruncationMetadata() {
        val step1 = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
                frame = ToolTimelineFixtures.TruncatedResult.callFrame,
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        )
        val step2 = reduceStreamFrame(
            TimelineReducerInput(
                prev = step1.next,
                frame = ToolTimelineFixtures.TruncatedResult.returnFrame,
                pendingToolReturnsByCallId = step1.updatedPendingToolReturnsByCallId,
            )
        )
        val ev = step2.next.events.single() as TimelineEvent.Confirmed
        val ui = timelineEventToUiMessage(ev)!!

        val truncMarker = ev.toolReturnTruncationByCallId["call-trunc-1"]
        assertNotNull(truncMarker)
        assertEquals(250000L, truncMarker.byteLen)

        val uiTrunc = ui.toolCalls!!.single().resultTruncation
        assertNotNull(uiTrunc)
        assertEquals(250000L, uiTrunc.byteLen)
        assertEquals("Preview of log data...", ui.toolCalls!!.single().result)
    }

    @Test
    fun blankAndDuplicateToolCallIdsHandledDeterministically() {
        // Blank ID stream test
        val blankCallOut = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
                frame = ToolTimelineFixtures.BlankAndDuplicateToolCallId.blankCallFrame,
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        )
        val blankReturnOut = reduceStreamFrame(
            TimelineReducerInput(
                prev = blankCallOut.next,
                frame = ToolTimelineFixtures.BlankAndDuplicateToolCallId.blankReturnFrame,
                pendingToolReturnsByCallId = blankCallOut.updatedPendingToolReturnsByCallId,
            )
        )
        val blankEv = blankReturnOut.next.events.single() as TimelineEvent.Confirmed
        assertTrue(blankEv.toolReturnContentByCallId.isEmpty())

        // Duplicate ID stream test
        val dupCallOut = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID),
                frame = ToolTimelineFixtures.BlankAndDuplicateToolCallId.duplicateCallFrame,
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        )
        val dupReturnOut = reduceStreamFrame(
            TimelineReducerInput(
                prev = dupCallOut.next,
                frame = ToolTimelineFixtures.BlankAndDuplicateToolCallId.duplicateReturnFrame,
                pendingToolReturnsByCallId = dupCallOut.updatedPendingToolReturnsByCallId,
            )
        )
        val dupEv = dupReturnOut.next.events.single() as TimelineEvent.Confirmed
        val dupUi = timelineEventToUiMessage(dupEv)!!

        assertEquals(2, dupUi.toolCalls!!.size)
        assertEquals("shared result", dupUi.toolCalls!![0].result)
        assertEquals("shared result", dupUi.toolCalls!![1].result)

        // Collision-safe key resolution verification
        val single1 = ChatRenderItem.Single(dupUi, GroupPosition.None, stableRunKey = "run-key-1")
        val single2 = ChatRenderItem.Single(dupUi, GroupPosition.None, stableRunKey = "run-key-1")
        val dedupedKeys = deduplicateRenderKeys(listOf(single1, single2)).map { it.key }

        assertEquals(2, dedupedKeys.toSet().size)
        assertEquals("run-key-1", dedupedKeys[0])
        assertTrue(dedupedKeys[1].startsWith("run-key-1#"))
    }
}
