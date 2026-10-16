package com.letta.mobile.feature.chat.screen

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.presence.presenceByAgent
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.testutil.TestData
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * letta-mobile-te3n1.1: Android's chat publishes its conversation's subagents into the app-wide
 * run registry through the shared publisher, so a dream reaches the Android mascot (it used to be
 * desktop-only) and a tool with subagents under it can read DELEGATING.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminChatViewModelSubagentPresenceTest {
    @Test
    fun `the conversation's subagents reach the run registry`() = runTest(timeout = 3.minutes) {
        // The fixture's ViewModel brings up a great many mocks; a loaded machine needs more than runTest's 1 min.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModel: AdminChatViewModel? = null
        try {
            val agent = TestData.agent("agent-dream", "Dreamer")
            val entries = listOf(
                SubagentEntry(toolCallId = "s1", subagentType = "Reflection", status = "running", parentAgentId = agent.id.value, parentConversationId = CONVERSATION),
                SubagentEntry(toolCallId = "s2", subagentType = "General-purpose", status = "running", parentAgentId = agent.id.value, parentConversationId = CONVERSATION),
            )
            val subagents = mockk<ISubagentRepository>(relaxed = true) {
                every { activeSubagentsFlow(any()) } returns flowOf(entries)
                every { currentActiveSubagents(any()) } returns entries
            }
            val registry = ConversationRunRegistry()
            val presentation = ChatPagingPresentation(settled = flowOf(PagingData.empty()), live = MutableStateFlow(emptyList()), close = { })
            val host = ChatPagingHost().apply { openCanonical = { _, _, _, _ -> presentation } }
            viewModel = openedChatViewModel(
                host,
                agent,
                CONVERSATION,
                FixtureSession(tag = "dream", runRegistry = registry, subagentRepository = subagents),
            )

            val run = registry.runs.value.getValue(CONVERSATION)
            assertEquals(1, run.subagentCount)
            assertTrue(run.dreaming)
            assertTrue(registry.runs.value.presenceByAgent().getValue(agent.id.value).dreaming)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    private companion object {
        const val CONVERSATION = "conversation-dream"
    }
}
