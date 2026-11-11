@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.acceptance

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.ui.canvas.LocalCanvasSessionRegistry
import com.letta.mobile.ui.canvas.boards.ProvideCanvasBoardSource
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.ProvideShellTaskRegistry
import com.letta.mobile.ui.shell.workspace.SHELL_TASKS_MODULE_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BoundedViewLifecycleTest {
    @Test
    fun threeMountsReleaseEveryBoardHoldAndTaskComposition() = runDesktopComposeUiTest(width = 1800, height = 800) {
        val fixture = AcceptanceWorkspace()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasks = SubagentRepository(NoOpChannelTransport(), owner)
        var config by mutableStateOf(fixture.lanes())
        val host = fixture.host()
        try {
            setContent {
                CompositionLocalProvider(LocalCanvasSessionRegistry provides fixture.registry) {
                    ProvideCanvasBoardSource(fixture.boards) {
                        ProvideShellTaskRegistry(tasks) {
                            LettaShellWorkspace(fixture.state(config), host, Modifier.size(1800.dp, 800.dp))
                        }
                    }
                }
            }
            repeat(3) {
                runOnIdle { config = fixture.lanes(main = listOf(fixture.page, fixture.canvas), side = listOf(fixture.tasks)) }
                waitUntil(timeoutMillis = AcceptanceWorkspace.TIMEOUT) { fixture.registry.get(fixture.id) != null }
                assertNotNull(fixture.registry.get(fixture.id))
                assertEquals(1, fixture.boards.holds(fixture.id), "one source hold on mount $it")
                onNodeWithTag(SHELL_TASKS_MODULE_TAG).assertExists()
                runOnIdle { config = fixture.lanes() }
                waitUntil(timeoutMillis = AcceptanceWorkspace.TIMEOUT) { fixture.registry.get(fixture.id) == null }
                assertEquals(0, fixture.boards.holds(fixture.id), "no source hold survives unmount $it")
                assertEquals(0, onAllNodesWithTag(SHELL_TASKS_MODULE_TAG).fetchSemanticsNodes().size)
            }
        } finally {
            tasks.close()
            owner.cancel()
        }
    }
}
