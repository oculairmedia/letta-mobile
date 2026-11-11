@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.acceptance

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAgentPort
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAuthor
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.canvas.boards.ProvideCanvasBoardSource
import com.letta.mobile.ui.shell.modules.ShellModuleTags
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.LocalShellLayoutAgentPort
import com.letta.mobile.ui.shell.workspace.rememberShellLayoutController

import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import kotlin.test.Test
import kotlin.test.assertEquals

class MissingContributionRecoveryTest {
    @Test
    fun unavailableContributionsLeaveThePageAndResetReachable() = runDesktopComposeUiTest(width = 1800, height = 800) {
        val fixture = AcceptanceWorkspace()
        val port = ShellLayoutAgentPort()
        val settings = InMemorySecureSettingsStore()
        val host = fixture.host()
        setContent {
            CompositionLocalProvider(LocalShellLayoutAgentPort provides port) {
                ProvideCanvasBoardSource(fixture.boards) {
                    val controller = rememberShellLayoutController(null, settings)
                    LettaShellWorkspace(fixture.state(controller.workspaceLayout().lanes), host, Modifier.size(1800.dp, 800.dp))
                }
            }
        }
        waitForIdle()
        val missing = fixture.lanes(main = listOf(fixture.page, ModuleSlot(RuntimeViewId("missing.contribution"))),
            side = listOf(ModuleSlot(ShellViews.CANVAS, "absent-board")))
        port.commit(ShellLayoutAuthor.Person, "Stored unavailable contributions", missing, port.attached.value!!.config.value)
        waitUntil(AcceptanceWorkspace.TIMEOUT) { onAllNodesWithTag(ShellModuleTags.UNAVAILABLE).fetchSemanticsNodes().size == 2 }
        onNodeWithTag(AcceptanceWorkspace.PAGE).assertExists()
        onNodeWithTag(AcceptanceWorkspace.RESET).performClick()
        waitForIdle()
        assertEquals(ShellLayoutConfig.Default, port.attached.value!!.config.value)
        assertEquals(0, onAllNodesWithTag(ShellModuleTags.UNAVAILABLE).fetchSemanticsNodes().size)
        onNodeWithTag(AcceptanceWorkspace.PAGE).assertExists()
    }

    @Test
    fun unknownSlotSurvivesSavingAndUpgrading() {
        val page = ModuleSlot(ShellViews.PAGE)
        val unknown = ModuleSlot(RuntimeViewId("missing.contribution"), "object")
        val saved = ShellLayoutConfig(version = 0, lanes = mapOf(ShellLanes.MAIN to listOf(page, unknown)))

        val restored = ShellLayoutConfig.decode(saved.encode()).upgraded()

        assertEquals(listOf(page, unknown), restored.mainLane())
    }
}
