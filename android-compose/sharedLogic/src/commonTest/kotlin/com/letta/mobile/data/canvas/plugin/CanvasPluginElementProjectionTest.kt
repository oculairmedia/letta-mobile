package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.AGENT
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.ID
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.PLUGIN
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.TYPE
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.USER
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.fallback
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.frame
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.move
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.moved
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.permutations
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.place
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.progress
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.props
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.remove
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.snapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.1: `_pluginElements` as the projector keeps it. Every convergence case plays
 * the same ops in every arrival order and compares the scenes byte for byte.
 */
class CanvasPluginElementProjectionTest {
    private val base = CanvasOpProjector.emptySceneJson()

    private fun project(ops: List<CanvasOp>): String = CanvasOpProjector.project(base, ops)

    private fun element(scene: String): CanvasPluginElement? = CanvasOpProjector.pluginElementsOf(scene).singleOrNull { it.id == ID }

    /** Projects [ops] in every order, asserts they all converge and returns the one scene. */
    private fun converged(vararg ops: CanvasOp): String {
        val scenes = permutations(ops.toList()).map(::project)
        scenes.forEach { assertEquals(scenes.first(), it, "peers diverged") }
        return scenes.first()
    }

    @Test
    fun aFirstWriteRoundTripsThroughTheProjector() {
        val scene = project(listOf(place(10)))
        val expected = CanvasPluginElement(
            id = ID, type = TYPE, v = 1, frame = frame, owner = CanvasGeometryOwner.EXPLICIT, ref = "job:7f3a",
            props = props("status" to "queued", "progress" to 0), snapshot = snapshot, fallback = fallback,
            meta = props("pluginId" to "letta.example", "createdBy" to AGENT),
        )
        assertEquals(listOf(expected), CanvasOpProjector.pluginElementsOf(scene))
        assertEquals("letta.example", expected.pluginId)
        assertEquals("widget", expected.kind)
        // Re-applying the same op changes nothing: projection is idempotent.
        assertEquals(scene, CanvasOpProjector.project(scene, listOf(place(10))))
    }

    @Test
    fun theProjectorKeepsPluginElementsAndDrawBoxNeverSeesThem() {
        val scene = project(listOf(place(10)))
        assertTrue(CanvasPluginElements.KEY in Json.parseToJsonElement(scene).jsonObject, scene)
        val forDrawBox = Json.parseToJsonElement(CanvasOpProjector.stripMetadataForDrawBox(scene)).jsonObject
        assertFalse(CanvasPluginElements.KEY in forDrawBox, "plugin elements must not reach DrawBox: $forDrawBox")
        assertEquals(JsonArray(emptyList()), forDrawBox["elements"])
    }

    @Test
    fun aStateUpdateKeepsWhatItDoesNotName() {
        val element = element(project(listOf(place(10), progress(20, 0.42))))!!
        assertEquals(props("status" to "running", "progress" to 0.42), element.props)
        assertEquals(fallback, element.fallback)
        assertEquals(snapshot, element.snapshot)
        assertEquals(frame, element.frame)
        assertEquals("job:7f3a", element.ref)
    }

    @Test
    fun placeStateUpdateAndMoveConvergeInEveryArrivalOrder() {
        val scene = converged(place(10), progress(20, 0.42), move(15))
        val element = element(scene)!!
        assertEquals(moved, element.frame)
        assertEquals(CanvasGeometryOwner.USER, element.owner)
        assertEquals(props("status" to "running", "progress" to 0.42), element.props)
        assertEquals(fallback, element.fallback)
    }

    @Test
    fun aMoveOlderThanALaterStateWriteStillWinsTheFrame() {
        // The person's move (15) is older than the plugin's update (20). Whole-entry LWW would let
        // the update erase the move; split provenance keeps both.
        val scene = project(listOf(place(10), move(15), progress(20, 0.9)))
        val element = element(scene)!!
        assertEquals(moved, element.frame)
        assertEquals(0.9, element.props["progress"].toString().toDouble())
        val entry = (Json.parseToJsonElement(scene).jsonObject[CanvasPluginElements.KEY] as JsonArray).single().jsonObject
        assertEquals(USER, (entry["_frame"] as JsonObject)["_actorId"].toString().trim('"'))
        assertEquals(PLUGIN, (entry["_state"] as JsonObject)["_actorId"].toString().trim('"'))
        assertEquals("20", entry["_lamport"].toString())
    }

    @Test
    fun aTombstoneBeatsALateOlderUpdate() {
        val scene = converged(place(10), remove(30), progress(20, 0.5))
        assertNull(element(scene))
        assertTrue(CanvasOpProjector.pluginElementsOf(scene).isEmpty())
    }

    @Test
    fun aNewerWriteAfterARemovalResurrectsTheElement() {
        val scene = converged(place(10), remove(20), place(30))
        assertEquals(TYPE, element(scene)?.type)
    }

    @Test
    fun aMoveConcurrentWithARemovalConvergesAndDoesNotBringTheElementBack() {
        // The removal (12) clears everything older than itself; the newer move (15) keeps a frame
        // alone, which is not an element. Every arrival order agrees.
        val scene = converged(place(10), move(15), remove(12))
        assertNull(element(scene))
    }

    @Test
    fun overlappingPartialStateWritesConverge() {
        // Three state writes naming different fields, the oldest arriving last on one peer: each
        // field is the latest of its own writes, so no order loses one.
        val refOnlyOld = CanvasOp.SetPluginElementOp("s5", PLUGIN, 5, ID, ref = "r-5")
        val propsOnly = CanvasOp.SetPluginElementOp("s7", PLUGIN, 7, ID, props = props("status" to "done"))
        val refOnlyNew = CanvasOp.SetPluginElementOp("s6", PLUGIN, 6, ID, ref = "r-6")
        val scene = converged(place(1), refOnlyOld, propsOnly, refOnlyNew)
        assertEquals("r-6", element(scene)?.ref)
        assertEquals(props("status" to "done"), element(scene)?.props)
    }

    @Test
    fun aRedrawOfTheSceneKeepsThePluginElements() {
        val placed = project(listOf(place(10)))
        val redraw = CanvasOp.ReplaceSceneOp("replace", AGENT, 20, """{"bgColor":"#ffffffff","elements":[]}""")
        assertEquals(TYPE, element(CanvasOpProjector.project(placed, listOf(redraw)))?.type)
    }

    @Test
    fun thePluginProvenanceCountsTowardsTheScenesClock() {
        val scene = project(listOf(place(10), move(41)))
        assertEquals(41, CanvasOpProjector.maxLamport(scene))
    }

    @Test
    fun removalsKeepABoundedNumberOfTombstones() {
        // Straight through the collection writer: projecting whole scenes 300 times is slow on wasm.
        var root = JsonObject(emptyMap())
        repeat(300) { i -> root = JsonObject(mapOf(CanvasPluginElements.KEY to CanvasPluginElements.remove(root, remove(i + 1L, id = "pe-$i")))) }
        val entries = root.getValue(CanvasPluginElements.KEY) as JsonArray
        assertEquals(256, entries.size)
        // The newest removals are the ones kept.
        assertTrue(entries.any { it.jsonObject["id"].toString() == "\"pe-299\"" })
        assertFalse(entries.any { it.jsonObject["id"].toString() == "\"pe-0\"" })
    }
}
