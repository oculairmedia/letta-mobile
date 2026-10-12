@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.settings.AppearanceSettings
import com.letta.mobile.data.settings.BackendConnectionTester
import com.letta.mobile.data.settings.SettingValue
import com.letta.mobile.data.settings.SettingsConfirmText
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.ui.host.LettaPaneTags
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-ckmub.3.12.1: the settings quality pass. One row model (every kind with its label,
 * one-line helper and a 48dp hit target), the layout per width, the Backend section's states, the
 * page search, the danger zone and the first-frame placeholders.
 */
class SettingsQualityPassTest {
    private val remote = LettaConfig(id = "default", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "", accessToken = null)

    // --- one row model ---------------------------------------------------------------------------

    @Test
    fun everyRowKindHasItsLabelHelperAndAFullHitTarget() = runComposeUiTest {
        val rows = listOf(
            SettingsRow.Toggle("toggle", "Haptics", checked = false, supporting = "Vibrate while replies run") {},
            SettingsRow.Choice("choice", "Preset", options = (1..5).map { SettingsOption("o$it", "Option $it") }, selected = "o1", supporting = "Pick one") {},
            SettingsRow.Info("info", "Version", "1.2.3", supporting = "Tap to copy"),
            SettingsRow.Status("status", "Health", SettingsStatus("Running", SettingsTone.Positive), supporting = "Since 10:00"),
            SettingsRow.Link("link", "Manage providers", supporting = "Accounts and keys") {},
        )
        setContent { Frame { SettingsModelSection(SettingsSectionModel("rows", "Rows", rows)) } }

        rows.forEach { row -> onNodeWithTag(SettingsSectionTags.ROW_PREFIX + row.key).assertHeightIsAtLeast(LettaDimens.Control.minTouchTarget) }
        listOf("Vibrate while replies run", "Pick one", "Tap to copy", "Since 10:00", "Accounts and keys").forEach { onNodeWithText(it).assertExists() }
        onNodeWithText("Option 1").assertExists()
        onNodeWithText("1.2.3").assertExists()
    }

    @Test
    fun theWholeToggleRowFlipsTheSwitch() = runComposeUiTest {
        val on = MutableStateFlow(false)
        setContent {
            Frame {
                val checked = on.collectAsState().value
                SettingsModelSection(SettingsSectionModel("s", "S", listOf(SettingsRow.Toggle("t", "Haptics", checked) { on.value = it })))
            }
        }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "t").performClick()

        assertTrue(on.value)
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "t").assertIsOn()
    }

    @Test
    fun aStatusSaysItsStateInWordsNotOnlyColour() = runComposeUiTest {
        setContent {
            Frame {
                SettingsModelSection(
                    SettingsSectionModel("s", "S", listOf(SettingsRow.Status("st", "Host", SettingsStatus("Can't reach host", SettingsTone.Error)))),
                )
            }
        }

        onNodeWithText("Can't reach host").assertExists()
    }

    @Test
    fun aDestructiveRowAsksBeforeItActs() = runComposeUiTest {
        var removed = false
        val row = SettingsRow.Link(
            key = "remove",
            title = "Remove this host",
            destructive = true,
            confirm = SettingsConfirmText("Remove this host?", "You can add it again later.", "Remove"),
        ) { removed = true }
        setContent { Frame { SettingsModelSection(SettingsSectionModel("d", "Danger", listOf(row))) } }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "remove").performClick()
        onNodeWithTag(LettaPaneTags.CONFIRM).assertExists()
        assertTrue(!removed)
        onNodeWithText("Remove").performClick()
        assertTrue(removed)
    }

    @Test
    fun aPointerShowsAShortChoiceAsASegmentedControl() = runComposeUiTest {
        val theme = MutableStateFlow(AppTheme.SYSTEM)
        setContent { Frame { AppearanceSettingsSection(AppearanceSettings(theme = SettingValue(theme) { theme.value = it })) } }

        onNodeWithTag(SettingsSectionTags.OPTION_PREFIX + "appearance-theme-" + AppTheme.LIGHT.name).performClick()

        assertEquals(AppTheme.LIGHT, theme.value)
    }

    // --- layout per width ------------------------------------------------------------------------

    @Test
    fun theLayoutFollowsTheWidth() {
        assertEquals(SettingsLayout.Phone, SettingsLayout.of(412.dp))
        assertEquals(SettingsLayout.Medium, SettingsLayout.of(720.dp))
        assertEquals(SettingsLayout.Wide, SettingsLayout.of(840.dp))
    }

    @Test
    fun aPhoneIsOneListWithTheSearchOnTop() = runComposeUiTest {
        pageAt(412.dp)

        onNodeWithTag(SettingsPageTags.SEARCH).assertExists()
        onNodeWithTag(SettingsPageTags.RAIL).assertDoesNotExist()
        onNodeWithTag(SettingsPageTags.CHIPS).assertDoesNotExist()
    }

    @Test
    fun aMediumWindowHasAChipPerSection() = runComposeUiTest {
        pageAt(720.dp)

        onNodeWithTag(SettingsPageTags.CHIPS).assertExists()
        onNodeWithTag(SettingsPageTags.CHIP_PREFIX + SettingsGroup.Appearance.name).assertExists()
        onNodeWithTag(SettingsPageTags.RAIL).assertDoesNotExist()
    }

    @Test
    fun aWideWindowHasASectionRailWithTheGroupInViewSelected() = runComposeUiTest {
        pageAt(840.dp)

        onNodeWithTag(SettingsPageTags.RAIL).assertExists()
        onNodeWithTag(SettingsPageTags.RAIL_PREFIX + SettingsGroup.Backend.name).assertExists()
        onNodeWithTag(SettingsPageTags.RAIL_PREFIX + SettingsGroup.Appearance.name).performClick()
        onNodeWithTag(SettingsPageTags.CHIPS).assertDoesNotExist()
    }

    @Test
    fun theScrollSpyPicksTheGroupAtTheTop() {
        val anchors = listOf(SettingsGroup.Backend to 0, SettingsGroup.Appearance to 600, SettingsGroup.About to 1200)

        assertEquals(SettingsGroup.Backend, settingsScrollSpy(anchors, scroll = 0, atEnd = false, slop = 40))
        assertEquals(SettingsGroup.Appearance, settingsScrollSpy(anchors, scroll = 580, atEnd = false, slop = 40))
        assertEquals(SettingsGroup.About, settingsScrollSpy(anchors, scroll = 900, atEnd = true, slop = 40))
        assertNull(settingsScrollSpy(emptyList(), scroll = 0, atEnd = false, slop = 40))
    }

    // --- Backend section -------------------------------------------------------------------------

    @Test
    fun offlineRowsThatNeedAHostAreDisabledAndSayWhy() = runComposeUiTest {
        val hostOnly = SettingsContribution(
            SettingsGroup.Providers,
            SettingsSectionModel("models", "Models", listOf(SettingsRow.Link("models-open", "Models") {})),
            needsHost = true,
        )
        page(BackendConnectionStatus.Failed("timed out"), providers = true, contributions = listOf(hostOnly))

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + SettingsCopy.PROVIDERS.key).assertIsNotEnabled()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "models-open").assertIsNotEnabled()
        assertEquals(2, onAllNodesWithText(SETTINGS_NEEDS_HOST).fetchSemanticsNodes().size)
    }

    @Test
    fun aConnectedHostUnlocksThem() = runComposeUiTest {
        page(BackendConnectionStatus.Connected("Iroh"), providers = true)

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + SettingsCopy.PROVIDERS.key).assertIsEnabled()
        onNodeWithText("Over Iroh").assertExists()
    }

    @Test
    fun aFailedTestSaysWhyAndHasNoLatency() = runComposeUiTest {
        val tester = BackendConnectionTester { _, _ -> SettingsStatus("Connection refused", SettingsTone.Error) }
        page(null, tester = tester)

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + BackendSettingsTags.TEST).assertIsNotEnabled()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + BackendSettingsTags.SERVER_URL).performTextInput("ws://10.0.0.2:4500")
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + BackendSettingsTags.TEST).performClick()

        onNodeWithText("Connection refused").assertExists()
        onNodeWithText("Can't reach").assertExists()
    }

    @Test
    fun aTestResultsBadgeIsTheLatencyOrWhatWentWrong() {
        assertEquals("Reachable · 42 ms", ConnectionTest.Done(SettingsStatus("ok", SettingsTone.Positive), 42).badge.text)
        assertEquals(SettingsTone.Error, ConnectionTest.Done(SettingsStatus("refused", SettingsTone.Error), 7).badge.tone)
        assertEquals("Not checked", ConnectionTest.Done(SettingsStatus("Iroh is checked on connect"), 1).badge.text)
    }

    @Test
    fun theStatusRowSpeaksPlainly() {
        assertEquals("Connected", BackendConnectionStatus.Connected("Iroh").settingsStatus().text)
        assertEquals("Can't reach host", BackendConnectionStatus.Failed("x").settingsStatus().text)
        assertEquals("Not connected", BackendConnectionStatus.Unconfigured.settingsStatus().text)
        assertEquals("x", BackendConnectionStatus.Failed("x").settingsHelper())
    }

    @Test
    fun theDangerZoneIsLastAndRemovesTheTokenOnlyAfterConfirming() = runComposeUiTest {
        var cleared = false
        val services = SettingsPageServices(
            BackendSettingsActions(onConfigSaved = {}, onTokenCleared = { cleared = true }),
            onOpenProviders = {},
        )
        assertEquals(SettingsGroup.Danger, services.visibleGroups(LettaConfig.Mode.SELF_HOSTED, true, hasSavedToken = true).last().first)
        assertTrue(services.visibleGroups(LettaConfig.Mode.SELF_HOSTED, true, hasSavedToken = false).none { it.first == SettingsGroup.Danger })

        setContent { Frame { DangerZoneSection(BackendFormState(remote.copy(accessToken = "sk"), BackendSettingsForm.of(remote)) {}, services.backend) } }
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + BackendSettingsTags.CLEAR_TOKEN).performClick()
        assertTrue(!cleared)
        onNodeWithText("Remove token").performClick()
        assertTrue(cleared)
    }

    // --- first frame -----------------------------------------------------------------------------

    @Test
    fun theFirstFrameDrawsBackendAndPlaceholdersForTheRest() {
        val services = SettingsPageServices(
            BackendSettingsActions(onConfigSaved = {}),
            appearance = AppearanceSettings(theme = SettingValue(MutableStateFlow(AppTheme.SYSTEM)) {}),
            onOpenProviders = {},
        )

        val first = services.pageGroups(LettaConfig.Mode.SELF_HOSTED, afterFirstFrame = false, hasSavedToken = false)
        assertEquals(1, first.drawn)
        assertEquals(listOf(SettingsGroup.Appearance, SettingsGroup.Providers), first.skeletons)

        val next = services.pageGroups(LettaConfig.Mode.SELF_HOSTED, afterFirstFrame = true, hasSavedToken = false)
        assertTrue(next.skeletons.isEmpty())
    }

    // --- search ----------------------------------------------------------------------------------

    @Test
    fun theSearchMatchesLabelsAndHelpersAcrossSections() {
        val about = SettingsContribution(SettingsGroup.About, SettingsSectionModel("about", "About", listOf(SettingsRow.Info("v", "Version", "1.0"))))
        val services = SettingsPageServices(
            BackendSettingsActions(onConfigSaved = {}, testConnection = BackendConnectionTester { _, _ -> SettingsStatus("ok") }),
            appearance = AppearanceSettings(theme = SettingValue(MutableStateFlow(AppTheme.SYSTEM)) {}),
            contributions = listOf(about),
        )
        val entries = services.searchEntries(LettaConfig.Mode.SELF_HOSTED, hasSavedToken = false)

        assertEquals(listOf("Theme"), entries.matching("THEME").map { it.title })
        assertEquals(listOf(SettingsGroup.About to "About"), entries.matching("version").map { it.group to it.section })
        assertEquals(listOf("Test connection"), entries.matching("before you save").map { it.title })
        assertTrue(entries.matching("   ").isEmpty())
        assertTrue(services.searchEntries(LettaConfig.Mode.LOCAL, false).matching("Host address").isEmpty())
    }

    @Test
    fun typingFiltersThePageAndSaysWhenNothingMatches() = runComposeUiTest {
        page(null, providers = true)

        onNodeWithTag(SettingsPageTags.SEARCH).performTextInput("providers")
        onNodeWithText(SettingsCopy.PROVIDERS.title).assertExists()
        onNodeWithTag(SettingsPageTags.GROUP_PREFIX + SettingsGroup.Backend.name).assertDoesNotExist()

        onNodeWithTag(SettingsPageTags.SEARCH).performTextInput("zzz")
        onNodeWithTag(SettingsPageTags.SEARCH_EMPTY).assertExists()
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun ComposeUiTest.page(
        connection: BackendConnectionStatus?,
        providers: Boolean = false,
        tester: BackendConnectionTester? = null,
        contributions: List<SettingsContribution> = emptyList(),
    ) {
        val services = SettingsPageServices(
            BackendSettingsActions(onConfigSaved = {}, testConnection = tester),
            onOpenProviders = if (providers) ({}) else null,
            contributions = contributions,
        )
        setContent { Frame { SettingsPage(SettingsPageState(remote, connection), services) } }
    }

    private fun ComposeUiTest.pageAt(width: Dp) {
        val services = SettingsPageServices(
            BackendSettingsActions(onConfigSaved = {}),
            appearance = AppearanceSettings(theme = SettingValue(MutableStateFlow(AppTheme.SYSTEM)) {}),
            onOpenProviders = {},
        )
        setContent {
            Frame {
                Box(Modifier.width(width).height(800.dp)) { SettingsPage(SettingsPageState(remote), services) }
            }
        }
    }
}
