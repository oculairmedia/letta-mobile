package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.CanvasBatchFixtures.addShape
import com.letta.mobile.data.canvas.CanvasBatchFixtures.labelledBox
import com.letta.mobile.data.canvas.CanvasBatchFixtures.note
import com.letta.mobile.data.canvas.CanvasBatchFixtures.owner
import com.letta.mobile.data.canvas.CanvasBatchFixtures.remove
import com.letta.mobile.data.canvas.CanvasBatchFixtures.sceneOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** letta-mobile-qygvv.30: the rules a batch is held to, on a scratch copy of the scene. */
class CanvasBatchValidatorTest {
    private fun invariantsOf(check: CanvasBatchCheck): List<String> =
        assertIs<CanvasBatchCheck.Invalid>(check, "expected a refusal").violations.map { "${it.opIndex} ${it.violation.invariant.wire}" }

    @Test
    fun aBoardThatAlreadyBreaksARuleStillTakesUnrelatedWrites() {
        val dangling = sceneOf(labelledBox("box-1", "label-1") + remove("box-1"))

        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(dangling, listOf(addShape("box-2"))))
    }

    @Test
    fun anAddOfAnIdAlreadyOnTheBoardIsRefused() {
        val scene = sceneOf(listOf(addShape("box-1")))

        assertEquals(listOf("0 element.duplicateId"), invariantsOf(CanvasBatchValidator.check(scene, listOf(addShape("box-1")))))
    }

    @Test
    fun aNoteThatIsNotACascadeDocumentIsRefused() {
        val proseMirror = "{\"type\":\"doc\",\"content\":[]}"

        assertEquals(listOf("0 document.decodes"), invariantsOf(CanvasBatchValidator.check("", listOf(note("n-1", proseMirror)))))
    }

    @Test
    fun anOwnerNamingAMissingShapeIsBlamedOnItsOp() {
        val batch = listOf(note("label-1"), owner("label-1", "ghost"))

        assertEquals(listOf("1 label.owner"), invariantsOf(CanvasBatchValidator.check("", batch)))
    }

    @Test
    fun opsInsideABatchOpAreNamedByTheirPlace() {
        val nested = CanvasOp.BatchOp("", "", 0L, listOf(addShape("box-1"), remove("ghost")))

        assertEquals(listOf("0.1 element.exists"), invariantsOf(CanvasBatchValidator.check("", listOf(nested))))
    }

    @Test
    fun anArrowBoundToAMissingShapeIsRefused() {
        val arrow = CanvasOp.AddElementOp(
            "", "", 0L, "arrow-1",
            CanvasBatchFixtures.shapeJson("arrow-1").replace("\"RECTANGLE\"", "\"ARROW\"").replace("\"text\":\"Plan\"", "\"endBinding\":\"ghost\""),
        )

        assertEquals(listOf("0 element.binding"), invariantsOf(CanvasBatchValidator.check("", listOf(arrow))))
    }
}
