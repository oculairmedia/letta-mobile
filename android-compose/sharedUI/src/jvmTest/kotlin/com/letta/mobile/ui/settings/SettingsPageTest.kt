@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaConfigMigration
import com.letta.mobile.data.settings.BackendConnectionTester
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.header.ShellHeaderTags
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared Settings page (letta-mobile-ckmub.3.12, S10): the Backend section in both modes with no
 * Cloud option (ckmub.9), its URL check, save and test, and the page's groups and anchors.
 */
class SettingsPageTest {
    private val hexId = "1a".repeat(32)
    private val remote = LettaConfig(id = "default", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "", accessToken = null)
    private val local = remote.copy(mode = LettaConfig.Mode.LOCAL)

    private fun ComposeUiTest.page(
        state: SettingsPageState,
        services: SettingsPageServices = SettingsPageServices(backend = BackendSettingsActions(onConfigSaved = {})),
        onBack: (() -> Unit)? = null,
    ) {
        setContent { Frame { SettingsPage(state, services, onBack = onBack) } }
    }

    @Test
    fun theModeIsLocalRuntimeOrRemoteHostAndNothingElse() = runComposeUiTest {
        page(SettingsPageState(remote))

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + BackendSettingsTags.MODE).performClick()

        onNodeWithTag(option(LettaConfig.Mode.LOCAL)).assertExists()
        onNodeWithTag(option(LettaConfig.Mode.SELF_HOSTED)).assertExists()
        assertEquals(listOf("Remote host", "Local runtime"), LettaConfig.Mode.entries.map { it.settingsLabel })
        assertEquals(0, onAllNodes(hasText("Cloud", substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun aRemoteHostAsksForTheAddressAndTheLocalRuntimeDoesNot() = runComposeUiTest {
        page(SettingsPageState(remote))
        onNodeWithTag(field(BackendSettingsTags.SERVER_URL)).assertExists()
        onNodeWithTag(field(BackendSettingsTags.TOKEN)).assertExists()

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + BackendSettingsTags.MODE).performClick()
        onNodeWithTag(option(LettaConfig.Mode.LOCAL)).performClick()

        onNodeWithTag(field(BackendSettingsTags.SERVER_URL)).assertDoesNotExist()
        onNodeWithTag(field(BackendSettingsTags.TOKEN)).assertDoesNotExist()
        onNodeWithTag(action(BackendSettingsTags.SAVE)).assertIsEnabled()
    }

    @Test
    fun theLocalRuntimeSavesWithoutAnAddress() = runComposeUiTest {
        var saved: LettaConfig? = null
        page(SettingsPageState(local), SettingsPageServices(BackendSettingsActions(onConfigSaved = { saved = it })))

        onNodeWithTag(field(BackendSettingsTags.SERVER_URL)).assertDoesNotExist()
        onNodeWithTag(action(BackendSettingsTags.SAVE)).performClick()

        assertEquals(LettaConfig.Mode.LOCAL, saved?.mode)
    }

    @Test
    fun aBareEndpointIdIsExplainedAndCannotBeSaved() = runComposeUiTest {
        page(SettingsPageState(remote))

        onNodeWithTag(field(BackendSettingsTags.SERVER_URL)).performTextInput(hexId)

        onNodeWithText("This looks like an Iroh endpoint id or ticket: enter it as iroh://$hexId").assertExists()
        onNodeWithTag(action(BackendSettingsTags.SAVE)).assertIsNotEnabled()
    }

    @Test
    fun aValidIrohUrlSavesAsARemoteHost() = runComposeUiTest {
        var saved: LettaConfig? = null
        page(SettingsPageState(remote), SettingsPageServices(BackendSettingsActions(onConfigSaved = { saved = it })))

        onNodeWithTag(field(BackendSettingsTags.SERVER_URL)).performTextInput("  iroh://$hexId@10.0.0.2:4433 ")
        onNodeWithTag(action(BackendSettingsTags.SAVE)).assertIsEnabled().performClick()

        assertEquals("iroh://$hexId@10.0.0.2:4433", saved?.serverUrl)
        assertEquals(LettaConfig.Mode.SELF_HOSTED, saved?.mode)
        assertNull(saved?.accessToken)
    }

    @Test
    fun aFixedUrlClearsTheProblem() = runComposeUiTest {
        page(SettingsPageState(remote))

        onNodeWithTag(field(BackendSettingsTags.SERVER_URL)).performTextInput("letta.example.com")
        onNodeWithTag(BackendSettingsTags.PROBLEM, useUnmergedTree = true).assertExists()
        onNodeWithTag(field(BackendSettingsTags.SERVER_URL)).performTextClearance()
        onNodeWithTag(field(BackendSettingsTags.SERVER_URL)).performTextInput("wss://letta.example.com/ws")
        onNodeWithTag(BackendSettingsTags.PROBLEM, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aMigratedCloudConfigSaysWhyAndSavingClearsTheNotice() = runComposeUiTest {
        var saved: LettaConfig? = null
        val migrated = LettaConfigMigration.migrate("CLOUD", remote.copy(serverUrl = "https://api.letta.com", accessToken = "sk"))
        page(SettingsPageState(migrated), SettingsPageServices(BackendSettingsActions(onConfigSaved = { saved = it })))

        onNodeWithText(LettaConfigMigration.CLOUD_NOTICE).assertExists()
        onNodeWithTag(field(BackendSettingsTags.SERVER_URL)).performTextInput("ws://10.0.0.2:4500")
        onNodeWithTag(action(BackendSettingsTags.SAVE)).performClick()

        assertNull(saved?.migrationNotice)
        assertEquals("ws://10.0.0.2:4500", saved?.serverUrl)
    }

    @Test
    fun testConnectionShowsWhatTheProbeFound() = runComposeUiTest {
        val probed = mutableListOf<Pair<String, String?>>()
        val tester = BackendConnectionTester { url, token ->
            probed += url to token
            SettingsStatus("Connected: letta-code 0.34.8", SettingsTone.Positive)
        }
        page(SettingsPageState(remote), SettingsPageServices(BackendSettingsActions(onConfigSaved = {}, testConnection = tester)))

        onNodeWithTag(field(BackendSettingsTags.SERVER_URL)).performTextInput("ws://10.0.0.2:4500")
        onNodeWithTag(action(BackendSettingsTags.TEST)).performClick()

        onNodeWithText("Connected: letta-code 0.34.8").assertExists()
        assertEquals(listOf<Pair<String, String?>>("ws://10.0.0.2:4500" to null), probed)
    }

    @Test
    fun anEmptyTokenFieldKeepsTheSavedToken() {
        val withToken = remote.copy(serverUrl = "https://letta.example.com", accessToken = "secret")
        val form = BackendSettingsForm.of(withToken).copy(serverUrl = "https://other.example.com")

        assertEquals("secret", form.applyTo(withToken).accessToken)
        assertNull(form.problem)
    }

    @Test
    fun theConnectionAndItsFailureShowAboveTheSections() = runComposeUiTest {
        page(SettingsPageState(remote, BackendConnectionStatus.Failed("Iroh tickets need the relay")))

        onNodeWithText("Connection failed").assertExists()
        onNodeWithText("Iroh tickets need the relay").assertExists()
    }

    @Test
    fun theBackArrowShowsOnlyWhenTheHostHasSomewhereToGo() = runComposeUiTest {
        var back = false
        page(SettingsPageState(remote), onBack = { back = true })

        onNodeWithTag(ShellHeaderTags.NAV).performClick()
        assertTrue(back)
    }

    @Test
    fun platformSectionsSitInTheirGroupAndModeOnlySectionsFollowTheForm() = runComposeUiTest {
        val onlyLocal = SettingsContribution(
            group = SettingsGroup.Backend,
            initial = SettingsSectionModel("only-local", "On-device model", listOf(SettingsRow.Note("n", SettingsStatus("model")))),
            showForMode = LettaConfig.Mode.LOCAL,
        )
        val about = SettingsContribution(SettingsGroup.About, SettingsSectionModel("about", "About", listOf(SettingsRow.Info("v", "Version", "1.0"))))
        page(SettingsPageState(remote), SettingsPageServices(BackendSettingsActions(onConfigSaved = {}), contributions = listOf(onlyLocal, about)))

        onNodeWithTag(SettingsSectionTags.SECTION_PREFIX + "only-local").assertDoesNotExist()
        onNodeWithTag(SettingsPageTags.GROUP_PREFIX + SettingsGroup.About.name).assertExists()
        onNodeWithTag(SettingsPageTags.GROUP_PREFIX + SettingsGroup.Chat.name).assertDoesNotExist()

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + BackendSettingsTags.MODE).performClick()
        onNodeWithTag(option(LettaConfig.Mode.LOCAL)).performClick()
        onNodeWithTag(SettingsSectionTags.SECTION_PREFIX + "only-local").assertExists()
    }

    @Test
    fun theAboutAnchorScrollsItsGroupIntoView() = runComposeUiTest {
        val about = SettingsContribution(SettingsGroup.About, SettingsSectionModel("about", "About", listOf(SettingsRow.Info("v", "Version", "1.0"))))
        setContent {
            Frame {
                Box(Modifier.width(400.dp).height(300.dp)) {
                    SettingsPage(
                        SettingsPageState(remote, section = ShellSettingsSection.About),
                        SettingsPageServices(BackendSettingsActions(onConfigSaved = {}), contributions = listOf(about)),
                    )
                }
            }
        }

        onNodeWithTag(SettingsSectionTags.SECTION_PREFIX + "about").performScrollTo().assertExists()
    }

    private fun option(mode: LettaConfig.Mode) = SettingsSectionTags.OPTION_PREFIX + BackendSettingsTags.MODE + "-" + mode.name

    private fun field(key: String) = SettingsSectionTags.ROW_PREFIX + key

    private fun action(key: String) = SettingsSectionTags.ACTION_PREFIX + key
}

/** The pointer host (pickers open as dialogs) in the shared theme. */
@Composable
internal fun Frame(content: @Composable () -> Unit) {
    ProvideLettaHost(LettaHost.Pointer) { SharedMaterialTheme { content() } }
}
