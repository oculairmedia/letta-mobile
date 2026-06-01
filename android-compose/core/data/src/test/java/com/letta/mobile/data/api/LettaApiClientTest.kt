package com.letta.mobile.data.api

import android.content.Context
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.testutil.FakeSettingsRepository
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class LettaApiClientTest {
    @Test
    fun `session returns coherent client and base url after backend switch`() = runTest {
        val cacheDir = Files.createTempDirectory("letta-api-client-test").toFile()
        val context = mockk<Context> {
            every { this@mockk.cacheDir } returns cacheDir
        }
        val settings = FakeSettingsRepository(
            initialActiveConfig = config(
                id = "one",
                serverUrl = "https://one.example/",
                token = "token-one",
            ),
        )
        val apiClient = LettaApiClient(context, settings)

        try {
            val first = apiClient.session()
            assertEquals("https://one.example", first.baseUrl)

            settings.activeConfigState.value = config(
                id = "two",
                serverUrl = "https://two.example/",
                token = "token-two",
            )

            val second = apiClient.session()
            assertEquals("https://two.example", second.baseUrl)
            assertNotSame(first.client, second.client)
        } finally {
            apiClient.invalidateClient()
            cacheDir.deleteRecursively()
        }
    }

    private fun config(
        id: String,
        serverUrl: String,
        token: String,
    ) = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = serverUrl,
        accessToken = token,
    )
}
