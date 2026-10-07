@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.ui.shell.rail.ShellAgentRail
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellAgentRailState
import com.letta.mobile.ui.shell.rail.ShellAgentRailTags
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanel
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellCanvasRowModel
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowModel
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlin.test.Test
import kotlin.test.assertEquals

/** Touch hosts open a row's menu by long-press (letta-mobile-c3np7.2.12): the default row menu. */
class ShellLongPressMenuTest {

    private val panel = ShellAgentPanelState(
        agent = ShellPanelAgent(name = "Meridian", agentId = "agent-1"),
        conversations = listOf(ShellConversationRowModel(id = "c1", title = "Handoff", preview = "", timeLabel = "4m")),
        canvases = listOf(ShellCanvasRowModel(id = CanvasId("k1"), title = "Board", timeLabel = "13h")),
    )

    @Test
    fun longPressingAConversationOffersArchive() = runComposeUiTest {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        setContent { Panel(ShellAgentPanelActions(onArchiveConversation = { id, archived -> toggled += id to archived })) }
        onNodeWithText("Handoff").performTouchInput { longClick() }
        onNodeWithText("Delete chat").assertExists()
        onNodeWithText("Archive chat").performClick()
        assertEquals(listOf("c1" to true), toggled)
        onNodeWithTag(ShellRowMenuTags.MENU).assertDoesNotExist()
    }

    @Test
    fun deletingFromTheLongPressMenuStillAsks() = runComposeUiTest {
        val deleted = mutableListOf<String>()
        setContent { Panel(ShellAgentPanelActions(onDeleteConversation = { deleted += it })) }
        onNodeWithText("Handoff").performTouchInput { longClick() }
        onNodeWithText("Delete chat").performClick()
        assertEquals(emptyList(), deleted)
        onNodeWithText("Delete chat?").assertExists()
        onNodeWithText("Delete").performClick()
        assertEquals(listOf("c1"), deleted)
    }

    @Test
    fun aTapStillOpensTheRowWithoutAMenu() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { Panel(ShellAgentPanelActions(onConversationSelected = { opened += it })) }
        onNodeWithText("Handoff").performClick()
        assertEquals(listOf("c1"), opened)
        onNodeWithTag(ShellRowMenuTags.MENU).assertDoesNotExist()
    }

    @Test
    fun aLongPressDoesNotAlsoOpenTheRow() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { Panel(ShellAgentPanelActions(onConversationSelected = { opened += it })) }
        onNodeWithText("Handoff").performTouchInput { longClick() }
        onNodeWithText("Archive chat").assertExists()
        assertEquals(emptyList(), opened)
    }

    @Test
    fun aCanvasWithoutAnArchiveHasNoMenu() = runComposeUiTest {
        setContent { Panel(ShellAgentPanelActions(onArchiveCanvas = null)) }
        onNodeWithText("Board").performTouchInput { longClick() }
        onNodeWithTag(ShellRowMenuTags.MENU).assertDoesNotExist()
    }

    @Test
    fun longPressingACanvasOffersArchive() = runComposeUiTest {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        setContent { Panel(ShellAgentPanelActions(onArchiveCanvas = { id, archived -> toggled += id.value to archived })) }
        onNodeWithText("Board").performTouchInput { longClick() }
        onNodeWithText("Archive canvas").performClick()
        assertEquals(listOf("k1" to true), toggled)
    }

    @Test
    fun longPressingAnAgentOrbOffersItsMenu() = runComposeUiTest {
        val events = mutableListOf<String>()
        val entries = ShellRailMapping.entries(
            ShellRailMapping.groups(listOf("a1" to "Alpha"), selectedAgentId = null),
            ShellRailFocus(pinnedAgentIds = setOf("a1")),
        )
        setContent {
            MaterialTheme {
                ShellAgentRail(
                    state = ShellAgentRailState(entries = entries),
                    actions = ShellAgentRailActions(
                        onAgentSelected = { events += "open:$it" },
                        onAgentPinnedChange = { id, pinned -> events += "pin:$id:$pinned" },
                        onAgentSettings = { events += "settings:$it" },
                    ),
                )
            }
        }
        onNodeWithTag(ShellAgentRailTags.orb("Alpha")).performTouchInput { longClick() }
        onNodeWithText("Open").assertExists()
        onNodeWithText("Agent settings").assertExists()
        onNodeWithText("Unpin agent").performClick()
        assertEquals(listOf("pin:a1:false"), events)
        onNodeWithTag(ShellAgentRailTags.orb("Alpha")).performTouchInput { longClick() }
        onNodeWithText("Agent settings").performClick()
        assertEquals(listOf("pin:a1:false", "settings:a1"), events)
    }

    @Composable
    private fun Panel(actions: ShellAgentPanelActions) {
        MaterialTheme {
            ShellAgentPanel(state = panel, actions = actions, modifier = Modifier.width(280.dp).fillMaxHeight())
        }
    }
}
