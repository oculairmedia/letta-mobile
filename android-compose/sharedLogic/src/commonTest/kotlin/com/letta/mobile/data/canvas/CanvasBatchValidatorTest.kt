package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.CanvasBatchFixtures.arrow1
import com.letta.mobile.data.canvas.CanvasBatchFixtures.batchOf
import com.letta.mobile.data.canvas.CanvasBatchFixtures.box1
import com.letta.mobile.data.canvas.CanvasBatchFixtures.box2
import com.letta.mobile.data.canvas.CanvasBatchFixtures.ghost
import com.letta.mobile.data.canvas.CanvasBatchFixtures.label1
import com.letta.mobile.data.canvas.CanvasBatchFixtures.labelledBox
import com.letta.mobile.data.canvas.CanvasBatchFixtures.note1
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
        val dangling = sceneOf(labelledBox(box1, label1) + box1.remove())

        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(dangling, listOf(box2.add())))
    }

    @Test
    fun anAddOfAnIdAlreadyOnTheBoardIsRefused() {
        val scene = sceneOf(listOf(box1.add()))

        assertEquals(listOf("0 element.duplicateId"), invariantsOf(CanvasBatchValidator.check(scene, listOf(box1.add()))))
    }

    @Test
    fun aNoteThatIsNotACascadeDocumentIsRefused() {
        assertEquals(listOf("0 document.decodes"), invariantsOf(CanvasBatchValidator.check("", listOf(note1.set(FixtureNoteBody.PROSE_MIRROR)))))
    }

    @Test
    fun anOwnerNamingAMissingShapeIsBlamedOnItsOp() {
        val batch = listOf(label1.set(), label1.ownedBy(ghost))

        assertEquals(listOf("1 label.owner"), invariantsOf(CanvasBatchValidator.check("", batch)))
    }

    @Test
    fun opsInsideABatchOpAreNamedByTheirPlace() {
        val nested = batchOf(listOf(box1.add(), ghost.remove()))

        assertEquals(listOf("0.1 element.exists"), invariantsOf(CanvasBatchValidator.check("", listOf(nested))))
    }

    @Test
    fun anArrowBoundToAMissingShapeIsRefused() {
        val arrow = arrow1.add(FixtureShapeKind.ARROW, endBinding = ghost)

        assertEquals(listOf("0 element.binding"), invariantsOf(CanvasBatchValidator.check("", listOf(arrow))))
    }
}
