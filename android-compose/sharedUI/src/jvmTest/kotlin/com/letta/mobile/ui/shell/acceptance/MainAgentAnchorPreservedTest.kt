@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.acceptance

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAgentPort
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAuthor
import com.letta.mobile.data.shell.layout.edit.ShellLayoutCommit
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.LocalShellLayoutAgentPort
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigationActions
import com.letta.mobile.ui.shell.workspace.rememberShellLayoutController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MainAgentAnchorPreservedTest {
    @Test
    fun layoutCommitsMovesUndoAndResetNeverNavigate() = runDesktopComposeUiTest(width = 1800, height = 800) {
        val fixture = AcceptanceWorkspace()
        val port = ShellLayoutAgentPort()
        val settings = InMemorySecureSettingsStore()
        val calls = mutableListOf<String>()
        val host = fixture.host(ShellWorkspaceNavigationActions(
            rail = ShellAgentRailActions(onAgentSelected = { calls += "agent" }, onHome = { calls += "home" },
                onNewSession = { calls += "session" }, onAgentPinnedChange = { _, _ -> calls += "pin" },
                onAgentSettings = { calls += "agent settings" }),
            panel = ShellAgentPanelActions(onOpenSection = { calls += "section" }, onOpenSettings = { calls += "settings" },
                onNewChat = { calls += "chat" }, onEditAgent = { calls += "edit" }, onArchiveFilterChange = { calls += "filter" },
                onConversationSelected = { calls += "conversation" }, onArchiveConversation = { _, _ -> calls += "archive" },
                onDeleteConversation = { calls += "delete" }, onRenameConversation = { _, _ -> calls += "rename" },
                onPinConversation = { _, _ -> calls += "pin conversation" }, onForkConversation = { _, _ -> calls += "fork" },
                onOpenCanvas = { calls += "canvas" }, onArchiveCanvas = { _, _ -> calls += "archive canvas" }),
            onRailDismiss = { calls += "dismiss" },
        ))
        setContent {
            CompositionLocalProvider(LocalShellLayoutAgentPort provides port) {
                val controller = rememberShellLayoutController(null, settings)
                LettaShellWorkspace(fixture.state(controller.workspaceLayout().lanes), host, Modifier.size(1800.dp, 800.dp))
            }
        }
        waitForIdle()
        val original = port.attached.value!!.config.value
        val layouts = listOf(fixture.lanes(side = listOf(fixture.tasks)),
            fixture.lanes(main = listOf(fixture.page, fixture.tasks)), fixture.lanes())
        layouts.forEach { config ->
            val before = port.attached.value!!.config.value
            assertIs<ShellLayoutCommit.Done>(port.commit(ShellLayoutAuthor.Person, "Change modules", config, before))
            waitForIdle()
            assertEquals(listOf(fixture.page), config.mainLane(1))
            onNodeWithTag(AcceptanceWorkspace.PAGE).assertExists()
            assertEquals(emptyList(), calls)
        }
        assertIs<ShellLayoutCommit.Done>(port.undoLatest(ShellLayoutAuthor.Person))
        waitForIdle()
        assertIs<ShellLayoutCommit.Done>(port.reset(ShellLayoutAuthor.Person))
        waitForIdle()
        assertEquals(original, port.attached.value!!.config.value)
        assertEquals(emptyList(), calls, "layout never selects a different agent or conversation")
        onNodeWithTag(AcceptanceWorkspace.PAGE).assertExists()
    }
}
