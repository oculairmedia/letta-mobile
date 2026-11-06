@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import com.letta.mobile.data.objectverbs.binding.withDevicePins

import com.letta.mobile.ui.components.ProvideObjectVerbs

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.home.SettingsStoreHomePinStore
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.shell.rail.ShellAgentRail
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellAgentRailState
import com.letta.mobile.ui.shell.rail.ShellAgentRailTags
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow

class SharedRailPinVerbTest {
    @Test
    fun railOrbPinsAndUnpinsThroughTheSharedDesktopBindingAndHomeSeesBoth() = runComposeUiTest {
        val backing = InMemorySecureSettingsStore().withDevicePins()
        val home = SettingsStoreHomePinStore(backing, emptyList(), source = MutableStateFlow("desktop-backend"))
        val pins = home.binding
        setContent {
            val ids by pins.ids("agent").collectAsState()
            ProvideObjectVerbs(pins) {
                MaterialTheme {
                    val entries = ShellRailMapping.entries(
                        ShellRailMapping.groups(listOf("a1" to "Alpha", "a2" to "Beta"), null),
                        ShellRailFocus(objectFacetsByAgentId = remember(ids) {
                            listOf("a1", "a2").associateWith { id ->
                                checkNotNull(pins.registry.describe(
                                    com.letta.mobile.data.objectverbs.ObjectRef("agent", id),
                                    com.letta.mobile.data.objectverbs.VerbContext("rail"),
                                )).facets
                            }
                        }),
                    )
                    ShellAgentRail(ShellAgentRailState(entries), ShellAgentRailActions())
                }
            }
        }
        onNodeWithTag(ShellAgentRailTags.PIN_DIVIDER).assertDoesNotExist()
        onNodeWithTag(ShellAgentRailTags.orb("Alpha")).performTouchInput { longClick() }
        onNodeWithText("Pin agent").performClick()
        assertEquals(listOf("agent:a1"), home.pinnedKeys.value)
        onNodeWithTag(ShellAgentRailTags.PIN_DIVIDER).assertExists()
        onNodeWithTag(ShellAgentRailTags.orb("Alpha")).performTouchInput { longClick() }
        onNodeWithText("Unpin agent").performClick()
        assertEquals(emptyList(), home.pinnedKeys.value)
        onNodeWithTag(ShellAgentRailTags.PIN_DIVIDER).assertDoesNotExist()
    }
}
