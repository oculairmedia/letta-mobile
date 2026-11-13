package com.letta.mobile.data.voice.session

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceSetupStoreTest {
    private val settings = InMemorySecureSettingsStore()
    private val secrets = InMemorySecureSettingsStore()
    private val store = VoiceSetupStore(settings, secrets)

    @Test
    fun aPlainUrlWithConsentGrantsBothPayloadsToItsOriginOnly() {
        assertNull(store.save(VoiceSetupDraft("http://127.0.0.1:8880", consent = true)))
        val setup = store.setup.value
        assertEquals(SpeechEndpointUrl("http://127.0.0.1:8880"), setup.endpoint.baseUrl)
        VoiceEndpointPayload.entries.forEach { assertTrue(setup.grants.allows(SpeechEndpointUrl("http://127.0.0.1:8880"), it)) }
        assertFalse(setup.grants.allows(SpeechEndpointUrl("http://127.0.0.1:9999"), VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION))
        assertNull(setup.gap)
    }

    @Test
    fun withoutConsentNothingIsGrantedAndAChangedAddressNeverInheritsConsent() {
        store.save(VoiceSetupDraft("http://127.0.0.1:8880", consent = true))
        store.save(VoiceSetupDraft("http://localhost:9000", consent = false))
        assertEquals(VoiceSetupGap.NO_CONSENT, store.setup.value.gap)
        assertTrue(store.setup.value.grants.grants.isEmpty())
    }

    @Test
    fun addressesThatAreNotPlainBaseUrlsAreRefusedAndNotStored() {
        listOf("", "not a url", "ftp://host", "https://host/?q=1", "https://host/#x", "https://user:pw@host").forEach { url ->
            assertEquals(VoiceErrorCode.INVALID_ENDPOINT, store.save(VoiceSetupDraft(url, consent = true)), url)
        }
        assertEquals(VoiceSetupGap.NO_ENDPOINT, store.setup.value.gap)
    }

    @Test
    fun aKeyOverPlainHttpToANonLoopbackHostIsRefused() {
        assertEquals(VoiceErrorCode.INSECURE_TRANSPORT, store.save(VoiceSetupDraft("http://192.168.1.20:8880", "sk-1", consent = true)))
        assertNull(store.save(VoiceSetupDraft("https://speech.example.net", "sk-1", consent = true)))
        // The stored key stays, so the LAN address is still refused until the key is blanked.
        assertEquals(VoiceErrorCode.INSECURE_TRANSPORT, store.save(VoiceSetupDraft("http://192.168.1.20:8880", consent = true)))
        assertNull(store.save(VoiceSetupDraft("http://192.168.1.20:8880", apiKey = "", consent = true)))
    }

    @Test
    fun theKeyLivesOnlyInTheSecretStoreAndBlankRemovesIt() {
        store.save(VoiceSetupDraft("https://speech.example.net", "sk-secret", consent = true))
        assertTrue(store.setup.value.hasApiKey)
        assertEquals("sk-secret", secrets.getString(VoiceSetupStore.KEY_API_KEY))
        assertNull(settings.getString(VoiceSetupStore.KEY_API_KEY))
        store.save(VoiceSetupDraft("https://speech.example.net", apiKey = null, consent = true))
        assertTrue(store.setup.value.hasApiKey, "null keeps the stored key")
        store.save(VoiceSetupDraft("https://speech.example.net", apiKey = "  ", consent = true))
        assertFalse(store.setup.value.hasApiKey)
        assertNull(store.apiKey())
    }

    @Test
    fun withoutASecureStoreTheKeyIsNeverKept() {
        val plain = InMemorySecureSettingsStore()
        val keyless = VoiceSetupStore(plain, secrets = null)
        assertFalse(keyless.keepsApiKey)
        assertNull(keyless.save(VoiceSetupDraft("http://192.168.1.20:8880", "sk-secret", consent = true)))
        assertNull(plain.getString(VoiceSetupStore.KEY_API_KEY))
        assertFalse(keyless.setup.value.hasApiKey)
    }

    @Test
    fun aDraftNeverPrintsItsKey() {
        assertFalse("sk-secret" in VoiceSetupDraft("https://x", "sk-secret").toString())
    }

    @Test
    fun theCheckOnlyAsksTheServerForItsModels() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("", HttpStatusCode.NotFound)
        }
        val result = withContext(Dispatchers.Default) { store.check(VoiceSetupDraft("https://speech.example.net", "sk-1"), engine) }
        assertNull(result, "any answer but 401/403 is reachable")
        assertEquals(HttpMethod.Get, seen.single().method)
        assertEquals("/v1/models", seen.single().url.encodedPath)
        assertEquals("Bearer sk-1", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun theCheckReportsRefusedCredentialsAndBadAddresses() = runTest {
        val refusing = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val refused = withContext(Dispatchers.Default) { store.check(VoiceSetupDraft("https://speech.example.net"), refusing) }
        assertEquals(VoiceErrorCode.UNAUTHORIZED, refused)
        val unused = MockEngine { error("must not be called") }
        assertEquals(VoiceErrorCode.INVALID_ENDPOINT, store.check(VoiceSetupDraft("https://h/?q"), unused))
    }
}
