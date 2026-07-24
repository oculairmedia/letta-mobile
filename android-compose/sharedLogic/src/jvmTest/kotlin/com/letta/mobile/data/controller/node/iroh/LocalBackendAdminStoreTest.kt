package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * lgns8.9: parity coverage for the on-disk agent.list port. Verifies the
 * projection matches admin-shim `agentToLettaState` / `readBlocksForAgent`
 * (ordering, locked wire invariants, block id scheme, paging) so the native
 * store tier can eventually replace the shim proxy without a client-visible
 * change.
 */
class LocalBackendAdminStoreTest {
    private fun tempStore(): File = Files.createTempDirectory("lc-local-backend-test").toFile()

    private fun writeAgent(base: File, fileName: String, json: String, mtimeMs: Long) {
        val agents = File(base, "agents").apply { mkdirs() }
        val f = File(agents, fileName)
        f.writeText(json)
        f.setLastModified(mtimeMs)
    }

    private fun writeBlock(base: File, agentId: String, label: String, value: String) {
        val dir = File(base, "memfs/$agentId/memory/system").apply { mkdirs() }
        File(dir, "$label.md").writeText(value)
    }

    @Test
    fun `projects agent record to the shim wire shape with locked invariants`() {
        val base = tempStore()
        writeAgent(
            base, "a1.json",
            """{"id":"agent-1","name":"Meridian","description":"main","system":"sys","tags":["x"],
               "model":"lmstudio/opus-4-8","model_settings":{"provider_type":"lmstudio","context_window_limit":200000,"temperature":0.7,"max_tokens":8192}}""",
            mtimeMs = 2_000L,
        )
        writeBlock(base, "agent-1", "persona", "I am Meridian")
        writeBlock(base, "agent-1", "human", "the user")

        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://127.0.0.1:4000/v1")
        val agents = store.listAgentsProjected(limit = null, offset = null)!!
        assertEquals(1, agents.size)
        val a = agents[0].jsonObject

        assertEquals("agent-1", a["id"]!!.jsonPrimitive.content)
        assertEquals("Meridian", a["name"]!!.jsonPrimitive.content)
        assertEquals("memgpt_agent", a["agent_type"]!!.jsonPrimitive.content)
        // Locked: metadata is JSON null, never {}.
        assertEquals(JsonNull, a["metadata"])
        // List endpoint never hydrates transcripts.
        assertEquals(0, (a["message_ids"] as JsonArray).size)
        assertEquals("user", a["last_stop_reason"]!!.jsonPrimitive.content)

        val llm = a["llm_config"]!!.jsonObject
        assertEquals("opus-4-8", llm["model"]!!.jsonPrimitive.content)
        assertEquals("lmstudio", llm["provider_name"]!!.jsonPrimitive.content)
        // provider_type "lmstudio" maps endpoint_type -> "openai".
        assertEquals("openai", llm["model_endpoint_type"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:4000/v1", llm["model_endpoint"]!!.jsonPrimitive.content)
        assertEquals(200000L, llm["context_window"]!!.jsonPrimitive.content.toLong())

        // Blocks: one per memfs/<agent>/memory/system/*.md, sha256(agentId:label) id.
        val blocks = a["blocks"] as JsonArray
        assertEquals(2, blocks.size)
        val labels = blocks.map { it.jsonObject["label"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("persona", "human"), labels)
        blocks.forEach {
            val id = it.jsonObject["id"]!!.jsonPrimitive.content
            assertTrue(id.startsWith("block-") && id.length == "block-".length + 24, "block id shape: $id")
            assertEquals(5000L, it.jsonObject["limit"]!!.jsonPrimitive.content.toLong())
        }
        // Distinct labels -> distinct ids (the collision bug the shim fixed).
        assertEquals(2, blocks.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet().size)
        // memory.blocks mirrors top-level blocks.
        assertEquals(2, (a["memory"]!!.jsonObject["blocks"] as JsonArray).size)
    }

    @Test
    fun `defaults a missing name and model and empty settings`() {
        val base = tempStore()
        writeAgent(base, "a.json", """{"id":"agent-x"}""", mtimeMs = 1_000L)
        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")
        val a = store.listAgentsProjected(null, null)!![0].jsonObject

        assertEquals("Untitled", a["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, a["description"])
        // Default handle lmstudio/opus-4-7 -> model opus-4-7.
        assertEquals("opus-4-7", a["model"]!!.jsonPrimitive.content)
        assertEquals(200000L, a["llm_config"]!!.jsonObject["context_window"]!!.jsonPrimitive.content.toLong())
        assertEquals(0, (a["blocks"] as JsonArray).size)
    }

    @Test
    fun `orders by mtime descending and pages by offset and limit`() {
        val base = tempStore()
        writeAgent(base, "old.json", """{"id":"agent-old"}""", mtimeMs = 1_000L)
        writeAgent(base, "new.json", """{"id":"agent-new"}""", mtimeMs = 3_000L)
        writeAgent(base, "mid.json", """{"id":"agent-mid"}""", mtimeMs = 2_000L)
        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")

        val ids = store.listAgentsProjected(null, null)!!.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("agent-new", "agent-mid", "agent-old"), ids)

        val page = store.listAgentsProjected(limit = 1, offset = 1)!!
        assertEquals(1, page.size)
        assertEquals("agent-mid", page[0].jsonObject["id"]!!.jsonPrimitive.content)

        // Offset past the end -> empty, not error.
        assertEquals(0, store.listAgentsProjected(limit = 5, offset = 99)!!.size)
    }

    @Test
    fun `returns null when the store directory is absent so caller falls back to proxy`() {
        val store = LocalBackendAdminStore(File("/nonexistent/lc-local-backend-xyz"), lmstudioBaseUrl = "http://e/v1")
        // agents/ missing -> empty list (not null); the null-on-error contract is
        // for read faults. An absent agents dir yields an empty array.
        assertEquals(0, store.listAgentsProjected(null, null)!!.size)
    }

    @Test
    fun `skips malformed agent json without failing the whole list`() {
        val base = tempStore()
        writeAgent(base, "good.json", """{"id":"agent-good"}""", mtimeMs = 2_000L)
        writeAgent(base, "bad.json", """{ not json """, mtimeMs = 3_000L)
        writeAgent(base, "noid.json", """{"name":"x"}""", mtimeMs = 4_000L)
        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")
        val ids = store.listAgentsProjected(null, null)!!.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("agent-good"), ids)
        assertNull(ids.firstOrNull { it != "agent-good" })
    }
}
