@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.memory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.memory.memfs.MemfsFile
import com.letta.mobile.data.memory.memfs.MemfsFileKind
import com.letta.mobile.data.memory.memfs.MemfsLoad
import com.letta.mobile.data.memory.memfs.MemfsPageActions
import com.letta.mobile.data.memory.memfs.MemfsPageState
import com.letta.mobile.data.memory.memfs.MemfsTab
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.pages.memfs.MemfsPageOptions
import com.letta.mobile.ui.shell.pages.memfs.MemfsPageTags
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Memory destination on a phone (letta-mobile-ckmub.3.21): one Blocks | Files mode switch, the
 * filter right under it, a Current | History sub-switch that does not say "Files" again, and no
 * floating refresh button - the list pulls to refresh.
 */
class MemoryWorkspacePageTest {
    private val calls = mutableListOf<String>()

    private val files = MemfsPageState(
        agentId = "agent-1",
        load = MemfsLoad.Loaded,
        files = listOf(MemfsFile("system/persona.md", isSystem = true, description = null, sizeBytes = 12, kind = MemfsFileKind.Markdown)),
    )

    private fun ComposeUiTest.show(view: MemoryWorkspaceView, host: LettaHost = LettaHost.Touch) {
        val binding = MemoryWorkspaceBinding(
            memory = MemoryPageState(),
            memoryActions = recording(),
            files = files,
            filesActions = recording(),
            onFilesAgentChange = {},
            view = view,
            onViewChange = {},
            filesOptions = MemfsPageOptions(showTitle = false, touch = host == LettaHost.Touch),
        )
        setContent {
            MaterialTheme {
                ProvideLettaHost(host) { Box(Modifier.size(380.dp, 800.dp)) { MemoryWorkspacePage(binding) } }
            }
        }
    }

    /** Every interface call is recorded by name; the Object methods behave as identity. */
    private inline fun <reified T : Any> recording(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> T::class.java.simpleName
                else -> {
                    calls += method.name
                    null
                }
            }
        } as T

    @Test
    fun onAPhoneFilesSaysFilesOnceWithTheFilterUnderTheModeSwitchAndNoRefreshButton() = runComposeUiTest {
        show(MemoryWorkspaceView.Files)

        onAllNodesWithText("Files").assertCountEquals(1)
        onNodeWithText("Current").assertExists()
        onNodeWithText("History").assertExists()
        onAllNodesWithContentDescription("Refresh").assertCountEquals(0)
        onNodeWithTag(MemfsPageTags.REFRESH).assertDoesNotExist()

        val switchBottom = onNodeWithTag(MemoryWorkspaceTags.view(MemoryWorkspaceView.Files)).getBoundsInRoot().bottom
        val filterTop = onNodeWithTag(MemfsPageTags.SEARCH).getBoundsInRoot().top
        val tabsTop = onNodeWithTag(MemfsPageTags.tab(MemfsTab.Files)).getBoundsInRoot().top
        assertTrue(filterTop >= switchBottom && filterTop < tabsTop, "the filter sits between the mode switch and the sub-tabs")
    }

    @Test
    fun onAPhoneTheFileListPullsToRefresh() = runComposeUiTest {
        show(MemoryWorkspaceView.Files)
        onNodeWithTag(MemfsPageTags.LIST).performTouchInput { swipeDown(startY = top, endY = bottom, durationMillis = 600) }
        waitForIdle()
        assertEquals(listOf("refresh"), calls.filter { it == "refresh" })
    }

    @Test
    fun onAPhoneBlocksHasNoRefreshButton() = runComposeUiTest {
        show(MemoryWorkspaceView.Blocks)
        onAllNodesWithContentDescription("Refresh").assertCountEquals(0)
    }

    @Test
    fun onAPointerHostFilesKeepsItsRefreshButton() = runComposeUiTest {
        show(MemoryWorkspaceView.Files, LettaHost.Pointer)
        onNodeWithTag(MemfsPageTags.REFRESH).assertExists()
    }

    @Test
    fun onAPointerHostBlocksKeepsItsRefreshButton() = runComposeUiTest {
        show(MemoryWorkspaceView.Blocks, LettaHost.Pointer)
        onAllNodesWithContentDescription("Refresh").assertCountEquals(1)
    }
}
