@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.CanvasDocumentViewerHost
import com.letta.mobile.ui.canvas.canvasViewerOptionTag
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewer
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerId
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry
import com.letta.mobile.ui.canvas.plugin.ProvideCanvasDocumentViewerRegistry
import com.letta.mobile.ui.canvas.plugin.RegisterCanvasDocumentViewer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The shell's live view registry (letta-mobile-n2stx.2.1, B1). */
class CanvasDocumentViewerRegistryTest {
    @Test
    fun aRegistrationIsVisibleUntilItsHandleCloses() {
        val registry = CanvasDocumentViewerRegistry()
        val registration = registry.register(viewer("Reader"))
        assertTrue(registry.current.contains(READER))

        registration.close()
        assertFalse(registry.current.contains(READER))
        registration.close()
        assertFalse(registry.current.contains(READER))
        assertTrue(registry.current.contains(CanvasDocumentViewerId.TEXT))
    }

    @Test
    fun closingAReplacedRegistrationKeepsItsSuccessor() {
        val registry = CanvasDocumentViewerRegistry()
        val first = registry.register(viewer("First"))
        val second = viewer("Second")
        val secondRegistration = registry.register(second)

        first.close()
        assertSame(second, registry.current.viewerFor(READER))

        secondRegistration.close()
        assertFalse(registry.current.contains(READER))
    }

    @Test
    fun theBuiltInCanvasCannotBeRegisteredOver() {
        val registry = CanvasDocumentViewerRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.register(CanvasDocumentViewer(CanvasDocumentViewerId.CANVAS, label = { "Mine" }, render = { _, _ -> }))
        }
        assertTrue(registry.current.contains(CanvasDocumentViewerId.CANVAS))
    }

    @Test
    fun aViewRegisteredAfterTheShellMountsIsOfferedAndItsCompositionIsReleasedWhenItLeaves() = runComposeUiTest {
        val registry = CanvasDocumentViewerRegistry()
        val session = session()
        var active = 0
        setContent {
            MaterialTheme {
                ProvideCanvasDocumentViewerRegistry(registry) {
                    CanvasDocumentViewerHost(session, Modifier.fillMaxSize()) { Text("Board", it.testTag(BOARD)) }
                }
            }
        }
        onNodeWithTag(canvasViewerOptionTag(READER)).assertDoesNotExist()

        val registration = runOnIdle {
            registry.register(
                CanvasDocumentViewer(READER, label = { "Reader" }, render = { context, modifier ->
                    DisposableEffect(Unit) {
                        active++
                        onDispose { active-- }
                    }
                    Text(context.canvasId.value, modifier.testTag(READER_CONTENT))
                }),
            )
        }
        onNodeWithTag(canvasViewerOptionTag(READER)).performClick()
        onNodeWithTag(READER_CONTENT).assertTextEquals(session.canvasId.value)
        runOnIdle { assertEquals(1, active) }

        runOnIdle { registration.close() }
        onNodeWithTag(canvasViewerOptionTag(READER)).assertDoesNotExist()
        onNodeWithTag(READER_CONTENT).assertDoesNotExist()
        onNodeWithTag(BOARD).assertTextEquals("Board")
        runOnIdle { assertEquals(0, active) }
    }

    @Test
    fun aCompositionScopedContributionLeavesWithItsComposition() = runComposeUiTest {
        val registry = CanvasDocumentViewerRegistry()
        val offered = mutableStateOf(true)
        setContent {
            ProvideCanvasDocumentViewerRegistry(registry) {
                val reader = remember { viewer("Reader") }
                if (offered.value) RegisterCanvasDocumentViewer(reader)
            }
        }
        runOnIdle { assertTrue(registry.current.contains(READER)) }
        runOnIdle { offered.value = false }
        runOnIdle { assertFalse(registry.current.contains(READER)) }
    }

    private fun viewer(name: String) =
        CanvasDocumentViewer(READER, label = { name }, render = { _, modifier -> Text(name, modifier) })

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(
                agentId = "agent-1",
                canvasId = CanvasId("canvas-registry-test"),
                initialSceneJson = """{"elements":[]}""",
            ),
        )
    }

    private companion object {
        val READER = CanvasDocumentViewerId("test.reader")
        const val READER_CONTENT = "registry-reader-content"
        const val BOARD = "registry-board"
    }
}
