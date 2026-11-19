@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.LettaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scan to connect sits in the Backend section in either runtime mode, wherever there is a scanner. */
class BackendScanEntryTest {
    private val local = LettaConfig(id = "default", mode = LettaConfig.Mode.LOCAL, serverUrl = "", accessToken = null)
    private val scanRow = SettingsSectionTags.ROW_PREFIX + BackendSettingsTags.SCAN

    @Test
    fun scanToConnectIsOfferedOnTheLocalRuntimeToo() = runComposeUiTest {
        var scans = 0
        val actions = BackendSettingsActions(onConfigSaved = {}, onScanToConnect = { scans++ })
        setContent { MaterialTheme { BackendSettingsSection(BackendFormState(local, BackendSettingsForm.of(local)) {}, actions) } }
        onNodeWithTag(scanRow).performClick()
        assertEquals(1, scans)
    }

    @Test
    fun noScannerMeansNoScanRow() = runComposeUiTest {
        val actions = BackendSettingsActions(onConfigSaved = {})
        setContent { MaterialTheme { BackendSettingsSection(BackendFormState(local, BackendSettingsForm.of(local)) {}, actions) } }
        onNodeWithTag(scanRow).assertDoesNotExist()
    }

    @Test
    fun searchFindsScanToConnectInEitherMode() {
        val services = SettingsPageServices(BackendSettingsActions(onConfigSaved = {}, onScanToConnect = {}))
        LettaConfig.Mode.entries.forEach { mode ->
            assertTrue(services.searchEntries(mode, hasSavedToken = false).any { it.title == SettingsCopy.SCAN.title }, "$mode")
        }
    }
}
