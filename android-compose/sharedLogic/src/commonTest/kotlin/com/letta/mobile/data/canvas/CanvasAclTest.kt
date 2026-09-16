package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasAclTest {

    @Test
    fun testAclPermissions() {
        val acl = CanvasAcl(
            ownerUserId = "alice",
            writerUserIds = setOf("bob"),
            writerAgentIds = setOf("agent-writer"),
            readerUserIds = setOf("charlie"),
            readerAgentIds = setOf("agent-reader"),
        )

        // Owner
        assertTrue(acl.canWrite("alice"))
        assertTrue(acl.canWrite("user:alice"))
        assertTrue(acl.canRead("alice"))
        assertTrue(acl.canRead("user:alice"))

        // Writers
        assertTrue(acl.canWrite("bob"))
        assertTrue(acl.canWrite("user:bob"))
        assertTrue(acl.canWrite("agent-writer"))
        assertTrue(acl.canWrite("agent:agent-writer"))
        assertTrue(acl.canRead("bob"))
        assertTrue(acl.canRead("agent-writer"))

        // Readers only
        assertFalse(acl.canWrite("charlie"))
        assertFalse(acl.canWrite("user:charlie"))
        assertTrue(acl.canRead("charlie"))
        assertTrue(acl.canRead("user:charlie"))
        assertFalse(acl.canWrite("agent-reader"))
        assertTrue(acl.canRead("agent-reader"))

        // Outsiders
        assertFalse(acl.canWrite("eve"))
        assertFalse(acl.canRead("eve"))
        assertFalse(acl.canWrite(null))
        assertFalse(acl.canRead(null))
    }

    @Test
    fun testDefaultPublicReadWhenReadersEmpty() {
        val acl = CanvasAcl(
            ownerUserId = "alice",
            writerUserIds = setOf("bob"),
        )
        assertTrue(acl.canWrite("alice"))
        assertTrue(acl.canWrite("bob"))
        assertFalse(acl.canWrite("charlie"))

        // When readers are empty, anyone can read
        assertTrue(acl.canRead("alice"))
        assertTrue(acl.canRead("bob"))
        assertTrue(acl.canRead("charlie"))
    }

    @Test
    fun testSessionEnforcesAclMutations() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val docId = CanvasId("canvas-acl-test")
        val acl = CanvasAcl(
            ownerUserId = "alice",
            writerUserIds = setOf("bob"),
            writerAgentIds = setOf("agent-writer"),
        )
        val initialDoc = CanvasDocument(
            id = docId,
            title = "Protected Canvas",
            revision = 1L,
            sceneJson = """{"bgColor":-1,"elements":[]}""",
            acl = acl,
        )
        store.upsert(initialDoc)

        val session = CanvasSession(canvasId = docId, store = store)
        session.load()

        // Unauthorized local op
        val unauthorizedOp = CanvasOp.SetBackgroundOp(
            opId = "op-unauth",
            actorId = "eve",
            lamport = 1L,
            colorHex = "#ffffff",
        )
        assertFailsWith<UnauthorizedCanvasMutationException> {
            session.applyLocal(unauthorizedOp)
        }

        // Unauthorized agent replace
        assertFailsWith<UnauthorizedCanvasMutationException> {
            session.applyAgentReplace("""{"bgColor":-2,"elements":[]}""", actorId = "eve-agent")
        }

        // Unauthorized remote op is silently dropped
        val remoteDropped = session.applyRemote(unauthorizedOp)
        assertNull(remoteDropped)

        // Authorized local op by owner
        val authorizedOp = CanvasOp.SetBackgroundOp(
            opId = "op-auth-1",
            actorId = "alice",
            lamport = 2L,
            colorHex = "#123456",
        )
        val updated = session.applyLocal(authorizedOp)
        assertEquals(2L, updated.revision)

        // Authorized local op by writer
        val authorizedWriterOp = CanvasOp.SetBackgroundOp(
            opId = "op-auth-2",
            actorId = "agent:agent-writer",
            lamport = 3L,
            colorHex = "#654321",
        )
        val updated2 = session.applyLocal(authorizedWriterOp)
        assertEquals(3L, updated2.revision)
    }

    @Test
    fun testExternalToolsEnforceAcl() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val docId = CanvasId("canvas-tool-acl")
        val acl = CanvasAcl(
            ownerUserId = "alice",
            writerAgentIds = setOf("allowed-agent"),
            readerAgentIds = setOf("allowed-agent", "readonly-agent"),
        )
        val doc = CanvasDocument(
            id = docId,
            title = "Tool Protected",
            revision = 1L,
            sceneJson = """{"bgColor":-1,"elements":[]}""",
            acl = acl,
        )
        store.upsert(doc)

        val replaceTool = CanvasReplaceSceneTool(store)
        val getSceneTool = CanvasGetSceneTool(store)
        val applyOpsTool = CanvasApplyOpsTool(store)

        // Unauthorized writer tries to replace scene
        val unauthorizedReplace = replaceTool.invoke(
            buildJsonObject {
                put("canvas_id", docId.value)
                put("scene_json", """{"bgColor":-1,"elements":[]}""")
            },
            agentId = "intruder-agent",
        )
        assertTrue(unauthorizedReplace is ExternalToolResult.Error)
        assertTrue((unauthorizedReplace as ExternalToolResult.Error).error.contains("Unauthorized"))

        // Unauthorized reader tries to get scene
        val unauthorizedGet = getSceneTool.invoke(
            buildJsonObject { put("canvas_id", docId.value) },
            agentId = "intruder-agent",
        )
        assertTrue(unauthorizedGet is ExternalToolResult.Error)

        // Read-only agent can read
        val readonlyGet = getSceneTool.invoke(
            buildJsonObject { put("canvas_id", docId.value) },
            agentId = "readonly-agent",
        )
        assertTrue(readonlyGet is ExternalToolResult.Success)

        // Read-only agent cannot write
        val readonlyWrite = replaceTool.invoke(
            buildJsonObject {
                put("canvas_id", docId.value)
                put("scene_json", """{"bgColor":-1,"elements":[]}""")
            },
            agentId = "readonly-agent",
        )
        assertTrue(readonlyWrite is ExternalToolResult.Error)

        // Allowed agent can write
        val allowedWrite = replaceTool.invoke(
            buildJsonObject {
                put("canvas_id", docId.value)
                put("scene_json", """{"bgColor":-999,"elements":[]}""")
            },
            agentId = "allowed-agent",
        )
        assertTrue(allowedWrite is ExternalToolResult.Success)
    }
}
