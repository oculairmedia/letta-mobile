package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.branch.BranchKind
import com.letta.mobile.data.chat.recompile.ConversationRecompileController
import com.letta.mobile.data.chat.recompile.ConversationRecompileGateway
import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.data.chat.recompile.RecompileStatus
import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.ui.chat.session.FakeChatSessionPort
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's conversation owner over an Iroh host's `admin_rpc`; its row actions are the shared [ConversationManagement]. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineConversationsTest {
    private val nora = AgentId("agent-1")
    private val ivo = AgentId("agent-2")

    @Test
    fun listsEveryAgentsConversationsAndRoutesToTheActiveOnes() = runTest {
        val host = IrohHost(
            HostConversation("c1", "agent-1", lastMessageAt = "2026-10-20T10:00:00Z"),
            HostConversation("c2", "agent-2"),
            HostConversation("c3", "agent-1", archived = true),
        )
        val owner = owner(host)
        runCurrent()

        assertTrue(owner.state.value.loaded)
        assertEquals(3, owner.state.value.conversations.size)
        assertEquals(
            listOf(
                ShellConversationRef(ConversationId("c1"), nora, "2026-10-20T10:00:00Z"),
                ShellConversationRef(ConversationId("c2"), ivo, ""),
            ),
            owner.conversations(),
        )
        owner.selectConversation(ConversationId("c2"))
        assertEquals(TimelineChatTarget("agent-2", "Ivo", "c2"), owner.selectedTarget())
    }

    @Test
    fun reorderedRosterKeeps883RowsWithoutAnother137AgentSweep() = runTest {
        repeat(5) { sample ->
            val roster = List(137) { Agent(id = AgentId("agent-$it"), name = "Agent $it") }
            val rows = List(883) { index ->
                Conversation(
                    id = ConversationId("conversation-$index"),
                    agentId = roster[index % roster.size].id,
                    lastMessageAt = "2026-10-20T10:00:00Z",
                    archived = index % 11 == 0,
                )
            }
            var calls = 0
            val base = IrohHost().chatGateway(backgroundScope)
            val delayed = object : ChatGateway by base {
                override suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation> {
                    calls++
                    delay(100)
                    return rows.filter { it.agentId == page.agent }.take(page.limit)
                }
            }
            val start = currentTime
            val owner = TimelineConversations(this, delayed, roster)
            runCurrent()
            assertTrue(owner.state.value.conversations.isEmpty())
            advanceUntilIdle()
            assertEquals(883, owner.state.value.conversations.size)
            assertEquals(137, calls)
            val cold = currentTime - start
            assertEquals(3_500, cold)

            val before = owner.state.value.conversations
            val warmStart = currentTime
            owner.roster = roster.reversed().map { it.copy(name = "Renamed ${it.name}") }
            runCurrent()
            assertEquals(before, owner.state.value.conversations, "refresh must not blank content")
            advanceUntilIdle()
            assertEquals(137, calls, "roster ordering must not trigger another fleet sweep")
            assertEquals(0, currentTime - warmStart)
            owner.selectConversation(rows.first().id)
            assertEquals("Renamed Agent 0", owner.selectedTarget()?.agentName)
            println("fleet sample=$sample coldCalls=137 coldFirst=$cold coldComplete=$cold warmCalls=${calls - 137} warmFirst=0 warmComplete=${currentTime - warmStart} rows=883")
        }
    }

    @Test
    fun hiddenOnlyRosterArrivingAfterStartupSettlesToLoadedEmpty() = runTest {
        val owner = TimelineConversations(
            backgroundScope,
            IrohHost().chatGateway(backgroundScope),
            emptyList(),
        )
        runCurrent()
        assertEquals(false, owner.state.value.loaded)

        owner.roster = listOf(Agent(id = nora, name = "Nora", hidden = true))
        runCurrent()

        assertTrue(owner.state.value.loaded)
        assertTrue(owner.state.value.conversations.isEmpty())
        assertNull(owner.state.value.error)
    }

    @Test
    fun changingVisibilityOfAnExistingAgentReloadsMembership() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1"), HostConversation("c2", "agent-2"))
        val owner = owner(host)
        runCurrent()
        owner.roster = owner.roster.map { if (it.id == ivo) it.copy(hidden = true) else it }
        runCurrent()
        assertEquals(listOf("c1"), owner.state.value.conversations.map { it.id.value })
        owner.roster = owner.roster.map { it.copy(hidden = false) }
        runCurrent()
        assertEquals(setOf("c1", "c2"), owner.state.value.conversations.map { it.id.value }.toSet())
    }

    @Test
    fun aRosterEmptiedAfterLoadingListsNothing() = runTest {
        val owner = owner(IrohHost(HostConversation("c1", "agent-1"), HostConversation("c2", "agent-2")))
        runCurrent()

        owner.roster = emptyList()
        runCurrent()

        assertTrue(owner.state.value.loaded)
        assertTrue(owner.state.value.conversations.isEmpty())
    }

    @Test
    fun aRosterArrivingAfterAnEmptyInitialRosterLoadsEveryAgentsConversations() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1"), HostConversation("c2", "agent-2"))
        val owner = TimelineConversations(
            backgroundScope,
            host.chatGateway(backgroundScope),
            emptyList(),
            ConversationManagementPrefs.from(MapSettingsStore()),
        )
        runCurrent()

        assertEquals(false, owner.state.value.loaded)
        assertTrue(host.methods.isEmpty(), "an empty initial roster does not query the backend")

        owner.roster = listOf(Agent(id = nora, name = "Nora"), Agent(id = ivo, name = "Ivo"))
        runCurrent()

        assertEquals(listOf("c1", "c2"), owner.state.value.conversations.map { it.id.value }.toSet().sorted())
        owner.refresh()
        assertEquals(listOf("c1", "c2"), owner.state.value.conversations.map { it.id.value }.toSet().sorted())
        assertEquals(2, owner.state.value.conversations.size)
    }

    @Test
    fun aFailedListSaysWhyAndARefreshRecoversTheList() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1")).apply { failing += "conversation.list" }
        val owner = owner(host)
        runCurrent()

        assertEquals("conversation.list denied", owner.state.value.error)
        assertEquals(false, owner.state.value.loaded)

        host.failing.clear()
        owner.refresh()

        assertEquals(null, owner.state.value.error)
        assertEquals(true, owner.state.value.loaded)
        assertEquals(listOf("c1"), owner.state.value.conversations.map { it.id.value })
    }

    @Test
    fun aNewChatJoinsTheTopOfTheListAndOpens() = runTest {
        val owner = owner(IrohHost(HostConversation("c1", "agent-1")))
        runCurrent()

        owner.startNewChat(ivo)
        runCurrent()

        assertEquals(ConversationId("new-1"), owner.selectedConversation)
        assertEquals(listOf("new-1", "c1"), owner.state.value.conversations.map { it.id.value })
        assertEquals(false, owner.state.value.creating)
    }

    @Test
    fun whenTheAgentsChatsCannotBeReadNoDuplicateIsMade() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1"))
        val owner = owner(AgentListFails(host.chatGateway(backgroundScope)))
        runCurrent()
        val opened = mutableListOf<ConversationId>()

        owner.createConversationForAgent(ivo) { opened += it }
        runCurrent()

        assertTrue(opened.isEmpty())
        assertEquals("timed out", owner.state.value.error)
        assertTrue("conversation.create" !in host.methods)
    }

    @Test
    fun aSecondAskWhileAChatIsBeingMadeGetsTheSameChat() = runTest {
        val owner = owner(IrohHost())
        runCurrent()
        val first = mutableListOf<ConversationId>()
        val second = mutableListOf<ConversationId>()

        owner.createConversationForAgent(nora) { first += it }
        owner.createConversationForAgent(nora) { second += it }
        runCurrent()

        assertEquals(listOf(ConversationId("new-1")), first)
        assertEquals(listOf(ConversationId("new-1")), second)
    }

    @Test
    fun theFirstChatIsTheRoutedOneElseTheFocusedAgentsElseANewOneWhenTheChatShows() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1"), HostConversation("c2", "agent-2"))
        val owner = owner(host)
        runCurrent()
        owner.openInitial(routed = ConversationId("gone"), focused = ivo, createIfNone = true)
        assertEquals(ConversationId("c2"), owner.selectedConversation)

        val routed = owner(host)
        runCurrent()
        routed.openInitial(routed = ConversationId("c1"), focused = ivo, createIfNone = true)
        assertEquals(ConversationId("c1"), routed.selectedConversation)

        val fresh = owner(IrohHost())
        runCurrent()
        fresh.openInitial(routed = null, focused = nora, createIfNone = false)
        assertNull(fresh.selectedConversation)
        fresh.openInitial(routed = null, focused = nora, createIfNone = true)
        runCurrent()
        assertEquals(ConversationId("new-1"), fresh.selectedConversation)
    }

    @Test
    fun aRenameShowsAtOnceAndComesBackWhenTheWriteFails() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1", summary = "Old"))
        val owner = owner(host)
        runCurrent()

        rename(owner, "  New  ")
        assertEquals("New", owner.state.value.conversations.single().summary)
        runCurrent()
        assertEquals("New", host.stored("c1")?.summary)

        host.failing += "conversation.update"
        rename(owner, "Newer")
        runCurrent()
        assertEquals("New", owner.state.value.conversations.single().summary)
    }

    @Test
    fun aFailedRenameNeverOverwritesALaterOne() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1", summary = "Old")).apply { failing += "conversation.update" }
        val owner = owner(host)
        runCurrent()

        rename(owner, "First")
        rename(owner, "Second")
        runCurrent()

        // Each failed write puts back what showed before it, and only while its own title still shows.
        assertEquals("First", owner.state.value.conversations.single().summary)
    }

    @Test
    fun deletingTheOpenChatMovesToTheAgentsNextActiveOne() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1"), HostConversation("c2", "agent-2"), HostConversation("c3", "agent-1"))
        val owner = owner(host)
        runCurrent()
        owner.selectConversation(ConversationId("c1"))

        owner.management.delete(ConversationId("c1"))
        assertEquals(setOf(ConversationId("c1")), owner.state.value.deleting)
        runCurrent()

        assertEquals(listOf("c2", "c3"), owner.state.value.conversations.map { it.id.value })
        assertEquals(ConversationId("c3"), owner.selectedConversation)
        assertTrue(owner.state.value.deleting.isEmpty())
        assertEquals(ConversationDeleteBehavior.MovesToArchived, owner.management.deleteBehavior)
    }

    @Test
    fun aFailedDeleteKeepsTheRowAndTellsTheChat() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1")).apply { failing += "conversation.archive" }
        val owner = owner(host)
        runCurrent()
        val chat = FakeChatSessionPort()
        backgroundScope.launch { owner.deliverTo(ConversationId("c1"), chat.actions) }

        owner.management.delete(ConversationId("c1"))
        runCurrent()

        assertEquals(listOf("c1"), owner.state.value.conversations.map { it.id.value })
        assertTrue(owner.state.value.deleting.isEmpty())
        assertEquals("conversation.archive denied", chat.composer.value.error)
    }

    @Test
    fun aSendFromHomeOpensTheConversationAndGoesOutOnceItsChatIsOpen() = runTest {
        val owner = owner(IrohHost(HostConversation("c1", "agent-1")))
        runCurrent()
        val chat = FakeChatSessionPort()

        owner.send(ConversationId("c1"), "hello")
        assertEquals(ConversationId("c1"), owner.selectedConversation)
        backgroundScope.launch { owner.deliverTo(ConversationId("c1"), chat.actions) }
        runCurrent()

        assertEquals(listOf("hello"), chat.sent)
        assertTrue(owner.state.value.drafts.isEmpty())
    }

    @Test
    fun forkCommandForksTheOpenChatRecordsItsParentAndKeepsItsPin() = runTest {
        val host = IrohHost(HostConversation("c1", "agent-1"))
        val owner = owner(host)
        runCurrent()
        owner.selectConversation(ConversationId("c1"))
        owner.management.setPinned(ConversationId("c1"), true)

        assertTrue(owner.management.branches.runBranchCommand("/fork"))
        assertEquals(false, owner.management.branches.runBranchCommand("hello"))
        runCurrent()

        val fork = host.calls.single { it.first == "conversation.fork" }.second
        assertEquals("c1" to "agent-1", fork.text("conversation_id") to fork.text("agent_id"))
        assertEquals(ConversationId("fork-1"), owner.selectedConversation)
        assertEquals(BranchKind.Fork, owner.management.conversationParents.value["fork-1"]?.kind)
        assertTrue("fork-1" in owner.management.pinnedConversationIds.value)
    }

    @Test
    fun recompileGoesToTheOpenConversation() = runTest {
        val gateway = Recompiling(IrohHost(HostConversation("c1", "agent-1")).chatGateway(backgroundScope))
        val owner = owner(gateway)
        runCurrent()

        owner.management.recompileOpenConversation()
        assertTrue(gateway.requests.isEmpty())
        owner.selectConversation(ConversationId("c1"))
        owner.management.recompileOpenConversation()
        runCurrent()

        assertEquals(listOf(ConversationRecompileRequest("c1", "agent-1")), gateway.requests)
        assertEquals(RecompileStatus.Succeeded, owner.management.recompile.status.value)
    }

    @Test
    fun anIrohHostThatDoesNotAdvertiseRecompileSaysItNeedsAnUpdate() = runTest {
        val owner = owner(IrohHost(HostConversation("c1", "agent-1")))
        runCurrent()
        owner.selectConversation(ConversationId("c1"))

        owner.management.recompileOpenConversation()
        runCurrent()

        val status = assertIs<RecompileStatus.Unavailable>(owner.management.recompile.status.value)
        assertEquals(ConversationRecompileController.UNAVAILABLE_HOST_OUTDATED, status.reason)
    }

    private fun rename(owner: TimelineConversations, title: String) =
        owner.management.rename(ConversationSummaryUpdate(ConversationId("c1"), ConversationSummary(title)))

    private fun TestScope.owner(host: IrohHost) = owner(host.chatGateway(backgroundScope))

    private fun TestScope.owner(gateway: ChatGateway) = TimelineConversations(
        backgroundScope,
        gateway,
        listOf(Agent(id = nora, name = "Nora"), Agent(id = ivo, name = "Ivo")),
        ConversationManagementPrefs.from(MapSettingsStore()),
    )

    /** A gateway whose agents' lists cannot be read right now. */
    private class AgentListFails(private val base: ChatGateway) : ChatGateway by base {
        override suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation> = error("timed out")
    }

    /** A gateway that recompiles, recording each request. */
    private class Recompiling(private val base: ChatGateway) : ChatGateway by base, ConversationRecompileGateway {
        val requests = mutableListOf<ConversationRecompileRequest>()

        override suspend fun recompileConversation(request: ConversationRecompileRequest) {
            requests += request
        }
    }
}
