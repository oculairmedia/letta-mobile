package com.letta.mobile.data.api

import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityUpdateParams
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.engine.mock.HttpResponseData)): IdentityApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val apiClient = object : LettaApiClient(null!!) {
            override fun getClient() = client
            override fun getBaseUrl() = "http://test"
        }
        return IdentityApi(apiClient)
    }

    @Test
    fun `listIdentities sends GET`() = runTest {
        var url: String? = null
        val api = createApi { req -> url = req.url.toString(); respond("[]", HttpStatusCode.OK, jsonHeaders) }

        api.listIdentities()

        assertTrue(url!!.contains("/v1/identities/"))
    }

    @Test
    fun `createIdentity sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"identity-1","identifier_key":"user-1","name":"User One","identity_type":"user","agent_ids":[],"block_ids":[],"properties":[]}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.createIdentity(IdentityCreateParams(identifierKey = "user-1", name = "User One", identityType = "user"))

        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `updateIdentity sends PATCH`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"identity-1","identifier_key":"user-1","name":"Updated","identity_type":"user","agent_ids":[],"block_ids":[],"properties":[]}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.updateIdentity("identity-1", IdentityUpdateParams(name = "Updated"))

        assertEquals(HttpMethod.Patch, method)
    }

    @Test
    fun `deleteIdentity sends DELETE`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("{}", HttpStatusCode.OK, jsonHeaders) }

        api.deleteIdentity("identity-1")

        assertEquals(HttpMethod.Delete, method)
    }

    @Test(expected = ApiException::class)
    fun `listIdentities throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }
        api.listIdentities()
    }
}
