package com.letta.mobile.data.context

import com.letta.mobile.data.compaction.CompactionOutcome
import com.letta.mobile.data.compaction.ConversationCompactResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-3io8k: the drawer card's presentation rules. */
class AgentContextCardModelTest {
    private fun inputs(
        used: Int? = 84_000,
        window: Int? = 200_000,
        conversationIsDefault: Boolean = true,
        compactSupported: Boolean? = null,
        compacting: Boolean = false,
        turnRunning: Boolean = false,
        lastOutcome: CompactionOutcome? = null,
    ) = AgentContextCardInputs(
        modelLabel = "Claude Opus",
        effort = "high",
        meter = ContextMeter.of(used, window),
        conversationIsDefault = conversationIsDefault,
        compactSupported = compactSupported,
        compacting = compacting,
        turnRunning = turnRunning,
        lastOutcome = lastOutcome,
    )

    @Test
    fun percentAndToneFollowTheThresholds() {
        val normal = AgentContextCardModel.present(inputs(used = 84_000))
        assertEquals(42, normal.usedPercent)
        assertEquals(ContextMeterLevel.Normal, normal.level)
        assertFalse(normal.nudgeCompact)
        assertEquals(ContextMeterLevel.Warning, AgentContextCardModel.present(inputs(used = 140_000)).level)
        val nearFull = AgentContextCardModel.present(inputs(used = 172_000))
        assertEquals(ContextMeterLevel.Warning, nearFull.level)
        assertTrue(nearFull.nudgeCompact)
        assertEquals(ContextMeterLevel.Critical, AgentContextCardModel.present(inputs(used = 180_000)).level)
    }

    @Test
    fun noWindowOrNoReadingHasNoPercent() {
        assertNull(AgentContextCardModel.present(inputs(window = null)).usedPercent)
        val empty = AgentContextCardModel.present(inputs(used = null))
        assertNull(empty.meter)
        assertNull(empty.usedPercent)
        assertFalse(empty.nudgeCompact)
    }

    @Test
    fun theScopeFollowsTheConversation() {
        assertEquals(ModelChangeScope.Agent, AgentContextCardModel.present(inputs()).scope)
        assertEquals(ModelChangeScope.Conversation, AgentContextCardModel.present(inputs(conversationIsDefault = false)).scope)
    }

    @Test
    fun compactIsHiddenWhenUnsupportedAndBusyWhileRunning() {
        assertEquals(CompactAffordance.Available, AgentContextCardModel.present(inputs()).compact)
        assertEquals(CompactAffordance.Hidden, AgentContextCardModel.present(inputs(compactSupported = false)).compact)
        assertEquals(CompactAffordance.Busy, AgentContextCardModel.present(inputs(compacting = true)).compact)
        assertEquals(CompactAffordance.Busy, AgentContextCardModel.present(inputs(turnRunning = true)).compact)
    }

    @Test
    fun theLastOutcomeBecomesANotice() {
        val compacted = AgentContextCardModel.present(
            inputs(lastOutcome = CompactionOutcome.Compacted(ConversationCompactResult("execute_command", messagesBefore = 48, messagesAfter = 12))),
        )
        assertEquals(CompactionNotice.Compacted, compacted.notice)
        assertEquals(48 to 12, compacted.messageCounts)
        val failed = AgentContextCardModel.present(inputs(lastOutcome = CompactionOutcome.Failed("hook said no")))
        assertEquals(CompactionNotice.Failed, failed.notice)
        assertEquals("hook said no", failed.noticeDetail)
        assertEquals(CompactionNotice.AlreadyCompact, AgentContextCardModel.present(inputs(lastOutcome = CompactionOutcome.AlreadyCompact(ConversationCompactResult("x")))).notice)
        assertEquals(CompactionNotice.None, AgentContextCardModel.present(inputs(lastOutcome = CompactionOutcome.Busy)).notice)
    }

    @Test
    fun aSmallerWindowThanTheConversationWarns() {
        assertTrue(AgentContextCardModel.overflowsWindow(usedTokens = 150_000, candidateWindow = 128_000))
        assertFalse(AgentContextCardModel.overflowsWindow(usedTokens = 100_000, candidateWindow = 128_000))
        assertFalse(AgentContextCardModel.overflowsWindow(usedTokens = null, candidateWindow = 128_000))
        assertFalse(AgentContextCardModel.overflowsWindow(usedTokens = 150_000, candidateWindow = null))
    }
}
