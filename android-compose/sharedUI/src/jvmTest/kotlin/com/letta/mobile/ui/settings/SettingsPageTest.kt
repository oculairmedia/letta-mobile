@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared Settings page (letta-mobile-ckmub.8): the backend form, its URL check and its save. */
class SettingsPageTest {
    private val hexId = "1a".repeat(32)
    private val saved = LettaConfig(id = "default", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "", accessToken = null)

    @Test
    fun aBareEndpointIdIsExplainedAndCannotBeSaved() = runComposeUiTest {
        setContent { SharedMaterialTheme { SettingsPage(SettingsPageState(saved), BackendSettingsActions(onConfigSaved = {})) } }

        onNodeWithTag(BackendSettingsTags.SERVER_URL).performTextInput(hexId)

        onNodeWithText("This looks like an Iroh endpoint id or ticket: enter it as iroh://$hexId").assertExists()
        onNodeWithTag(BackendSettingsTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun aValidIrohUrlSavesWithTheChosenModeAndToken() = runComposeUiTest {
        var savedConfig: LettaConfig? = null
        setContent {
            SharedMaterialTheme {
                SettingsPage(SettingsPageState(saved), BackendSettingsActions(onConfigSaved = { savedConfig = it }))
            }
        }

        onNodeWithTag(BackendSettingsTags.SERVER_URL).performTextInput("  iroh://$hexId@10.0.0.2:4433 ")
        onNodeWithTag(BackendSettingsTags.SAVE).assertIsEnabled().performClick()

        assertEquals("iroh://$hexId@10.0.0.2:4433", savedConfig?.serverUrl)
        assertEquals(LettaConfig.Mode.SELF_HOSTED, savedConfig?.mode)
        assertNull(savedConfig?.accessToken)
    }

    @Test
    fun theConnectionAndItsFailureShowAboveTheForm() = runComposeUiTest {
        val failed = BackendConnectionStatus.Failed("Iroh tickets need the relay")
        setContent { SharedMaterialTheme { SettingsPage(SettingsPageState(saved, failed), BackendSettingsActions(onConfigSaved = {})) } }

        onNodeWithText("Connection failed").assertExists()
        onNodeWithText("Iroh tickets need the relay").assertExists()
    }

    @Test
    fun theBackArrowShowsOnlyWhenTheHostHasSomewhereToGo() = runComposeUiTest {
        var back = false
        setContent {
            SharedMaterialTheme {
                SettingsPage(SettingsPageState(saved), BackendSettingsActions(onConfigSaved = {}), onBack = { back = true })
            }
        }

        onNodeWithTag(SettingsPageTags.BACK).performClick()
        assertTrue(back)
    }

    @Test
    fun anEmptyTokenFieldKeepsTheSavedToken() {
        val withToken = saved.copy(serverUrl = "https://letta.example.com", accessToken = "secret")
        val form = BackendSettingsForm.of(withToken).copy(serverUrl = "https://other.example.com")

        assertEquals("secret", form.applyTo(withToken).accessToken)
        assertNull(form.problem)
    }

    @Test
    fun aFixedUrlClearsTheProblem() = runComposeUiTest {
        setContent { SharedMaterialTheme { SettingsPage(SettingsPageState(saved), BackendSettingsActions(onConfigSaved = {})) } }

        onNodeWithTag(BackendSettingsTags.SERVER_URL).performTextInput("letta.example.com")
        onNodeWithTag(BackendSettingsTags.PROBLEM, useUnmergedTree = true).assertExists()
        onNodeWithTag(BackendSettingsTags.SERVER_URL).performTextClearance()
        onNodeWithTag(BackendSettingsTags.SERVER_URL).performTextInput("https://letta.example.com")
        onNodeWithTag(BackendSettingsTags.PROBLEM, useUnmergedTree = true).assertDoesNotExist()
    }
}
