package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.PluginToolHost.Companion.BAD_TYPE
import com.letta.mobile.data.canvas.PluginToolHost.Companion.INCOMPLETE
import com.letta.mobile.data.canvas.PluginToolHost.Companion.MOVE
import com.letta.mobile.data.canvas.PluginToolHost.Companion.PLACE
import com.letta.mobile.data.canvas.PluginToolHost.Companion.PROGRESS
import com.letta.mobile.data.canvas.PluginToolHost.Companion.REMOVE
import com.letta.mobile.data.canvas.PluginToolHost.Companion.json
import com.letta.mobile.data.canvas.PluginToolHost.Companion.shape
import com.letta.mobile.data.canvas.PluginToolHost.Companion.withoutOpIds
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * letta-mobile-s416w.5, the CanvasComposeHostParityTest of plugin elements: the same canvas_apply_ops
 * calls answered by the Iroh host and by an app's own App Server (board open, board closed) answer
 * the same, publish the same ops with the same clocks and leave the same board, read back the same
 * by canvas_get_scene. Only what each host mints differs: op ids, and the revision it counts.
 */
class CanvasPluginHostParityTest {
    /** Place, update with string props, move beside a drawn element, refuse, check, remove. */
    private val calls: List<Pair<List<String>, Boolean>> = listOf(
        listOf(PLACE) to false,
        listOf(PROGRESS) to false,
        listOf(MOVE, shape) to false,
        listOf(INCOMPLETE) to false,
        listOf(BAD_TYPE) to false,
        listOf(PROGRESS, INCOMPLETE) to true,
        listOf(PLACE.replace("pe-1", "pe-9"), REMOVE) to false,
    )

    @Test
    fun everyHostAnswersTheSameCallsTheSame() = runTest {
        val (iroh, open, closed) = PluginToolHost.all()
        for ((ops, dryRun) in calls) {
            val answers = listOf(iroh, open, closed).map { host -> comparable(host.applyOps(*ops.toTypedArray(), dryRun = dryRun), dryRun) }
            assertEquals(answers[0], answers[1], "$ops: the host and an open board answer alike")
            assertEquals(answers[0], answers[2], "$ops: the host and a closed board answer alike")

            val reads = listOf(iroh, open, closed).map { it.scene() }
            reads.drop(1).forEach { read ->
                assertEquals(reads[0].pluginElements, read.pluginElements, "$ops: the same plugin elements")
                assertEquals(withoutOpIds(reads[0].sceneJson), withoutOpIds(read.sceneJson), "$ops: the same scene")
            }
        }
        assertEquals(unstamped(iroh.logged()), unstamped(open.logged()), "the same ops, in the same order, with the same clocks")
    }

    /** A result without what each host counts or mints for itself: its revision. */
    private fun comparable(result: ExternalToolResult, dryRun: Boolean): Any {
        val content = (result as? ExternalToolResult.Success)?.content ?: return result
        return if (dryRun) {
            json.decodeFromString(CanvasDryRunResult.serializer(), content).copy(revision = 0L)
        } else {
            json.decodeFromString(CanvasApplyOpsResult.serializer(), content).copy(revision = 0L)
        }
    }

    private fun unstamped(ops: List<CanvasOp>?): List<CanvasOp> = assertIs<List<CanvasOp>>(ops).map { it.withStamp("", it.lamport) }
}
