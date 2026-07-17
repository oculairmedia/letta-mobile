package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.util.Telemetry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-c4igq.5: the render-scope projection must let a headless probe
 * catch a foreign-agent item without a human looking at the screen.
 */
class RenderScopeProjectionTest {

    @BeforeTest fun enableDiag() { Telemetry.renderDiagEnabled.set(true) }
    @AfterTest fun disableDiag() { Telemetry.renderDiagEnabled.set(false) }

    private fun msg(id: String, agentId: String?, runId: String? = null) = UiMessage(
        id = id, role = "assistant", content = "c-$id",
        timestamp = "2026-04-19T12:00:00Z", runId = runId, agentId = agentId,
    )

    @Test
    fun projectionFlagsForeignAgentItem() {
        val items = listOf(
            ChatRenderItem.Single(msg("a1", agentId = "agent-A"), GroupPosition.None),
            ChatRenderItem.RunBlock(
                runId = "rB",
                messages = listOf(msg("b1", agentId = "agent-B", runId = "rB") to GroupPosition.First),
            ),
        )
        val foreign = RenderDiagnostics.onRenderScopeProjection(
            activeAgentId = "agent-A",
            conversationId = "conv-1",
            items = items,
        )
        assertEquals(1, foreign, "the agent-B RunBlock must be flagged as a foreign item")
    }

    @Test
    fun projectionReportsCleanWhenAllItemsMatchActiveAgent() {
        val items = listOf(
            ChatRenderItem.Single(msg("a1", agentId = "agent-A"), GroupPosition.None),
            ChatRenderItem.RunBlock(
                runId = "rA",
                messages = listOf(msg("a2", agentId = "agent-A", runId = "rA") to GroupPosition.First),
            ),
            // null agentId (legacy) is NOT foreign.
            ChatRenderItem.Single(msg("legacy", agentId = null), GroupPosition.None),
        )
        val foreign = RenderDiagnostics.onRenderScopeProjection(
            activeAgentId = "agent-A",
            conversationId = "conv-1",
            items = items,
        )
        assertEquals(0, foreign, "same-agent + null-agentId items must not be flagged foreign")
    }
}
