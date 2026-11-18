@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.data.voice.host.client.HostVoiceLink
import com.letta.mobile.data.voice.host.client.HostVoiceReply
import com.letta.mobile.data.voice.host.client.SharedVoiceSetup
import com.letta.mobile.data.voice.session.VoiceSetupDraft
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.chat.voice.VoiceSetupAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Voice settings against a host that shares a speech server: shown as the host reports it,
 * read-only on a device without admin rights, editable on an admin device, and the one-tap share of
 * this device's own server when the host has none. No "enter your speech server" prompt appears
 * where the host shares one.
 */
class HostVoiceSettingsUiTest {
    private val settings = InMemorySecureSettingsStore()
    private val secrets = InMemorySecureSettingsStore()
    private val store = VoiceSetupStore(settings, secrets)

    /** A host answering `voice.setup.get` with [status] and the admin probe as [admin] says. */
    private class Host(var status: String, private val admin: Boolean) : HostVoiceLink {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override val hostCapabilities: Set<String> = setOf(HostVoiceWire.CAPABILITY)

        override suspend fun call(method: String, params: JsonObject): HostVoiceReply {
            calls += method to params
            return when (method) {
                HostVoiceWire.SETUP_GET -> HostVoiceReply(true, parse(status))
                HostVoiceWire.SETUP_TEST ->
                    if (admin) HostVoiceReply(true, parse(TEST_RESULT)) else HostVoiceReply(false, error = "forbidden")
                HostVoiceWire.SETUP_SET -> HostVoiceReply(true, parse(CONFIGURED)).also { status = CONFIGURED }
                else -> HostVoiceReply(false, error = "voice.not_configured")
            }
        }

        override fun stream(method: String, params: JsonObject): Flow<JsonObject> = emptyFlow()

        fun writes(): List<JsonObject> = calls.filter { it.first == HostVoiceWire.SETUP_SET }.map { it.second }
    }

    private fun access(host: Host) = VoiceSetupAccess(store, shared = SharedVoiceSetup(host)) { null }

    private fun row(key: String) = SettingsSectionTags.ROW_PREFIX + key

    private fun action(key: String) = SettingsSectionTags.ACTION_PREFIX + key

    @Test
    fun aDeviceWithoutAdminRightsSeesTheSharedServerReadOnlyAndIsNotAskedForOne() = runComposeUiTest {
        val host = Host(CONFIGURED, admin = false)
        setContent { MaterialTheme { VoiceSettingsSection(access(host)) } }
        waitUntil(timeoutMillis = 5_000) { host.calls.count { it.first == HostVoiceWire.SETUP_GET } >= 1 }
        waitForIdle()

        onNodeWithTag(row(VoiceSettingsKeys.HOST_SERVER)).assertExists()
        onNodeWithText("https://speech.example.invalid").assertExists()
        onNodeWithText("Kept on the host").assertExists()
        onNodeWithText("Only an admin device can change the shared speech server.").assertExists()
        onNodeWithTag(row(VoiceSettingsKeys.URL)).assertDoesNotExist()
        onNodeWithTag(row(VoiceSettingsKeys.HOST_URL)).assertDoesNotExist()
        assertTrue(host.writes().isEmpty())
    }

    @Test
    fun anAdminDeviceEditsTheSharedServerForEveryDevice() = runComposeUiTest {
        val host = Host(CONFIGURED, admin = true)
        setContent { MaterialTheme { VoiceSettingsSection(access(host)) } }
        waitUntil(timeoutMillis = 5_000) { host.calls.any { it.first == HostVoiceWire.SETUP_TEST } }
        waitForIdle()

        onNodeWithTag(row(VoiceSettingsKeys.HOST_KEY_FIELD)).performTextInput("sk-shared")
        onNodeWithTag(action(VoiceSettingsKeys.HOST_SAVE)).performClick()
        waitUntil(timeoutMillis = 5_000) { host.writes().isNotEmpty() }

        val write = host.writes().single()
        assertEquals("https://speech.example.invalid", write.getValue("base_url").jsonPrimitive.content)
        assertEquals("sk-shared", write.getValue("api_key").jsonPrimitive.content)
        assertEquals("rev-2", write.getValue("expected_revision").jsonPrimitive.content)
        assertNull(settings.getString(VoiceSetupStore.KEY_API_KEY), "the shared key is never stored on this device")
        assertNull(secrets.getString(VoiceSetupStore.KEY_API_KEY))
    }

    @Test
    fun thisDeviceAcknowledgesItsRecordingsGoToTheSharedServer() = runComposeUiTest {
        val host = Host(CONFIGURED, admin = false)
        setContent { MaterialTheme { VoiceSettingsSection(access(host)) } }
        waitUntil(timeoutMillis = 5_000) { host.calls.isNotEmpty() }
        waitForIdle()

        onNodeWithTag(row(VoiceSettingsKeys.HOST_ACK)).performClick()

        assertEquals(EndpointOrigin("https://speech.example.invalid:443"), store.setup.value.hostAudioAck)
    }

    @Test
    fun anAdminDeviceSharesItsOwnServerWithItsKeyAfterConfirming() = runComposeUiTest {
        assertNull(store.save(VoiceSetupDraft("https://speech.example.invalid", apiKey = "sk-local", consent = true)))
        val host = Host(NOTHING, admin = true)
        setContent { MaterialTheme { VoiceSettingsSection(access(host)) } }
        waitUntil(timeoutMillis = 5_000) { host.calls.any { it.first == HostVoiceWire.SETUP_TEST } }
        waitForIdle()

        onNodeWithTag(row(VoiceSettingsKeys.SHARE)).performClick()
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { host.writes().isNotEmpty() }
        waitForIdle()

        assertEquals("sk-local", host.writes().single().getValue("api_key").jsonPrimitive.content)
        assertFalse(store.setup.value.hasApiKey, "the key is removed here once the host holds it")
        onNodeWithTag(row(VoiceSettingsKeys.HOST_SERVER)).assertExists()
    }

    @Test
    fun aDeviceWithoutAdminRightsIsToldAnAdminCanShareItsServer() = runComposeUiTest {
        assertNull(store.save(VoiceSetupDraft("https://speech.example.invalid", consent = true)))
        val host = Host(NOTHING, admin = false)
        setContent { MaterialTheme { VoiceSettingsSection(access(host)) } }
        waitUntil(timeoutMillis = 5_000) { host.calls.any { it.first == HostVoiceWire.SETUP_TEST } }
        waitForIdle()

        onNodeWithText("An admin device can share this speech server with every device.").assertExists()
        onNodeWithTag(row(VoiceSettingsKeys.URL)).assertExists()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): JsonElement = json.parseToJsonElement(text)

        const val TEST_RESULT = """{"model_count": 2, "checked_at_ms": 1767225600000}"""

        const val NOTHING = """{"contract_version": 1, "configured": false, "has_api_key": false,
            "transcription": {"available": false}, "speech": {"available": false},
            "consent": {"grants": {"audio_for_transcription": false, "text_for_synthesis": false}},
            "reachability": {"state": "unknown"}}"""

        const val CONFIGURED = """{"contract_version": 1, "configured": true, "revision": "rev-2",
            "base_url": "https://speech.example.invalid", "has_api_key": true,
            "transcription": {"available": true, "model": "stt-model-a", "language": "en"},
            "speech": {"available": true, "model": "tts-model-a", "voice": "voice-a"},
            "consent": {"origin": "https://speech.example.invalid:443",
                        "grants": {"audio_for_transcription": true, "text_for_synthesis": true},
                        "granted_at_ms": 1767225600000},
            "reachability": {"state": "reachable", "checked_at_ms": 1767225600000}}"""
    }
}
