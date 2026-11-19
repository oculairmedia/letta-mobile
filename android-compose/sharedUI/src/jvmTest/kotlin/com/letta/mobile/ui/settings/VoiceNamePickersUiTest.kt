@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.catalog.SpeechCatalog
import com.letta.mobile.data.voice.catalog.SpeechCatalogException
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model and voice pickers: filled from the server's list on this device's own server (asked
 * directly) and on the shared one (asked through the host), a custom name for any server, and plain
 * fields with Try again when the list cannot be loaded. Fake servers and names only.
 */
class VoiceNamePickersUiTest {
    private val store = VoiceSetupStore(InMemorySecureSettingsStore(), InMemorySecureSettingsStore())
    private val listed = SpeechCatalog(listOf("whisper-1", "tts-1", "tts-model-b"), listOf("voice-a", "voice-b"))

    private fun row(key: String) = SettingsSectionTags.ROW_PREFIX + key

    private fun option(key: String, name: String) = SettingsSectionTags.OPTION_PREFIX + key + "-" + name

    private fun saveLocalServer() {
        assertTrue(store.save(VoiceSetupDraft("https://speech.example.invalid", consent = true)) == null)
    }

    @Test
    fun thisDevicesOwnServerOffersItsListedNamesAndSavesThePick() = runComposeUiTest {
        saveLocalServer()
        val asked = mutableListOf<String>()
        val access = VoiceSetupAccess(store, catalog = { draft -> asked += draft.baseUrl; listed }) { null }
        setContent { Frame { Column(Modifier.verticalScroll(rememberScrollState())) { VoiceSettingsSection(access) } } }
        waitUntil(timeoutMillis = 5_000) { asked.isNotEmpty() }
        waitForIdle()

        onNodeWithTag(row(VoiceSettingsKeys.SPEECH_MODEL)).performScrollTo().performClick()
        onNodeWithTag(option(VoiceSettingsKeys.SPEECH_MODEL, "tts-model-b")).performClick()
        onNodeWithTag(row(VoiceSettingsKeys.VOICE)).performScrollTo().performClick()
        onNodeWithTag(option(VoiceSettingsKeys.VOICE, "voice-b")).performClick()
        onNodeWithTag(SettingsSectionTags.ACTION_PREFIX + VoiceSettingsKeys.SAVE).performScrollTo().performClick()

        assertEquals(listOf("https://speech.example.invalid"), asked)
        assertEquals(SpeechModelName("tts-model-b"), store.setup.value.endpoint.speechModel)
        assertEquals(SpeechVoiceName("voice-b"), store.setup.value.endpoint.voice)
    }

    @Test
    fun customLetsThePersonTypeANameTheServerDoesNotList() = runComposeUiTest {
        saveLocalServer()
        var asked = 0
        val access = VoiceSetupAccess(store, catalog = { asked++; listed }) { null }
        setContent { Frame { Column(Modifier.verticalScroll(rememberScrollState())) { VoiceSettingsSection(access) } } }
        waitUntil(timeoutMillis = 5_000) { asked == 1 }
        waitForIdle()

        onNodeWithTag(row(VoiceSettingsKeys.VOICE)).performScrollTo().performClick()
        onNodeWithTag(option(VoiceSettingsKeys.VOICE, CUSTOM)).performClick()
        onNodeWithTag(row(VoiceSettingsKeys.VOICE + VoiceSettingsKeys.CUSTOM_SUFFIX)).performScrollTo().performTextReplacement("my-clone")
        onNodeWithTag(SettingsSectionTags.ACTION_PREFIX + VoiceSettingsKeys.SAVE).performScrollTo().performClick()

        assertEquals(SpeechVoiceName("my-clone"), store.setup.value.endpoint.voice)
    }

    @Test
    fun aFailedListLeavesPlainFieldsAndTryAgainAsksAgain() = runComposeUiTest {
        saveLocalServer()
        var attempts = 0
        val access = VoiceSetupAccess(store, catalog = {
            attempts++
            throw SpeechCatalogException(VoiceErrorCode.UNAUTHORIZED)
        }) { null }
        setContent { Frame { Column(Modifier.verticalScroll(rememberScrollState())) { VoiceSettingsSection(access) } } }
        waitUntil(timeoutMillis = 5_000) { attempts == 1 }
        waitForIdle()

        onNodeWithText("Couldn't load the server's list. You can still type the names.").assertExists()
        onNodeWithTag(row(VoiceSettingsKeys.SPEECH_MODEL)).performScrollTo().performTextInput("x-")
        onNodeWithTag(row(VoiceSettingsKeys.TRANSCRIPTION_MODEL + VoiceSettingsKeys.CATALOG_RETRY_SUFFIX)).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { attempts == 2 }
    }

    @Test
    fun noSavedServerMeansNoPickersAndNoRequest() = runComposeUiTest {
        var attempts = 0
        val access = VoiceSetupAccess(store, catalog = { attempts++; listed }) { null }
        setContent { Frame { Column(Modifier.verticalScroll(rememberScrollState())) { VoiceSettingsSection(access) } } }
        waitForIdle()

        onNodeWithTag(row(VoiceSettingsKeys.VOICE)).assertDoesNotExist()
        assertEquals(0, attempts)
    }

    @Test
    fun anAdminPicksTheSharedServersNamesListedByTheHost() = runComposeUiTest {
        val host = ListingHost()
        val access = VoiceSetupAccess(store, shared = SharedVoiceSetup(host)) { null }
        setContent { Frame { Column(Modifier.verticalScroll(rememberScrollState())) { VoiceSettingsSection(access) } } }
        waitUntil(timeoutMillis = 5_000) { host.methods.contains(HostVoiceWire.MODELS_LIST) }
        waitForIdle()

        onNodeWithTag(row(VoiceSettingsKeys.HOST_VOICE)).performScrollTo().performClick()
        onNodeWithTag(option(VoiceSettingsKeys.HOST_VOICE, "voice-b")).performClick()
        onNodeWithTag(SettingsSectionTags.ACTION_PREFIX + VoiceSettingsKeys.HOST_SAVE).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { host.writes.isNotEmpty() }

        assertEquals("voice-b", host.writes.single().getValue("voice").jsonPrimitive.content)
        assertTrue(HostVoiceWire.MODELS_LIST in host.methods, "the host lists the shared server; the device never calls it")
    }

    /** An admin host sharing a configured server whose list it answers. */
    private class ListingHost : HostVoiceLink {
        val methods = mutableListOf<String>()
        val writes = mutableListOf<JsonObject>()

        override val hostCapabilities: Set<String> = setOf(HostVoiceWire.CAPABILITY)

        override suspend fun call(method: String, params: JsonObject): HostVoiceReply {
            methods += method
            return when (method) {
                HostVoiceWire.SETUP_GET -> HostVoiceReply(true, parse(STATUS))
                HostVoiceWire.SETUP_TEST -> HostVoiceReply(true, parse("""{"model_count":3,"checked_at_ms":1}"""))
                HostVoiceWire.MODELS_LIST -> HostVoiceReply(true, parse(MODELS))
                HostVoiceWire.SETUP_SET -> HostVoiceReply(true, parse(STATUS)).also { writes += params }
                else -> HostVoiceReply(false, error = "voice.not_configured")
            }
        }

        override fun stream(method: String, params: JsonObject): Flow<JsonObject> = emptyFlow()
    }

    private companion object {
        const val CUSTOM = "voice-custom-name"

        fun parse(text: String) = Json.parseToJsonElement(text)

        val STATUS = """
            {"contract_version":1,"configured":true,"revision":"rev-1","base_url":"https://speech.example.invalid","has_api_key":true,
             "transcription":{"available":true,"model":"whisper-1"},"speech":{"available":true,"model":"tts-1","voice":"voice-a"},
             "consent":{"origin":"https://speech.example.invalid:443","grants":{"audio_for_transcription":true,"text_for_synthesis":true}},
             "reachability":{"state":"reachable","checked_at_ms":1}}
        """.trimIndent()

        val MODELS = """
            {"models":[{"id":"whisper-1"},{"id":"tts-1"}],"voices":[{"id":"voice-a"},{"id":"voice-b"}],
             "voices_source":"upstream","checked_at_ms":1}
        """.trimIndent()
    }
}
