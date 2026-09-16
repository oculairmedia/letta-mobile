package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanvasMultiplayerSyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun multiplayerSync_twoClientsConvergeOverSyncTransport() = runTest {
        val sharedTransport = LoopbackCanvasSyncTransport()
        val canvasId = CanvasId("canvas-collab-1")

        // Client A
        val storeA = InMemoryCanvasDocumentStore()
        val sessionA = CanvasSession.create(
            store = storeA,
            canvasId = canvasId,
            title = "Shared Architecture",
            syncTransport = sharedTransport,
        )

        // Client B
        val storeB = InMemoryCanvasDocumentStore()
        val sessionB = CanvasSession.create(
            store = storeB,
            canvasId = canvasId,
            title = "Shared Architecture",
            syncTransport = sharedTransport,
        )

        val jobA = sessionA.startSync(backgroundScope)
        val jobB = sessionB.startSync(backgroundScope)
        assertNotNull(jobA)
        assertNotNull(jobB)
        runCurrent()

        // 1. Client A adds title element locally
        val addTitleOp = CanvasOp.AddElementOp(
            opId = "op-title-1",
            actorId = "client_a",
            lamport = 1L,
            elementId = "title",
            elementJson = """{"id": "title", "type": "Text", "text": "Meridian Sync"}""",
        )
        sessionA.applyLocal(addTitleOp)
        runCurrent()

        // Verify Client B received and projected Client A's op
        val docB1 = sessionB.document.value
        assertNotNull(docB1)
        val sceneB1 = json.parseToJsonElement(docB1.sceneJson).jsonObject
        val elementsB1 = sceneB1["elements"]?.jsonArray
        assertEquals(1, elementsB1?.size)
        assertEquals("title", elementsB1?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content)

        // 2. Client B adds a node element locally
        val addNodeOp = CanvasOp.AddElementOp(
            opId = "op-node-1",
            actorId = "client_b",
            lamport = 2L,
            elementId = "node-1",
            elementJson = """{"id": "node-1", "type": "Shape", "shapeType": "RECTANGLE"}""",
        )
        sessionB.applyLocal(addNodeOp)
        runCurrent()

        // Verify Client A received and projected Client B's op
        val docA2 = sessionA.document.value
        assertNotNull(docA2)
        val sceneA2 = json.parseToJsonElement(docA2.sceneJson).jsonObject
        val elementsA2 = sceneA2["elements"]?.jsonArray
        assertEquals(2, elementsA2?.size)

        // 3. Client A changes background color locally
        val setBgOp = CanvasOp.SetBackgroundOp(
            opId = "op-bg-1",
            actorId = "client_a",
            lamport = 3L,
            colorHex = "#223344ff",
        )
        sessionA.applyLocal(setBgOp)
        runCurrent()

        // Verify Client B has matching background
        val docB3 = sessionB.document.value
        assertNotNull(docB3)
        val sceneB3 = json.parseToJsonElement(docB3.sceneJson).jsonObject
        assertEquals("#223344ff", sceneB3["bgColor"]?.jsonPrimitive?.content)

        // Both sessions have converged
        assertEquals(sessionA.document.value?.sceneJson, sessionB.document.value?.sceneJson)

        // 4. Verify Deduplication: duplicate op from transport does not create double entries
        sessionB.applyRemote(addTitleOp)
        runCurrent()
        assertEquals(sessionA.document.value?.sceneJson, sessionB.document.value?.sceneJson)
    }

    @Test
    fun multiplayerSync_agentReplaceWinsAndResetsScene() = runTest {
        val sharedTransport = LoopbackCanvasSyncTransport()
        val canvasId = CanvasId("canvas-collab-2")

        val store = InMemoryCanvasDocumentStore()
        val session = CanvasSession.create(
            store = store,
            canvasId = canvasId,
            syncTransport = sharedTransport,
        )
        session.startSync(backgroundScope)
        runCurrent()

        // Human adds an initial draft
        session.applyLocal(
            CanvasOp.AddElementOp(
                opId = "draft-1",
                actorId = "human",
                lamport = 1L,
                elementId = "rough-sketch",
                elementJson = """{"id": "rough-sketch", "type": "Pen"}""",
            )
        )
        runCurrent()

        // Agent issues replace_scene
        val agentDiagram = """{"bgColor": "#eceff1ff", "elements": [{"id": "clean-diagram", "type": "Shape"}]}"""
        val replaceOp = CanvasOp.ReplaceSceneOp(
            opId = "agent-replace-1",
            actorId = "agent",
            lamport = 10L,
            sceneJson = agentDiagram,
        )
        session.applyRemote(replaceOp)
        runCurrent()

        val currentScene = session.document.value?.sceneJson
        assertNotNull(currentScene)
        val parsed = json.parseToJsonElement(currentScene).jsonObject
        assertEquals("#eceff1ff", parsed["bgColor"]?.jsonPrimitive?.content)
        val elements = parsed["elements"]?.jsonArray
        assertEquals(1, elements?.size)
        assertEquals("clean-diagram", elements?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }
}
