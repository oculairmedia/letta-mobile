package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasAcl
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.movePluginElement
import com.letta.mobile.data.canvas.movePluginElements
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.AGENT
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.ID
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.PLUGIN
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.frame
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.moved
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.permutations
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.place
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.progress
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.props
import com.letta.mobile.data.canvas.removePluginElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.3: a person's moves and removals of plugin elements through [CanvasSession],
 * and their undo and redo on the board's one history ([CanvasPluginElementUndo]).
 */
class CanvasPluginElementMoveUndoTest {
    private suspend fun session(): CanvasSession = CanvasSession.create(
        InMemoryCanvasDocumentStore(),
        CanvasCreateOptions(title = "Plugins", acl = CanvasAcl(CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(AGENT, PLUGIN))),
    )

    private suspend fun placed(): CanvasSession = session().also { it.applyAgentBatch(listOf(place(0)), AGENT) }

    private fun CanvasSession.element(id: String = ID): CanvasPluginElement? = pluginElements().singleOrNull { it.id == id }

    private suspend fun CanvasSession.lastOp(): CanvasOp = opLog.getOps(canvasId, 0).last()

    private suspend fun CanvasSession.undo(step: CanvasHistory.Step.Documents) = assertNotNull(applyLocalStamped(step.undo))

    private suspend fun CanvasSession.redo(step: CanvasHistory.Step.Documents) = assertNotNull(applyLocalStamped(step.redo))

    @Test
    fun aMoveWritesTheFrameRegisterOnlyAsTheUser() = runTest {
        val session = placed()
        val before = assertNotNull(session.element())
        assertNotNull(session.movePluginElement(ID, moved))

        val op = assertIs<CanvasOp.SetPluginElementOp>(session.lastOp())
        val frameOnly = CanvasOp.SetPluginElementOp(op.opId, CanvasSession.LOCAL_USER_ACTOR_ID, op.lamport, ID, frame = moved, owner = CanvasGeometryOwner.USER)
        assertEquals(frameOnly, op, "the move names the frame register and nothing else")
        assertEquals(before.copy(frame = moved, owner = CanvasGeometryOwner.USER), session.element())
    }

    @Test
    fun aMoveAndAConcurrentPluginStateUpdateBothSurviveInEveryOrder() = runTest {
        val session = placed()
        session.movePluginElement(ID, moved)
        val move = session.lastOp()
        // The plugin wrote at the same moment on another peer: the same lamport, not after the move.
        val update = progress(move.lamport, 0.5)
        val scenes = permutations(listOf(place(0), move, update)).map { CanvasOpProjector.project(CanvasOpProjector.emptySceneJson(), it) }
        scenes.forEach { assertEquals(scenes.first(), it, "peers diverged") }
        val element = CanvasOpProjector.pluginElementsOf(scenes.first()).single()
        assertEquals(moved, element.frame)
        assertEquals(CanvasGeometryOwner.USER, element.owner)
        assertEquals(props("status" to "running", "progress" to 0.5), element.props)

        // And through the session: the update arrives after the move and keeps it.
        session.applyRemote(update, vouchedActor = PLUGIN)
        assertEquals(element, session.element())
    }

    @Test
    fun undoingAMovePutsTheFrameBackAndKeepsThePluginStateAndRedoMovesItAgain() = runTest {
        val session = placed()
        val step = assertNotNull(session.movePluginElement(ID, moved))
        session.applyAgentBatch(listOf(progress(0, 0.9)), AGENT)

        session.undo(step)
        val undone = assertNotNull(session.element())
        assertEquals(frame, undone.frame)
        assertEquals(CanvasGeometryOwner.EXPLICIT, undone.owner)
        assertEquals(props("status" to "running", "progress" to 0.9), undone.props, "undo does not touch the state")
        assertTrue(step.undo.all { it is CanvasOp.SetPluginElementOp && it.props == null && it.snapshot == null && it.meta == null })

        session.redo(step)
        assertEquals(undone.copy(frame = moved, owner = CanvasGeometryOwner.USER), session.element())
    }

    @Test
    fun undoingARemovalBringsTheElementBackAndRedoRemovesItAgain() = runTest {
        val session = placed()
        val before = assertNotNull(session.element())
        val step = assertNotNull(session.removePluginElement(ID))
        assertNull(session.element())
        assertNull(session.removePluginElement(ID), "removing what is gone does nothing")

        session.undo(step)
        assertEquals(before, session.element())
        session.redo(step)
        assertNull(session.element())
    }

    @Test
    fun undoingAPlacementRemovesTheElementAndRedoPlacesItAgain() = runTest {
        val session = session()
        val placement = place(0)
        val step = assertNotNull(CanvasPluginElementUndo.stepFor(placement, session.pluginElements(), "placed"))
        session.applyAgentBatch(listOf(placement), AGENT)
        val placed = assertNotNull(session.element())

        session.undo(step)
        assertNull(session.element())
        session.redo(step)
        assertEquals(placed, session.element())
    }

    @Test
    fun aGroupMoveIsOneBatchAndOneStepAndSkipsWhatDoesNotMove() = runTest {
        val session = session()
        session.applyAgentBatch(listOf(place(0), place(0, id = "pe-b"), place(0, id = "pe-c")), AGENT)
        val revision = session.document.value!!.revision
        val elsewhere = CanvasDocumentFrame(0f, 0f, 320f, 240f)
        val step = assertNotNull(
            session.movePluginElements(mapOf(ID to moved, "pe-b" to elsewhere, "pe-c" to frame, "pe-missing" to moved)),
        )
        assertEquals(revision + 1, session.document.value!!.revision, "one revision")
        val batch = assertIs<CanvasOp.BatchOp>(session.lastOp())
        assertEquals(listOf(ID, "pe-b"), batch.ops.map { (it as CanvasOp.SetPluginElementOp).elementId })
        assertEquals(2, step.undo.size)

        session.undo(step)
        assertTrue(session.pluginElements().all { it.frame == frame })
        assertNull(session.movePluginElement("pe-c", frame), "a move to where it is does nothing")
    }

    @Test
    fun aCheckpointRestoreBringsBackThePluginElementsItHeld() = runTest {
        val session = placed()
        val placedCheckpoint = session.checkpoints.value.first()
        val placedElement = assertNotNull(session.element())
        session.movePluginElement(ID, moved)
        session.applyAgentBatch(listOf(progress(0, 1.0)), AGENT)

        session.restoreCheckpoint(placedCheckpoint.checkpointId)
        assertEquals(placedElement, session.element())
    }
}
