package com.letta.mobile.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.util.EncryptedPrefsHelper
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
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
    fun `checkServerReachability reuses LettaApiClient client and marks server reachable`() {
        val sharedPreferences = context.getSharedPreferences("connectivity-monitor-test", Context.MODE_PRIVATE)
        sharedPreferences.edit().clear().commit()
        mockkObject(EncryptedPrefsHelper)

        try {
            every { EncryptedPrefsHelper.getEncryptedPrefs(any()) } returns sharedPreferences

            val settingsRepository = SettingsRepository(context)
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

                assertTrue(requestLatch.await(2, TimeUnit.SECONDS))
                assertTrue(waitForCondition(timeoutMillis = 2_000) { monitor!!.isServerReachable.value })

                assertTrue(requestedUrl == "https://example.com/v1/agents?limit=1")
                io.mockk.verify(exactly = 1) { apiClient.getBaseUrl() }
                coVerify(exactly = 1) { apiClient.getClient() }
            } finally {
                monitor?.release()
                httpClient.close()
            }
        } finally {
            unmockkObject(EncryptedPrefsHelper)
        }
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
