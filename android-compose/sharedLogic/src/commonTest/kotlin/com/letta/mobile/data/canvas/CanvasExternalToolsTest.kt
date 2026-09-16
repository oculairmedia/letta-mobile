package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanvasExternalToolsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var store: InMemoryCanvasDocumentStore

    // A fresh registry per test is the isolation now; it used to be a process-global object that
    // every test had to remember to clear by hand, before and after.
    private lateinit var sessions: CanvasSessionRegistry

    @BeforeTest
    fun setUp() {
        store = InMemoryCanvasDocumentStore()
        sessions = CanvasSessionRegistry()
    }

    @Test
    fun advertisedInExternalToolRegistry() {
        val tools = CanvasExternalTools.all(store, sessions)
        val registry = ExternalToolRegistry.hostTools(tools)
        val advertised = registry.listAdvertisedTools().map { it.name }

        assertEquals(
            listOf(
                "canvas.create",
                "canvas.get_scene",
                "canvas.replace_scene",
                "canvas.apply_ops",
                "canvas.export_svg",
                "canvas.list",
            ),
            advertised,
        )

        val commandGroups = registry.advertisedToolsCommandGroups()
        assertNotNull(commandGroups)
        val toolNamesInGroups = commandGroups.flatMap { it.tools }.map { it.name }
        assertTrue(toolNamesInGroups.containsAll(advertised))
    }

    @Test
    fun canvasCreateAndGetSceneFlow() = runTest {
        val tools = CanvasExternalTools.all(store, sessions).associateBy { it.name }
        val createTool = tools.getValue("canvas.create")
        val getSceneTool = tools.getValue("canvas.get_scene")

        val createResult = createTool.invoke(
            buildJsonObject {
                put("title", "Test Canvas")
                put("conversation_id", "conv-101")
            },
            agentId = "agent-1",
        )
        assertIs<ExternalToolResult.Success>(createResult)
        val created = json.decodeFromString<CanvasCreateResult>(createResult.content)
        assertTrue(created.canvasId.isNotBlank())

        val getResult = getSceneTool.invoke(
            buildJsonObject {
                put("canvas_id", created.canvasId)
            },
        )
        assertIs<ExternalToolResult.Success>(getResult)
        val scene = json.decodeFromString<CanvasGetSceneResult>(getResult.content)
        assertEquals("", scene.sceneJson)
        assertEquals(1L, scene.revision)
    }

    @Test
    fun canvasReplaceSceneWithActiveSessionUpdatesSession() = runTest {
        val canvasId = CanvasId("canvas-active-1")
        val session = CanvasSession.create(
            store = store,
            canvasId = canvasId,
            title = "Active Session Canvas",
            initialSceneJson = "{\"initial\":true}",
        )
        sessions.register(session)

        val replaceTool = CanvasReplaceSceneTool(store, sessions)
        val newSceneJson = "{\"bgColor\":-1,\"elements\":[{\"id\":\"1\"}]}"

        val replaceResult = replaceTool.invoke(
            buildJsonObject {
                put("canvas_id", canvasId.value)
                put("scene_json", newSceneJson)
            },
        )
        assertIs<ExternalToolResult.Success>(replaceResult)
        val replaced = json.decodeFromString<CanvasReplaceSceneResult>(replaceResult.content)
        assertTrue(replaced.ok)
        assertEquals(2L, replaced.revision)

        // Verify active session received the update
        val currentDoc = session.document.value
        assertNotNull(currentDoc)
        assertEquals(newSceneJson, currentDoc.sceneJson)
        assertEquals(2L, currentDoc.revision)

        // Verify persisted store received the update
        val persistedDoc = store.get(canvasId)
        assertNotNull(persistedDoc)
        assertEquals(newSceneJson, persistedDoc.sceneJson)
        assertEquals(2L, persistedDoc.revision)
    }

    @Test
    fun canvasApplyOpsAppliesReplaceOp() = runTest {
        val canvasId = CanvasId("canvas-ops-1")
        store.upsert(
            CanvasDocument(
                id = canvasId,
                title = "Ops Canvas",
                revision = 1L,
                sceneJson = "{}",
                updatedAtEpochMs = 1000L,
            )
        )

        val applyOpsTool = CanvasApplyOpsTool(store, sessions)
        val ops: List<CanvasOp> = listOf(
            CanvasOp.ReplaceSceneOp(
                opId = "op-1",
                actorId = "agent-x",
                lamport = 1L,
                sceneJson = "{\"elements\":[{\"id\":\"circle\"}]}",
            )
        )
        val opsJsonElement = json.parseToJsonElement(json.encodeToString<List<CanvasOp>>(ops))

        val result = applyOpsTool.invoke(
            buildJsonObject {
                put("canvas_id", canvasId.value)
                put("ops", opsJsonElement)
            },
        )
        assertIs<ExternalToolResult.Success>(result)
        val applyResult = json.decodeFromString<CanvasApplyOpsResult>(result.content)
        assertTrue(applyResult.ok)
        assertEquals(2L, applyResult.revision)

        val doc = store.get(canvasId)
        assertNotNull(doc)
        // The stored scene now carries the replace's lamport and actor on each element, so a
        // stale element op arriving later loses against it instead of finding unstamped content
        // to overwrite. What DrawBox is handed is still exactly the scene the agent sent.
        assertEquals(
            "{\"elements\":[{\"id\":\"circle\"}]}",
            CanvasOpProjector.stripMetadataForDrawBox(doc.sceneJson),
        )
    }

    @Test
    fun canvasExportSvgAndListTools() = runTest {
        val doc1 = CanvasDocument(
            id = CanvasId("canvas-svg-1"),
            conversationId = "conv-list-1",
            agentId = "agent-1",
            title = "Doc 1",
            revision = 1L,
            sceneJson = "{}",
            updatedAtEpochMs = 1000L,
        )
        store.upsert(doc1)

        val exportSvgTool = CanvasExportSvgTool(store, sessions)
        val svgResult = exportSvgTool.invoke(buildJsonObject { put("canvas_id", "canvas-svg-1") })
        assertIs<ExternalToolResult.Success>(svgResult)
        val svg = json.decodeFromString<CanvasExportSvgResult>(svgResult.content)
        assertTrue(svg.svg.contains("<svg"))

        val listTool = CanvasListTool(store, sessions)
        val listResult = listTool.invoke(buildJsonObject { put("conversation_id", "conv-list-1") })
        assertIs<ExternalToolResult.Success>(listResult)
        val list = json.decodeFromString<CanvasListResult>(listResult.content)
        assertEquals(listOf("canvas-svg-1"), list.ids)
    }

    @Test
    fun errorHandlingMissingParamsOrDoc() = runTest {
        val tools = CanvasExternalTools.all(store, sessions).associateBy { it.name }
        val getSceneTool = tools.getValue("canvas.get_scene")

        val missingParamResult = getSceneTool.invoke(buildJsonObject { })
        assertIs<ExternalToolResult.Error>(missingParamResult)
        assertTrue(missingParamResult.error.contains("canvas_id"))

        val missingDocResult = getSceneTool.invoke(buildJsonObject { put("canvas_id", "non-existent") })
        assertIs<ExternalToolResult.Error>(missingDocResult)
        assertTrue(missingDocResult.error.contains("not found"))
    }
}
