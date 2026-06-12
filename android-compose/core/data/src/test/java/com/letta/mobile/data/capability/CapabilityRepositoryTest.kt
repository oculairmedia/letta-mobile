package com.letta.mobile.data.capability

import com.letta.mobile.data.api.AdminApiUnavailableForLocalRuntimeException
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityRepositoryTest {
    @Test
    fun `local runtime config skips project probe and defaults unsupported`() = runTest {
        val settings = FakeSettingsRepository(initialActiveConfig = localConfig())
        val projectApi = FakeProjectApi { error("local config must not probe Admin API") }
        val repository = CapabilityRepository(
            settings,
            projectApi,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertFalse(repository.projectsSupported.value)
        assertEquals(0, projectApi.probeCount)
    }

    @Test
    fun `remote config still probes project capability`() = runTest {
        val settings = FakeSettingsRepository(initialActiveConfig = remoteConfig())
        val projectApi = FakeProjectApi { true }
        val repository = CapabilityRepository(
            settings,
            projectApi,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertTrue(repository.projectsSupported.value)
        assertEquals(1, projectApi.probeCount)
    }

    @Test
    fun `probe exception marks projects unsupported without escaping collector`() = runTest {
        val settings = FakeSettingsRepository(initialActiveConfig = remoteConfig(id = "remote-1"))
        val projectApi = FakeProjectApi { throw AdminApiUnavailableForLocalRuntimeException() }
        val repository = CapabilityRepository(
            settings,
            projectApi,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()
        settings.activeConfigState.value = remoteConfig(id = "remote-2")
        advanceUntilIdle()

        assertFalse(repository.projectsSupported.value)
        assertEquals(2, projectApi.probeCount)
    }

    private class FakeProjectApi(
        private val probeResult: suspend () -> Boolean,
    ) : ProjectApi(apiClient = mockk<LettaApiClient>()) {
        var probeCount = 0

        override suspend fun probeAvailability(): Boolean {
            probeCount += 1
            return probeResult()
        }
    }

    private fun localConfig() = LettaConfig(
        id = "local",
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local-lettacode://device",
    )

    private fun remoteConfig(id: String = "remote") = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = "https://admin.example.test",
    )
}
