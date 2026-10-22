package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web's conversation owner over an Iroh host, end to end below the owner: the shared
 * IrohAdminRpcChatGateway over the session's AppServerClientChannel over an App Server client whose
 * host takes only `admin_rpc` (an Iroh host refuses the App Server's own `conversation_*`
 * commands). Every list, open and row action must go out as `admin_rpc`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineConversationsIrohTest {
    @Test
    fun theListLoadsOverAdminRpcAndNoNativeCommandIsSent() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1", "Plans"), HostConversation("c2", "agent-2", "Ada's"))
        val owner = owner(host)
        runCurrent()

        assertTrue(owner.state.value.loaded, "the list loaded: ${owner.state.value.error}")
        assertEquals(listOf("c1", "c2"), owner.state.value.conversations.map { it.id.value })
        assertEquals(listOf("agent-1", "agent-2"), host.calls.map { (_, params) -> params.text("agent_id") }.sorted())
        assertEquals(listOf("conversation.list", "conversation.list"), host.methods)
        assertTrue(host.native.isEmpty(), "native commands sent: ${host.native}")
    }

    @Test
    fun rowActionsGoOutAsAdminRpc() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1", "Plans"))
        val owner = owner(host)
        runCurrent()
        val management = owner.management

        management.rename(ConversationSummaryUpdate(ConversationId("c1"), ConversationSummary("Release")))
        management.setArchived(ConversationId("c1"), archived = true)
        owner.selectConversation(ConversationId("c1"))
        assertTrue(management.branches.runBranchCommand("/fork"))
        runCurrent()
        management.delete(ConversationId("c1"))
        runCurrent()

        assertEquals(
            listOf(
                "conversation.list", "conversation.list",
                "conversation.update", "conversation.archive", "conversation.fork", "conversation.archive",
            ),
            host.methods,
        )
        assertTrue(host.native.isEmpty(), "native commands sent: ${host.native}")
        assertEquals("Release", host.stored("c1")?.summary)
        assertEquals("c1", management.conversationParents.value["fork-1"]?.conversationId)
        assertTrue(owner.state.value.conversations.none { it.id.value == "c1" }, "the deleted chat left the list")
        assertEquals(ConversationId("fork-1"), owner.selectedConversation)
    }

    @Test
    fun anAgentWithNoListedChatOpensItsOwnBeforeANewOneIsMade() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1", "Plans"), HostConversation("old", "agent-2", "Earlier"))
        val owner = owner(host, roster = listOf("agent-1"))
        runCurrent()

        owner.createConversationForAgent(AgentId("agent-2")) {}
        runCurrent()
        assertEquals(ConversationId("old"), owner.selectedConversation)

        owner.createConversationForAgent(AgentId("agent-3")) {}
        runCurrent()
        assertEquals(listOf("conversation.list", "conversation.list", "conversation.list", "conversation.create"), host.methods)
        assertEquals(ConversationId("new-1"), owner.selectedConversation)
        assertTrue(host.native.isEmpty(), "native commands sent: ${host.native}")
    }

    @Test
    fun agentFreeWorkerConversationsNeverCrowdOutTheAgentsOwn() = runTest {
        val workers = List(98) { HostConversation("worker-$it", agent = null, lastMessageAt = "2026-10-22T10:00:00Z") }
        val own = List(60) { HostConversation("c$it", "agent-1", lastMessageAt = "2026-10-01T00:00:00Z") }
        val host = IrohHost(*(workers + own + HostConversation("d1", "agent-2")).toTypedArray())
        val owner = owner(host)
        runCurrent()

        val listed = owner.state.value.conversations.map { it.id.value }
        assertEquals(61, listed.size, "every agent's own conversation, read past the first page")
        assertTrue(listed.none { it.startsWith("worker-") })
        assertTrue(host.calls.all { (_, params) -> params.text("agent_id").isNotEmpty() }, "no cross-agent read")
    }

    private fun TestScope.owner(host: IrohHost, roster: List<String> = listOf("agent-1", "agent-2")) = TimelineConversations(
        backgroundScope,
        host.chatGateway(backgroundScope),
        roster.map { Agent(id = AgentId(it), name = it) },
        ConversationManagementPrefs.from(MapSettingsStore()),
    )
}
