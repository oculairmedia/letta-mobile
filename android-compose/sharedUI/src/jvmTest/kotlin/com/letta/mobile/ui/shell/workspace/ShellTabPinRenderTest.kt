@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.desktopshell.ShellLayoutStore
import com.letta.mobile.data.shell.SessionShellChatPort
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.components.tabs.LettaTabStripTags
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.host.desktopPointer
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class ShellTabPinRenderTest {
    @Test
    fun headerPinsAndUnpinsPreview() = runComposeUiTest {
        val state = ShellWorkspaceState(ShellDestination.Chat(conversationId = "a"))
        val navigator = ShellWorkspaceNavigator(state, SessionShellChatPort())
        val layout = ShellLayoutController(ShellLayoutStore(InMemorySecureSettingsStore(), "test"))
        setContent {
            MaterialTheme { ProvideLettaHost(LettaHost.desktopPointer { it }) {
                val header = rememberShellWorkspaceHeader(
                    ShellWorkspaceHeaderBinding(navigator, layout, ShellWorkspaceSearch(null, UnifiedSearchSources())) {
                        LettaTab(it, "Alpha")
                    },
                )
                ShellWorkspaceHeader(header, Modifier.size(800.dp, 48.dp))
            } }
        }
        fun capture(name: String) {
            val file = File("build/reports/tab-pin/$name.png")
            file.parentFile.mkdirs()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        }
        capture("preview")
        onNodeWithContentDescription("Pin Alpha tab").performClick()
        runOnIdle { assertEquals(listOf("a"), state.tabs.pinnedConversationIds) }
        capture("pinned")
        onNodeWithContentDescription("Unpin Alpha tab").performClick()
        runOnIdle { assertEquals("a", state.tabs.transientConversationId) }
        capture("unpinned")
        onNodeWithTag(LettaTabStripTags.tab("a")).performTouchInput { doubleClick() }
        runOnIdle { assertEquals(listOf("a"), state.tabs.pinnedConversationIds) }
        onNodeWithTag(LettaTabStripTags.tab("a")).performMouseInput { rightClick() }
        onNodeWithText("Unpin tab").performClick()
        runOnIdle { assertEquals("a", state.tabs.transientConversationId) }
    }
}
