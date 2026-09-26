package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.21: an agent's canvas writes are held to the scene format the apps draw, and
 * land on its own conversation's canvas unless it names another.
 */
class HostCanvasSceneSchemaTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val conversationCanvas = CanvasId.forConversation(CONVERSATION).value
    private val conversationTopic = CanvasRelayProtocol.conversationTopic(CONVERSATION)

    private class Host {
        val store = InMemoryCanvasRelayStore()
        val relay = CanvasRelayHost(store, hostId = { "host-1" })
        val registry = ExternalToolRegistry.hostTools(
            HostCanvasTools.all(HostCanvasBackend(relay, store, InMemoryHostCanvasDirectory())),
        )

        suspend fun call(tool: String, input: JsonObject, conversation: String? = CONVERSATION) =
            registry.invoke(tool, input, agentId = AGENT, conversationId = conversation)

        suspend fun logged(topic: String) = store.readAfter(topic, 0L)
    }

    /** The scene the model wrote on 2026-09-24 (ops.jsonl cursor 2): types and fields it invented. */
    private val inventedScene: JsonObject = buildJsonObject {
        put(
            "elements",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "rect-1")
                        put("type", "rectangle")
                        put("x", 100)
                        put("y", 100)
                        put("width", 300)
                        put("height", 200)
                        put("color", "#4caf50")
                        put("z_order", 1)
                    },
                    buildJsonObject {
                        put("id", "doc-1")
                        put("type", "document")
                        put("title", "Plan")
                        put("content", "Steps")
                    },
                ),
            ),
        )
    }

    private fun replaceInput(scene: JsonObject, canvasId: String? = null) = buildJsonObject {
        canvasId?.let { put("canvas_id", it) }
        put("scene_json", scene.toString())
    }

    private fun Any.content(): String = assertIs<ExternalToolResult.Success>(this, "tool call failed: $this").content

    private fun Any.error(): String = assertIs<ExternalToolResult.Error>(this, "expected a refusal, got $this").error

    private fun elementsOf(sceneJson: String) = json.parseToJsonElement(sceneJson).jsonObject.getValue("elements").jsonArray

    @Test
    fun theInventedSceneIsRefusedWithTheReasonAndNothingIsPublished() = runTest {
        val host = Host()

        val refusal = host.call(CanvasToolContract.REPLACE_SCENE, replaceInput(inventedScene)).error()

        assertTrue("rect-1" in refusal && "unknown type 'rectangle'" in refusal, refusal)
        assertTrue("doc-1" in refusal && "unknown type 'document'" in refusal, refusal)
        assertTrue("Shape, Text, Path, Image" in refusal, "names the allowed types: $refusal")
        assertTrue("Shape example" in refusal && "Text example" in refusal, "shows the closest types: $refusal")
        assertTrue("set_document" in refusal, "says where notes go: $refusal")
        assertEquals(emptyList(), host.logged(conversationTopic), "an unrenderable scene never enters the log")
    }

    @Test
    fun aValidShapeAndTextScenePublishesAndReachesTheApps() = runTest {
        val host = Host()
        val phone = TestApp("phone", backgroundScope).open(CONVERSATION, agentId = AGENT)
        phone.connect(host.relay)
        runCurrent()

        val replaced = host.call(CanvasToolContract.REPLACE_SCENE, replaceInput(CanvasSceneSchema.sceneExample)).content()
        runCurrent()

        assertEquals(true, json.decodeFromString<CanvasReplaceSceneResult>(replaced).ok)
        val onPhone = CanvasOpProjector.stripMetadataForDrawBox(phone.scene())
        assertEquals(2, elementsOf(onPhone).size, "the phone projects both elements: $onPhone")
        assertEquals(onPhone, CanvasSceneRenderGuard.renderable(onPhone, conversationCanvas), "and draws every one")
    }

    @Test
    fun aCallThatNamesNoCanvasUsesItsConversationsCanvas() = runTest {
        val host = Host()

        val replaced = json.decodeFromString<CanvasReplaceSceneResult>(
            host.call(CanvasToolContract.REPLACE_SCENE, replaceInput(CanvasSceneSchema.sceneExample)).content(),
        )
        val read = json.decodeFromString<CanvasGetSceneResult>(host.call(CanvasToolContract.GET_SCENE, JsonObject(emptyMap())).content())

        assertEquals(conversationCanvas, replaced.canvasId)
        assertEquals(conversationCanvas, read.canvasId)
        assertEquals(CanvasSceneSchema.hint, read.schemaHint)
        assertEquals(1, host.logged(conversationTopic).size, "written to the conversation's topic")
    }

    @Test
    fun theListMarksTheConversationsCanvasCurrentAndListsItFirst() = runTest {
        val host = Host()
        host.call(CanvasToolContract.CREATE, buildJsonObject { put("title", "Elsewhere") }).content()

        val listed = json.decodeFromString<CanvasListResult>(host.call(CanvasToolContract.LIST, JsonObject(emptyMap())).content())

        assertEquals(conversationCanvas, listed.ids.first())
        assertEquals(listOf(true, false), listed.canvases.map { it.current })
    }

    @Test
    fun anExplicitCanvasIdStillWins() = runTest {
        val host = Host()
        val other = json.decodeFromString<CanvasCreateResult>(
            host.call(CanvasToolContract.CREATE, buildJsonObject { put("title", "Other") }).content(),
        ).canvasId

        val replaced = json.decodeFromString<CanvasReplaceSceneResult>(
            host.call(CanvasToolContract.REPLACE_SCENE, replaceInput(CanvasSceneSchema.sceneExample, canvasId = other)).content(),
        )

        assertEquals(other, replaced.canvasId)
        assertEquals(emptyList(), host.logged(conversationTopic))
    }

    @Test
    fun anElementOpMissingAFieldIsRefusedNamingIt() = runTest {
        val host = Host()
        val pointless = buildJsonObject {
            put("type", "Shape")
            put("shapeType", "RECTANGLE")
        }
        val op = buildJsonObject {
            put("type", "add_element")
            put("elementId", "box-9")
            put("elementJson", pointless)
        }

        val refusal = host.call(CanvasToolContract.APPLY_OPS, buildJsonObject { put("ops", JsonArray(listOf(op))) }).error()

        assertTrue("box-9" in refusal && "missing required field 'points'" in refusal, refusal)
        assertEquals(emptyList(), host.logged(conversationTopic))
    }

    @Test
    fun documentOpsAreValidatedBeforeHostPublication() = runTest {
        val unrecognized = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"x"}]}]}"""
        val malformed = "{"

        for ((documentId, documentJson) in listOf("prose-mirror-note" to unrecognized, "malformed-note" to malformed)) {
            val host = Host()
            val op = buildJsonObject {
                put("type", "set_document")
                put("documentId", documentId)
                put("documentJson", documentJson)
            }

            val refusal = host.call(CanvasToolContract.APPLY_OPS, buildJsonObject {
                put("ops", JsonArray(listOf(op)))
            }).error()

            assertTrue(documentId in refusal && "blocks" in refusal, refusal)
            assertEquals(emptyList(), host.logged(conversationTopic), "an unrenderable document never enters the log")
        }
    }

    @Test
    fun validCascadeDocumentsArePublishedAndLoggedThroughTheHost() = runTest {
        val host = Host()
        val cascade = """{"version":2,"blocks":[{"id":"p1","content":{"text":"x"}}]}"""
        val empty = """{"version":2,"blocks":[]}"""

        for ((documentId, documentJson) in listOf("cascade-note" to cascade, "empty-note" to empty)) {
            val op = buildJsonObject {
                put("type", "set_document")
                put("documentId", documentId)
                put("documentJson", documentJson)
            }
            val result = host.call(CanvasToolContract.APPLY_OPS, buildJsonObject {
                put("ops", JsonArray(listOf(op)))
            }).content()
            assertEquals(true, json.decodeFromString<CanvasApplyOpsResult>(result).ok)
        }

        val logged = host.logged(conversationTopic).map { assertIs<CanvasOp.SetDocumentOp>(it.op) }
        assertEquals(listOf("cascade-note", "empty-note"), logged.map { it.documentId })
        assertEquals(listOf(cascade, empty), logged.map { it.documentJson })
    }

    @Test
    fun opsWithoutIdentityAndWithObjectElementsAreAccepted() = runTest {
        val host = Host()
        val op = buildJsonObject {
            put("type", "add_element")
            put("elementId", "box-1")
            put("elementJson", CanvasSceneSchema.shape.example)
        }

        val applied = host.call(CanvasToolContract.APPLY_OPS, buildJsonObject { put("ops", JsonArray(listOf(op))) }).content()

        assertEquals(true, json.decodeFromString<CanvasApplyOpsResult>(applied).ok)
        val logged = assertIs<CanvasOp.AddElementOp>(host.logged(conversationTopic).single().op)
        assertEquals(AGENT, logged.actorId)
        assertTrue("\"strokeWidth\"" in logged.elementJson, "filled to what DrawBox requires: ${logged.elementJson}")
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
    }
}
