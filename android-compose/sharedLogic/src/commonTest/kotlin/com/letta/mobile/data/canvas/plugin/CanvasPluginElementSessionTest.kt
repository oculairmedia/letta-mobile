package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasAcl
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.AGENT
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.ID
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.frame
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.moved
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.place
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.progress
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.props
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-s416w.1: plugin elements through a [CanvasSession]. Undo is a new op stamped when it
 * is applied ([CanvasSession.applyLocalStamped]); with split provenance, undoing a person's move
 * puts the frame back without touching the state the plugin wrote since.
 */
class CanvasPluginElementSessionTest {
    private suspend fun session(): CanvasSession = CanvasSession.create(
        InMemoryCanvasDocumentStore(),
        CanvasCreateOptions(title = "Plugins", acl = CanvasAcl(CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(AGENT))),
    )

    private fun CanvasSession.element(): CanvasPluginElement? = CanvasOpProjector.pluginElementsOf(sceneJsonOrEmpty()).singleOrNull()

    private fun moveTo(to: com.letta.mobile.data.canvas.CanvasDocumentFrame, owner: CanvasGeometryOwner) =
        CanvasOp.SetPluginElementOp("undo-src", CanvasSession.LOCAL_USER_ACTOR_ID, 0, ID, frame = to, owner = owner)

    @Test
    fun undoingAMovePutsTheFrameBackAndKeepsTheLaterPluginState() = runTest {
        val session = session()
        session.applyAgentBatch(listOf(place(0)), AGENT)
        session.applyLocalStamped(listOf(moveTo(moved, CanvasGeometryOwner.USER)))
        session.applyAgentBatch(listOf(progress(0, 0.8)), AGENT)
        assertEquals(moved, session.element()?.frame)

        // The inverse was built before the plugin's update and carries a stale clock.
        val inverse = moveTo(frame, CanvasGeometryOwner.EXPLICIT)
        // Unstamped, last-writer-wins drops it: this is why undo restamps.
        assertEquals(moved, CanvasOpProjector.pluginElementsOf(CanvasOpProjector.project(session.sceneJsonOrEmpty(), listOf(inverse))).single().frame)

        session.applyLocalStamped(listOf(inverse))
        val element = session.element()!!
        assertEquals(frame, element.frame)
        assertEquals(CanvasGeometryOwner.EXPLICIT, element.owner)
        assertEquals(props("status" to "running", "progress" to 0.8), element.props)
    }

    @Test
    fun undoingARemovalBringsTheWholeElementBack() = runTest {
        val session = session()
        session.applyAgentBatch(listOf(place(0)), AGENT)
        val before = session.element()!!
        session.applyLocalStamped(listOf(CanvasOp.RemovePluginElementOp("r", CanvasSession.LOCAL_USER_ACTOR_ID, 0, ID)))
        assertNull(session.element())

        val restore = CanvasOp.SetPluginElementOp(
            "undo-src", CanvasSession.LOCAL_USER_ACTOR_ID, 0, ID,
            elementType = before.type, v = before.v, frame = before.frame, owner = before.owner, ref = before.ref,
            props = before.props, snapshot = before.snapshot, fallback = before.fallback, meta = before.meta,
        )
        session.applyLocalStamped(listOf(restore))
        assertEquals(before, session.element())
    }
}
