package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeArchiveApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeFolderApi
import com.letta.mobile.testutil.FakeGroupApi
import com.letta.mobile.testutil.FakeIdentityApi
import com.letta.mobile.testutil.FakeJobApi
import com.letta.mobile.testutil.FakeMcpServerApi
import com.letta.mobile.testutil.FakeModelApi
import com.letta.mobile.testutil.FakePassageApi
import com.letta.mobile.testutil.FakeProjectApi
import com.letta.mobile.testutil.FakeProjectWorkApi
import com.letta.mobile.testutil.FakeProviderApi
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeScheduleApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeStepApi
import com.letta.mobile.testutil.FakeToolApi
import com.letta.mobile.testutil.TestData
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)

// letta-mobile-g2ff0: tests must wrap DAOs in dagger.Lazy because
// production constructor now takes Lazy<AgentDao> / Lazy<ConversationDao>.
private fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }
class SessionScopedAgentRepositoryTest {

    @Test
    fun `agent repository proxy switches state and calls to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeApi = FakeAgentApi().apply {
            agents = mutableListOf(TestData.agent(id = "agent-a", name = "Backend A Agent"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createDefaultSessionRepositoryGraphFactory(
                fakeApi,
                lazyOf(FakeAgentDao()),
                FakeConversationApi(),
                lazyOf(FakeConversationDao()),
                FakeArchiveApi(),
                FakeFolderApi(),
                FakeGroupApi(),
                FakeIdentityApi(),
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
                appContext = mockk(relaxed = true),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val proxy = SessionScopedAgentRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        proxy.refreshAgents()
        advanceUntilIdle()
        assertEquals(AgentId("agent-a"), proxy.agents.value.single().id)

        fakeApi.agents = mutableListOf(TestData.agent(id = "agent-b", name = "Backend B Agent"))
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        proxy.refreshAgents()
        advanceUntilIdle()

        assertEquals(AgentId("agent-b"), proxy.agents.value.single().id)
        assertNull(proxy.getCachedAgent("agent-a"))
    }

    @Test
    fun `agent repository proxy listAgentSummaries hits slim path not interface default`() = runTest {
        // Fail-on-revert for the SessionScoped interface-default miss: without an
        // override, Hilt's SessionScopedAgentRepository would take
        // IAgentRepository.listAgentSummaries' deriving default (refreshAgents →
        // full listAgents) and never dial listAgentsSlim. Same defect class as
        // listConversationsForAgent on SessionScopedConversationRepository.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeApi = FakeAgentApi().apply {
            agents = mutableListOf(
                TestData.agent(id = "a1", name = "Agent One", description = "first"),
                TestData.agent(id = "a2", name = "Agent Two", description = null),
            )
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createDefaultSessionRepositoryGraphFactory(
                fakeApi,
                lazyOf(FakeAgentDao()),
                FakeConversationApi(),
                lazyOf(FakeConversationDao()),
                FakeArchiveApi(),
                FakeFolderApi(),
                FakeGroupApi(),
                FakeIdentityApi(),
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
                appContext = mockk(relaxed = true),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val proxy = SessionScopedAgentRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        val summaries = proxy.listAgentSummaries()
        advanceUntilIdle()

        assertEquals(listOf("a1", "a2"), summaries.map { it.id.value })
        assertEquals(listOf("Agent One", "Agent Two"), summaries.map { it.name })
        assertEquals("first", summaries[0].description)
        assertTrue(fakeApi.calls.contains("listAgentsSlim"))
        assertFalse(fakeApi.calls.contains("listAgents"))
        // Proxy's full-agent cache stays untouched — slim is a separate path.
        assertTrue(proxy.agents.value.isEmpty())
    }
}
