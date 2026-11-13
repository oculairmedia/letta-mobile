@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.memory

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.memory.memfs.MemfsFile
import com.letta.mobile.data.memory.memfs.MemfsFileKind
import com.letta.mobile.data.memory.memfs.MemfsLoad
import com.letta.mobile.data.memory.memfs.MemfsPageActions
import com.letta.mobile.data.memory.memfs.MemfsPageState
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.memory.MemoryGraphRenderTest
import com.letta.mobile.ui.shell.pages.FocusMemoryWorkspacePage
import com.letta.mobile.ui.shell.pages.memfs.MemfsPageOptions
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Memory destination as the shell shows it (the focused agent's picker, the Blocks | Files
 * switch, the page header) in the dark theme at a phone (360dp), either side of the 720dp
 * breakpoint (700dp, 760dp) and a wide desktop (1200dp): Blocks with nothing selected, Blocks with
 * a block open, and Files. Writes PNGs to build/memory-destination-render/ for review; it asserts
 * only that each rendered.
 */
class MemoryDestinationRenderTest {
    private val graph = MemoryGraphRenderTest()

    @Test
    fun rendersBlocksAndFilesAtEveryWidth() {
        listOf(PHONE, BELOW, ABOVE, WIDE).forEach { width ->
            val selected = graph.pageState(MemoryGraphRenderTest.HUMAN_BLOCK)
            render("memory-$width-blocks.png", width, MemoryWorkspaceView.Blocks, selected.copy(selection = null))
            render("memory-$width-block-open.png", width, MemoryWorkspaceView.Blocks, selected)
            render("memory-$width-files.png", width, MemoryWorkspaceView.Files, selected.copy(selection = null))
        }
    }

    private fun render(name: String, width: Int, view: MemoryWorkspaceView, memory: MemoryPageState) =
        runDesktopComposeUiTest(width = width, height = HEIGHT) {
            val touch = width <= BELOW
            val binding = MemoryWorkspaceBinding(
                memory = memory,
                memoryActions = graph.actions(),
                files = FILES,
                filesActions = filesActions(),
                onFilesAgentChange = {},
                view = view,
                onViewChange = {},
                filesOptions = MemfsPageOptions(touch = touch),
            )
            setContent {
                SharedMaterialTheme(darkTheme = true) {
                    ProvideLettaHost(if (touch) LettaHost.Touch else LettaHost.Pointer) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            FocusMemoryWorkspacePage(binding, onSelectAgent = {})
                        }
                    }
                }
            }
            // Let the graph's bloom run out and the simulation park.
            mainClock.advanceTimeBy(MemoryGraphRenderTest.BLOOM_SETTLE_MILLIS)
            waitForIdle()
            val out = File("build/memory-destination-render").apply { mkdirs() }.resolve(name)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
            assertTrue(out.length() > 0)
        }

    private fun filesActions(): MemfsPageActions =
        Proxy.newProxyInstance(MemfsPageActions::class.java.classLoader, arrayOf(MemfsPageActions::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MemfsPageActions"
                else -> null
            }
        } as MemfsPageActions

    private companion object {
        const val PHONE = 360
        const val BELOW = 700
        const val ABOVE = 760
        const val WIDE = 1200
        const val HEIGHT = 820
        val FILES = MemfsPageState(
            agentId = "agent-1",
            load = MemfsLoad.Loaded,
            files = listOf("system/persona.md", "system/human.md", "reference/pm/checkpoint.md", "reference/pm/routing.md").map { path ->
                MemfsFile(path, isSystem = path.startsWith("system/"), description = null, sizeBytes = 2_048, kind = MemfsFileKind.Markdown)
            },
        )
    }
}
