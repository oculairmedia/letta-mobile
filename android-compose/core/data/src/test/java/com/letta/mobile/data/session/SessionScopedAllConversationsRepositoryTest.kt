package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)

// letta-mobile-g2ff0: tests must wrap DAOs in dagger.Lazy because
// production constructor now takes Lazy<AgentDao> / Lazy<ConversationDao>.
private fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }
class SessionScopedAllConversationsRepositoryTest {

    @Test
    fun `all conversations repository proxy switches cache to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", summary = "Backend A"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                lazyOf(FakeAgentDao()),
                fakeConversationApi,
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
        val conversationsProxy = SessionScopedAllConversationsRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        conversationsProxy.refresh()
        advanceUntilIdle()
        assertEquals(listOf("conv-a"), conversationsProxy.conversations.value.map { it.id.value })

        fakeConversationApi.conversations = mutableListOf(TestData.conversation(id = "conv-b", summary = "Backend B"))
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        conversationsProxy.refresh()
        advanceUntilIdle()

        assertEquals(listOf("conv-b"), conversationsProxy.conversations.value.map { it.id.value })
        assertTrue(conversationsProxy.hasFreshConversations(maxAgeMs = 60_000))
    }

    @Test
    fun `conversation refresh retries when session graph rebuilds mid-operation`() = runTest {
        // letta-mobile-xzoy3: activeConfigChanges emits while the collector
        // refresh is mid-flight; the rebuild lands before the op finishes and
        // withCurrentSession's post-check throws CancellationException("Session
        // switched during operation"). The retry must re-run on the rebuilt graph.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-b", summary = "Backend B"))
            listDelayMillis = 50L
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                lazyOf(FakeAgentDao()),
                fakeConversationApi,
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
        val conversationsProxy = SessionScopedAllConversationsRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        var error: Throwable? = null
        val refreshJob = launch { error = runCatching { conversationsProxy.refresh() }.exceptionOrNull() }
        advanceTimeBy(1)
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceTimeBy(1)
        advanceUntilIdle()
        refreshJob.join()

        assertNull(error)
        assertEquals(listOf("conv-b"), conversationsProxy.conversations.value.map { it.id.value })
        assertEquals(2, fakeConversationApi.calls.count { it == "listConversations" })
    }

    @Test
    fun `conversation refresh survives stale transport error after session rebuild`() = runTest {
        // letta-mobile-xzoy3: the rebuild lands while the refresh runs on the
        // old graph; the stale transport throws the default-transport ISE. The
        // retry waits for the new graph and re-runs the refresh there.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-b", summary = "Backend B"))
            listDelayMillis = 50L
            staleTransportIaeCountdown = 1
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                lazyOf(FakeAgentDao()),
                fakeConversationApi,
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
        val conversationsProxy = SessionScopedAllConversationsRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        var error: Throwable? = null
        val refreshJob = launch { error = runCatching { conversationsProxy.refresh() }.exceptionOrNull() }
        advanceTimeBy(1)
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceTimeBy(1)
        advanceUntilIdle()
        refreshJob.join()

        assertNull(error)
        assertEquals(listOf("conv-b"), conversationsProxy.conversations.value.map { it.id.value })
        assertEquals(2, fakeConversationApi.calls.count { it == "listConversations" })
    }

    @Test
    fun `conversation refresh rethrows stale transport error when session graph never settles`() = runTest {
        // letta-mobile-xzoy3: stale-transport error with no graph change within
        // the 300ms settle window -> original error surfaces (no silent no-op).
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", summary = "Backend A"))
            staleTransportIaeCountdown = Int.MAX_VALUE
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                lazyOf(FakeAgentDao()),
                fakeConversationApi,
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
        val conversationsProxy = SessionScopedAllConversationsRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        var error: Throwable? = null
        val refreshJob = launch { error = runCatching { conversationsProxy.refresh() }.exceptionOrNull() }
        advanceUntilIdle()
        refreshJob.join()

        assertTrue(error is IllegalStateException)
        assertEquals("admin_rpc is not supported by this transport", error?.message)
        assertEquals(1, fakeConversationApi.calls.count { it == "listConversations" })
    }

    @Test
    fun `conversation refresh abandons after retry budget when session graph keeps switching`() = runTest {
        // letta-mobile-xzoy3: bounded-retry guard — every attempt's post-check
        // fires because a fresh rebuild lands mid-operation; after
        // MAX_SESSION_SWITCH_RETRIES the refresh must stop and throw explicitly.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeConversationApi = FakeConversationApi().apply {
            conversations = mutableListOf(TestData.conversation(id = "conv-a", summary = "Backend A"))
            listDelayMillis = 200L
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                lazyOf(FakeAgentDao()),
                fakeConversationApi,
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
        val conversationsProxy = SessionScopedAllConversationsRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        var error: Throwable? = null
        val refreshJob = launch { error = runCatching { conversationsProxy.refresh() }.exceptionOrNull() }
        // three attempts, each interrupted mid-flight by a fresh rebuild
        advanceTimeBy(1)
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceTimeBy(1)
        advanceTimeBy(201)
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-c")
        advanceTimeBy(1)
        advanceTimeBy(201)
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-d")
        advanceTimeBy(1)
        advanceTimeBy(201)
        advanceUntilIdle()
        refreshJob.join()

        assertTrue(error is IllegalStateException)
        assertEquals(
            "Session graph kept switching; refresh abandoned after 3 attempts",
            error?.message,
        )
        assertEquals(3, fakeConversationApi.calls.count { it == "listConversations" })
    }
}
