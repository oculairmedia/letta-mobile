package com.letta.mobile.runtime.local.modelcatalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class EmbeddedModelDownloadAuthTest {
    @Test
    fun isHuggingFaceUrl_matchesOnlyHuggingFaceHosts() {
        assertTrue("https://huggingface.co/google/model/resolve/main/model.litertlm".isHuggingFaceUrl())
        assertFalse("https://example.com/google/model/resolve/main/model.litertlm".isHuggingFaceUrl())
        assertFalse("https://huggingface.co.evil.test/google/model".isHuggingFaceUrl())
    }

    @Test
    fun authorizationHeaderInjectedOnlyForHuggingFaceUrls() = runTest {
        val token = "hf_secret_token"
        val seenAuthorization = mutableMapOf<String, String?>()
        val client = HttpClient(
            MockEngine { request ->
                seenAuthorization[request.url.host] = request.headers[HttpHeaders.Authorization]
                respond(
                    content = "ok",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, "2"),
                )
            }
        )

        client.get("https://huggingface.co/google/model") {
            if ("https://huggingface.co/google/model".isHuggingFaceUrl()) bearerAuth(token)
        }
        client.get("https://example.com/google/model") {
            if ("https://example.com/google/model".isHuggingFaceUrl()) bearerAuth(token)
        }

        assertEquals("Bearer $token", seenAuthorization["huggingface.co"])
        assertNull(seenAuthorization["example.com"])
    }

    @Test
    fun huggingFace401And403MapToAuthRequiredWithoutTokenLeak() {
        val secret = "hf_secret_token"
        val message401 = embeddedModelDownloadFailureMessage(
            "https://huggingface.co/google/gemma/resolve/main/model.litertlm?token=$secret",
            401,
        )
        val message403 = embeddedModelDownloadFailureMessage(
            "https://huggingface.co/google/gemma/resolve/main/model.litertlm",
            403,
        )

        assertEquals("Requires Hugging Face access token.", message401)
        assertEquals("Requires Hugging Face access token.", message403)
        assertFalse(message401.contains(secret))
    }

    @Test
    fun nonHuggingFace401KeepsGenericHttpFailure() {
        assertEquals(
            "Download failed with HTTP 401.",
            embeddedModelDownloadFailureMessage("https://example.com/model.litertlm", 401),
        )
    }
}
