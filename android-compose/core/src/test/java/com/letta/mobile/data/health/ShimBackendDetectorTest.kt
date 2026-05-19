package com.letta.mobile.data.health

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimBackendDetectorTest {

    @Test
    fun `refreshActive detects letta-code admin shim health marker`() = runTest {
        val config = config(id = "shim")
        val detector = detector(
            settingsRepository = FakeSettingsRepository(initialActiveConfig = config),
            responseBody = """
                {
                  "version":"shim-0.2.0",
                  "status":"ok",
                  "server_id":"server-1",
                  "backend":"letta-code-local"
                }
            """.trimIndent(),
        )

        assertTrue(detector.refreshActive())
        assertTrue(detector.states.value.getValue(config.id))
    }

    @Test
    fun `refreshActive keeps vanilla backend on REST path`() = runTest {
        val config = config(id = "vanilla")
        val detector = detector(
            settingsRepository = FakeSettingsRepository(initialActiveConfig = config),
            responseBody = """
                { "version":"0.11.0", "status":"ok", "backend":"letta-server" }
            """.trimIndent(),
        )

        assertFalse(detector.refreshActive())
        assertFalse(detector.states.value.getValue(config.id))
    }

    @Test
    fun `refresh caches result per active config id`() = runTest {
        var requests = 0
        val config = config(id = "cached")
        val detector = detector(
            settingsRepository = FakeSettingsRepository(initialActiveConfig = config),
            responseBody = """{ "version":"shim-0.2.0", "backend":"letta-code-local" }""",
            onRequest = { requests++ },
        )

        assertTrue(detector.refreshActive())
        assertTrue(detector.refreshActive())
        assertEquals(1, requests)
    }

    private fun detector(
        settingsRepository: ISettingsRepository,
        responseBody: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        onRequest: () -> Unit = {},
    ): ShimBackendDetector {
        val client = HttpClient(MockEngine { request ->
            onRequest()
            assertEquals("/v1/health", request.url.encodedPath)
            respond(responseBody, status, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
        }
        return ShimBackendDetector(settingsRepository, apiClient)
    }

    private fun config(id: String) = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = "http://localhost:8291",
        accessToken = "token",
    )

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
