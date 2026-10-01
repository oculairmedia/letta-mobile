package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * canvas.compose C2 (letta-mobile-bglj6.7): who owns a block document's frame, and which compose
 * request made it, ride on the document entry under whole-document last-writer-wins, kept across
 * writes that do not name them, exactly like the frame and colour.
 */
class CanvasGeometryOwnerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val frame = CanvasDocumentFrame(x = 80f, y = 152f, width = 320f, height = 236f)
    private val provenance = CanvasComposeProvenance(
        artifactId = "weekend-plan",
        key = "shopping",
        kind = "CHECKLIST",
        catalog = "letta.canvas.compose",
        version = 1,
    )

    private fun set(
        lamport: Long,
        frame: CanvasDocumentFrame? = null,
        owner: CanvasGeometryOwner? = null,
        compose: CanvasComposeProvenance? = null,
        body: String = "{\"v\":$lamport}",
        actor: String = "a",
        id: String = "n",
    ) = CanvasOp.SetDocumentOp(
        opId = "op-$lamport-$actor", actorId = actor, lamport = lamport, documentId = id, documentJson = body,
        frame = frame, owner = owner, compose = compose,
    )

    private fun project(vararg ops: CanvasOp, from: String = ""): String = CanvasOpProjector.project(from, ops.toList())

    private fun single(scene: String): CanvasSceneDocument = CanvasOpProjector.documentsOf(scene).single()

    private fun entry(scene: String): JsonObject =
        json.parseToJsonElement(scene).jsonObject.getValue("_documents").jsonArray.single().jsonObject

    @Test
    fun aWriteWithAFrameAndNoOwnerIsExplicit() {
        val scene = project(set(1, frame = frame))
        assertEquals(CanvasGeometryOwner.EXPLICIT, single(scene).owner)
        assertEquals("explicit", entry(scene).getValue("owner").jsonPrimitive.content, "stored under its lower-case wire name")
    }

    @Test
    fun aFramelessDocumentStaysOwnerlessUntilSomeoneGivesItAFrame() {
        val frameless = project(set(1), set(2))
        assertNull(single(frameless).owner, "a legacy frameless note has no owner; the renderer lays it out as AUTO")
        assertFalse("owner" in entry(frameless), "nothing is written for it")

        val placed = project(set(3, frame = frame), from = frameless)
        assertEquals(CanvasGeometryOwner.EXPLICIT, single(placed).owner)
    }

    @Test
    fun aComposedDocumentKeepsItsOwnerAndProvenanceAcrossWritesThatDoNotNameThem() {
        val composed = project(set(1, frame = frame, owner = CanvasGeometryOwner.AUTO, compose = provenance))
        assertEquals(CanvasGeometryOwner.AUTO, single(composed).owner)
        assertEquals(provenance, single(composed).compose)

        val typed = project(set(2), from = composed)
        assertEquals(CanvasGeometryOwner.AUTO, single(typed).owner, "typing does not take the frame from compose")
        assertEquals(provenance, single(typed).compose)

        // A frame given without an owner does not overrule the owner the document has.
        val reframed = project(set(3, frame = frame.copy(x = 400f)), from = typed)
        assertEquals(CanvasGeometryOwner.AUTO, single(reframed).owner)
        assertEquals(400f, single(reframed).frame?.x)

        val moved = project(set(4, frame = frame.copy(x = 500f), owner = CanvasGeometryOwner.USER), from = reframed)
        assertEquals(CanvasGeometryOwner.USER, single(moved).owner, "a named owner replaces the stored one")
        assertEquals(provenance, single(moved).compose, "a move keeps where the note came from")
    }

    @Test
    fun aRemovalDropsOwnerAndProvenance() {
        val composed = project(set(1, frame = frame, owner = CanvasGeometryOwner.AUTO, compose = provenance))
        val recreated = project(CanvasOp.RemoveDocumentOp("rm", "a", 2, "n"), set(3), from = composed)
        assertNull(single(recreated).owner)
        assertNull(single(recreated).compose)
    }

    @Test
    fun anOlderOwnerWriteLosesAndBothArrivalOrdersConverge() {
        val auto = set(5, frame = frame, owner = CanvasGeometryOwner.AUTO, compose = provenance, actor = "agent")
        val user = set(7, frame = frame.copy(x = 9f), owner = CanvasGeometryOwner.USER, compose = provenance, actor = "local_user")
        val oneWay = project(auto, user)
        val otherWay = project(user, auto)
        assertEquals(oneWay, otherWay)
        assertEquals(CanvasGeometryOwner.USER, single(oneWay).owner)
        assertEquals(9f, single(oneWay).frame?.x)
    }

    /**
     * Whole-document LWW is unchanged, so a field the winning write leaves out is kept only from
     * what that peer already had, as for frame and colour today: per-property LWW is
     * letta-mobile-bglj6.16. The owner a write NAMES always converges.
     */
    @Test
    fun aNamedOwnerConvergesEvenWhenTheWinnerLeavesProvenanceOut() {
        val auto = set(5, frame = frame, owner = CanvasGeometryOwner.AUTO, compose = provenance, actor = "agent")
        val user = set(7, frame = frame.copy(x = 9f), owner = CanvasGeometryOwner.USER, actor = "local_user")
        assertEquals(CanvasGeometryOwner.USER, single(project(auto, user)).owner)
        assertEquals(CanvasGeometryOwner.USER, single(project(user, auto)).owner)
    }

    @Test
    fun aSceneWrittenBeforeOwnersStillLoadsAndItsClockIsUnchanged() {
        val legacy = """{"bgColor":"#ffffffff","elements":[],"_documents":[{"id":"n","json":"{}",""" +
            """"frame":{"x":1.0,"y":2.0,"width":3.0,"height":4.0},"_lamport":41,"_actorId":"a","_opId":"o"}]}"""
        val document = single(legacy)
        assertNull(document.owner)
        assertNull(document.compose)
        assertEquals(41L, CanvasOpProjector.maxLamport(legacy))

        val composed = project(set(42, frame = frame, owner = CanvasGeometryOwner.AUTO, compose = provenance))
        assertEquals(42L, CanvasOpProjector.maxLamport(composed), "the provenance's version is not mistaken for a clock")
    }

    @Test
    fun anOwnerOrProvenanceThisBuildDoesNotKnowIsKeptAsStored() {
        val future = """{"bgColor":"#ffffffff","elements":[],"_documents":[{"id":"n","json":"{}","owner":"fit",""" +
            """"compose":{"artifactId":"a","key":"k","kind":"TABLE","catalog":"letta.canvas.compose","version":2,"rows":3},""" +
            """"_lamport":1,"_actorId":"a","_opId":"o"}]}"""
        assertNull(single(future).owner, "an unknown owner reads as none")
        assertEquals("TABLE", single(future).compose?.kind, "an unknown kind is still read")

        val typed = entry(project(set(2), from = future))
        assertEquals("fit", typed.getValue("owner").jsonPrimitive.content, "and survives a write that does not name it")
        assertEquals("3", typed.getValue("compose").jsonObject.getValue("rows").jsonPrimitive.content)
    }

    @Test
    fun theDocumentEntryKeysStayOutOfDrawBox() {
        val composed = project(set(1, frame = frame, owner = CanvasGeometryOwner.AUTO, compose = provenance))
        val drawn = CanvasOpProjector.stripMetadataForDrawBox(composed)
        assertFalse("owner" in drawn || "compose" in drawn, drawn)
    }

    @Test
    fun aComposeElementKeepsItsProvenanceThroughValidationAndProjectionAndDrawBoxNeverSeesIt() {
        val elementJson = """{"type":"Text","text":"Weekend plan","textTopLeft":"80.0,80.0","wrapWidth":480.0,""" +
            """"fontSize":36.0,"zIndex":2,"_compose":{"artifactId":"weekend-plan","key":"heading","kind":"TEXT",""" +
            """"catalog":"letta.canvas.compose","version":1}}"""
        val validated = assertIs<CanvasSceneCheck.Valid>(CanvasSceneValidator.element("cmp-weekend-plan-heading", elementJson))
        assertTrue("\"_compose\"" in validated.json, validated.json)

        val add = CanvasOp.AddElementOp("add", "agent", 1, "cmp-weekend-plan-heading", elementJson)
        val checked = assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check("", listOf(add)), "the batch validator accepts it")
        val element = json.parseToJsonElement(checked.sceneJson).jsonObject.getValue("elements").jsonArray.single().jsonObject
        val stamped = element.getValue("_compose").jsonObject
        assertEquals("weekend-plan", stamped.getValue("artifactId").jsonPrimitive.content)
        assertEquals("TEXT", stamped.getValue("kind").jsonPrimitive.content)

        val drawn = json.parseToJsonElement(CanvasOpProjector.stripMetadataForDrawBox(checked.sceneJson)).jsonObject
        val drawnElement = (drawn.getValue("elements") as JsonArray).single().jsonObject
        assertFalse("_compose" in drawnElement, "DrawBox's strict decoder never sees it")
        assertEquals("Weekend plan", drawnElement.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun aPersonsMoveMakesTheNoteTheirs() = runTest {
        val session = CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "t", canvasId = CanvasId("own-1")))
        session.setDocument("n", "{}", frame = frame, owner = CanvasGeometryOwner.AUTO, compose = provenance)
        assertEquals(CanvasGeometryOwner.AUTO, session.documents().single().owner)

        session.moveDocument("n", frame.copy(x = 999f))
        val moved = session.documents().single()
        assertEquals(CanvasGeometryOwner.USER, moved.owner)
        assertEquals(provenance, moved.compose)
    }

    @Test
    fun aGroupMoveMakesEveryMovedNoteTheirsAndLeavesTheRestAlone() = runTest {
        val session = CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "t", canvasId = CanvasId("own-2")))
        listOf("a", "b", "c").forEach { session.setDocument(it, "{}", frame = frame, owner = CanvasGeometryOwner.AUTO) }

        session.moveDocuments(mapOf("a" to frame.copy(x = 1f), "b" to frame.copy(x = 2f), "c" to frame))

        val owners = session.documents().associate { it.id to it.owner }
        assertEquals(
            mapOf("a" to CanvasGeometryOwner.USER, "b" to CanvasGeometryOwner.USER, "c" to CanvasGeometryOwner.AUTO),
            owners,
            "a note already at its frame did not move",
        )
    }

    @Test
    fun setDocumentWritesOwnerAndProvenanceAndANewOwnerIsAChange() = runTest {
        val session = CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "t", canvasId = CanvasId("own-3")))
        session.setDocument("n", "{}", frame = frame, owner = CanvasGeometryOwner.AUTO, compose = provenance)
        assertNull(
            session.setDocument("n", "{}", frame = frame, owner = CanvasGeometryOwner.AUTO, compose = provenance),
            "the same document is not written again",
        )
        assertNull(session.setDocument("n", "{}"), "a write that names no owner keeps it")
        assertNotNull(session.setDocument("n", "{}", owner = CanvasGeometryOwner.USER), "a different owner is a change")
        assertEquals(CanvasGeometryOwner.USER, session.documents().single().owner)
        assertNotNull(session.setDocument("n", "{}", compose = provenance.copy(key = "other")), "so is different provenance")
    }

    @Test
    fun undoingAMoveGivesTheNoteBackToCompose() = runTest {
        val session = CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "t", canvasId = CanvasId("own-4")))
        session.setDocument("n", "{}", frame = frame, owner = CanvasGeometryOwner.AUTO, compose = provenance)
        val before = session.documents()
        session.moveDocument("n", frame.copy(x = 999f))
        val step = assertNotNull(CanvasDocumentUndo.stepBetween(before, session.documents()))

        session.applyLocalStamped(step.undo)

        assertEquals(before, session.documents(), "frame, owner and provenance all as they were")
    }
}
