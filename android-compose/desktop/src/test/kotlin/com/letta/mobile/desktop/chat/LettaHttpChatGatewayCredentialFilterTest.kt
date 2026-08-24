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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LettaHttpChatGatewayCredentialFilterTest {
    @Test
    fun filtersModelsToCredentialedProviderTypes() = runTest {
        val gateway = gateway(providersJson = providersJson("openai", "lmstudio"))

        val models = gateway.listLlmModels()

        assertEquals(listOf("openai/gpt-4o", "lmstudio/qwen"), models.map { it.id })
    }

    @Test
    fun providerFetchFailureKeepsAllModels() = runTest {
        val gateway = gateway(providersJson = null)

        val models = gateway.listLlmModels()

        assertEquals(ALL_MODEL_IDS, models.map { it.id })
    }

    @Test
    fun emptyProviderListKeepsAllModels() = runTest {
        val gateway = gateway(providersJson = "[]")

        val models = gateway.listLlmModels()

        assertEquals(ALL_MODEL_IDS, models.map { it.id })
    }

    @Test
    fun fullProviderPageWithoutUsableCursorKeepsAllModels() = runTest {
        val gateway = gateway(providersJson = fullProviderPageWithBlankFinalId())

        val models = gateway.listLlmModels()

        assertEquals(ALL_MODEL_IDS, models.map { it.id })
    }

    @Test
    fun providerFetchCancellationPropagates() = runTest {
        val gateway = gateway(providersJson = null, providersCancellation = true)

        assertFailsWith<CancellationException> {
            gateway.listLlmModels()
        }
    }

    private fun gateway(providersJson: String?, providersCancellation: Boolean = false): DesktopLettaHttpChatGateway {
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/models" -> respond(
                    content = MODELS_JSON,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                "/v1/providers" -> {
                    if (providersCancellation) {
                        throw CancellationException("provider lookup cancelled")
                    } else if (providersJson == null) {
                        respond(
                            content = "server error",
                            status = HttpStatusCode.InternalServerError,
                        )
                    } else {
                        respond(
                            content = providersJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
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

    private fun providersJson(vararg types: String): String {
        val entries = types.joinToString(",") { type ->
            """
                {"id":"provider-$type","name":"$type","provider_type":"$type","api_key":"sk-test","base_url":"https://$type.example/v1"}
            """.trimIndent()
        }
        return "[$entries]"
    }

    private fun fullProviderPageWithBlankFinalId(): String {
        val entries = (0 until 100).joinToString(",") { index ->
            val id = if (index == 99) "" else "provider-$index"
            """{"id":"$id","name":"provider-$index","provider_type":"type-$index"}"""
        }
        return "[$entries]"
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
