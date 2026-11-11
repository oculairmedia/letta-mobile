@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.acceptance

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.ui.canvas.LocalCanvasSessionRegistry
import com.letta.mobile.ui.canvas.boards.ProvideCanvasBoardSource
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.ProvideShellTaskRegistry
import com.letta.mobile.ui.shell.workspace.SHELL_TASKS_MODULE_TAG
import com.letta.mobile.ui.shell.workspace.shellCanvasModuleTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RenderedCrossHostParityTest {
    @Test
    fun phoneTabletAndDesktopMountTheFittingModulesAndRestoreTheSameBoard() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val fixture = AcceptanceWorkspace()
        val session = runBlocking { fixture.boards.acquire(fixture.id) }!!
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasks = SubagentRepository(NoOpChannelTransport(), owner)
        val config = fixture.lanes(main = listOf(fixture.page, fixture.canvas), side = listOf(fixture.tasks))
        val host = fixture.host()
        var width by mutableStateOf(360)
        try {
            setContent {
                CompositionLocalProvider(LocalCanvasSessionRegistry provides fixture.registry) {
                    ProvideCanvasBoardSource(fixture.boards) {
                        ProvideShellTaskRegistry(tasks) {
                            val state = fixture.state(config)
                            LettaShellWorkspace(state.copy(layout = state.layout.copy(sidebarVisible = false)), host, Modifier.size(width.dp, 800.dp))
                        }
                    }
                }
            }
            listOf(360, 700, 760, 1200, 760, 700, 360, 1200).forEach { size ->
                runOnIdle { width = size }
                val expected = when (size) {
                    360, 700 -> setOf(AcceptanceWorkspace.PAGE)
                    760 -> setOf(AcceptanceWorkspace.PAGE, SHELL_TASKS_MODULE_TAG)
                    else -> setOf(AcceptanceWorkspace.PAGE, SHELL_TASKS_MODULE_TAG, shellCanvasModuleTag(fixture.id))
                }
                val tags = setOf(AcceptanceWorkspace.PAGE, SHELL_TASKS_MODULE_TAG, shellCanvasModuleTag(fixture.id))
                waitUntil(AcceptanceWorkspace.TIMEOUT) {
                    tags.filter { onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }.toSet() == expected
                }
                assertEquals(expected, tags.filter { onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }.toSet(), "mounted set at $size dp")
                val acquired = runBlocking { fixture.boards.acquire(fixture.id) }!!
                assertSame(session, acquired)
                fixture.boards.release(acquired)
                if (size == 1200) assertSame(session, fixture.registry.get(fixture.id))
            }
        } finally {
            fixture.boards.release(session)
            tasks.close()
            owner.cancel()
        }
    }
}
