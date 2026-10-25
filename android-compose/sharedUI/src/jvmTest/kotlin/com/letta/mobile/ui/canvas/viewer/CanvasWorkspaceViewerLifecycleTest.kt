@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.CANVAS_VIEWER_TOGGLE_TAG
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.canvas.LocalCanvasBoardSessionImportGate
import com.letta.mobile.ui.canvas.canvasViewerOptionTag
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewer
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerId
import com.letta.mobile.ui.canvas.plugin.ProvideCanvasDocumentViewers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CanvasWorkspaceViewerLifecycleTest {
    @Test
    fun workspaceKeepsOneRegisteredSyncingSessionWhileTextViewerIsSelected() = runComposeUiTest {
        val transport = CountingCanvasSyncTransport()
        val presenceTransport = CountingPresenceTransport()
        val session = session(transport)
        val sessionRegistry = CanvasSessionRegistry()
        val workspaceVisible = mutableStateOf(true)

        setContent {
            MaterialTheme {
                if (workspaceVisible.value) {
                    CanvasWorkspace(
                        session = session,
                        sessionRegistry = sessionRegistry,
                        presenceTransport = presenceTransport,
                        showTitle = false,
                    )
                }
            }
        }

        awaitWorkspaceMounted(session, sessionRegistry, transport, presenceTransport)
        assertEquals(1, transport.collectorStarts.get())
        selectTextAndApplyRemoteUpdate(session, sessionRegistry, transport, presenceTransport)
        assertViewerTogglesKeepWorkspaceSync(session, sessionRegistry, transport, presenceTransport)
        assertEquals(4, presenceTransport.collectorStarts.get())
        closeWorkspace(workspaceVisible, session, sessionRegistry, transport)
    }

    @Test
    fun publicViewerRegistrationTracksChangesAndFallsBackWithoutDisconnectingWorkspace() = runComposeUiTest {
        val initialScene = """{"elements":[],"_future":{"kept":["before"]}}"""
        val transport = CountingCanvasSyncTransport()
        val presenceTransport = CountingPresenceTransport()
        val session = session(transport, initialScene)
        val sessionRegistry = CanvasSessionRegistry()
        val pluginId = CanvasDocumentViewerId("test.raw-scene")
        val plugin = PluginViewerRegistration(
            id = pluginId,
            enabled = mutableStateOf(true),
            viewer = pluginViewer(pluginId),
        )
        mountPluginWorkspace(session, sessionRegistry, presenceTransport, plugin)

        awaitWorkspaceMounted(session, sessionRegistry, transport, presenceTransport)
        val initialDocument = session.document.value!!
        onNodeWithTag(canvasViewerOptionTag(pluginId)).assertIsDisplayed().performClick()
        assertPluginContext(session, initialDocument)
        assertTrue(initialDocument.sceneJson.contains("\"_future\""))

        sendRemotePatternUpdate(transport)
        awaitRemotePatternUpdate(session)
        val updatedDocument = session.document.value!!
        assertTrue(updatedDocument.revision > initialDocument.revision)
        assertTrue(updatedDocument.sceneJson.contains("\"_future\""))
        assertPluginContext(session, updatedDocument)

        runOnIdle { plugin.enabled.value = false }
        onNodeWithTag(canvasViewerOptionTag(pluginId)).assertDoesNotExist()
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.CANVAS)).assertIsSelected()
        assertSame(session, sessionRegistry.get(session.canvasId))
        assertEquals(1, transport.activeCollectors.get())
        assertEquals(1, transport.collectorStarts.get())
        assertEquals(updatedDocument, session.document.value)
    }

    @Test
    fun replacingReadySessionHidesOldDrawingUntilNewSceneIsImported() = runComposeUiTest {
        val sessionA = session(CountingCanvasSyncTransport(), OLD_SESSION_SCENE, CanvasId("replace-session-a"))
        val sessionB = session(CountingCanvasSyncTransport(), NEW_SESSION_SCENE, CanvasId("replace-session-b"))
        val originalSceneA = sessionA.sceneJsonOrEmpty()
        val controllerA = DrawBoxController(Reducer(UseCase()))
        val controllerB = DrawBoxController(Reducer(UseCase())).also { it.importPath(OLD_SESSION_SCENE) }
        val currentWorkspace = mutableStateOf(sessionA to controllerA)
        val sessionBImportStarted = CompletableDeferred<Unit>()
        val releaseSessionBImport = CompletableDeferred<Unit>()

        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCanvasBoardSessionImportGate provides { openingSession ->
                        if (openingSession === sessionB) {
                            sessionBImportStarted.complete(Unit)
                            releaseSessionBImport.await()
                        }
                    },
                ) {
                    CanvasWorkspace(
                        session = currentWorkspace.value.first,
                        controller = currentWorkspace.value.second,
                        showTitle = false,
                    )
                }
            }
        }

        waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            controllerA.state.value.elements.singleOrNull()?.id == "old-session-shape"
        }
        onNodeWithContentDescription("Canvas board").assertIsDisplayed()

        try {
            runOnIdle { currentWorkspace.value = sessionB to controllerB }
            waitUntil(timeoutMillis = TIMEOUT_MILLIS) { sessionBImportStarted.isCompleted }

            onNodeWithContentDescription("Canvas board").assertDoesNotExist()
            assertEquals("old-session-shape", controllerB.state.value.elements.single().id)

            releaseSessionBImport.complete(Unit)
            waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
                controllerB.state.value.elements.singleOrNull()?.id == "new-session-shape" &&
                    onAllNodesWithContentDescription("Canvas board").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithContentDescription("Canvas board").assertIsDisplayed()

            val editedColor = Color(0xFF123456)
            runOnIdle { controllerB.setBgColor(editedColor) }
            waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
                sessionB.document.value?.let { DrawingSerializer.deserialize(it.sceneJson).bgColor == editedColor } == true
            }
            assertEquals(originalSceneA, sessionA.sceneJsonOrEmpty(), "the replaced session must not receive B's edit")
            assertEquals(editedColor, DrawingSerializer.deserialize(sessionB.sceneJsonOrEmpty()).bgColor)
            assertEquals(sessionB.canvasId, sessionB.document.value?.id)

            onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.TEXT)).performClick()
            onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertTextEquals(sessionB.sceneJsonOrEmpty())
        } finally {
            releaseSessionBImport.complete(Unit)
        }
    }

    private fun pluginViewer(id: CanvasDocumentViewerId): CanvasDocumentViewer = CanvasDocumentViewer(
        id = id,
        label = { "Raw scene" },
        render = { context, modifier ->
            Text(
                text = "${context.canvasId.value}\n${context.revision}\n${context.sceneJson}",
                modifier = modifier.testTag(CUSTOM_VIEWER_CONTENT_TAG),
            )
        },
    )

    private fun ComposeUiTest.mountPluginWorkspace(
        session: CanvasSession,
        registry: CanvasSessionRegistry,
        presence: CountingPresenceTransport,
        plugin: PluginViewerRegistration,
    ) {
        setContent {
            MaterialTheme {
                ProvideCanvasDocumentViewers(
                    registrations = {
                        if (plugin.enabled.value) register(plugin.viewer) else unregister(plugin.id)
                    },
                ) {
                    CanvasWorkspace(
                        session = session,
                        sessionRegistry = registry,
                        presenceTransport = presence,
                        showTitle = false,
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.assertPluginContext(
        session: CanvasSession,
        document: CanvasDocument,
    ) {
        onNodeWithTag(CUSTOM_VIEWER_CONTENT_TAG).assertTextEquals(
            "${session.canvasId.value}\n${document.revision}\n${document.sceneJson}",
        )
    }

    private fun sendRemotePatternUpdate(transport: CountingCanvasSyncTransport) = runBlocking {
        transport.send(
            CanvasOp.SetBackgroundPatternOp(
                opId = "remote-pattern-update-for-plugin-viewer",
                actorId = "peer-1",
                lamport = 1,
                pattern = CanvasBackgroundPattern(kind = CanvasBackgroundPattern.DOTS),
            ),
        )
    }

    private suspend fun ComposeUiTest.awaitRemotePatternUpdate(session: CanvasSession) {
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            session.document.value?.sceneJson?.contains("dots") == true
        }
    }

    private suspend fun ComposeUiTest.awaitWorkspaceMounted(
        session: CanvasSession,
        registry: CanvasSessionRegistry,
        sync: CountingCanvasSyncTransport,
        presence: CountingPresenceTransport,
    ) {
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            registry.get(session.canvasId) === session &&
                sync.activeCollectors.get() == 1 && presence.activeCollectors.get() == 1
        }
    }

    private suspend fun ComposeUiTest.selectTextAndApplyRemoteUpdate(
        session: CanvasSession,
        registry: CanvasSessionRegistry,
        sync: CountingCanvasSyncTransport,
        presence: CountingPresenceTransport,
    ) {
        onNodeWithTag(CANVAS_VIEWER_TOGGLE_TAG).assertIsDisplayed()
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.TEXT)).performClick()
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) { presence.activeCollectors.get() == 0 }
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertTextEquals(session.document.value!!.sceneJson)
        assertSame(session, registry.get(session.canvasId))
        runBlocking {
            sync.send(
                CanvasOp.SetBackgroundPatternOp(
                    opId = "remote-pattern-update",
                    actorId = "peer-1",
                    lamport = 1,
                    pattern = CanvasBackgroundPattern(kind = CanvasBackgroundPattern.DOTS),
                ),
            )
        }
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            session.document.value?.sceneJson?.contains("dots") == true
        }
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertTextEquals(session.document.value!!.sceneJson)
    }

    private suspend fun ComposeUiTest.assertViewerTogglesKeepWorkspaceSync(
        session: CanvasSession,
        registry: CanvasSessionRegistry,
        sync: CountingCanvasSyncTransport,
        presence: CountingPresenceTransport,
    ) {
        repeat(3) {
            onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.CANVAS)).performClick()
            waitUntil(timeoutMillis = TIMEOUT_MILLIS) { presence.activeCollectors.get() == 1 }
            onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertDoesNotExist()
            onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.TEXT)).performClick()
            waitUntil(timeoutMillis = TIMEOUT_MILLIS) { presence.activeCollectors.get() == 0 }
            onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertTextEquals(session.document.value!!.sceneJson)
            assertSame(session, registry.get(session.canvasId))
            assertEquals(1, sync.activeCollectors.get())
            assertEquals(1, sync.collectorStarts.get())
        }
    }

    private suspend fun ComposeUiTest.closeWorkspace(
        workspaceVisible: MutableState<Boolean>,
        session: CanvasSession,
        registry: CanvasSessionRegistry,
        sync: CountingCanvasSyncTransport,
    ) {
        runOnIdle { workspaceVisible.value = false }
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            registry.get(session.canvasId) == null && sync.activeCollectors.get() == 0
        }
    }

    private class CountingCanvasSyncTransport : CanvasSyncTransport {
        private val remoteOps = MutableSharedFlow<CanvasOp>(extraBufferCapacity = 8)
        val collectorStarts = AtomicInteger()
        val activeCollectors = AtomicInteger()

        override suspend fun publish(canvasId: CanvasId, op: CanvasOp) = Unit

        override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = flow {
            collectorStarts.incrementAndGet()
            activeCollectors.incrementAndGet()
            try {
                emitAll(remoteOps)
            } finally {
                activeCollectors.decrementAndGet()
            }
        }

        suspend fun send(op: CanvasOp) {
            remoteOps.emit(op)
        }
    }

    private data class PluginViewerRegistration(
        val id: CanvasDocumentViewerId,
        val enabled: MutableState<Boolean>,
        val viewer: CanvasDocumentViewer,
    )

    private class CountingPresenceTransport : CanvasPresenceTransport {
        val collectorStarts = AtomicInteger()
        val activeCollectors = AtomicInteger()
        private val presences: Flow<List<CanvasPresence>> = flow {
            collectorStarts.incrementAndGet()
            activeCollectors.incrementAndGet()
            try {
                emit(emptyList())
                awaitCancellation()
            } finally {
                activeCollectors.decrementAndGet()
            }
        }

        override suspend fun updatePresence(canvasId: CanvasId, presence: CanvasPresence) = Unit

        override fun observePresence(canvasId: CanvasId): Flow<List<CanvasPresence>> = presences
    }

    private fun session(
        transport: CanvasSyncTransport,
        initialSceneJson: String = """{"elements":[]}""",
        canvasId: CanvasId = CanvasId("canvas-workspace-viewer-lifecycle"),
    ): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(
                canvasId = canvasId,
                agentId = "peer-1",
                initialSceneJson = initialSceneJson,
                syncTransport = transport,
            ),
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val CUSTOM_VIEWER_CONTENT_TAG = "custom-canvas-viewer-content"
        const val OLD_SESSION_SCENE = """{"bgColor":"#ffffffff","elements":[
            {"id":"old-session-shape","type":"Shape","zIndex":2,
             "points":["20.0,20.0","80.0,80.0"],"strokeColor":"#000000ff","strokeWidth":4.0,
             "shapeType":"RECTANGLE","modifiedAt":1}
        ]}"""
        const val NEW_SESSION_SCENE = """{"bgColor":"#ffffffff","elements":[
            {"id":"new-session-shape","type":"Shape","zIndex":2,
             "points":["120.0,120.0","200.0,200.0"],"strokeColor":"#000000ff","strokeWidth":4.0,
             "shapeType":"RECTANGLE","modifiedAt":2}
        ]}"""
    }
}
