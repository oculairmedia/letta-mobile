@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.LettaConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** A host that keeps several backends adds, switches and removes them in the Backend section. */
class SavedBackendsTest {
    private val home = LettaConfig(id = "home", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "ws://home.lan:8080")
    private val work = LettaConfig(id = "work", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "ws://work.lan:8080")

    private val added = mutableListOf<LettaConfig>()
    private val removed = mutableListOf<String>()

    private fun saved(configs: List<LettaConfig>) = SavedBackends(
        configs = configs,
        activeId = home.id,
        onSwitch = {},
        onAdd = { added += it },
        onRemove = { removed += it },
    )

    private val services = SettingsPageServices(backend = BackendSettingsActions(onConfigSaved = {}))

    @Test
    fun addingABackendSavesANewOneFromAnEmptyForm() = runComposeUiTest {
        setContent { Frame { SettingsPage(SettingsPageState(home, saved = saved(listOf(home))), services) } }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + SavedBackendsTags.ADD).performClick()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + BackendSettingsTags.SERVER_URL).performTextInput("ws://lab.lan:8080")
        onNodeWithText("Add backend").performClick()

        assertEquals(listOf("ws://lab.lan:8080"), added.map { it.serverUrl })
        // Back to the backend in use.
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + SavedBackendsTags.ADD).assertExists()
    }

    @Test
    fun aBackendNotInUseCanBeRemovedAfterConfirming() = runComposeUiTest {
        setContent { Frame { SettingsPage(SettingsPageState(home, saved = saved(listOf(home, work))), services) } }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + SavedBackendsTags.SAVED).assertExists()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + SavedBackendsTags.REMOVE_PREFIX + home.id).assertDoesNotExist()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + SavedBackendsTags.REMOVE_PREFIX + work.id).performClick()
        onNodeWithText("Remove").performClick()

        assertEquals(listOf(work.id), removed)
    }
}
