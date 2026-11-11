@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.acceptance

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAgentPort
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAuthor
import com.letta.mobile.data.shell.layout.edit.ShellLayoutCommit
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.canvas.LocalCanvasSessionRegistry
import com.letta.mobile.ui.canvas.boards.ProvideCanvasBoardSource
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.LocalShellLayoutAgentPort
import com.letta.mobile.ui.shell.workspace.rememberShellLayoutController
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DomainStateSurvivesViewReplacementTest {
    @Test
    fun removingMovingAndUndoingViewsNeverWritesTheDocument() = runDesktopComposeUiTest(width = 1800, height = 800) {
        val fixture = AcceptanceWorkspace()
        val session = runBlocking { fixture.boards.acquire(fixture.id) }!!
        val snapshot = runBlocking { fixture.store.get(fixture.id) }!!
        val scene = session.document.value!!.sceneJson
        val port = ShellLayoutAgentPort()
        val settings = InMemorySecureSettingsStore()
        val host = fixture.host()
        try {
            setContent {
                CompositionLocalProvider(LocalCanvasSessionRegistry provides fixture.registry, LocalShellLayoutAgentPort provides port) {
                    ProvideCanvasBoardSource(fixture.boards) {
                        val controller = rememberShellLayoutController(null, settings)
                        LettaShellWorkspace(fixture.state(controller.workspaceLayout().lanes), host, Modifier.size(1800.dp, 800.dp))
                    }
                }
            }
            waitForIdle()
            val layouts = listOf(fixture.lanes(side = listOf(fixture.canvas)), fixture.lanes(),
                fixture.lanes(side = listOf(fixture.canvas)), fixture.lanes(main = listOf(fixture.page, fixture.canvas)),
                fixture.lanes(side = listOf(fixture.canvas)))
            layouts.forEach { layout ->
                assertIs<ShellLayoutCommit.Done>(port.commit(ShellLayoutAuthor.Person, "Replace view", layout, port.attached.value!!.config.value))
                waitForIdle()
                assertEquals(snapshot, runBlocking { fixture.store.get(fixture.id) })
                assertEquals(scene, session.document.value!!.sceneJson)
                val acquired = runBlocking { fixture.boards.acquire(fixture.id) }!!
                assertSame(session, acquired)
                fixture.boards.release(acquired)
            }
            assertIs<ShellLayoutCommit.Done>(port.undoLatest(ShellLayoutAuthor.Person))
            waitForIdle()
            assertEquals(snapshot, runBlocking { fixture.store.get(fixture.id) })
            assertEquals(scene, session.document.value!!.sceneJson)
        } finally {
            fixture.boards.release(session)
        }
    }
}
