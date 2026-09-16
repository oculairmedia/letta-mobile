package com.letta.mobile.feature.chat.screen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.letta.mobile.data.health.ShimBackendDetector
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.feature.chat.route.ChatRouteArgs
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.testutil.TestData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminChatViewModelStartupTest {
    @Test
    fun `immediate constructor startup opens and retains canonical presentation`() = runTest {
        // Unlike StandardTestDispatcher, Main.immediate must enter launch bodies
        // during construction, with cached repository results that never suspend.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModel: AdminChatViewModel? = null
        try {
            assertFalse(Dispatchers.Main.immediate.isDispatchNeeded(coroutineContext))
            val agent = TestData.agent("agent-startup", "Startup")
            var constructorReturned = false
            var opens = 0
            var closes = 0
            val presentation = ChatPagingPresentation(
                settled = flowOf(PagingData.empty()),
                live = MutableStateFlow(emptyList()),
                close = { closes++ },
            )
            val host = ChatPagingHost().apply {
                openCanonical = { agentId, conversationId, target, _ ->
                    if (opens == 0) {
                        assertFalse("Opening must happen inside the constructor", constructorReturned)
                    } else {
                        assertEquals("Replacing must retire the constructor's job", 1, closes)
                    }
                    assertEquals(agent.id.value, agentId)
                    assertEquals("conversation-startup", conversationId)
                    assertEquals(null, target)
                    opens++
                    presentation
                }
            }
            val vm = openedChatViewModel(host, agent, "conversation-startup", "startup")
            viewModel = vm
            constructorReturned = true
            assertEquals(1, opens)
            assertEquals(ConversationId("conversation-startup"), vm.conversationId)
            assertSame(presentation, vm.pagingPresentation.value)
            assertTrue(presentation.hasBoundRoute)

            // Exercise the initialized viewport map and retained route/job after
            // construction. A late null initializer must not erase the live job.
            presentation.saveViewport(ChatPagingViewport("message-1", 12))
            presentation.clearViewport()
            presentation.requestTail()
            assertEquals(2, opens)
            assertSame(presentation, vm.pagingPresentation.value)
            vm.viewModelScope.cancel()
            assertEquals("Both presentation jobs must remain owned", 2, closes)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }
}
