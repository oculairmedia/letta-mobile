@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.hasTestTag
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.settings.AppearanceSettings
import com.letta.mobile.data.settings.ChatSettings
import com.letta.mobile.data.settings.SettingValue
import com.letta.mobile.data.settings.SettingsAction
import com.letta.mobile.data.settings.SettingsConfirm
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.ui.host.LettaPaneTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The SettingsSection primitives (letta-mobile-ckmub.3.12, P9) and the platform-section renderer. */
class SettingsSectionTest {
    @Test
    fun aPlatformSectionsDraftsReachItsActionAndGateIt() = runComposeUiTest {
        val model = SettingsSectionModel(
            key = "provider",
            title = "Local runtime provider",
            rows = listOf(
                SettingsRow.Text("base-url", "Base URL", problem = { if (it.startsWith("http")) null else "Enter an http URL." }),
                SettingsRow.Actions(
                    "actions",
                    listOf(SettingsAction("save", "Save", primary = true, enabledFor = { it["base-url"].startsWith("http") }) {}),
                ),
            ),
        )
        setContent { Frame { SettingsModelSection(model) } }

        onNodeWithTag(SettingsSectionTags.ACTION_PREFIX + "save").assertIsNotEnabled()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "base-url").performTextInput("ftp://x")
        onNodeWithText("Enter an http URL.").assertExists()
        onNodeWithTag(SettingsSectionTags.ACTION_PREFIX + "save").assertIsNotEnabled()
    }

    @Test
    fun aValidDraftIsWhatTheActionSaves() = runComposeUiTest {
        var savedUrl: String? = null
        val model = SettingsSectionModel(
            key = "provider",
            title = "Provider",
            rows = listOf(
                SettingsRow.Text("base-url", "Base URL"),
                SettingsRow.Actions("actions", listOf(SettingsAction("save", "Save") { savedUrl = it["base-url"] })),
            ),
        )
        setContent { Frame { SettingsModelSection(model) } }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "base-url").performTextInput("http://localhost:11434/v1")
        onNodeWithTag(SettingsSectionTags.ACTION_PREFIX + "save").assertIsEnabled().performClick()

        assertEquals("http://localhost:11434/v1", savedUrl)
    }

    @Test
    fun aContributionFollowsItsUpdates() = runComposeUiTest {
        val state = MutableStateFlow(section("Not running"))
        setContent { Frame { SettingsContributionSection(SettingsContribution.of(SettingsGroup.Backend, state)) } }
        onNodeWithText("Not running").assertExists()

        state.value = section("Running")

        onNodeWithText("Running").assertExists()
    }

    @Test
    fun aPendingConfirmationAsksBeforeItActs() = runComposeUiTest {
        var confirmed = false
        val model = section("Using the default location.").copy(
            confirm = SettingsConfirm("Switch folder?", "The runtime restarts against /data.", "Use this folder", { confirmed = true }, {}),
        )
        setContent { Frame { SettingsModelSection(model) } }

        onNodeWithTag(LettaPaneTags.CONFIRM).assertExists()
        onNodeWithText("Use this folder").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun aToggleRowWritesItsSetting() = runComposeUiTest {
        val written = mutableListOf<Boolean>()
        val canvas = SettingValue(MutableStateFlow(false)) { written += it }
        setContent { Frame { ChatSettingsSection(ChatSettings(openChatsOnCanvas = canvas)) } }

        onNode(toggleIn("chat-open-on-canvas")).assertIsOff().performClick()
        assertEquals(listOf(true), written)
    }

    @Test
    fun aDisabledToggleRowCannotChange() = runComposeUiTest {
        val row = SettingsRow.Toggle("auto-launch", "Launch Letta when I sign in", checked = true, supporting = "Unsupported here.", enabled = false) {}
        setContent { Frame { SettingsModelSection(SettingsSectionModel("login", "Start at login", listOf(row))) } }

        onNodeWithText("Unsupported here.").assertExists()
        onNode(toggleIn("auto-launch")).assertIsNotEnabled()
    }

    @Test
    fun aChoiceRowPicksFromItsOptions() = runComposeUiTest {
        val theme = MutableStateFlow(AppTheme.SYSTEM)
        val setting = SettingValue(theme) { theme.value = it }
        setContent { Frame { AppearanceSettingsSection(AppearanceSettings(theme = setting)) } }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-theme").performClick()
        onNodeWithTag(SettingsSectionTags.OPTION_PREFIX + "appearance-theme-" + AppTheme.DARK.name).performClick()

        assertEquals(AppTheme.DARK, theme.value)
        onNodeWithText("Dark").assertExists()
    }

    @Test
    fun anAppearanceWithNothingBoundDrawsNothing() = runComposeUiTest {
        setContent { Frame { AppearanceSettingsSection(AppearanceSettings()) } }

        onNodeWithTag(SettingsSectionTags.SECTION_PREFIX + "appearance").assertDoesNotExist()
    }

    private fun section(status: String) = SettingsSectionModel(
        key = "runtime",
        title = "Local runtime",
        rows = listOf(SettingsRow.Note("health", SettingsStatus(status))),
    )

    /** The row itself is the switch: one toggleable node with the row's tag. */
    private fun toggleIn(rowKey: String) = isToggleable() and hasTestTag(SettingsSectionTags.ROW_PREFIX + rowKey)
}
