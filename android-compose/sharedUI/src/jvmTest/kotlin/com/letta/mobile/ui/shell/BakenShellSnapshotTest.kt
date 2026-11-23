@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.model.DisplayNames
import com.letta.mobile.ui.chat.AgentSphere
import com.letta.mobile.ui.chat.surface.timeline.rows.RenderRow
import com.letta.mobile.ui.chat.surface.timeline.rows.message
import com.letta.mobile.ui.chat.surface.timeline.rows.single
import com.letta.mobile.ui.shell.pages.home.HomeConnectionPanel
import com.letta.mobile.ui.shell.pages.home.HomeConnectionState
import com.letta.mobile.ui.shell.rail.ShellAgentRail
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellAgentRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shell in the Baken brand (Home rail with the Baken mark, the Home hero with the two-colour mark and the wordmark,
 * a vermilion primary action and a composer) and a short chat (the user's neutral bubble, the assistant sphere),
 * rendered in both themes to build/baken-shell-snapshots for review.
 */
class BakenShellSnapshotTest {
    private val entries = ShellRailMapping.entries(
        ShellRailMapping.groups(listOf("a1" to "Alpha", "b1" to "Beta"), selectedAgentId = null),
        ShellRailFocus(selectedAgentId = "a1"),
    )

    private fun snapshot(dark: Boolean) = runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                Row(Modifier.size(FRAME_WIDTH.dp, FRAME_HEIGHT.dp).background(MaterialTheme.colorScheme.background).testTag(FRAME)) {
                    ShellAgentRail(
                        state = ShellAgentRailState(entries = entries, homeSelected = true),
                        actions = ShellAgentRailActions(),
                        modifier = Modifier.fillMaxHeight(),
                    )
                    Column(Modifier.width(CONTENT_WIDTH.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        HomeConnectionPanel(HomeConnectionState(ChatScreenStatus.Loading), onRetryConnection = {}, wordmark = DisplayNames.WORDMARK)
                        Button(onClick = {}) { Text("Send") }
                        OutlinedTextField(value = "Message Alpha", onValueChange = {})
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithContentDescription("Home").assertExists()
        val out = File("build/baken-shell-snapshots").apply { mkdirs() }.resolve("shell-${if (dark) "dark" else "light"}.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    /** A short chat (the user's neutral bubble, the agent's reply under the vermilion-to-petrol sphere) in one theme. */
    private fun chatSnapshot(dark: Boolean) = runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                Column(
                    Modifier.size(CHAT_WIDTH.dp, CHAT_HEIGHT.dp).background(MaterialTheme.colorScheme.background).padding(16.dp)
                        .testTag(FRAME),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RenderRow(single(message("u1", "user", "Plan a taco night for six")))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentSphere(size = 28.dp)
                        Text("Sure: three fillings, two salsas, and a lime crema.", color = MaterialTheme.colorScheme.onSurface)
                    }
                    RenderRow(single(message("u2", "user", "Make one of them vegetarian")))
                }
            }
        }
        waitForIdle()
        val out = File("build/baken-shell-snapshots").apply { mkdirs() }.resolve("chat-${if (dark) "dark" else "light"}.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun shellInBothThemes() = listOf(true, false).forEach(::snapshot)

    @Test
    fun chatInBothThemes() = listOf(true, false).forEach(::chatSnapshot)

    private companion object {
        const val FRAME = "baken-shell-frame"
        const val WINDOW_WIDTH = 900
        const val WINDOW_HEIGHT = 700
        const val FRAME_WIDTH = 760
        const val FRAME_HEIGHT = 600
        const val CONTENT_WIDTH = 680
        const val CHAT_WIDTH = 520
        const val CHAT_HEIGHT = 320
    }
}
