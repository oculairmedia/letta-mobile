package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.LettaConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LettaHttpChatGatewayCredentialFilterTest {
    @Test
    fun returnsFullNormalizedCatalogWithoutCallingProviders() = runTest {
        var providersCalled = false
        val gateway = gateway(onProvidersRequested = { providersCalled = true })

        val models = gateway.listLlmModels()

        assertEquals(ALL_MODEL_IDS, models.map { it.id })
        assertFalse(providersCalled, "listLlmModels must not query /v1/providers")
    }

    private fun gateway(onProvidersRequested: () -> Unit = {}): DesktopLettaHttpChatGateway {
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/models" -> respond(
                    content = MODELS_JSON,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                "/v1/providers" -> {
                    onProvidersRequested()
                    respond(
                        content = "server error",
                        status = HttpStatusCode.InternalServerError,
                    )
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }) {
            install(ContentNegotiation) {
                json(desktopChatJson)
            }
        }
        return DesktopLettaHttpChatGateway(
            config = LettaConfig(
                id = "local",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "http://localhost:8283",
            ),
            httpClient = client,
        )
    }

    private companion object {
        val ALL_MODEL_IDS = listOf("openai/gpt-4o", "lmstudio/qwen", "anthropic/claude")

        val MODELS_JSON = """
            [
              {"id":"openai/gpt-4o","name":"GPT-4o","handle":"openai/gpt-4o","provider_type":"openai"},
              {"id":"lmstudio/qwen","name":"Qwen","handle":"lmstudio/qwen","provider_type":"lmstudio"},
              {"id":"anthropic/claude","name":"Claude","handle":"anthropic/claude","provider_type":"anthropic"}
            ]
        """.trimIndent()
    }
}
