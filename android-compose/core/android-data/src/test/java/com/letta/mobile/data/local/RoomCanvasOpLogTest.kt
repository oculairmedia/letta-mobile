package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.canvas.CanvasAcl
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.movePluginElement
import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import com.letta.mobile.data.canvas.removePluginElement
import com.letta.mobile.data.model.CanvasActorId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.CanvasOpId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomCanvasOpLogTest {

    private lateinit var database: LettaDatabase
    private lateinit var opLog: RoomCanvasOpLog

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        opLog = RoomCanvasOpLog(database.canvasOpDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun appendAndRetrieveOpsWithSinceLamport() = runBlocking {
        val canvasId = CanvasId("canvas-ops-test-1")
        val op1 = CanvasOp.SetBackgroundOp(
            opId = "op-1",
            actorId = "user-1",
            lamport = 1L,
            colorHex = "#ff0000",
        )
        val op2 = CanvasOp.AddElementOp(
            opId = "op-2",
            actorId = "agent-1",
            lamport = 2L,
            elementId = "elem-1",
            elementJson = """{"id":"elem-1","type":"rectangle"}""",
        )
        val op3 = CanvasOp.UpdateElementOp(
            opId = "op-3",
            actorId = "user-1",
            lamport = 5L,
            elementId = "elem-1",
            elementJson = """{"id":"elem-1","type":"rectangle","color":"#00ff00"}""",
        )

        opLog.append(canvasId, op1)
        opLog.append(canvasId, op2)
        opLog.append(canvasId, op3)

        val allOps = opLog.getOps(canvasId, sinceLamport = 0L)
        assertEquals(3, allOps.size)
        assertEquals(listOf(op1, op2, op3), allOps)

        val opsSince2 = opLog.getOps(canvasId, sinceLamport = 2L)
        assertEquals(1, opsSince2.size)
        assertEquals(listOf(op3), opsSince2)

        val opsSince5 = opLog.getOps(canvasId, sinceLamport = 5L)
        assertTrue(opsSince5.isEmpty())
    }

    @Test
    fun idempotentAppendDoesNotDuplicate() = runBlocking {
        val canvasId = CanvasId("canvas-ops-test-2")
        val op = CanvasOp.RemoveElementOp(
            opId = "op-del-1",
            actorId = "user-1",
            lamport = 10L,
            elementId = "elem-to-del",
        )

        opLog.append(canvasId, op)
        opLog.append(canvasId, op)

        val ops = opLog.getOps(canvasId, sinceLamport = 0L)
        assertEquals(1, ops.size)
        assertEquals(op, ops.first())
    }

    @Test
    fun hasReturnsExpectedPresence() = runBlocking {
        val canvasId = CanvasId("canvas-ops-test-3")
        val op = CanvasOp.ReplaceSceneOp(
            opId = "op-replace-1",
            actorId = "agent-1",
            lamport = 1L,
            sceneJson = """{"elements":[]}""",
        )

        assertFalse(opLog.has(canvasId, "op-replace-1"))
        opLog.append(canvasId, op)
        assertTrue(opLog.has(canvasId, "op-replace-1"))
        assertFalse(opLog.has(canvasId, "non-existent-op"))
    }

    @Test
    fun roundTripsBatchOp() = runBlocking {
        val canvasId = CanvasId("canvas-ops-test-4")
        val batch = CanvasOp.BatchOp(
            opId = "batch-1",
            actorId = "user-1",
            lamport = 3L,
            ops = listOf(
                CanvasOp.SetBackgroundOp("sub-1", "user-1", 3L, "#ffffff"),
                CanvasOp.RemoveElementOp("sub-2", "user-1", 3L, "elem-x"),
            ),
        )

        opLog.append(canvasId, batch)
        val retrieved = opLog.getOps(canvasId, 0L)
        assertEquals(1, retrieved.size)
        assertEquals(batch, retrieved.first())
    }

    /**
     * letta-mobile-s416w.3: plugin elements on Android, the Room document store and the Room op log
     * together, with no schema change. After a restart (new store and log over the same database)
     * the board holds the same elements, the log replays to them, and a person's move still lands.
     */
    @Test
    fun pluginElementsRoundTripThroughRoomAndARestart() = runBlocking {
        val canvasId = CanvasId("canvas-ops-plugin")
        val agent = "agent:room"
        val frame = CanvasDocumentFrame(100f, 100f, 320f, 240f)
        val moved = CanvasDocumentFrame(500f, 40f, 320f, 240f)
        fun place(id: String) = CanvasOp.SetPluginElementOp(
            opId = "place-$id", actorId = agent, lamport = 0L, elementId = id,
            elementType = "ext:letta.example/widget", frame = frame,
            props = JsonObject(mapOf("status" to JsonPrimitive("queued"))),
            fallback = CanvasPluginFallback("Widget $id", openUrl = "https://example.test/$id"),
        )
        val update = CanvasOp.SetPluginElementOp(
            opId = "progress-a", actorId = agent, lamport = 0L, elementId = "pe-a",
            props = JsonObject(mapOf("status" to JsonPrimitive("done"))),
        )
        val documents = InMemoryCanvasDocumentStore()
        val session = CanvasSession.create(
            documents,
            CanvasCreateOptions(
                canvasId = canvasId,
                acl = CanvasAcl(CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(agent)),
                opLog = opLog,
            ),
        )
        session.applyAgentBatch(listOf(place("pe-a"), place("pe-b")), CanvasActorId(agent))
        assertTrue(session.movePluginElement("pe-a", moved) != null)
        session.applyAgentBatch(listOf(update), CanvasActorId(agent))
        assertTrue(session.removePluginElement("pe-b") != null)
        val before = session.pluginElements()

        val restartedLog = RoomCanvasOpLog(database.canvasOpDao())
        val reopened = CanvasSession.open(
            documents,
            canvasId,
            CanvasConversationOptions(opLog = restartedLog),
        )!!
        assertEquals(before, reopened.pluginElements())
        val element = reopened.pluginElements().single()
        assertEquals(moved, element.frame)
        assertEquals(CanvasGeometryOwner.USER, element.owner)
        assertEquals(JsonPrimitive("done"), element.props["status"])
        val logged = restartedLog.getOps(canvasId, 0L)
        assertEquals(session.opLog.getOps(canvasId, 0L), logged)
        assertEquals(before, CanvasOpProjector.pluginElementsOf(CanvasOpProjector.project(CanvasOpProjector.emptySceneJson(), logged)))

        assertTrue(reopened.movePluginElement("pe-a", frame) != null)
        val stored = documents.get(canvasId)!!
        assertEquals(frame, CanvasOpProjector.pluginElementsOf(stored.sceneJson).single().frame)
    }

    @Test
    fun pluginElementOpsKeepTheirTypeNamesAndLoadFromANewerBuild() = runBlocking {
        val canvasId = CanvasId("canvas-ops-plugin-newer")
        val remove = CanvasOp.RemovePluginElementOp("rm-1", "local_user", 1L, "pe-1")
        assertEquals("remove_plugin_element", CanvasOpEntity.fromCanvasOp(canvasId, remove).opType)
        val set = CanvasOp.SetPluginElementOp("set-1", "local_user", 2L, "pe-1", frame = CanvasDocumentFrame(1f, 2f, 3f, 4f))
        assertEquals("set_plugin_element", CanvasOpEntity.fromCanvasOp(canvasId, set).opType)

        // A row a newer build wrote: fields this build does not know are ignored, not fatal.
        val newer = """{"type":"set_plugin_element","opId":"newer-1","actorId":"plugin:x","lamport":3,"elementId":"pe-1",""" +
            """"props":{"status":"done"},"layer":"top","snapshot":{"assetRef":"sha256:ab","blurhash":"L6PZ"}}"""
        database.canvasOpDao().insert(
            CanvasOpEntity("newer-1", canvasId.value, 3L, "plugin:x", "set_plugin_element", newer, 0L),
        )
        val loaded = opLog.getOps(canvasId, 0L).single() as CanvasOp.SetPluginElementOp
        assertEquals("pe-1", loaded.elementId)
        assertEquals("sha256:ab", loaded.snapshot?.assetRef)
        assertEquals(JsonPrimitive("done"), loaded.props?.get("status"))
    }

    /** A notebook store whose writes can fail: the crash between the op log and the document. */
    private class FailingDocuments(private val inner: InMemoryCanvasDocumentStore = InMemoryCanvasDocumentStore()) :
        CanvasDocumentStore by inner {
        var failWrites = false

        override suspend fun upsert(doc: CanvasDocument) {
            check(!failWrites) { "process died before the document was written" }
            inner.upsert(doc)
        }
    }

    @Test
    fun appendedRowsStayUnsettledUntilSettled() = runBlocking {
        val canvasId = CanvasId("canvas-ops-journal")
        val first = CanvasOp.SetBackgroundOp("journal-1", "user-1", 1L, "#000000")
        val second = CanvasOp.SetBackgroundOp("journal-2", "user-1", 2L, "#ffffff")
        opLog.append(canvasId, second)
        opLog.append(canvasId, first)
        assertEquals("in append order", listOf("journal-2", "journal-1"), opLog.unsettled(canvasId).map { it.opId })

        opLog.settle(canvasId, listOf(CanvasOpId("journal-2")))
        opLog.append(canvasId, second) // a redelivered op keeps its settled state
        assertEquals(listOf("journal-1"), RoomCanvasOpLog(database.canvasOpDao()).unsettled(canvasId).map { it.opId })
        opLog.settle(canvasId, (1..1200).map { CanvasOpId("absent-$it") } + CanvasOpId("journal-1"))
        assertEquals(emptyList<String>(), opLog.unsettled(canvasId).map { it.opId })
    }

    @Test
    fun anOpLoggedBeforeACrashedDocumentWriteIsRecoveredAfterARestart() = runBlocking {
        val documents = FailingDocuments()
        val session = CanvasSession.create(documents, CanvasCreateOptions(opLog = opLog))
        val op = CanvasOp.AddElementOp(
            opId = "crash-1", actorId = CanvasSession.LOCAL_USER_ACTOR_ID, lamport = 7L, elementId = "rect-1",
            elementJson = """{"id":"rect-1","type":"Shape","zIndex":1,"points":["0.0,0.0","10.0,10.0"],"shapeType":"RECTANGLE"}""",
        )

        documents.failWrites = true
        val crashed = try {
            session.applyLocal(op)
            false
        } catch (_: IllegalStateException) {
            true
        }
        documents.failWrites = false
        assertTrue("the write was expected to fail", crashed)
        assertFalse(documents.get(session.canvasId)!!.sceneJson.contains("rect-1"))

        // A restart: a new log over the same table, a new session over the stored document.
        val restartedLog = RoomCanvasOpLog(database.canvasOpDao())
        val reopened = CanvasSession.open(documents, session.canvasId, CanvasConversationOptions(opLog = restartedLog))!!

        assertTrue("the reopened board lost the logged op", reopened.sceneJsonOrEmpty().contains("rect-1"))
        assertTrue("the recovered op was not stored", documents.get(session.canvasId)!!.sceneJson.contains("rect-1"))
        assertEquals(emptyList<String>(), restartedLog.unsettled(session.canvasId).map { it.opId })
        assertEquals(1, database.canvasOpDao().countForCanvas(session.canvasId.value))
    }
}
