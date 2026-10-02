@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.repository.modelcontrol.ExposureChange
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectForm
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The picker footer, the Models sheet switches and the Providers > Accounts sections (letta-mobile-w4q4p.6.1). */
class ModelControlUiTest {
    @Composable
    private fun Frame(height: Int = 760, content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = lightColorScheme()) { Box(Modifier.size(900.dp, height.dp)) { content() } }
    }

    private fun pickerActions(
        selected: MutableList<ModelPickerEntry> = mutableListOf(),
        refreshes: MutableList<Unit> = mutableListOf(),
        edits: MutableList<Unit> = mutableListOf(),
        toggles: MutableList<String> = mutableListOf(),
    ) = ModelPickerActions(
        onSelect = { selected += it },
        onQueryChange = {},
        onToggleGroup = { toggles += it },
        onRefresh = { refreshes += Unit },
        onEditModels = { edits += Unit },
    )

    @Test
    fun pickerFooterRefreshesAndOpensTheModelsSheet() = runComposeUiTest {
        val refreshes = mutableListOf<Unit>()
        val edits = mutableListOf<Unit>()
        setContent { Frame { Column { ModelPickerContent(ModelControlUiFixtures.pickerState(), pickerActions(refreshes = refreshes, edits = edits)) } } }

        onNodeWithTag(ModelPickerTags.REFRESH).performClick()
        onNodeWithTag(ModelPickerTags.EDIT).performClick()

        assertEquals(1, refreshes.size)
        assertEquals(1, edits.size)
        onNodeWithText(ModelControlStrings.REFRESH_MODELS).assertIsDisplayed()
        onNodeWithText(ModelControlStrings.EDIT_MODELS).assertIsDisplayed()
    }

    @Test
    fun aRunningRefreshSpinsAndCannotBeStartedTwice() = runComposeUiTest {
        setContent { Frame { Column { ModelPickerContent(ModelControlUiFixtures.pickerState(refreshing = true), pickerActions()) } } }

        onNodeWithTag(ModelPickerTags.REFRESH).assertIsNotEnabled()
        onNodeWithTag(ModelPickerTags.REFRESH_PROGRESS, useUnmergedTree = true).assertExists()
        onNodeWithText(ModelControlStrings.REFRESHING_MODELS).assertIsDisplayed()
    }

    @Test
    fun aFailedRefreshShowsItsError() = runComposeUiTest {
        val state = ModelControlUiFixtures.pickerState(error = "Couldn't refresh models: upstream timed out")
        setContent { Frame { Column { ModelPickerContent(state, pickerActions()) } } }

        onNodeWithTag(ModelPickerTags.ERROR).assertIsDisplayed()
        onNodeWithText("Couldn't refresh models: upstream timed out").assertIsDisplayed()
    }

    @Test
    fun editModelsIsHiddenWhenTheHostHasNoExposure() = runComposeUiTest {
        setContent { Frame { Column { ModelPickerContent(ModelControlUiFixtures.pickerState(canEditModels = false), pickerActions()) } } }

        onNodeWithTag(ModelPickerTags.EDIT).assertDoesNotExist()
        onNodeWithTag(ModelPickerTags.REFRESH).assertIsDisplayed()
    }

    @Test
    fun theCurrentModelIsMarkedAndAPickIsReported() = runComposeUiTest {
        val selected = mutableListOf<ModelPickerEntry>()
        setContent { Frame { Column { ModelPickerContent(ModelControlUiFixtures.pickerState(), pickerActions(selected = selected)) } } }

        onNodeWithTag("${ModelPickerTags.ROW_PREFIX}${ModelControlUiFixtures.SELECTED}").assertIsSelected()
        assertTrue(onAllNodesWithText("Med").fetchSemanticsNodes().isNotEmpty(), "tiers show after the name")
        onNodeWithTag("${ModelPickerTags.ROW_PREFIX}minimax/minimax-m3-lightning").performClick()

        assertEquals("minimax/minimax-m3-lightning", selected.single().handle.value)
    }

    @Test
    fun providerHeadersAreHeadingsThatFoldTheirGroup() = runComposeUiTest {
        var state by mutableStateOf(ModelControlUiFixtures.pickerState())
        val actions = pickerActions().copy(onToggleGroup = { key -> state = state.copy(collapsed = state.collapsed + key) })
        setContent { Frame { Column { ModelPickerContent(state, actions) } } }

        onNodeWithTag("${ModelPickerTags.GROUP_PREFIX}minimax").assert(isHeading())
        onNodeWithText("MINIMAX").assertIsDisplayed()
        onNodeWithTag("${ModelPickerTags.GROUP_PREFIX}minimax").performClick()

        onNodeWithTag("${ModelPickerTags.ROW_PREFIX}minimax/minimax-m3").assertDoesNotExist()
        onNodeWithTag("${ModelPickerTags.ROW_PREFIX}${ModelControlUiFixtures.SELECTED}").assertExists()
    }

    @Test
    fun theModelsSheetTogglesExposureAsSwitches() = runComposeUiTest {
        val changes = mutableListOf<ExposureChange>()
        val closes = mutableListOf<Unit>()
        val adds = mutableListOf<Unit>()
        val actions = ModelsEditActions(
            onClose = { closes += Unit },
            onQueryChange = {},
            onExposedChange = { changes += it },
            onAddProvider = { adds += Unit },
        )
        setContent { Frame { Column { ModelsEditContent(ModelControlUiFixtures.managementState(), actions) } } }

        val shown = onNodeWithTag("${ModelsEditTags.ROW_PREFIX}chatgpt-plus-pro/gpt-5.5")
        shown.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOn()
        onNodeWithTag("${ModelsEditTags.ROW_PREFIX}chatgpt-plus-pro/gpt-5.5-mini").assertIsOff()
        shown.performClick()
        onNodeWithTag("${ModelsEditTags.ROW_PREFIX}chatgpt-plus-pro/gpt-5.5-mini").performClick()
        onNodeWithTag(ModelsEditTags.CLOSE).performClick()
        onNodeWithTag(ModelsEditTags.ADD_PROVIDER).performClick()

        assertEquals(
            listOf(
                ExposureChange(ModelHandle("chatgpt-plus-pro/gpt-5.5"), exposed = false),
                ExposureChange(ModelHandle("chatgpt-plus-pro/gpt-5.5-mini"), exposed = true),
            ),
            changes,
        )
        assertEquals(1, closes.size)
        assertEquals(1, adds.size)
        onNodeWithTag("${ModelsEditTags.SECTION_PREFIX}chatgpt-plus-pro").assert(isHeading())
        onNodeWithText("OPENAI CODEX").assertIsDisplayed()
    }

    private class SettingsRecorder {
        val connects = mutableListOf<ConnectableProvider>()
        val disconnects = mutableListOf<ConnectableProvider>()
        val signIns = mutableListOf<ConnectableProvider>()
        val refreshes = mutableListOf<Unit>()

        val actions = ProviderSettingsActions(
            management = ProviderManagementActions(
                onRefresh = { refreshes += Unit },
                onQueryChange = {},
                onFilterChange = {},
                onToggleExpanded = {},
                onExposedChange = {},
                onProviderExposedChange = { _, _ -> },
                onConnect = { connects += it },
                onDisconnect = { disconnects += it },
                form = ProviderFormActions(onChange = {}, onSubmit = {}, onDismiss = {}),
                onConfirmDisconnect = {},
                onDismissDisconnect = {},
            ),
            onOpenSignIn = { signIns += it },
            onCheckSignIn = {},
            onDismissSignIn = {},
            onEdit = { connects += it },
        )
    }

    @Test
    fun accountsListsAccountsToConnectTheConnectedOnesAndTheRest() = runComposeUiTest {
        val recorder = SettingsRecorder()
        var page by mutableStateOf(ProviderSettingsPage.ACCOUNTS)
        setContent {
            Frame(height = 1400) {
                ProviderSettingsPane(ModelControlUiFixtures.managementState(), recorder.actions, page, onPageChange = { page = it })
            }
        }

        listOf(ModelControlStrings.CONNECT_ACCOUNT, ModelControlStrings.CONNECTED, ModelControlStrings.OTHER_PROVIDERS).forEach {
            onNode(hasText(it) and isHeading()).assertExists()
        }
        onNodeWithTag("${ProviderSettingsTags.CARD_PREFIX}anthropic-oauth").performClick()
        assertEquals("anthropic-oauth", recorder.signIns.single().id)
        val badges = onAllNodesWithText(ModelControlStrings.CONNECTED_BADGE, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(badges.size >= 4, "a badge on each connected card, plus the section title")
        onNodeWithTag("${ProviderSettingsTags.REMOVE_PREFIX}lmstudio").performClick()
        assertEquals("lmstudio", recorder.disconnects.single().id)
        onNodeWithTag("${ProviderSettingsTags.CARD_PREFIX}openai-compatible").performClick()
        assertEquals("openai-compatible", recorder.connects.single().id)
        onNodeWithTag(ProviderSettingsTags.REFRESH).performClick()
        assertEquals(1, recorder.refreshes.size)

        onNodeWithTag(ProviderSettingsTags.HAVE_KEY).performClick()
        assertEquals(ProviderSettingsPage.API_KEYS, page)
    }

    @Test
    fun theApiKeysPageEditsAKeyInPlaceMasked() = runComposeUiTest {
        val recorder = SettingsRecorder()
        val anthropic = ModelControlUiFixtures.providers.single { it.id == "anthropic" }
        val state: ProviderManagementState = ModelControlUiFixtures.managementState().copy(
            form = ProviderConnectForm(anthropic).withValue("apiKey", "sk-ant-secret"),
        )
        setContent { Frame { ProviderSettingsPane(state, recorder.actions, ProviderSettingsPage.API_KEYS, onPageChange = {}) } }

        onNodeWithTag(ProviderSettingsTags.INLINE_FORM).assertIsDisplayed()
        val field = onNodeWithTag("provider_field_apiKey")
        val shows = { text: String -> SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)) }
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        field.assert(shows("\u2022".repeat("sk-ant-secret".length)))
        onNodeWithTag("provider_field_reveal_apiKey").performClick()
        field.assert(shows("sk-ant-secret"))
        onNodeWithText(ModelControlStrings.SAVE_AND_VERIFY).assertIsDisplayed()
    }
}
