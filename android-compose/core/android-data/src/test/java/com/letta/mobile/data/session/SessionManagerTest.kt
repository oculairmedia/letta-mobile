package com.letta.mobile.data.session

import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)

// letta-mobile-g2ff0: tests must wrap DAOs in dagger.Lazy because
// production constructor now takes Lazy<AgentDao> / Lazy<ConversationDao>.
private fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }
class SessionManagerTest {

    @Test
    fun `active config change rebuilds session graph and cancels previous scope`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory {
                this.settingsRepository = settingsRepository
            },
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        val secondGraph = sessionManager.current
        assertNotEquals(firstGraph.id, secondGraph.id)
        assertTrue(firstGraph.scope.coroutineContext.job.isCancelled)
        org.junit.Assert.assertFalse(secondGraph.scope.coroutineContext.job.isCancelled)
    }

    @Test
    fun `transport adjacent state holders are recreated when graph rebuilds`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory(),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        val secondGraph = sessionManager.current
        assertNotEquals(System.identityHashCode(firstGraph.channelTransport), System.identityHashCode(secondGraph.channelTransport))
        assertNotEquals(System.identityHashCode(firstGraph.cronRepository), System.identityHashCode(secondGraph.cronRepository))
        assertNotEquals(
            System.identityHashCode(firstGraph.vibesyncEventStreamRepository),
            System.identityHashCode(secondGraph.vibesyncEventStreamRepository),
        )
    }

    @Test
    fun `same backend config emission does not rebuild graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory {
                this.settingsRepository = settingsRepository
            },
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = LettaConfig(id = "backend-b", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://backend-a.example.test")
        advanceUntilIdle()

        assertEquals(System.identityHashCode(firstGraph), System.identityHashCode(sessionManager.current))
        assertEquals(System.identityHashCode(firstGraph.channelTransport), System.identityHashCode(sessionManager.current.channelTransport))
    }

    @Test
    fun `embedded local model selection on same config id rebuilds session graph`() = runTest {
        // letta-mobile-mlyhq: model selection edits the same config id, which
        // the id-distinct activeConfigChanges never emitted; graph must rebuild.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = localConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory {
                this.settingsRepository = settingsRepository
            },
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = localConfig("backend-a").copy(
            localModelPath = "/data/user/0/ca.oculair.meridian.dev/files/embedded-models/gemma.litertlm",
            localModelHandle = "google/gemma-3n-E2B-it-litert-lm",
        )
        advanceUntilIdle()

        val secondGraph = sessionManager.current
        assertNotEquals(firstGraph.id, secondGraph.id)
        assertTrue(firstGraph.scope.coroutineContext.job.isCancelled)
        assertTrue(!secondGraph.scope.coroutineContext.job.isCancelled)
    }

    private fun localConfig(
        id: String,
        serverUrl: String = "local://device",
    ): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = serverUrl,
    )
}
