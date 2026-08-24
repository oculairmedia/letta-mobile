package com.letta.mobile.data.api

import android.content.Context
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.testutil.FakeSettingsRepository
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.fail
import org.junit.Test

class LettaApiClientTest {
    @Test
    fun `session uses last valid admin url when active config is local lettacode`() = runTest {
        val cacheDir = Files.createTempDirectory("letta-api-client-local-test").toFile()
        val context = mockk<Context> {
            every { this@mockk.cacheDir } returns cacheDir
        }
        val remoteConfig = config(
            id = "remote",
            serverUrl = "https://admin.example/",
            token = "remote-token",
        )
        val localConfig = LettaConfig(
            id = "local",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "local-lettacode://device",
        )
        val settings = FakeSettingsRepository(initialActiveConfig = localConfig).apply {
            configsState.value = listOf(remoteConfig, localConfig)
        }
        val apiClient = LettaApiClient(context, settings)

        try {
            val session = apiClient.session()

            assertEquals("https://admin.example", session.baseUrl)
            assertEquals("https://admin.example/", apiClient.getBaseUrl())
        } finally {
            apiClient.invalidateClient()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `session reports local runtime unsupported when no valid admin url exists`() = runTest {
        val cacheDir = Files.createTempDirectory("letta-api-client-local-disabled-test").toFile()
        val context = mockk<Context> {
            every { this@mockk.cacheDir } returns cacheDir
        }
        val localConfig = LettaConfig(
            id = "local",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "local-lettacode://device",
        )
        val settings = FakeSettingsRepository(initialActiveConfig = localConfig)
        val apiClient = LettaApiClient(context, settings)

        try {
            apiClient.session()
            fail("Expected local runtime admin API exception")
        } catch (e: AdminApiUnavailableForLocalRuntimeException) {
            assertFalse(e.message.orEmpty().contains("Expected URL scheme"))
            assertFalse(e.message.orEmpty().contains("local-lettacode"))
        } finally {
            apiClient.invalidateClient()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `session hard-fails with typed iroh error when active config is iroh and http configs exist`() = runTest {
        val cacheDir = Files.createTempDirectory("letta-api-client-iroh-test").toFile()
        val context = mockk<Context> {
            every { this@mockk.cacheDir } returns cacheDir
        }
        // A stale HTTP admin config is present in the list — the choke point
        // MUST NOT silently fall back to it while iroh:// is the active backend.
        val staleHttp = config(
            id = "stale-http",
            serverUrl = "https://stale.example/",
            token = "stale-token",
        )
        val irohConfig = LettaConfig(
            id = "iroh",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "iroh://EndpointTicketAbc123",
        )
        val settings = FakeSettingsRepository(initialActiveConfig = irohConfig).apply {
            configsState.value = listOf(staleHttp, irohConfig)
        }
        val apiClient = LettaApiClient(context, settings)

        try {
            apiClient.session()
            fail("Expected IrohAdminApiUnavailableException")
        } catch (e: IrohAdminApiUnavailableException) {
            assertEquals(true, e.message.orEmpty().contains("iroh://EndpointTicketAbc123"))
        } finally {
            apiClient.invalidateClient()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `session hard-fails for corrupted https-prefixed iroh url instead of treating it as http`() = runTest {
        val cacheDir = Files.createTempDirectory("letta-api-client-iroh-corrupt-test").toFile()
        val context = mockk<Context> {
            every { this@mockk.cacheDir } returns cacheDir
        }
        // `https://iroh://<ticket>` is a known corrupted-config shape: it
        // startsWith "https://" but is still an Iroh endpoint. The iroh guard
        // must win over the http-admin check.
        val corruptedIroh = LettaConfig(
            id = "iroh-corrupt",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "https://iroh://EndpointTicketXyz",
        )
        val settings = FakeSettingsRepository(initialActiveConfig = corruptedIroh)
        val apiClient = LettaApiClient(context, settings)

        try {
            apiClient.session()
            fail("Expected IrohAdminApiUnavailableException")
        } catch (e: IrohAdminApiUnavailableException) {
            assertFalse(e.message.orEmpty().isBlank())
        } finally {
            apiClient.invalidateClient()
            cacheDir.deleteRecursively()
        }
    }

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
    ): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = serverUrl,
        accessToken = token,
    )
}
