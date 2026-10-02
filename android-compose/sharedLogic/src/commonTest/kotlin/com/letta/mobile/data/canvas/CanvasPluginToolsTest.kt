package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.PluginToolHost.Companion.AGENT
import com.letta.mobile.data.canvas.PluginToolHost.Companion.BAD_TYPE
import com.letta.mobile.data.canvas.PluginToolHost.Companion.INCOMPLETE
import com.letta.mobile.data.canvas.PluginToolHost.Companion.INTRUDER
import com.letta.mobile.data.canvas.PluginToolHost.Companion.PLACE
import com.letta.mobile.data.canvas.PluginToolHost.Companion.PROGRESS
import com.letta.mobile.data.canvas.PluginToolHost.Companion.REMOVE
import com.letta.mobile.data.canvas.PluginToolHost.Companion.SNAPSHOT_REF
import com.letta.mobile.data.canvas.PluginToolHost.Companion.content
import com.letta.mobile.data.canvas.PluginToolHost.Companion.error
import com.letta.mobile.data.canvas.PluginToolHost.Companion.json
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.5: canvas_apply_ops takes set_plugin_element / remove_plugin_element, and
 * canvas_get_scene answers the plugin elements, the same way on the Iroh host and on an app's own
 * App Server, board open or closed. Every case runs on all three ([PluginToolHost.all]).
 */
class CanvasPluginToolsTest {
    private suspend fun onEveryHost(case: suspend (PluginToolHost) -> Unit) = PluginToolHost.all().forEach { case(it) }

    @Test
    fun aPlacementWithoutIdentityIsPublishedAsTheCallersAndReadBackCompact() = runTest {
        onEveryHost { host ->
            val applied = json.decodeFromString(CanvasApplyOpsResult.serializer(), host.applyOps(PLACE).content())
            assertTrue(applied.ok, "$host")

            host.logged()?.let { logged ->
                val op = assertIs<CanvasOp.SetPluginElementOp>(logged.single(), "$host publishes the op itself")
                assertEquals(AGENT, op.actorId, "$host stamps the caller")
                assertTrue(op.opId.isNotBlank() && op.lamport > 0L, "$host stamps a fresh identity: $op")
            }
            val scene = host.scene()
            val element = scene.pluginElements.single()
            assertEquals(
                setOf("id", "type", "v", "frame", "ref", "props", "snapshot", "fallback", "owner"),
                element.keys,
                "$host lists the element compact: what was written, no provenance",
            )
            assertEquals(JsonPrimitive("ext:letta.example/widget"), element["type"])
            assertEquals(JsonPrimitive(SNAPSHOT_REF), element.getValue("snapshot").jsonObject["assetRef"])
            assertFalse(CanvasPluginElementsKey in scene.sceneJson, "$host keeps the raw collection out of scene_json: ${scene.sceneJson}")
            assertEquals(CanvasSceneSchema.hint, scene.schemaHint, "$host")
            assertEquals(PluginToolHost.CANVAS, scene.canvasId, "$host")
        }
    }

    @Test
    fun aStateUpdateWithPropsAsAStringLandsAndARemovalTakesTheElementOff() = runTest {
        onEveryHost { host ->
            host.applyOps(PLACE).content()
            host.applyOps(PROGRESS).content()

            val updated = json.decodeFromJsonElement(CanvasPluginElement.serializer(), host.scene().pluginElements.single())
            assertEquals(JsonPrimitive("running"), updated.props["status"], "$host takes props given as a JSON string")
            assertEquals("Example widget", updated.fallback.title, "$host keeps what the update left null")

            host.applyOps(REMOVE).content()
            assertEquals(emptyList(), host.scene().pluginElements, "$host")
        }
    }

    @Test
    fun anIncompleteFirstWriteIsRefusedWholeAndNothingIsPublished() = runTest {
        onEveryHost { host ->
            val before = host.scene().sceneJson

            val refusal = host.applyOps(PLACE.replace("pe-1", "pe-ok"), INCOMPLETE).error()

            assertTrue("pe-2" in refusal, "$host names the element: $refusal")
            assertTrue(refusal.startsWith("Refused: nothing in this batch was published"), "$host: $refusal")
            assertEquals(before, host.scene().sceneJson, "$host publishes nothing of the batch")
            assertEquals(emptyList(), host.scene().pluginElements)
        }
    }

    @Test
    fun anEnvelopeOutsideTheSchemaIsRefusedAtItsPath() = runTest {
        onEveryHost { host ->
            val refusal = host.applyOps(BAD_TYPE).error()
            assertTrue("pe-3/elementType" in refusal, "$host points at the field: $refusal")
            assertEquals(emptyList(), host.logged().orEmpty(), "$host")
        }
    }

    @Test
    fun aDryRunReportsEnvelopeProblemsAndPublishesNothing() = runTest {
        onEveryHost { host ->
            val checked = json.decodeFromString(CanvasDryRunResult.serializer(), host.applyOps(INCOMPLETE, dryRun = true).content())
            assertFalse(checked.valid, "$host")
            assertTrue(checked.problems.any { it.opIndex == "0" && "pe-2" in it.subject + it.detail }, "$host: ${checked.problems}")

            val fine = json.decodeFromString(CanvasDryRunResult.serializer(), host.applyOps(PLACE, dryRun = true).content())
            assertTrue(fine.valid, "$host: $fine")
            assertEquals(emptyList(), host.scene().pluginElements, "$host: a dry run never writes")
            assertEquals(emptyList(), host.logged().orEmpty())
        }
    }

    @Test
    fun anAgentTheBoardDoesNotLetWriteIsRefusedAsBefore() = runTest {
        onEveryHost { host ->
            host.scene() // the agent's own conversation canvas exists, its ACL the agent's
            val refusal = host.applyOps(PLACE, agent = INTRUDER).error()
            assertTrue(refusal.startsWith("Unauthorized: actor '$INTRUDER'"), "$host: $refusal")
            assertNull(host.logged()?.firstOrNull(), "$host")
        }
    }

    @Test
    fun theContractTellsAnAgentWhereThePluginElementsAreAndStaysShort() {
        val getScene = CanvasToolContract.getScene.description
        assertTrue("plugin_elements" in getScene && "set_plugin_element" in getScene, getScene)
        assertTrue(CanvasPluginElementsKey in CanvasSceneSchema.hint && "plugin_elements" in CanvasSceneSchema.hint, CanvasSceneSchema.hint)
        assertTrue(getScene.length <= GET_SCENE_DESCRIPTION_MAX_CHARS, "${getScene.length} chars")
        assertTrue(CanvasSceneSchema.hint.length <= HINT_MAX_CHARS, "${CanvasSceneSchema.hint.length} chars")
    }

    private companion object {
        const val CanvasPluginElementsKey = "_pluginElements"

        /** Every get_scene result carries the hint, so it stays a paragraph, not a manual. */
        const val HINT_MAX_CHARS = 700
        const val GET_SCENE_DESCRIPTION_MAX_CHARS = 400
    }
}
