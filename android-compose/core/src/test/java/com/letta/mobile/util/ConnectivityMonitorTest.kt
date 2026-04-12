package com.letta.mobile.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ConnectivityMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `checkServerReachability reuses LettaApiClient client and marks server reachable`() {
        val activeConfigFlow = MutableStateFlow(
            LettaConfig(
                id = "cfg-1",
                mode = LettaConfig.Mode.CLOUD,
                serverUrl = "https://example.com",
                accessToken = "token"
            )
        )
        val settingsRepository = mockk<SettingsRepository> {
            every { activeConfig } returns activeConfigFlow
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

        val monitor = ConnectivityMonitor(context, settingsRepository, apiClient)

        val checkMethod = ConnectivityMonitor::class.java.getDeclaredMethod("checkServerReachability")
        checkMethod.isAccessible = true
        checkMethod.invoke(monitor)

        assertTrue(requestLatch.await(2, TimeUnit.SECONDS))
        assertTrue(waitForCondition(timeoutMillis = 2_000) { monitor.isServerReachable.value })

        assertTrue(requestedUrl == "https://example.com/v1/agents?limit=1")
        io.mockk.verify(exactly = 1) { apiClient.getBaseUrl() }
        coVerify(exactly = 1) { apiClient.getClient() }

        httpClient.close()
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
