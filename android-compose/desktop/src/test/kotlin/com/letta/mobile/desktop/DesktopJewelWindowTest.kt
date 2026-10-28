@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceHost
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceSlots
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.ShellSidebarControl
import com.letta.mobile.ui.shell.workspace.ShellWindowChrome
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceDrawer
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceEvents
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceLayout
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigation
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigationActions
import com.letta.mobile.ui.shell.workspace.ShellConversationTabs
import com.letta.mobile.ui.shell.workspace.ShellMinimalHeader
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.ui.shell.workspace.barHeight
import dev.nucleusframework.window.styling.TitleBarStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopJewelWindowTest {
    @Test
    fun idleHoistedHeaderDoesNotRecomposeContent() = runDesktopComposeUiTest(width = 1440, height = 900) {
        mainClock.autoAdvance = false
        val header = mutableStateOf<ShellWorkspaceHeaderState?>(null)
        val title = mutableStateOf("Workspace title")
        var contentPasses = 0
        setContent {
            val style = rememberDesktopTitleBarStyle(header)
            val frame = Any()
            SideEffect { contentPasses++ }
            Column {
                DesktopTitleBarHeader("Fallback", header, Modifier.width(1440.dp).height(style.metrics.height))
                // The old window binding and DesktopShellFrame had a fresh identity on each pass.
                HeaderLoopWorkspace(header, title, frame)
            }
        }
        repeat(10) { mainClock.advanceTimeByFrame() }
        val settled = contentPasses
        repeat(60) { mainClock.advanceTimeByFrame() }
        println("desktop workspace tracer: settle=$settled, after60=$contentPasses")
        assertTrue(settled > 0 && header.value != null, "the workspace must compose and hoist its header")
        assertEquals(settled, contentPasses, "idle window content must settle across 60 frames")
        runOnIdle { title.value = "Renamed workspace" }
        repeat(5) { mainClock.advanceTimeByFrame() }
        onNodeWithText("Renamed workspace").assertExists()
        assertEquals(settled, contentPasses, "a real workspace header update stays in the title bar")
    }

    @Test
    fun headerUpdatesStayInTitleBarAndIdleContentSettles() = runComposeUiTest {
        mainClock.autoAdvance = false
        val header = mutableStateOf<ShellWorkspaceHeaderState?>(null)
        var contentPasses = 0
        var observedStyle: TitleBarStyle? = null
        setContent {
            val style = rememberDesktopTitleBarStyle(header)
            SideEffect {
                contentPasses++
                observedStyle = style
            }
            Column {
                DesktopTitleBarHeader("Fallback", header, Modifier.width(900.dp).height(style.metrics.height))
                Text("Window content")
            }
        }
        repeat(10) { mainClock.advanceTimeByFrame() }
        val settled = contentPasses
        repeat(60) { mainClock.advanceTimeByFrame() }
        assertTrue(settled > 0, "window content must have composed")
        assertEquals(settled, contentPasses, "idle content must settle across 60 frames")
        onNodeWithText("Fallback").assertExists()
        runOnIdle { header.value = ShellWorkspaceHeaderState(title = "Updated title") }
        repeat(2) { mainClock.advanceTimeByFrame() }
        onNodeWithText("Updated title").assertExists()
        println("desktop header tracer: settled=$settled, after60=$settled, afterTitle=$contentPasses")
        assertEquals(settled, contentPasses, "a title-only update must stay in the title bar")

        runOnIdle {
            header.value = ShellWorkspaceHeaderState(
                tabs = ShellConversationTabs(listOf(LettaTab("chat", "Open chat")), activeKey = "chat"),
            )
        }
        repeat(2) { mainClock.advanceTimeByFrame() }
        onNodeWithText("Open chat").assertExists()
        assertEquals(Color.Transparent, checkNotNull(observedStyle).colors.border, "tabs join the page without a hairline")
        runOnIdle { header.value = ShellWorkspaceHeaderState(title = "Compact", minimal = ShellMinimalHeader(null, {})) }
        repeat(2) { mainClock.advanceTimeByFrame() }
        onNodeWithText("Compact").assertExists()
        assertEquals(checkNotNull(header.value).barHeight(48.dp), checkNotNull(observedStyle).metrics.height)
    }

    @Test
    fun `window host retains Nucleus native decoration contract`() {
        val project = desktopProjectDirectory()
        val source = project.resolve(
            "src/main/kotlin/com/letta/mobile/desktop/DesktopJewelWindow.kt",
        ).readText()
        val build = project.resolve("build.gradle.kts").readText()

        assertContains(source, "DecoratedWindow(")
        assertContains(source, "BasicTitleBar(")
        assertContains(source, "style = titleBarStyle")
        assertFalse(source.lineSequence().any { it.trim().startsWith("undecorated = true") })
        val hasCore = build.contains("implementation(\"dev.nucleusframework:nucleus.decorated-window-core:") ||
            build.contains("libs.nucleus.decorated.window.core")
        kotlin.test.assertTrue(hasCore, "build.gradle.kts must include nucleus.decorated-window-core")
        val hasAwt = build.contains("implementation(\"dev.nucleusframework:nucleus.decorated-window-awt:") ||
            build.contains("libs.nucleus.decorated.window.awt")
        kotlin.test.assertTrue(hasAwt, "build.gradle.kts must include nucleus.decorated-window-awt")
        val hasJni = build.contains("implementation(\"dev.nucleusframework:nucleus.decorated-window-jni:") ||
            build.contains("libs.nucleus.decorated.window.jni")
        kotlin.test.assertTrue(hasJni, "build.gradle.kts must include nucleus.decorated-window-jni")
    }
}

/** The real shared workspace over a changing frame, without an AWT window or backend services. */
@Composable
private fun HeaderLoopWorkspace(header: MutableState<ShellWorkspaceHeaderState?>, title: State<String>, frame: Any) {
    val host = remember {
        LettaShellWorkspaceHost(
            actions = ShellWorkspaceNavigationActions(),
            slots = LettaShellWorkspaceSlots(main = { Text("Content", it) }, agentCard = {}, sidePane = {}, overlays = {}),
            chrome = ShellWindowChrome.Hoisted { header.value = it },
            drawer = ShellWorkspaceDrawer(),
            events = ShellWorkspaceEvents(),
        )
    }
    LettaShellWorkspace(
        state = LettaShellWorkspaceState(
            header = ShellWorkspaceHeaderState(
                title = title.value,
                sidebar = ShellSidebarControl(false, onToggle = { frame.hashCode() }),
            ),
            navigation = ShellWorkspaceNavigation(
                ShellLibraryRailState(emptyList(), ShellRailFocus()),
                ShellAgentPanelState(ShellPanelAgent(name = "", agentId = "")),
            ),
            layout = ShellWorkspaceLayout(reducedMotion = true),
        ),
        host = host,
    )
}

internal fun desktopProjectDirectory(): File {
    val workingDirectory = File(System.getProperty("user.dir"))
    return generateSequence(workingDirectory) { it.parentFile }
        .flatMap { root ->
            sequenceOf(root.resolve("desktop"), root.resolve("android-compose/desktop"))
        }
        .firstOrNull { candidate -> candidate.resolve("build.gradle.kts").isFile }
        ?: error("Could not locate the desktop Gradle project from $workingDirectory")
}
