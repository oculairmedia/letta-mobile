@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.modelcontrol

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServicesPageUiTest {
    private val fixtures = ServiceCredentialsUiFixtures

    @Test
    fun anAdminTestsAndOpensAServiceFromItsCard() = runDesktopComposeUiTest {
        val recorder = ServiceCredentialsUiFixtures.Recorder()
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
        val recorder = ServiceCredentialsUiFixtures.Recorder()
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
        val recorder = ServiceCredentialsUiFixtures.Recorder()
        setContent { MaterialTheme { ServicesPage(ServiceCredentialsBinding(fixtures.state(canManage = false), recorder.actions)) } }
        onNodeWithText(ServiceCredentialStrings.READ_ONLY).assertIsDisplayed()
        onNodeWithTag("${ServiceCredentialTags.TEST_PREFIX}typesafe-jev").assertDoesNotExist()
    }

    @Test
    fun anOpenFormDisappearsWhenManagementIsRevoked() = runDesktopComposeUiTest {
        val recorder = ServiceCredentialsUiFixtures.Recorder()
        var canManage by mutableStateOf(true)
        val editing = fixtures.speech.serviceId
        setContent {
            val state = fixtures.state(editing = editing, canManage = canManage)
            MaterialTheme { ServicesPage(ServiceCredentialsBinding(state, recorder.actions)) }
        }
        onNodeWithTag(ServiceCredentialTags.SAVE).assertIsDisplayed()
        canManage = false
        waitForIdle()
        onNodeWithTag(ServiceCredentialTags.SAVE).assertDoesNotExist()
        onNodeWithTag(ServiceCredentialTags.REMOVE).assertDoesNotExist()
        onNodeWithText(ServiceCredentialStrings.READ_ONLY).assertIsDisplayed()
    }

    @Test
    fun aTypedSecretIsDroppedWhenTheFormsActionsChange() = runDesktopComposeUiTest {
        val first = ServiceCredentialsUiFixtures.Recorder()
        val second = ServiceCredentialsUiFixtures.Recorder()
        var recorder by mutableStateOf(first)
        val state = fixtures.state(editing = fixtures.speech.serviceId)
        setContent { MaterialTheme { ServicesPage(ServiceCredentialsBinding(state, recorder.actions)) } }
        onNodeWithTag("${ServiceCredentialTags.FIELD_PREFIX}base_url").performTextInput("https://speech.example.invalid")
        onNodeWithTag("${ServiceCredentialTags.FIELD_PREFIX}secret").performTextInput("placeholder-key-not-real")
        // The host connection changed under the open page: its form must not carry the secret to the new host.
        recorder = second
        waitForIdle()
        onNodeWithTag("${ServiceCredentialTags.FIELD_PREFIX}base_url").performTextInput("https://speech.example.invalid")
        onNodeWithTag(ServiceCredentialTags.SAVE).assertIsNotEnabled()
        assertEquals(emptyList(), second.saved)
    }

    @Test
    fun theKeptKeyHintPromisesRetentionOnlyForTheSameOrigin() {
        val hint = ServiceCredentialStrings.SECRET_KEPT_HINT
        // The key is bound to the origin (ServiceAddress.originOf): scheme, host and effective port; a path change keeps it.
        assertTrue(listOf("scheme", "host", "port").all { it in hint }, hint)
    }

    @Test
    fun theSelectedPageFallsBackToAccountsWhileServicesIsUnavailable() = runDesktopComposeUiTest {
        val actions = ProviderSettingsActions(
            management = ProviderManagementActions(
                onRefresh = {}, onQueryChange = {}, onFilterChange = {}, onToggleExpanded = {}, onExposedChange = {},
                onProviderExposedChange = { _, _ -> }, onConnect = {}, onDisconnect = {},
                form = ProviderFormActions(onChange = {}, onSubmit = {}, onDismiss = {}),
                onConfirmDisconnect = {}, onDismissDisconnect = {},
            ),
            onOpenSignIn = {}, onCheckSignIn = {}, onDismissSignIn = {}, onEdit = {},
        )
        var binding by mutableStateOf<ServiceCredentialsBinding?>(null)
        setContent {
            MaterialTheme {
                ProviderSettingsPane(
                    ModelControlUiFixtures.managementState(),
                    actions,
                    pages = ProviderSettingsPages(ProviderSettingsPage.SERVICES, binding),
                )
            }
        }
        onNode(hasText(ProviderSettingsPage.ACCOUNTS.label) and isHeading()).assertExists()
        onNode(hasText(ProviderSettingsPage.SERVICES.label) and isHeading()).assertDoesNotExist()
        binding = ServiceCredentialsBinding(fixtures.state(), ServiceCredentialsUiFixtures.Recorder().actions)
        waitForIdle()
        onNode(hasText(ProviderSettingsPage.SERVICES.label) and isHeading()).assertExists()
        binding = null
        waitForIdle()
        onNode(hasText(ProviderSettingsPage.ACCOUNTS.label) and isHeading()).assertExists()
    }
}
