package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.CanvasBatchFixtures.box1
import com.letta.mobile.data.canvas.CanvasBatchFixtures.box2
import com.letta.mobile.data.canvas.CanvasBatchFixtures.box7
import com.letta.mobile.data.canvas.CanvasBatchFixtures.ghost
import com.letta.mobile.data.canvas.CanvasBatchFixtures.label1
import com.letta.mobile.data.canvas.CanvasBatchFixtures.labelledBox
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.30: an agent's batch is held to the board it leaves, all or nothing, and can
 * be checked first with dry_run.
 */
class HostCanvasBatchValidationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val topic = CanvasRelayProtocol.conversationTopic(CONVERSATION)

    private class Host {
        val store = InMemoryCanvasRelayStore()
        val registry = ExternalToolRegistry.hostTools(
            HostCanvasTools.all(HostCanvasBackend(CanvasRelayHost(store, hostId = { "host-1" }), store, InMemoryHostCanvasDirectory())),
        )

        suspend fun call(tool: String, input: JsonObject) = registry.invoke(tool, input, agentId = AGENT, conversationId = CONVERSATION)
    }

    private fun ops(ops: List<CanvasOp>, dryRun: Boolean = false) = buildJsonObject {
        put("ops", JsonArray(ops.map { json.encodeToJsonElement<CanvasOp>(it) }))
        if (dryRun) put(CanvasDryRun.PARAM, true)
    }

    private suspend fun Host.apply(batch: List<CanvasOp>, dryRun: Boolean = false) = call(CanvasToolContract.APPLY_OPS, ops(batch, dryRun))

    private suspend fun Host.loggedCount() = store.readAfter(topic, 0L).size

    private fun Any.content(): String = assertIs<ExternalToolResult.Success>(this, "tool call failed: $this").content

    private fun Any.error(): String = assertIs<ExternalToolResult.Error>(this, "expected a refusal, got $this").error

    @Test
    fun removingAShapeItsLabelStillNamesRefusesTheWholeBatch() = runTest {
        val host = Host()
        host.apply(labelledBox(box1, label1)).content()
        val before = host.loggedCount()

        val refusal = host.apply(listOf(box2.add(), box1.remove())).error()

        assertTrue("op 1 (remove_element 'box-1')" in refusal, refusal)
        assertTrue("[label.owner on 'label-1']" in refusal, refusal)
        assertEquals(before, host.loggedCount(), "not even the valid add_element is appended")
    }

    @Test
    fun removingTheShapeAndItsLabelTogetherIsAccepted() = runTest {
        val host = Host()
        host.apply(labelledBox(box1, label1)).content()

        host.apply(listOf(box1.remove(), label1.remove())).content()
    }

    @Test
    fun anUpdateOfAMissingElementIsRefused() = runTest {
        val host = Host()
        host.apply(listOf(box1.add())).content()
        val before = host.loggedCount()

        val whole = host.apply(listOf(box1.update(), ghost.update())).error()
        val partial = host.apply(listOf(ghost.partialUpdate())).error()

        assertTrue("op 1 (update_element 'ghost')" in whole && "[element.exists on 'ghost']" in whole, whole)
        assertTrue("op 0 (update_element 'ghost')" in partial && "[element.shape on 'ghost']" in partial, partial)
        assertEquals(before, host.loggedCount(), "neither batch reached the log")
    }

    @Test
    fun aValidBatchIsPublishedWhole() = runTest {
        val host = Host()
        val batch = labelledBox(box1, label1) + box1.update()

        val result = json.decodeFromString<CanvasApplyOpsResult>(host.apply(batch).content())

        assertTrue(result.ok)
        assertEquals(batch.size, host.loggedCount())
        assertEquals(listOf(label1.id), CanvasOpProjector.documentsOf(logScene(host)).map { it.id })
        assertEquals(mapOf(label1.id to box1.id), CanvasOpProjector.labelOwnersOf(logScene(host)))
    }

    @Test
    fun aDryRunReportsWithoutPublishing() = runTest {
        val host = Host()
        host.apply(listOf(box1.add())).content()
        val before = host.loggedCount()

        val invalid = json.decodeFromString<CanvasDryRunResult>(host.apply(listOf(ghost.remove()), dryRun = true).content())
        val valid = json.decodeFromString<CanvasDryRunResult>(host.apply(listOf(box1.update()), dryRun = true).content())

        assertEquals(false, invalid.valid)
        assertEquals(listOf("0"), invalid.problems.map { it.opIndex })
        assertEquals(listOf(CanvasStateInvariant.ELEMENT_EXISTS.wire), invalid.problems.map { it.invariant })
        assertEquals(true, valid.valid)
        assertEquals(before.toLong(), valid.revision)
        assertEquals(before, host.loggedCount(), "a dry run publishes nothing")
    }

    @Test
    fun aReplaceThatDropsALabelledShapeIsRefusedAndCanBeDryRun() = runTest {
        val host = Host()
        // The example scene draws box-1 and title-1: box-7, which owns the label, is dropped.
        host.apply(labelledBox(box7, label1)).content()
        val input = buildJsonObject {
            put("scene_json", CanvasSceneSchema.sceneExample.toString())
            put(CanvasDryRun.PARAM, true)
        }

        val checked = json.decodeFromString<CanvasDryRunResult>(host.call(CanvasToolContract.REPLACE_SCENE, input).content())

        assertEquals(listOf(CanvasStateInvariant.LABEL_OWNER.wire), checked.problems.map { it.invariant })
    }

    private suspend fun logScene(host: Host): String = CanvasOpProjector.project("", host.store.readAfter(topic, 0L).map { it.op })

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-batch"
    }
}
