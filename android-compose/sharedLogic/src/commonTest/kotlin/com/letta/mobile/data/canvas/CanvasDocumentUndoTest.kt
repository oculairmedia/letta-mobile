package com.letta.mobile.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Undoing a document is not a rewind - the op log only moves forward - so it is a new op that
 * happens to restore what was there. These tests are about the moment that matters: the inverse
 * is computed against the scene AS IT WAS, which is the only time the old text, frame, colour and
 * style are still known.
 */
class CanvasDocumentUndoTest {

    private fun set(
        id: String = "note-1",
        json: String = """{"blocks":[]}""",
        frame: CanvasDocumentFrame? = null,
    ) = CanvasOp.SetDocumentOp(
        opId = "op-1",
        actorId = "local_user",
        lamport = 1L,
        documentId = id,
        documentJson = json,
        frame = frame,
        color = null,
        style = null,
    )

    private fun document(
        id: String = "note-1",
        json: String = """{"blocks":["before"]}""",
        frame: CanvasDocumentFrame? = CanvasDocumentFrame(1f, 2f, 300f, 200f),
        color: String? = "#ff0000",
        style: CanvasTextStyle? = CanvasTextStyle(fontScale = 1.5f),
    ) = CanvasSceneDocument(id = id, json = json, frame = frame, color = color, style = style)

    @Test
    fun writingANoteThatDidNotExistIsUndoneByRemovingIt() {
        val inverse = CanvasDocumentUndo.inverseOf(set(), before = emptyList())
        assertTrue(inverse is CanvasOp.RemoveDocumentOp)
        assertEquals("note-1", inverse.documentId)
    }

    @Test
    fun editingANoteIsUndoneByRestoringEveryFieldItHad() {
        // The fields the change did not name must still be restored explicitly: setDocument leaves
        // a field alone when it is given null, so an inverse that omits the colour would keep
        // whatever colour the change had set.
        val before = document()
        val inverse = CanvasDocumentUndo.inverseOf(set(json = """{"blocks":["after"]}"""), listOf(before))

        assertTrue(inverse is CanvasOp.SetDocumentOp)
        assertEquals(before.json, inverse.documentJson)
        assertEquals(before.frame, inverse.frame)
        assertEquals(before.color, inverse.color)
        assertEquals(before.style, inverse.style)
    }

    @Test
    fun namingANoteThatHadNoTitleIsUndoneByClearingIt() {
        val before = CanvasSceneDocument(id = "note-1", json = "{}")
        val rename = CanvasOp.SetDocumentOp(
            opId = "r", actorId = "a", lamport = 2, documentId = "note-1", documentJson = "{}", title = "Plan",
        )
        val inverse = CanvasDocumentUndo.inverseOf(rename, listOf(before))
        assertTrue(inverse is CanvasOp.SetDocumentOp)
        assertEquals("", inverse.title)
    }

    @Test
    fun removingANoteIsUndoneByPuttingItBackWhole() {
        val before = document()
        val remove = CanvasOp.RemoveDocumentOp(
            opId = "op-2",
            actorId = "local_user",
            lamport = 2L,
            documentId = before.id,
        )

        val inverse = CanvasDocumentUndo.inverseOf(remove, listOf(before))

        assertTrue(inverse is CanvasOp.SetDocumentOp)
        assertEquals(before.json, inverse.documentJson)
        assertEquals(before.frame, inverse.frame)
        assertEquals(before.color, inverse.color)
        assertEquals(before.style, inverse.style)
    }

    @Test
    fun aChangeThatChangesNothingRecordsNoStep() {
        // Otherwise the undo button stops on steps that do nothing, which reads as undo being
        // broken: you press it and the board does not move.
        val before = document()
        assertNull(CanvasDocumentUndo.inverseOf(set(json = before.json), listOf(before)))
    }

    @Test
    fun aMoveIsAChangeEvenWhenTheTextIsIdentical() {
        val before = document()
        val moved = set(json = before.json, frame = CanvasDocumentFrame(99f, 99f, 300f, 200f))

        val inverse = CanvasDocumentUndo.inverseOf(moved, listOf(before))

        assertTrue(inverse is CanvasOp.SetDocumentOp)
        assertEquals(before.frame, inverse.frame)
    }

    @Test
    fun removingSomethingAlreadyGoneRecordsNoStep() {
        val remove = CanvasOp.RemoveDocumentOp(
            opId = "op-3",
            actorId = "local_user",
            lamport = 3L,
            documentId = "note-gone",
        )
        assertNull(CanvasDocumentUndo.inverseOf(remove, emptyList()))
    }

    @Test
    fun aBatchIsUndoneBackToFront() {
        // A batch that removes one note and edits another has to put the edited one back first:
        // restoring in the same order would land on a scene the second inverse no longer describes.
        val first = document(id = "note-1")
        val second = document(id = "note-2", json = """{"blocks":["two"]}""")
        val batch = CanvasOp.BatchOp(
            opId = "op-4",
            actorId = "local_user",
            lamport = 4L,
            ops = listOf(
                CanvasOp.RemoveDocumentOp("op-4a", "local_user", 4L, first.id),
                set(id = second.id, json = """{"blocks":["edited"]}"""),
            ),
        )

        val inverse = CanvasDocumentUndo.inverseOf(batch, listOf(first, second))

        assertTrue(inverse is CanvasOp.BatchOp)
        assertEquals(2, inverse.ops.size)
        assertEquals(second.id, (inverse.ops.first() as CanvasOp.SetDocumentOp).documentId)
        assertEquals(first.id, (inverse.ops.last() as CanvasOp.SetDocumentOp).documentId)
    }

    @Test
    fun aBatchThatChangesNothingRecordsNoStep() {
        val existing = document()
        val batch = CanvasOp.BatchOp(
            opId = "op-5",
            actorId = "local_user",
            lamport = 5L,
            ops = listOf(set(json = existing.json)),
        )
        assertNull(CanvasDocumentUndo.inverseOf(batch, listOf(existing)))
    }

    @Test
    fun createThenEditInOneBatchUndoesBackToNothing() {
        // The second child's "before" is the note the FIRST child created. Taken against the
        // pre-batch scene instead, its inverse writes a note back that should have been removed.
        val batch = CanvasOp.BatchOp(
            opId = "op-6",
            actorId = "local_user",
            lamport = 6L,
            ops = listOf(
                set(id = "note-new", json = """{"blocks":["first"]}"""),
                set(id = "note-new", json = """{"blocks":["edited"]}"""),
            ),
        )

        val inverse = CanvasDocumentUndo.inverseOf(batch, before = emptyList())

        assertTrue(inverse is CanvasOp.BatchOp)
        // Undoing has to END with the note gone, whatever it does on the way.
        val last = inverse.ops.last()
        assertTrue(last is CanvasOp.RemoveDocumentOp, "the created note must end up removed, got $last")
        assertEquals("note-new", last.documentId)
    }

    @Test
    fun removeThenRecreateInOneBatchUndoesToTheOriginal() {
        val original = document(id = "note-1", json = """{"blocks":["original"]}""")
        val batch = CanvasOp.BatchOp(
            opId = "op-7",
            actorId = "local_user",
            lamport = 7L,
            ops = listOf(
                CanvasOp.RemoveDocumentOp("op-7a", "local_user", 7L, original.id),
                set(id = original.id, json = """{"blocks":["recreated"]}"""),
            ),
        )

        val inverse = CanvasDocumentUndo.inverseOf(batch, listOf(original))

        assertTrue(inverse is CanvasOp.BatchOp)
        // The recreate is undone first (it saw an absent note, so its inverse removes), and the
        // removal is undone last, putting the original back exactly as it was.
        val last = inverse.ops.last()
        assertTrue(last is CanvasOp.SetDocumentOp)
        assertEquals(original.json, last.documentJson)
        assertEquals(original.frame, last.frame)
        assertEquals(original.color, last.color)
    }

    @Test
    fun aRepeatedSetOnlyUndoesToTheStateBeforeTheBatch() {
        val original = document(id = "note-1", json = """{"blocks":["v1"]}""")
        val batch = CanvasOp.BatchOp(
            opId = "op-8",
            actorId = "local_user",
            lamport = 8L,
            ops = listOf(
                set(id = original.id, json = """{"blocks":["v2"]}"""),
                set(id = original.id, json = """{"blocks":["v3"]}"""),
            ),
        )

        val inverse = CanvasDocumentUndo.inverseOf(batch, listOf(original))

        assertTrue(inverse is CanvasOp.BatchOp)
        // Whatever the intermediate steps say, the last write must restore v1 - not v2, which is
        // what a pre-batch-state inverse would leave behind.
        val last = inverse.ops.last() as CanvasOp.SetDocumentOp
        assertEquals("""{"blocks":["v1"]}""", last.documentJson)
    }

    @Test
    fun aBatchOverTwoDocumentsUndoesEachToItsOwnPriorState() {
        val first = document(id = "note-1", json = """{"blocks":["one"]}""")
        val batch = CanvasOp.BatchOp(
            opId = "op-9",
            actorId = "local_user",
            lamport = 9L,
            ops = listOf(
                set(id = first.id, json = """{"blocks":["one-edited"]}"""),
                set(id = "note-2", json = """{"blocks":["two-new"]}"""),
            ),
        )

        val inverse = CanvasDocumentUndo.inverseOf(batch, listOf(first)) as CanvasOp.BatchOp

        // note-2 was created by this batch, so its inverse removes it; note-1 existed, so its
        // inverse restores the text it had.
        val removal = inverse.ops.filterIsInstance<CanvasOp.RemoveDocumentOp>().single()
        assertEquals("note-2", removal.documentId)
        val restore = inverse.ops.filterIsInstance<CanvasOp.SetDocumentOp>().single()
        assertEquals(first.json, restore.documentJson)
    }
}
