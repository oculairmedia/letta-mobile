package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
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
            agentId = "agent-1",
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
            options = CanvasCreateOptions(
                canvasId = canvasId,
                title = "Active Session Canvas",
                agentId = "agent-1",
                initialSceneJson = "{\"initial\":true}",
            ),
        )
        sessions.register(session)

        val replaceTool = CanvasReplaceSceneTool(store, sessions)
        val newSceneJson = "{\"bgColor\":-1,\"elements\":[{\"id\":\"1\"}]}"

        val replaceResult = replaceTool.invoke(
            buildJsonObject {
                put("canvas_id", canvasId.value)
                put("scene_json", newSceneJson)
            },
            agentId = "agent-1",
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
            agentId = "agent-x",
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

        // Not offered, and plain about it when called anyway: no plausible empty SVG (qsq7v).
        val exportSvgTool = CanvasExportSvgTool(store, sessions)
        val svgResult = exportSvgTool.invoke(buildJsonObject { put("canvas_id", "canvas-svg-1") }, agentId = "agent-1")
        assertIs<ExternalToolResult.Error>(svgResult)
        assertTrue(svgResult.error.contains("not implemented"))

        val listTool = CanvasListTool(store, sessions)
        val listResult = listTool.invoke(buildJsonObject { put("conversation_id", "conv-list-1") }, agentId = "agent-1")
        assertIs<ExternalToolResult.Success>(listResult)
        val list = json.decodeFromString<CanvasListResult>(listResult.content)
        assertEquals(listOf("canvas-svg-1"), list.ids)
    }

    @Test
    fun errorHandlingMissingParamsOrDoc() = runTest {
        val tools = CanvasExternalTools.all(store, sessions).associateBy { it.name }
        val getSceneTool = tools.getValue("canvas.get_scene")

        val missingParamResult = getSceneTool.invoke(buildJsonObject { }, agentId = "agent-1")
        assertIs<ExternalToolResult.Error>(missingParamResult)
        assertTrue(missingParamResult.error.contains("canvas_id"))

        val missingDocResult = getSceneTool.invoke(buildJsonObject { put("canvas_id", "non-existent") }, agentId = "agent-1")
        assertIs<ExternalToolResult.Error>(missingDocResult)
        assertTrue(missingDocResult.error.contains("not found"))
    }

    @Test
    fun everyToolRefusesACallWithoutAnAuthenticatedAgent() = runTest {
        store.upsert(openDocument(CanvasId("canvas-open"), conversationId = "conv-open"))
        val inputs = mapOf(
            "canvas.create" to buildJsonObject { put("title", "x") },
            "canvas.get_scene" to buildJsonObject { put("canvas_id", "canvas-open") },
            "canvas.replace_scene" to buildJsonObject { put("canvas_id", "canvas-open"); put("scene_json", "{}") },
            "canvas.apply_ops" to buildJsonObject { put("canvas_id", "canvas-open"); put("ops", buildJsonArray { }) },
            "canvas.export_svg" to buildJsonObject { put("canvas_id", "canvas-open") },
            "canvas.list" to buildJsonObject { put("conversation_id", "conv-open") },
        )
        for (tool in CanvasExternalTools.all(store, sessions)) {
            val result = tool.invoke(inputs.getValue(tool.name), agentId = null)
            assertIs<ExternalToolResult.Error>(result, "${tool.name} must refuse an unauthenticated call")
            assertTrue(result.error.contains("authenticated agent"), "${tool.name}: ${result.error}")
        }
        // The open document is untouched by the refused writes.
        assertEquals(1L, store.get(CanvasId("canvas-open"))?.revision)
    }

    @Test
    fun agentIdInTheInputNeverOverridesTheAuthenticatedCaller() = runTest {
        val protectedDoc = CanvasDocument(
            id = CanvasId("canvas-guarded"),
            title = "Guarded",
            revision = 1L,
            sceneJson = "{}",
            updatedAtEpochMs = 1000L,
            acl = CanvasAcl(ownerUserId = "alice", writerAgentIds = setOf("trusted-agent"), readerAgentIds = setOf("trusted-agent")),
        )
        store.upsert(protectedDoc)
        val getSceneTool = CanvasGetSceneTool(store, sessions)

        val spoofed = getSceneTool.invoke(
            buildJsonObject {
                put("canvas_id", "canvas-guarded")
                put("agent_id", "trusted-agent")
            },
            agentId = "intruder",
        )
        assertIs<ExternalToolResult.Error>(spoofed)
        assertTrue(spoofed.error.contains("Unauthorized"))

        // canvas.create stamps ownership from the runtime identity, not the input.
        val created = CanvasCreateTool(store, sessions).invoke(
            buildJsonObject { put("agent_id", "trusted-agent") },
            agentId = "intruder",
        )
        assertIs<ExternalToolResult.Success>(created)
        val newDoc = store.get(CanvasId(json.decodeFromString<CanvasCreateResult>(created.content).canvasId))
        assertNotNull(newDoc)
        assertEquals("intruder", newDoc.agentId)
        assertEquals(setOf("intruder"), newDoc.acl?.writerAgentIds)
    }

    @Test
    fun conversationLookupsOnlyRevealACanvasTheCallerMayRead() = runTest {
        val hidden = CanvasDocument(
            id = CanvasId("canvas-hidden"),
            conversationId = "conv-private",
            agentId = "owner-agent",
            title = "Private",
            revision = 1L,
            sceneJson = "{}",
            updatedAtEpochMs = 1000L,
            acl = CanvasAcl(ownerUserId = "alice", readerAgentIds = setOf("owner-agent")),
        )
        store.upsert(hidden)

        val listResult = CanvasListTool(store, sessions).invoke(
            buildJsonObject { put("conversation_id", "conv-private") },
            agentId = "stranger",
        )
        assertIs<ExternalToolResult.Success>(listResult)
        assertEquals(emptyList(), json.decodeFromString<CanvasListResult>(listResult.content).ids)

        // A conversation has one canvas, so a stranger is refused rather than handed the id or
        // a second canvas for the same conversation.
        val createResult = CanvasCreateTool(store, sessions).invoke(
            buildJsonObject { put("conversation_id", "conv-private") },
            agentId = "stranger",
        )
        assertIs<ExternalToolResult.Error>(createResult)
        assertTrue(createResult.error.contains("Unauthorized"))
        assertTrue(createResult.error.contains("canvas-hidden").not(), "the private canvas id leaked through canvas.create")

        // The reader it names still resolves the existing canvas.
        val allowed = CanvasListTool(store, sessions).invoke(
            buildJsonObject { put("conversation_id", "conv-private") },
            agentId = "owner-agent",
        )
        assertIs<ExternalToolResult.Success>(allowed)
        assertEquals(listOf("canvas-hidden"), json.decodeFromString<CanvasListResult>(allowed.content).ids)
    }

    @Test
    fun concurrentToolWritesNeverShareARevision() = runTest {
        val canvasId = CanvasId("canvas-concurrent")
        val racingStore = RevisionRacingStore(InMemoryCanvasDocumentStore())
        racingStore.upsert(openDocument(canvasId))
        val replaceTool = CanvasReplaceSceneTool(racingStore, sessions)

        // Both calls read revision 1 and park; only then may either of them write.
        val first = async {
            replaceTool.invoke(buildJsonObject { put("canvas_id", canvasId.value); put("scene_json", "{\"a\":1}") }, agentId = "agent-1")
        }
        val second = async {
            replaceTool.invoke(buildJsonObject { put("canvas_id", canvasId.value); put("scene_json", "{\"b\":2}") }, agentId = "agent-1")
        }
        runCurrent()
        assertEquals(2, racingStore.parkedReads, "both calls must hold the same snapshot before the race")
        racingStore.releaseReads()
        val results = listOf(first.await(), second.await())

        val successes = results.filterIsInstance<ExternalToolResult.Success>()
        val conflicts = results.filterIsInstance<ExternalToolResult.Error>()
        assertEquals(1, successes.size, "exactly one writer wins: $results")
        assertEquals(1, conflicts.size)
        assertTrue(conflicts.single().error.startsWith("Conflict"))
        assertEquals(2L, racingStore.get(canvasId)?.revision)
    }

    @Test
    fun applyOpsRebindsEveryOperationToTheAuthenticatedCaller() = runTest {
        val canvasId = CanvasId("canvas-attrib")
        store.upsert(openDocument(canvasId))
        val ops: List<CanvasOp> = listOf(
            CanvasOp.BatchOp(
                opId = "batch-1",
                actorId = "impostor",
                lamport = 1L,
                ops = listOf(
                    CanvasOp.AddElementOp(
                        opId = "add-1",
                        actorId = "impostor",
                        lamport = 1L,
                        elementId = "e1",
                        elementJson = """{"id":"e1","type":"rect"}""",
                    ),
                    CanvasOp.SetBackgroundOp(opId = "bg-1", actorId = "impostor", lamport = 2L, colorHex = "#ffffff"),
                ),
            ),
        )
        val result = CanvasApplyOpsTool(store, sessions).invoke(
            buildJsonObject {
                put("canvas_id", canvasId.value)
                put("ops", json.parseToJsonElement(json.encodeToString<List<CanvasOp>>(ops)))
            },
            agentId = "agent-real",
        )
        assertIs<ExternalToolResult.Success>(result)

        val scene = store.get(canvasId)?.sceneJson.orEmpty()
        assertTrue(scene.contains("agent-real"), "provenance must carry the caller: $scene")
        assertTrue(!scene.contains("impostor"), "the input's actor must not reach provenance: $scene")
    }

    @Test
    fun withActorRebindsNestedBatches() {
        val nested = CanvasOp.BatchOp(
            opId = "b", actorId = "x", lamport = 1L,
            ops = listOf(
                CanvasOp.BatchOp(
                    opId = "b2", actorId = "x", lamport = 1L,
                    ops = listOf(CanvasOp.RemoveElementOp(opId = "r", actorId = "x", lamport = 1L, elementId = "e")),
                ),
            ),
        )
        val rebound = nested.withActor("me") as CanvasOp.BatchOp
        val inner = rebound.ops.single() as CanvasOp.BatchOp
        assertEquals("me", rebound.actorId)
        assertEquals("me", inner.actorId)
        assertEquals("me", inner.ops.single().actorId)
    }

    @Test
    fun racingCreatesForOneConversationShareOneCanvas() = runTest {
        val createTool = CanvasCreateTool(store, sessions)
        val input = buildJsonObject { put("conversation_id", "conv-shared") }
        val results = (1..5).map { async { createTool.invoke(input, agentId = "agent-1") } }.map { it.await() }
        val ids = results.map { assertIs<ExternalToolResult.Success>(it); json.decodeFromString<CanvasCreateResult>(it.content).canvasId }
        assertEquals(1, ids.toSet().size, "every creator must get the same canvas: $ids")
        assertEquals(ids.first(), store.getForConversation("conv-shared")?.id?.value)

        // The same holds for a session-level open.
        val session = CanvasSession.getOrCreateForConversation(store, "conv-shared")
        assertEquals(ids.first(), session.canvasId.value)
    }

    @Test
    fun agentScopedListingAppliesTheReadCheck() = runTest {
        // The document names agent-1 but its ACL has since stopped letting agent-1 read.
        store.upsert(
            CanvasDocument(
                id = CanvasId("canvas-revoked"), agentId = "agent-1", title = "Revoked", revision = 1L, sceneJson = "{}",
                updatedAtEpochMs = 1L, acl = CanvasAcl(ownerUserId = "alice", readerAgentIds = setOf("someone-else")),
            ),
        )
        store.upsert(
            CanvasDocument(
                id = CanvasId("canvas-mine"), agentId = "agent-1", title = "Mine", revision = 1L, sceneJson = "{}",
                updatedAtEpochMs = 1L, acl = CanvasAcl(ownerUserId = "alice", writerAgentIds = setOf("agent-1")),
            ),
        )
        val listed = CanvasListTool(store, sessions).invoke(buildJsonObject { }, agentId = "agent-1")
        assertIs<ExternalToolResult.Success>(listed)
        assertEquals(listOf("canvas-mine"), json.decodeFromString<CanvasListResult>(listed.content).ids)
    }

    private fun openDocument(id: CanvasId, conversationId: String? = null) = CanvasDocument(
        id = id,
        conversationId = conversationId,
        title = "Open",
        revision = 1L,
        sceneJson = "{}",
        updatedAtEpochMs = 1000L,
    )

    /**
     * Snapshots the document, then parks every reader until [releaseReads]: two tool calls both
     * hold revision N and race their conditional writes for N+1.
     */
    private class RevisionRacingStore(private val inner: CanvasDocumentStore) : CanvasDocumentStore by inner {
        private val gate = CompletableDeferred<Unit>()
        var parkedReads: Int = 0
            private set
        fun releaseReads() { gate.complete(Unit) }
        override suspend fun get(id: CanvasId): CanvasDocument? {
            val snapshot = inner.get(id)
            parkedReads++
            gate.await()
            return snapshot
        }
    }
}
