package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.timelineNow
import com.letta.mobile.ui.chat.render.GoalStatusUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The timeline port's optional search and goal-status hooks: both capabilities stay off for the
 * hosts that provide nothing (Android's wide parity), and a host that provides them gets the
 * same search pipeline Android's coordinator runs (.local matches now, remote hits after the
 * debounce, stale answers dropped) and the goal card's read/refresh/continue loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineChatSessionSearchAndGoalsTest {
    private val target = TimelineChatTarget(agentId = "agent-1", agentName = "Nora", conversationId = "conv-1")

    @Test
    fun withoutTheHooksStaysSearchAndGoalFreeAsWebAndDesktopAre() = runTest {
        val port = port(FakeTimelineChatSession())
        runCurrent()

        val capabilities = port.capabilities.value
        assertFalse(capabilities.search, "no search source: the page hides the search bar")
        assertFalse(capabilities.goals, "no goal source: the page hides the goal card")
        val state = port.uiState.value
        assertEquals("", state.searchQuery)
        assertFalse(state.isSearchActive)
        assertNull(state.goalStatus)
        assertFalse(state.isGoalStatusLoading)
    }

    @Test
    fun theHooksTurnTheirCapabilitiesOn() = runTest {
        val port = port(FakeTimelineChatSession(), search = { emptyList() }, goalStatus = { null })
        runCurrent()

        assertTrue(port.capabilities.value.search)
        assertTrue(port.capabilities.value.goals)
    }

    @Test
    fun aQueryMatchesLoadedMessagesNowAndMergesTheHostsHitsAfterTheDebounce() = runTest {
        val remote = mutableListOf<String>()
        val session = loadedConversation(
            confirmed("srv-1", "the needle hides here"),
            confirmed("srv-2", "nothing to see"),
        )
        val port = port(session, search = { request ->
            remote += request.query.orEmpty()
            listOf(remoteHit("srv-90", "a needle from the server"))
        })
        runCurrent()

        port.actions.updateSearchQuery("needle")
        runCurrent()
        val immediate = port.uiState.value
        assertTrue(immediate.isSearchActive)
        assertTrue(immediate.isSearching, "the remote hit is still on its way")
        assertEquals(listOf("the needle hides here"), immediate.searchResults.map { it.content })

        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        val merged = port.uiState.value
        assertFalse(merged.isSearching)
        assertEquals(
            listOf("the needle hides here", "a needle from the server"),
            merged.searchResults.map { it.content },
            "local matches stay first, the remote hit follows",
        )
        assertEquals(listOf("needle"), remote, "the host's search answers exactly the asked query")
    }

    @Test
    fun aQueryIgnoresCaseAsTheAndroidSearchDoes() = runTest {
        val session = loadedConversation(confirmed("srv-1", "The Needle Hides Here"))
        val port = port(session, search = { emptyList() })
        runCurrent()

        port.actions.updateSearchQuery("needle")
        runCurrent()
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(listOf("The Needle Hides Here"), port.uiState.value.searchResults.map { it.content })
    }

    @Test
    fun aClearCancelsTheSearchAndResetsThePage() = runTest {
        val session = loadedConversation(confirmed("srv-1", "the needle hides here"))
        val port = port(session, search = { listOf(remoteHit("srv-90", "never lands")) })
        runCurrent()

        port.actions.updateSearchQuery("needle")
        runCurrent()
        port.actions.clearSearch()
        runCurrent()
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        val state = port.uiState.value
        assertEquals("", state.searchQuery)
        assertFalse(state.isSearchActive)
        assertFalse(state.isSearching)
        assertTrue(state.searchResults.isEmpty(), "a cancelled search never shows its results")
    }

    @Test
    fun aStaleRemoteAnswerCannotOverwriteANewerQuery() = runTest {
        val session = loadedConversation(confirmed("srv-1", "old needle, new needle"))
        val port = port(session, search = { request -> listOf(aboutRemoteHit(request)) })
        runCurrent()

        port.actions.updateSearchQuery("old")
        advanceTimeBy(SEARCH_DEBOUNCE_MS - 100)
        runCurrent()
        port.actions.updateSearchQuery("new")
        advanceTimeBy(SEARCH_DEBOUNCE_MS + SEARCH_DEBOUNCE_MS - 100 + 1)
        runCurrent()

        val state = port.uiState.value
        assertEquals("new", state.searchQuery)
        assertFalse(state.isSearching)
        assertEquals(
            listOf("old needle, new needle", "about new"),
            state.searchResults.map { it.content },
            "only the query still shown gets its remote hits",
        )
    }

    @Test
    fun aFailingRemoteSearchEndsLookingAndKeepsTheLocalMatches() = runTest {
        val session = loadedConversation(confirmed("srv-1", "the needle hides here"))
        val port = port(session, search = { throw IllegalStateException("offline") })
        runCurrent()

        port.actions.updateSearchQuery("needle")
        runCurrent()
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        val state = port.uiState.value
        assertFalse(state.isSearching, "a failed lookup leaves the search usable")
        assertEquals(listOf("the needle hides here"), state.searchResults.map { it.content })
    }

    @Test
    fun theGoalLoadsWhenThePortOpensAndRefreshPropagates() = runTest {
        var status: GoalStatusUi? = goal("Ship the release")
        val port = port(FakeTimelineChatSession(), goalStatus = { status })
        runCurrent()
        assertEquals("Ship the release", port.uiState.value.goalStatus?.objective)

        status = goal("Write the postmortem")
        port.actions.refreshGoalStatus()
        runCurrent()
        assertEquals("Write the postmortem", port.uiState.value.goalStatus?.objective)
        assertFalse(port.uiState.value.isGoalStatusLoading)
    }

    @Test
    fun aFailingGoalReadEndsLoadingWithoutAStatus() = runTest {
        val port = port(FakeTimelineChatSession(), goalStatus = { throw IllegalStateException("offline") })
        runCurrent()
        val state = port.uiState.value
        assertNull(state.goalStatus)
        assertFalse(state.isGoalStatusLoading, "the card does not spin forever on a failed read")
    }

    @Test
    fun continueGoalSendsTheActiveGoalsNextStepPrompt() = runTest {
        val session = loadedConversation()
        val port = port(session, goalStatus = { goal("Finish the outline") })
        runCurrent()

        port.actions.continueGoal()
        runCurrent()

        assertEquals(
            listOf(
                "Continue working on the active goal: Finish the outline. Take the next concrete " +
                    "step, and update or complete the goal when appropriate.",
            ),
            session.sends.map { it.first },
        )
    }

    @Test
    fun withoutTheHooksTheSearchAndGoalActionsDoNothing() = runTest {
        val session = loadedConversation(confirmed("srv-1", "the needle hides here"))
        val port = port(session)
        runCurrent()

        port.actions.updateSearchQuery("needle")
        port.actions.clearSearch()
        port.actions.refreshGoalStatus()
        port.actions.continueGoal()
        runCurrent()
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        val state = port.uiState.value
        assertEquals("", state.searchQuery)
        assertFalse(state.isSearchActive)
        assertNull(state.goalStatus)
        assertTrue(session.sends.isEmpty(), "without a goal source there is nothing to continue")
    }

    private fun TestScope.port(
        session: FakeTimelineChatSession,
        search: (suspend (MessageSearchRequest) -> List<ParsedSearchMessage>)? = null,
        goalStatus: (suspend () -> GoalStatusUi?)? = null,
    ): TimelineChatSessionPort {
        val port = TimelineChatSessionPort(
            session, target, backgroundScope,
            TimelineChatSessionOptions(search = search, goalStatus = goalStatus),
        )
        // The page's flows share while collected, as ChatSurface collects them.
        backgroundScope.launch { port.uiState.collect {} }
        backgroundScope.launch { port.composer.collect {} }
        backgroundScope.launch { port.capabilities.collect {} }
        return port
    }

    private fun TestScope.loadedConversation(vararg events: TimelineEvent): FakeTimelineChatSession {
        val session = FakeTimelineChatSession()
        session.timeline.value = timelineWith(*events)
        return session
    }

    private fun confirmed(serverId: String, content: String): TimelineEvent.Confirmed =
        TimelineEvent.Confirmed(
            position = serverId.substringAfter("-").toDouble(),
            otid = "otid-$serverId",
            content = content,
            serverId = serverId,
            messageType = TimelineMessageType.USER,
            date = timelineNow(),
            runId = null,
            stepId = null,
        )

    /** A host hit: nowhere in the timeline, so it can only come from the remote lookup. */
    private fun remoteHit(id: String, content: String) = ParsedSearchMessage(
        messageId = id,
        agentId = target.agentId,
        role = "user",
        content = content,
        date = null,
        conversationId = target.conversationId,
    )

    /** The answer the fake repository gives [request]: one fresh hit, labelled by its query. */
    private fun aboutRemoteHit(request: MessageSearchRequest) =
        remoteHit("srv-9${request.query.orEmpty().length}", "about ${request.query}")

    private fun goal(objective: String) = GoalStatusUi(objective = objective, status = "in_progress")

    private fun timelineWith(vararg events: TimelineEvent) =
        Timeline(conversationId = target.conversationId, events = persistentListOf(*events))

    private companion object {
        /** The debounce the port keeps between a query and the host's remote lookup. */
        const val SEARCH_DEBOUNCE_MS = 180L
    }
}
