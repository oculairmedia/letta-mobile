package com.letta.mobile.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolSchemaGenerateParams
import com.letta.mobile.data.model.ToolUpdateParams
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ToolApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData)): ToolApi {
        val client = trackClient(HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }) } })
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
            coEvery { session() } returns ApiSession(client, "http://test")
        }
        return ToolApi(apiClient)
    }

    @Test
    fun `listTools sends GET`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("[]", HttpStatusCode.OK, jsonHeaders) }
        api.listTools()
        assertEquals(HttpMethod.Get, method)
    }

    @Test
    fun `countTools sends GET to count endpoint`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("3", HttpStatusCode.OK, jsonHeaders)
        }

        val count = api.countTools()

        assertEquals(3, count)
        assertTrue(url!!.endsWith("/v1/tools/count"))
    }

    @Test
    fun `attachTool sends PATCH`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("", HttpStatusCode.OK, jsonHeaders) }
        api.attachTool("a1", "t1")
        assertEquals(HttpMethod.Patch, method)
    }

    @Test
    fun `detachTool sends PATCH`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("", HttpStatusCode.OK, jsonHeaders) }
        api.detachTool("a1", "t1")
        assertEquals(HttpMethod.Patch, method)
    }

    @Test
    fun `upsertTool omits name and sends schema fields`() = runTest {
        var body: String? = null
        val api = createApi { req ->
            body = requestBody(req.body)
            respond("{\"id\":\"tool-1\",\"name\":\"weather_lookup\"}", HttpStatusCode.OK, jsonHeaders)
        }

        api.upsertTool(
            ToolCreateParams(
                sourceCode = "def weather_lookup(city: str) -> str:\n    return city",
                sourceType = "python",
                jsonSchema = buildJsonObject { put("name", "weather_lookup") },
            )
        )

        val payload = Json.parseToJsonElement(body!!).jsonObject

        assertTrue(payload.containsKey("source_code"))
        assertEquals("python", payload["source_type"]?.toString()?.trim('"'))
        assertTrue(payload.containsKey("json_schema"))
        assertFalse(payload.containsKey("name"))
    }

    @Test
    fun `updateTool omits name and sends schema fields`() = runTest {
        var body: String? = null
        val api = createApi { req ->
            body = requestBody(req.body)
            respond("{\"id\":\"tool-1\",\"name\":\"weather_lookup\"}", HttpStatusCode.OK, jsonHeaders)
        }

        api.updateTool(
            toolId = "tool-1",
            params = ToolUpdateParams(
                sourceCode = "def weather_lookup(city: str) -> str:\n    return city",
                sourceType = "python",
                jsonSchema = buildJsonObject { put("name", "weather_lookup") },
            )
        )

        val payload = Json.parseToJsonElement(body!!).jsonObject

        assertTrue(payload.containsKey("source_code"))
        assertEquals("python", payload["source_type"]?.toString()?.trim('"'))
        assertTrue(payload.containsKey("json_schema"))
        assertFalse(payload.containsKey("name"))
    }

    @Test
    fun `generateJsonSchema posts to schema endpoint`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond("{\"name\":\"weather_lookup\"}", HttpStatusCode.OK, jsonHeaders)
        }

        val schema = api.generateJsonSchema(
            ToolSchemaGenerateParams(
                code = "def weather_lookup(city: str) -> str:\n    return city",
            )
        )

        assertEquals(HttpMethod.Post, method)
        assertTrue(url!!.endsWith("/v1/tools/generate-schema"))
        assertEquals("weather_lookup", schema["name"]?.toString()?.trim('"'))
    }

    @Test(expected = ApiException::class)
    fun `listTools throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }
        api.listTools()
    }

    private fun requestBody(body: Any): String {
        val outgoing = body as OutgoingContent
        return when (outgoing) {
            is OutgoingContent.ByteArrayContent -> outgoing.bytes().decodeToString()
            is OutgoingContent.ReadChannelContent -> error("Unsupported request body type: ReadChannelContent")
            is OutgoingContent.WriteChannelContent -> error("Unsupported request body type: WriteChannelContent")
            is OutgoingContent.NoContent -> ""
            is OutgoingContent.ProtocolUpgrade -> error("Unsupported request body type: ProtocolUpgrade")
            else -> error("Unsupported request body type: ${outgoing::class.simpleName}")
        }
    }
}
