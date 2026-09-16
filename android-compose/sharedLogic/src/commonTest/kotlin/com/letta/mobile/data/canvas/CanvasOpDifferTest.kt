package com.letta.mobile.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CanvasOpDifferTest {

    @Test
    fun diff_emptyScenesProducesNoOps() {
        val ops = CanvasOpDiffer.diff(
            oldSceneJson = "",
            newSceneJson = "",
            actorId = "test_user",
            lamportSupplier = { 1L },
        )
        assertTrue(ops.isEmpty())
    }

    @Test
    fun diff_detectsBackgroundColorChange() {
        val oldScene = """{"bgColor": "#ffffffff", "elements": []}"""
        val newScene = """{"bgColor": "#000000ff", "elements": []}"""

        val ops = CanvasOpDiffer.diff(
            oldSceneJson = oldScene,
            newSceneJson = newScene,
            actorId = "test_user",
            lamportSupplier = { 1L },
        )

        assertEquals(1, ops.size)
        val op = assertIs<CanvasOp.SetBackgroundOp>(ops[0])
        assertEquals("#000000ff", op.colorHex)
    }

    @Test
    fun diff_detectsAddUpdateAndRemoveElements() {
        var lamport = 0L
        val lamportSupplier = { ++lamport }

        val scene0 = """{"bgColor": "#ffffffff", "elements": []}"""
        val scene1 = """{"bgColor": "#ffffffff", "elements": [{"id": "el-1", "type": "Text", "text": "Hello"}]}"""

        // 1. Add element
        val ops1 = CanvasOpDiffer.diff(scene0, scene1, "user-1", lamportSupplier)
        assertEquals(1, ops1.size)
        val addOp = assertIs<CanvasOp.AddElementOp>(ops1[0])
        assertEquals("el-1", addOp.elementId)

        // 2. Update element and add a second element
        val scene2 = """{"bgColor": "#ffffffff", "elements": [{"id": "el-1", "type": "Text", "text": "Updated"}, {"id": "el-2", "type": "Shape"}]}"""
        val ops2 = CanvasOpDiffer.diff(scene1, scene2, "user-1", lamportSupplier)
        assertEquals(2, ops2.size)
        val updateOp = ops2.filterIsInstance<CanvasOp.UpdateElementOp>().first()
        val addOp2 = ops2.filterIsInstance<CanvasOp.AddElementOp>().first()
        assertEquals("el-1", updateOp.elementId)
        assertEquals("el-2", addOp2.elementId)

        // 3. Remove element el-1
        val scene3 = """{"bgColor": "#ffffffff", "elements": [{"id": "el-2", "type": "Shape"}]}"""
        val ops3 = CanvasOpDiffer.diff(scene2, scene3, "user-1", lamportSupplier)
        assertEquals(1, ops3.size)
        val removeOp = assertIs<CanvasOp.RemoveElementOp>(ops3[0])
        assertEquals("el-1", removeOp.elementId)
    }

    @Test
    fun diff_sequentialLocalEditsProduceMultipleOps() {
        var lamport = 100L
        val lamportSupplier = { ++lamport }

        val baseScene = """{"bgColor": "#ffffffff", "elements": []}"""
        // Edit 1: add a box
        val edit1 = """{"bgColor": "#ffffffff", "elements": [{"id": "box-1", "type": "RECTANGLE"}]}"""
        val ops1 = CanvasOpDiffer.diff(baseScene, edit1, "user-1", lamportSupplier)

        // Edit 2: add a circle
        val edit2 = """{"bgColor": "#ffffffff", "elements": [{"id": "box-1", "type": "RECTANGLE"}, {"id": "circle-1", "type": "CIRCLE"}]}"""
        val ops2 = CanvasOpDiffer.diff(edit1, edit2, "user-1", lamportSupplier)

        // Combined sequential local edits produce >= 2 ops
        val totalOps = ops1 + ops2
        assertTrue(totalOps.size >= 2, "Expected >= 2 ops from sequential local edits, got ${totalOps.size}")
        assertEquals(2, totalOps.size)
        assertEquals("box-1", (totalOps[0] as CanvasOp.AddElementOp).elementId)
        assertEquals("circle-1", (totalOps[1] as CanvasOp.AddElementOp).elementId)
    }
}
