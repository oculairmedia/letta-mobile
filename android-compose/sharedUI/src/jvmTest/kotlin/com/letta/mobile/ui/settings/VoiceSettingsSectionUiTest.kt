@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.session.VoiceSetupDraft
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.chat.voice.VoiceSetupAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Voice settings section: validation, consent, the key field only where keys are kept, Save and Test. */
class VoiceSettingsSectionUiTest {
    private val settings = InMemorySecureSettingsStore()
    private val secrets = InMemorySecureSettingsStore()
    private val checked = mutableListOf<VoiceSetupDraft>()

    private fun access(keepsKeys: Boolean = true, answer: VoiceErrorCode? = null) =
        VoiceSetupAccess(VoiceSetupStore(settings, secrets.takeIf { keepsKeys })) { draft ->
            checked += draft
            answer
        }

    private fun row(key: String) = SettingsSectionTags.ROW_PREFIX + key

    private fun action(key: String) = SettingsSectionTags.ACTION_PREFIX + key

    @Test
    fun savingAPlainUrlWithConsentMakesVoiceModeReady() = runComposeUiTest {
        val access = access()
        var ready: Boolean? = null
        setContent { MaterialTheme { VoiceSettingsSection(access, onSaved = { ready = it }) } }
        onNodeWithTag(row(VoiceSettingsKeys.URL)).performTextInput("http://127.0.0.1:8880")
        onNodeWithTag(row(VoiceSettingsKeys.CONSENT)).performClick()
        onNodeWithTag(action(VoiceSettingsKeys.SAVE)).performClick()
        onNodeWithText("Saved.").assertExists()
        assertEquals(true, ready)
        assertEquals(SpeechEndpointUrl("http://127.0.0.1:8880"), access.store.setup.value.endpoint.baseUrl)
        assertNull(access.store.setup.value.gap)
    }

    @Test
    fun anAddressWithAQueryShowsItsProblemAndCannotBeSaved() = runComposeUiTest {
        setContent { MaterialTheme { VoiceSettingsSection(access()) } }
        onNodeWithTag(row(VoiceSettingsKeys.URL)).performTextInput("https://speech.example.net/?x=1")
        onNodeWithText("Use a plain http or https address, without a query or a login.").assertExists()
        onNodeWithTag(action(VoiceSettingsKeys.SAVE)).assertIsNotEnabled()
        onNodeWithTag(action(VoiceSettingsKeys.TEST)).assertIsNotEnabled()
    }

    @Test
    fun theTestOnlyChecksReachabilityAndSaysWhatHappened() = runComposeUiTest {
        val access = access(answer = VoiceErrorCode.UNREACHABLE)
        setContent { MaterialTheme { VoiceSettingsSection(access) } }
        onNodeWithTag(row(VoiceSettingsKeys.URL)).performTextInput("https://speech.example.net")
        onNodeWithTag(action(VoiceSettingsKeys.TEST)).performClick()
        waitUntil(timeoutMillis = 5_000) { checked.isNotEmpty() }
        onNodeWithText("Couldn't reach the speech server.").assertExists()
        assertTrue(access.store.setup.value.endpoint.baseUrl == null, "a test stores nothing")
    }

    @Test
    fun theKeyFieldIsLeftOutWhereNoSecureStoreKeepsKeys() = runComposeUiTest {
        setContent { MaterialTheme { VoiceSettingsSection(access(keepsKeys = false)) } }
        onNodeWithTag(row(VoiceSettingsKeys.URL)).assertExists()
        onNodeWithTag(row(VoiceSettingsKeys.KEY)).assertDoesNotExist()
    }

    @Test
    fun aTypedKeyGoesOnlyToTheSecureStore() = runComposeUiTest {
        setContent { MaterialTheme { VoiceSettingsSection(access()) } }
        onNodeWithTag(row(VoiceSettingsKeys.URL)).performTextInput("https://speech.example.net")
        onNodeWithTag(row(VoiceSettingsKeys.KEY)).performTextInput("sk-voice")
        onNodeWithTag(row(VoiceSettingsKeys.CONSENT)).performClick()
        onNodeWithTag(action(VoiceSettingsKeys.SAVE)).performClick()
        waitForIdle()
        assertEquals("sk-voice", secrets.getString(VoiceSetupStore.KEY_API_KEY))
        assertNull(settings.getString(VoiceSetupStore.KEY_API_KEY))
    }
}
