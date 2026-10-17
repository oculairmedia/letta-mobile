package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.runtime.ToolCallId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.8 / ulcg6.1: the subagent dual view's state and how a subagent becomes a view. */
class SubagentDualViewTest {
    private fun view(agent: String, title: String? = null) =
        SubagentView(AgentId(agent), ConversationId("conv-$agent"), title)

    @Test
    fun openingMovesTheReaderToTheSubagentAndReopeningDoesNotDuplicate() {
        val first = view("agent-a")
        val state = SubagentDualViewState.Closed.open(first).open(view("agent-b")).open(first)

        assertEquals(listOf("agent-a", "agent-b"), state.views.map { it.agentId.value })
        assertEquals(first.key, state.activeKey)
        assertTrue(state.showsSubagent)
    }

    @Test
    fun theParentIsOneStepAwayAndTheViewsStayOpen() {
        val state = SubagentDualViewState.Closed.open(view("agent-a")).showParent()

        assertEquals(DualViewFocus.PARENT, state.focus)
        assertFalse(state.showsSubagent)
        assertTrue(state.isOpen)
        assertTrue(state.select(view("agent-a").key).showsSubagent)
    }

    @Test
    fun closingTheActiveViewHandsOverAndTheLastOneReturnsToTheParent() {
        val a = view("agent-a")
        val b = view("agent-b")
        val c = view("agent-c")
        val open = SubagentDualViewState.Closed.open(a).open(b).open(c).select(b.key)

        val afterB = open.close(b.key)
        assertEquals(c.key, afterB.activeKey)
        assertEquals(listOf(a.key, c.key), afterB.views.map { it.key })
        assertEquals(SubagentDualViewState.Closed, afterB.close(a.key).close(c.key))
    }

    @Test
    fun openViewsAreBounded() {
        val state = (1..MAX_OPEN_SUBAGENT_VIEWS + 2).fold(SubagentDualViewState.Closed) { acc, i -> acc.open(view("agent-$i")) }

        assertEquals(MAX_OPEN_SUBAGENT_VIEWS, state.views.size)
        assertEquals("agent-${MAX_OPEN_SUBAGENT_VIEWS + 2}", state.active?.agentId?.value)
    }

    @Test
    fun theRegistrysBareDefaultIsTheAddressableDefaultConversation() {
        val opened = subagentViewOf(AgentId("agent-child"), ConversationId("default"), SubagentViewLabel(title = "Audit"))

        assertEquals(ConversationId("conv-default-agent-child"), opened?.conversationId)
        assertEquals("Audit", opened?.title)
    }

    @Test
    fun nothingOpensWithoutAConversationOrForAnExternalCodingAgent() {
        assertNull(subagentViewOf(AgentId("agent-child"), null, SubagentViewLabel()))
        assertNull(subagentViewOf(null, ConversationId("conv-1"), SubagentViewLabel()))
        assertNull(subagentViewOf(AgentId("claude_0b1c"), ConversationId("conv-1"), SubagentViewLabel()))
        assertNull(subagentViewOf(AgentId("agent-x"), ConversationId("conv-1"), SubagentViewLabel(identity = SubagentIdentity.CODEX)))
    }

    @Test
    fun aDispatchOpensWhatItsRegistryEntryNames() {
        val running = entry(conversation = null)
        val finished = entry(conversation = "default")
        val target = SubagentDispatchTarget(ToolCallId("toolu_1"), description = "Audit the build")

        assertNull(listOf(running).subagentViewForDispatch(target), "running: the child has no conversation yet")
        val opened = listOf(finished).subagentViewForDispatch(target)
        assertEquals(ConversationId("conv-default-agent-child"), opened?.conversationId)
        assertEquals("Audit the build", opened?.title)
    }

    @Test
    fun withoutARegistryADispatchOpensItsChildAgentsDefaultConversation() {
        val target = SubagentDispatchTarget(ToolCallId("toolu_9"), AgentId("agent-web"), "Look it up")

        assertEquals(ConversationId("conv-default-agent-web"), emptyList<SubagentEntry>().subagentViewForDispatch(target)?.conversationId)
        assertNull(emptyList<SubagentEntry>().subagentViewForDispatch(target.copy(childAgent = null)))
    }

    @Test
    fun aReflectionRunOpensAsADream() {
        val reflection = entry(conversation = "conv-r").copy(subagentType = "Reflection")
        val item = projectSubagentRoster(listOf(reflection), now = 0L).background.single()

        assertEquals(SubagentViewKind.DREAM, item.toSubagentView()?.kind)
    }

    @Test
    fun externalCodingAgentsAreRecognisedByTypeOrId() {
        assertEquals(SubagentIdentity.CLAUDE_CODE, subagentIdentityOf("claude-code"))
        assertEquals(SubagentIdentity.CODEX, subagentIdentityOf("General-purpose", listOf("codex_1f2e")))
        assertEquals(SubagentIdentity.LETTA, subagentIdentityOf("General-purpose", listOf("agent-1", null)))
        assertTrue(SubagentIdentity.CODEX.isExternal)
    }

    private fun entry(conversation: String?) = SubagentEntry(
        toolCallId = "toolu_1",
        description = "work",
        subagentType = "General-purpose",
        status = SubagentStatus.COMPLETED,
        subagentAgentId = "agent-child",
        subagentConversationId = conversation,
    )
}
