package com.letta.mobile.data.session

import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.toolsets.ToolsetRuntime
import com.letta.mobile.data.toolsets.ToolsetState
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeMcpServerApi
import com.letta.mobile.testutil.FakeModelApi
import com.letta.mobile.testutil.FakeProjectApi
import com.letta.mobile.testutil.FakeProviderApi
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeScheduleApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeStepApi
import com.letta.mobile.testutil.FakeToolApi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)

// letta-mobile-g2ff0: tests must wrap DAOs in dagger.Lazy because
// production constructor now takes Lazy<AgentDao> / Lazy<ConversationDao>.
private fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }
class SessionScopedChannelTransportTest {

    @Test
    fun `toolset states follow publications and graph switches`() = runTest {
        val runtime = ToolsetRuntime("agent-1", "conv-1")
        val initial = ToolsetState(emptyList(), "auto")
        val firstStates = MutableStateFlow(mapOf(runtime to initial))
        val secondStates = MutableStateFlow(mapOf(runtime to initial.copy(preference = "codex")))
        fun graph(states: StateFlow<Map<ToolsetRuntime, ToolsetState>>): SessionGraph {
            val transport = mockk<IChannelTransport>(relaxed = true)
            every { transport.state } returns MutableStateFlow(ChannelTransportState.Idle)
            every { transport.toolsetStates } returns states
            every { transport.events } returns MutableSharedFlow()
            every { transport.frameEvents } returns MutableSharedFlow()
            return mockk<SessionGraph>(relaxed = true).also { graph ->
                every { graph.channelTransport } returns transport
            }
        }
        val graphs = MutableStateFlow(graph(firstStates))
        val manager = mockk<SessionManager>(relaxed = true)
        every { manager.current } answers { graphs.value }
        every { manager.currentGraph } returns graphs
        val wrapper = SessionScopedChannelTransport(manager, backgroundScope, FrameCollectorOverflowReconciler { _, _ ->
            RecentMessagesReconcileOutcome.Applied(0)
        })
        runCurrent()
        assertEquals("auto", wrapper.toolsetStates.value[runtime]?.preference)
        firstStates.value = mapOf(runtime to initial.copy(preference = "first-update"))
        runCurrent()
        assertEquals("first-update", wrapper.toolsetStates.value[runtime]?.preference)
        graphs.value = graph(secondStates)
        runCurrent()
        assertEquals("codex", wrapper.toolsetStates.value[runtime]?.preference)
        firstStates.value = emptyMap()
        secondStates.value = mapOf(runtime to initial)
        runCurrent()
        assertEquals("auto", wrapper.toolsetStates.value[runtime]?.preference)
        wrapper.close()
    }

    @Test
    fun `channel transport proxy switches to rebuilt graph state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory(),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val proxy = SessionScopedChannelTransport(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
            overflowReconciler = FrameCollectorOverflowReconciler { _, _ ->
                RecentMessagesReconcileOutcome.Applied(0)
            },
        )
        advanceUntilIdle()

        assertTrue(proxy.state.value is ChannelTransportState.Idle)

        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        assertTrue(proxy.state.value is ChannelTransportState.Idle)
        proxy.close()
    }
}
