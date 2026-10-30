@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.header

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.conversations.ConversationManagementPrefs
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import kotlinx.coroutines.flow.emptyFlow
import java.lang.reflect.Proxy
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.search.LettaSearchSection
import com.letta.mobile.ui.shell.conversations.ShellTimelineChatBinding
import com.letta.mobile.ui.shell.conversations.rememberShellTimelineChat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.test.assertTrue

import com.letta.mobile.data.search.MessageSearchCapabilities
import com.letta.mobile.data.search.MessageSearchHit
import com.letta.mobile.data.search.MessageSearchMode
import com.letta.mobile.data.search.MessageSearchQuery
import com.letta.mobile.data.search.MessageSearchRole
import com.letta.mobile.data.search.MessageSearchSelection
import com.letta.mobile.data.search.MessageSearchSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The unified search carries the requested scopes, message mode, roles and preparation status. */
class UnifiedSearchResultsTest {
    private val search = object : MessageSearchSource {
        override val capabilities = MessageSearchCapabilities.LOCAL

        override suspend fun search(query: MessageSearchQuery): List<MessageSearchHit> = emptyList()
    }
    private val sources = UnifiedSearchSources(agentId = "a-1", messageSearch = search)

    @Test
    fun agentOnlySearchDoesNotPrepareConversationsButChangingScopeDoes() = runComposeUiTest {
        var calls = 0
        val prepare: suspend (AgentId?) -> Unit = { calls++ }
        val query = mutableStateOf(UnifiedSearchQuery("needle", scopeId = UnifiedSearchKind.Agents.id))
        mainClock.autoAdvance = false
        setContent { rememberUnifiedSearchSections(query.value, UnifiedSearchSources(prepareConversations = prepare)) }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals(0, calls, "an Agents-only query must not trigger conversation paging")
        runOnIdle { query.value = query.value.copy(scopeId = UnifiedSearchKind.Conversations.id) }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals(1, calls, "the unchanged text must prepare when the scope changes to Conversations")
    }

    @Test
    fun transientPreparationFailureRetriesWithoutChangingTheQuery() = runComposeUiTest(
        effectContext = CoroutineExceptionHandler { _, _ -> },
    ) {
        var calls = 0
        val prepare: suspend (AgentId?) -> Unit = {
            calls++
            if (calls == 1) error("Temporary conversation outage")
        }
        mainClock.autoAdvance = false
        setContent { rememberUnifiedSearchSections(UnifiedSearchQuery("needle"), UnifiedSearchSources(prepareConversations = prepare)) }
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        assertEquals(2, calls, "a transient preparation failure must retry the same query")
    }

    @Test
    fun preparationFailureIsVisibleAlongsideSearchResults() = runComposeUiTest(
        effectContext = CoroutineExceptionHandler { _, _ -> },
    ) {
        var sections = emptyList<LettaSearchSection>()
        val prepare: suspend (AgentId?) -> Unit = { error("Temporary conversation outage") }
        mainClock.autoAdvance = false
        setContent {
            sections = rememberUnifiedSearchSections(UnifiedSearchQuery("needle"), UnifiedSearchSources(prepareConversations = prepare))
        }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertTrue(sections.any { it.title == "Conversation search incomplete" }, "partial search needs a visible warning")
    }

    @Test
    fun persistedAgentPinsRemainAvailableWithoutAConversationOwner() = runComposeUiTest {
        val binding = ShellTimelineChatBinding<ChatSessionPort>(
            workspace = ShellWorkspaceState(), session = "offline", connection = BackendConnectionStatus.Unconfigured,
            agents = emptyList(), prefs = ConversationManagementPrefs(), backend = { null },
            open = { _, _, _ -> null }, close = {}, pinnedAgents = setOf(AgentId("pinned-offline")),
        )
        var pins = emptySet<String>()
        setContent { pins = rememberShellTimelineChat(binding).list.pinnedAgentIds }
        waitForIdle()
        assertEquals(setOf("pinned-offline"), pins)
    }

    @Test
    fun focusedPagesWaitForVisibilityAndResumeWhenTheRosterArrives() = runComposeUiTest {
        val agent = AgentId("late-agent")
        val recent = Conversation(ConversationId("late-recent"), agent)
        val archived = Conversation(ConversationId("late-archived"), agent, archived = true)
        val rows = listOf(recent, archived)
        val reads = mutableListOf<AgentId>()
        val unexpected = Proxy.newProxyInstance(ChatGateway::class.java.classLoader, arrayOf(ChatGateway::class.java)) {
                _, method, _ -> error("Unexpected gateway call: ${method.name}")
        } as ChatGateway
        val gateway = object : ChatGateway by unexpected {
            override val conversationChanges = emptyFlow<Pair<ConversationId, AgentId?>>()
            override val deleteBehavior = ConversationDeleteBehavior.Permanent
            override suspend fun listRecentConversations(limit: Int, after: ConversationId?): List<Conversation> {
                require(limit == 30)
                require(after == null)
                return listOf(recent)
            }
            override suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation> {
                require(page.agent == agent)
                require(page.after == null)
                require(page.archiveStatus == "all")
                reads += page.agent
                return rows.take(page.limit)
            }
            override suspend fun getConversation(conversationId: String): Conversation = rows.single { it.id.value == conversationId }
        }
        val agents = mutableStateOf(emptyList<Agent>())
        val workspace = ShellWorkspaceState(ShellDestination.Chat()).apply { focusedAgentId = agent }
        var owner: com.letta.mobile.data.chat.conversations.TimelineConversations? = null
        setContent {
            owner = rememberShellTimelineChat(ShellTimelineChatBinding<ChatSessionPort>(
                workspace, "late-roster", BackendConnectionStatus.Connected("Iroh"), agents.value,
                ConversationManagementPrefs(), backend = { gateway }, open = { _, _, _ -> null }, close = {},
            )).owner
        }
        waitForIdle()
        assertEquals(emptyList(), reads, "unresolved roster membership must not widen the read")
        assertEquals("late-recent", owner?.selectedTarget()?.conversationId, "the fetched row already opens the chat")
        runOnIdle { agents.value = listOf(Agent(agent, name = "Late agent")) }
        waitForIdle()
        assertEquals(listOf(agent), reads, "the focused visible agent must load after directory arrival")
        assertTrue(owner?.state?.value?.conversations?.any { it.id == archived.id } == true)
    }

    @Test
    fun messageQueryCarriesModeAndRolesAndTheAgentScope() {
        val asked = messageQueryFor(
            UnifiedSearchQuery(
                text = "hi",
                filterToAgent = true,
                message = MessageSearchSelection(MessageSearchMode.Semantic, setOf(MessageSearchRole.Tool)),
            ),
            sources,
        )
        val expected = MessageSearchQuery(
            "hi",
            "a-1",
            MessageSearchSelection(MessageSearchMode.Semantic, setOf(MessageSearchRole.Tool)),
        )
        assertEquals(expected, asked)
    }

    @Test
    fun blankOrOutOfScopeQueriesDoNotRun() {
        assertNull(messageQueryFor(UnifiedSearchQuery(""), sources))
        assertNull(messageQueryFor(UnifiedSearchQuery("hi", scopeId = UnifiedSearchKind.Agents.id), sources))
    }
}
