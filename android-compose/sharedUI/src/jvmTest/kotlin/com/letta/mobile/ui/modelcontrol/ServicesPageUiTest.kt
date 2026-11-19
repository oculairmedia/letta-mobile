@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.modelcontrol

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ServicesPageUiTest {
    private val fixtures = ServiceCredentialsUiFixtures

    @Test
    fun anAdminTestsAndOpensAServiceFromItsCard() = runDesktopComposeUiTest {
        val recorder = fixtures.Recorder()
        setContent { MaterialTheme { ServicesPage(ServiceCredentialsBinding(fixtures.state(), recorder.actions)) } }
        onNodeWithText("Test passed").assertIsDisplayed()
        onNodeWithText("The service refused the key.").assertIsDisplayed()
        onNodeWithTag("${ServiceCredentialTags.TEST_PREFIX}typesafe-jev").performClick()
        onNodeWithTag("${ServiceCredentialTags.EDIT_PREFIX}example-speech").performClick()
        assertEquals(listOf(fixtures.jev.serviceId), recorder.tested)
        assertEquals(listOf(fixtures.speech.serviceId), recorder.edited)
    }

    @Test
    fun aNewServiceNeedsAKeyAndTheTypedKeyLeavesOnlyInTheDraft() = runDesktopComposeUiTest {
        val recorder = fixtures.Recorder()
        val state = fixtures.state(editing = fixtures.speech.serviceId)
        setContent { MaterialTheme { ServicesPage(ServiceCredentialsBinding(state, recorder.actions)) } }
        onNodeWithTag(ServiceCredentialTags.SAVE).assertIsNotEnabled()
        onNodeWithTag("${ServiceCredentialTags.FIELD_PREFIX}base_url").performTextInput("https://speech.example.invalid")
        onNodeWithTag("${ServiceCredentialTags.FIELD_PREFIX}secret").performTextInput("placeholder-key-not-real")
        onNodeWithTag(ServiceCredentialTags.SAVE).assertIsEnabled().performClick()
        val (id, draft) = recorder.saved.single()
        assertEquals(fixtures.speech.serviceId, id)
        assertEquals("placeholder-key-not-real", draft.secret)
        assertFalse("placeholder-key-not-real" in draft.toString())
    }

    @Test
    fun aDeviceWithoutAdminRightsSeesStatusButNoActions() = runDesktopComposeUiTest {
        val recorder = fixtures.Recorder()
        setContent { MaterialTheme { ServicesPage(ServiceCredentialsBinding(fixtures.state(canManage = false), recorder.actions)) } }
        onNodeWithText(ServiceCredentialStrings.READ_ONLY).assertIsDisplayed()
        onNodeWithTag("${ServiceCredentialTags.TEST_PREFIX}typesafe-jev").assertDoesNotExist()
    }
}
