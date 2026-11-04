@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.viewer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasApplyOpsTool
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.LoopbackCanvasSyncTransport
import com.letta.mobile.data.canvas.NotebookCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.CanvasWorkspaceSessionLifetime
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CanvasWorkspaceStoreLifecycleTest {
    @Test
    fun survivorReceivesUpdatesAndDispatchesToDurableStoreAfterOriginCloses() {
        val path = Files.createTempDirectory("canvas-mount-store-")
        val id = CanvasId("shared-mount")
        var persistedRevision: Long? = null
        try {
            NotebookLocalStore(path, "mount-peer").use { notebooks ->
                val store = NotebookCanvasDocumentStore(notebooks)
                val collectors = AtomicInteger()
                val transport = countingLoopback(collectors)
                val origin = runBlocking {
                    CanvasSession.create(store, CanvasCreateOptions(
                        canvasId = id,
                        agentId = "agent-1",
                        initialSceneJson = """{"elements":[],"_future":{"kept":"value"}}""",
                        syncTransport = transport,
                    ))
                }
                val survivor = runBlocking {
                    assertNotNull(CanvasSession.open(store, id, CanvasConversationOptions(syncTransport = transport)))
                }
                val registry = CanvasSessionRegistry()
                val originVisible = mutableStateOf(true)
                val survivorVisible = mutableStateOf(false)
                runComposeUiTest {
                    setContent {
                        if (originVisible.value) CanvasWorkspaceSessionLifetime(origin, registry)
                        if (survivorVisible.value) CanvasWorkspaceSessionLifetime(survivor, registry)
                    }
                    waitUntil(timeoutMillis = TIMEOUT_MS) { registry.get(id) === origin }
                    runOnIdle { survivorVisible.value = true }
                    waitUntil(timeoutMillis = TIMEOUT_MS) { registry.get(id) === survivor && collectors.get() == 2 }
                    runBlocking {
                        origin.applyLocal(CanvasOp.SetBackgroundPatternOp(
                            "origin-edit", "agent-1", 1, CanvasBackgroundPattern(kind = CanvasBackgroundPattern.DOTS),
                        ))
                    }
                    waitUntil(timeoutMillis = TIMEOUT_MS) { survivor.sceneJsonOrEmpty().contains("dots") }
                    runOnIdle { originVisible.value = false }
                    waitUntil(timeoutMillis = TIMEOUT_MS) { registry.get(id) === survivor && collectors.get() == 1 }
                    updateThroughTool(store, registry, id)
                    assertSame(survivor, registry.get(id))
                    assertTrue(survivor.sceneJsonOrEmpty().contains("grid"))
                    assertPersisted(store, survivor, id)
                    persistedRevision = survivor.document.value?.revision
                    runOnIdle { survivorVisible.value = false }
                    waitUntil(timeoutMillis = TIMEOUT_MS) { registry.get(id) == null && collectors.get() == 0 }
                }
            }
            assertSurvivesRestart(path, id, assertNotNull(persistedRevision))
        } finally {
            path.toFile().deleteRecursively()
        }
    }

    private fun countingLoopback(collectors: AtomicInteger): CanvasSyncTransport {
        val loopback = LoopbackCanvasSyncTransport()
        return object : CanvasSyncTransport {
            override suspend fun publish(canvasId: CanvasId, op: CanvasOp) = loopback.publish(canvasId, op)
            override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = flow {
                collectors.incrementAndGet()
                try {
                    emitAll(loopback.subscribe(canvasId))
                } finally {
                    collectors.decrementAndGet()
                }
            }
        }
    }

    private fun updateThroughTool(store: NotebookCanvasDocumentStore, registry: CanvasSessionRegistry, id: CanvasId) {
        val ops: List<CanvasOp> = listOf(CanvasOp.SetBackgroundPatternOp(
            "survivor-edit", "agent-1", 1, CanvasBackgroundPattern(kind = CanvasBackgroundPattern.GRID),
        ))
        val result = runBlocking {
            CanvasApplyOpsTool(store, registry).invoke(buildJsonObject {
                put("canvas_id", id.value)
                put("ops", Json.parseToJsonElement(Json.encodeToString(ops)))
            }, agentId = "agent-1")
        }
        assertIs<ExternalToolResult.Success>(result)
    }

    private fun assertPersisted(store: NotebookCanvasDocumentStore, session: CanvasSession, id: CanvasId) {
        val persisted = runBlocking { assertNotNull(store.get(id)) }
        assertEquals(id, persisted.id)
        assertEquals(session.document.value?.revision, persisted.revision)
        assertTrue(persisted.sceneJson.contains("grid"))
        assertUnknownFieldPreserved(persisted.sceneJson)
    }

    private fun assertSurvivesRestart(path: Path, id: CanvasId, expectedRevision: Long) {
        NotebookLocalStore(path, "restart-peer").use { restarted ->
            val stored = runBlocking { assertNotNull(NotebookCanvasDocumentStore(restarted).get(id)) }
            assertEquals(id, stored.id)
            assertEquals(expectedRevision, stored.revision)
            assertTrue(stored.sceneJson.contains("grid"))
            assertUnknownFieldPreserved(stored.sceneJson)
        }
    }

    private fun assertUnknownFieldPreserved(sceneJson: String) {
        assertEquals(
            Json.parseToJsonElement("""{"kept":"value"}"""),
            Json.parseToJsonElement(sceneJson).jsonObject["_future"],
        )
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
