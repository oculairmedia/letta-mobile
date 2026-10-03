package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileCanvasOpLogTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("canvas_oplog_test_")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun processRestartSimulationRestoresPersistedCanvasOps() = runTest {
        val canvasId = CanvasId("canvas-desktop-proc-restart")
        val op1 = CanvasOp.SetBackgroundOp(
            opId = "desk-op-1",
            actorId = "user-desk",
            lamport = 1L,
            colorHex = "#123456",
        )
        val op2 = CanvasOp.AddElementOp(
            opId = "desk-op-2",
            actorId = "agent-desk",
            lamport = 2L,
            elementId = "desk-el-1",
            elementJson = """{"id":"desk-el-1","type":"circle"}""",
        )
        val op3 = CanvasOp.UpdateElementOp(
            opId = "desk-op-3",
            actorId = "user-desk",
            lamport = 5L,
            elementId = "desk-el-1",
            elementJson = """{"id":"desk-el-1","type":"circle","radius":40}""",
        )

        // Step 1: Write using first op log instance
        val log1 = FileCanvasOpLog(rootDirectory = tempDir)
        log1.append(canvasId, op1)
        log1.append(canvasId, op2)
        log1.append(canvasId, op3)

        assertEquals(3, log1.getOps(canvasId, 0L).size)

        // Step 2: "Process kill" — abandon log1 instance completely and instantiate a fresh log instance
        val log2 = FileCanvasOpLog(rootDirectory = tempDir)

        // Step 3: Verify fresh log2 recovers all ops from disk in order
        val allRecovered = log2.getOps(canvasId, 0L)
        assertEquals(3, allRecovered.size)
        assertEquals(listOf(op1, op2, op3), allRecovered)

        // Filter by sinceLamport
        val since2 = log2.getOps(canvasId, sinceLamport = 2L)
        assertEquals(listOf(op3), since2)

        // Verify has checks
        assertTrue(log2.has(canvasId, "desk-op-1"))
        assertTrue(log2.has(canvasId, "desk-op-2"))
        assertTrue(log2.has(canvasId, "desk-op-3"))
        assertFalse(log2.has(canvasId, "non-existent-op"))

        // Step 4: Idempotent append on log2
        log2.append(canvasId, op1)
        assertEquals(3, log2.getOps(canvasId, 0L).size)
    }

    /**
     * letta-mobile-s416w.3: plugin element ops in the desktop and host op log. A session writes
     * them through the one log; a fresh log reads them back equal, and replaying them gives the
     * board the session had.
     */
    @Test
    fun pluginElementOpsRoundTripAndReplayAfterARestart() = runTest {
        val fixtures = CanvasPluginElementFixtures
        val canvasId = CanvasId("canvas-plugin-ops")
        val session = CanvasSession.create(
            InMemoryCanvasDocumentStore(),
            CanvasCreateOptions(
                canvasId = canvasId,
                acl = CanvasAcl(CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(fixtures.AGENT)),
                opLog = FileCanvasOpLog(rootDirectory = tempDir),
            ),
        )
        session.applyAgentBatch(listOf(fixtures.place(0), fixtures.place(0, id = "pe-b")), fixtures.AGENT)
        session.movePluginElement(fixtures.ID, fixtures.moved)
        session.applyAgentBatch(listOf(fixtures.progress(0, 0.25)), fixtures.AGENT)
        session.removePluginElement("pe-b")
        val written = session.opLog.getOps(canvasId, 0L)

        val reopened = FileCanvasOpLog(rootDirectory = tempDir).getOps(canvasId, 0L)
        assertEquals(written, reopened)
        val replayed = CanvasOpProjector.project(CanvasOpProjector.emptySceneJson(), reopened)
        assertEquals(session.pluginElements(), CanvasOpProjector.pluginElementsOf(replayed))
    }

    /** An op written by a newer build, with fields this one does not know, still loads. */
    @Test
    fun aPluginElementOpFromANewerBuildStillLoads() = runTest {
        val canvasId = CanvasId("canvas-plugin-newer")
        FileCanvasOpLog(rootDirectory = tempDir).append(canvasId, CanvasOp.SetBackgroundOp("seed", "u", 1L, "#fff"))
        val file = Files.list(tempDir).use { files -> files.filter { it.toString().endsWith(".jsonl") }.findFirst().get() }
        val newer = """{"type":"set_plugin_element","opId":"newer-1","actorId":"plugin:x","lamport":2,"elementId":"pe-1",""" +
            """"props":{"status":"done"},"layer":"top","snapshot":{"assetRef":"sha256:ab","blurhash":"L6PZ"}}"""
        Files.writeString(file, newer + "\n", java.nio.file.StandardOpenOption.APPEND)

        val loaded = FileCanvasOpLog(rootDirectory = tempDir).getOps(canvasId, 0L).last()
        val op = loaded as CanvasOp.SetPluginElementOp
        assertEquals("pe-1", op.elementId)
        assertEquals("sha256:ab", op.snapshot?.assetRef)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("done"), op.props?.get("status"))
    }
}
