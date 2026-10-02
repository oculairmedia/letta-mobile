package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasBatchCheck
import com.letta.mobile.data.canvas.CanvasBatchValidator
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.withActor
import com.letta.mobile.data.canvas.withStamp
import kotlin.test.assertIs

/**
 * A board for the compose tests: a scene that compiled batches are checked against and published
 * to the way a host does it (the batch validator, then each op stamped after the scene's newest).
 */
internal class ComposeBoard(var sceneJson: String = "") {
    var revision: Long = 0
    val published = mutableListOf<List<CanvasOp>>()

    /** What the hosts' publish does with checked ops: stamp, apply, bump the revision. */
    fun publish(ops: List<CanvasOp>): Long {
        published += ops
        var lamport = CanvasOpProjector.maxLamport(sceneJson)
        val stamped = ops.map { it.withActor(AGENT).withStamp("op-${++revision}-${lamport + 1}", ++lamport) }
        sceneJson = CanvasOpProjector.project(sceneJson, stamped)
        return revision
    }

    /** [ops] checked by the batch validator and published; fails the test when the board refuses them. */
    fun publishChecked(ops: List<CanvasOp>): Long {
        val valid = assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(sceneJson, ops), "the board refused the batch")
        return publish(valid.ops)
    }

    /** An ordinary note, placed by a person. */
    fun addNote(id: String, frame: CanvasDocumentFrame) {
        publishChecked(
            listOf(
                CanvasOp.SetDocumentOp(
                    "", "", 0, id,
                    """{"version":2,"blocks":[{"id":"b1","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"A note of my own","spans":[]}}]}""",
                    frame = frame,
                ),
            ),
        )
    }

    /** An ordinary drawn rectangle over [box]. */
    fun addBox(id: String, box: Slot) {
        publishChecked(
            listOf(
                CanvasOp.AddElementOp(
                    "", "", 0, id,
                    """{"type":"Shape","shapeType":"RECTANGLE","points":["${box.x},${box.y}","${box.right},${box.bottom}"],"strokeColor":"#000000ff","strokeWidth":2.0,"zIndex":0}""",
                ),
            ),
        )
    }

    companion object {
        const val AGENT = "agent-1"
        const val CANVAS = "canvas-conversation-conv-123"
    }
}

/** The request fixture (`request.json`; the guide's example is the same request, held equal by CanvasComposeFixturesTest). */
internal val WEEKEND_PLAN: String get() = CanvasComposeGuide.EXAMPLE_REQUEST

internal fun readyOf(compilation: ComposeCompilation): ComposeCompilation.Ready =
    assertIs<ComposeCompilation.Ready>(compilation, "expected a batch, got $compilation")

internal fun refusedOf(compilation: ComposeCompilation): ComposeRefusal =
    assertIs<ComposeCompilation.Refused>(compilation, "expected a refusal, got $compilation").refusal

internal fun compileText(request: String, sceneJson: String = "", fallback: String = "fallback"): ComposeCompilation =
    CanvasComposeCompiler.compile(ComposeJson.parse(request), sceneJson) { fallback }
