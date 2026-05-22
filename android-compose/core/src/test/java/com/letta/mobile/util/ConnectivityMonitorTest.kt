package com.letta.mobile.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.testutil.InMemorySecureSettingsStore
import com.letta.mobile.testutil.createTestPreferencesDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class ConnectivityMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `stopMonitoring cancels active config collector and restart creates a single replacement`() {
        val settingsRepository = createSettingsRepository()
        val apiClient = mockk<LettaApiClient>(relaxed = true) {
            every { getBaseUrl() } returns "https://example.com/"
        }

        var monitor: ConnectivityMonitor? = null
        try {
            monitor = ConnectivityMonitor(context, settingsRepository, apiClient)
            val startMonitoring = ConnectivityMonitor::class.java.getDeclaredMethod("startMonitoring").apply {
                isAccessible = true
            }
            val stopMonitoring = ConnectivityMonitor::class.java.getDeclaredMethod("stopMonitoring").apply {
                isAccessible = true
            }

            var previousJob = activeConfigCollectorJob(monitor)
            assertNotNull(previousJob)
            assertTrue(previousJob!!.isActive)

            repeat(3) {
                stopMonitoring.invoke(monitor)
                assertTrue(previousJob!!.isCancelled)
                assertNull(activeConfigCollectorJob(monitor))

                startMonitoring.invoke(monitor)
                val replacementJob = activeConfigCollectorJob(monitor)
                assertNotNull(replacementJob)
                assertTrue(replacementJob!!.isActive)
                assertNotSame(previousJob, replacementJob)
                previousJob = replacementJob
            }
        } finally {
            monitor?.release()
        }
    }

    @Test
    fun `checkServerReachability reuses LettaApiClient client and marks server reachable`() {
        val settingsRepository = createSettingsRepository()
        runBlocking {
            settingsRepository.saveConfig(
                LettaConfig(
                    id = "cfg-1",
                    mode = LettaConfig.Mode.CLOUD,
                    serverUrl = "https://example.com",
                    accessToken = "token"
                )
            )
        }

        var requestedUrl: String? = null
        val requestLatch = CountDownLatch(1)
        val httpClient = HttpClient(MockEngine { request ->
            requestedUrl = request.url.toString()
            requestLatch.countDown()
            respond("{}", HttpStatusCode.OK)
        })
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns httpClient
            every { getBaseUrl() } returns "https://example.com/"
        }

        var monitor: ConnectivityMonitor? = null
        try {
            monitor = ConnectivityMonitor(context, settingsRepository, apiClient)

            val checkMethod = ConnectivityMonitor::class.java.getDeclaredMethod("checkServerReachability")
            checkMethod.isAccessible = true
            checkMethod.invoke(monitor)

            val currentMonitor = monitor
            assertTrue(requestLatch.await(2, TimeUnit.SECONDS))
            assertTrue(waitForCondition(timeoutMillis = 2_000) { currentMonitor.isServerReachable.value })

            assertTrue(requestedUrl == "https://example.com/v1/agents?limit=1")
            io.mockk.verify(exactly = 1) { apiClient.getBaseUrl() }
            coVerify(exactly = 1) { apiClient.getClient() }
        } finally {
            monitor?.release()
            httpClient.close()
        }
    }

    private fun createSettingsRepository(): SettingsRepository = SettingsRepository(
        dataStore = createTestPreferencesDataStore(),
        secureSettingsStore = InMemorySecureSettingsStore(),
    )

    private fun activeConfigCollectorJob(monitor: ConnectivityMonitor): Job? {
        val field = ConnectivityMonitor::class.java.getDeclaredField("activeConfigCollectorJob")
        field.isAccessible = true
        return field.get(monitor) as Job?
    }

    private fun waitForCondition(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(25)
        }
        return condition()
    }
}
