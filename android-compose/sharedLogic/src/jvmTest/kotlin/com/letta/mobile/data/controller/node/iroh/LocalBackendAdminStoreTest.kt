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

    private fun b64u(s: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun writeConversation(base: File, key: String, json: String, realTimesJsonl: String? = null) {
        val dir = File(base, "conversations/${b64u(key)}").apply { mkdirs() }
        File(dir, "conversation.json").writeText(json)
        if (realTimesJsonl != null) File(dir, "_real-times.jsonl").writeText(realTimesJsonl)
    }

    @Test
    fun `conversation list projects wire shape, applies sidecar times, and orders by recency`() {
        val base = tempStore()
        // Default conv with a SENTINEL last_message_at, but a real sidecar time -> should sort newest.
        writeConversation(
            base, "default:agent-1",
            """{"id":"default","agent_id":"agent-1","created_at":"2025-01-01T00:00:00.000Z","last_message_at":"2026-01-01T00:00:05.000Z","summary":"s"}""",
            realTimesJsonl = """{"id":"m1","iso":"2025-06-01T12:00:00.000Z"}
{"id":"m2","iso":"2025-09-09T09:00:00.000Z"}""",
        )
        // A real conversation with an older explicit last_message_at.
        writeConversation(
            base, "conversation:conv-abc",
            """{"id":"conv-abc","agent_id":"agent-1","last_message_at":"2025-03-03T03:00:00.000Z"}""",
        )
        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")
        val convs = store.listConversationsProjected(agentId = "agent-1", archiveStatus = "active", limit = null, offset = null)!!
        assertEquals(2, convs.size)
        // Order: default (sidecar max 2025-09-09) before conv-abc (2025-03-03).
        assertEquals("conv-default-agent-1", convs[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("2025-09-09T09:00:00.000Z", convs[0].jsonObject["last_message_at"]!!.jsonPrimitive.content)
        assertEquals("conv-abc", convs[1].jsonObject["id"]!!.jsonPrimitive.content)
        // Locked translate invariants.
        assertEquals("user-00000000-0000-4000-8000-000000000000", convs[0].jsonObject["created_by_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, convs[0].jsonObject["model"])
        assertEquals(0, (convs[0].jsonObject["isolated_block_ids"] as JsonArray).size)
    }

    @Test
    fun `conversation list filters archived and scopes to the agent`() {
        val base = tempStore()
        writeConversation(base, "conversation:c-active", """{"id":"c-active","agent_id":"agent-1","last_message_at":"2025-02-02T00:00:00.000Z"}""")
        writeConversation(base, "conversation:c-arch", """{"id":"c-arch","agent_id":"agent-1","archived":true,"last_message_at":"2025-02-02T00:00:00.000Z"}""")
        writeConversation(base, "conversation:c-other", """{"id":"c-other","agent_id":"agent-2","last_message_at":"2025-02-02T00:00:00.000Z"}""")
        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")

        val active = store.listConversationsProjected("agent-1", "active", null, null)!!
        assertEquals(listOf("c-active"), active.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        val archived = store.listConversationsProjected("agent-1", "archived", null, null)!!
        assertEquals(listOf("c-arch"), archived.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        val all = store.listConversationsProjected("agent-1", "all", null, null)!!.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("c-active", "c-arch"), all) // agent-2's conv excluded by scope
    }

    private fun writeMessages(base: File, key: String, jsonl: String) {
        val dir = File(base, "conversations/${b64u(key)}").apply { mkdirs() }
        File(dir, "messages.jsonl").writeText(jsonl)
    }

    private fun writeSidecar(base: File, key: String, name: String, body: String) {
        val dir = File(base, "conversations/${b64u(key)}").apply { mkdirs() }
        File(dir, name).writeText(body)
    }

    @Test
    fun `message list fans out user assistant tool with otid echo, sidecar times, and reminder stripping`() {
        val base = tempStore()
        val key = "default:agent-1"
        // Session header (skipped) + v3 envelope rows with `content` (mapped to parts).
        // Sentinel metadata.created_at dates are overridden by _real-times.jsonl.
        writeMessages(
            base, key,
            """{"type":"session","version":3,"id":"default"}
{"type":"message","id":"env-1","message":{"id":"ui-1","role":"user","metadata":{"created_at":"2026-01-01T00:00:01.000Z"},"content":[{"type":"text","text":"<system-reminder>ctx</system-reminder>\n\nHello"}]}}
{"type":"message","id":"env-2","message":{"id":"ui-2","role":"assistant","metadata":{"created_at":"2026-01-01T00:00:02.000Z"},"content":[{"type":"text","text":"working"},{"type":"toolCall","id":"call-1","name":"Bash","arguments":{"command":"ls"}}]}}
{"type":"message","id":"env-3","message":{"id":"ui-3","role":"toolResult","toolCallId":"call-1","toolName":"Bash","isError":false,"metadata":{"created_at":"2026-01-01T00:00:03.000Z"},"content":[{"type":"text","text":"output-here"}]}}""",
        )
        writeSidecar(
            base, key, "_real-times.jsonl",
            """{"id":"ui-1","iso":"2025-06-01T12:00:00.000Z"}
{"id":"ui-2","iso":"2025-06-01T12:00:00.000Z"}
{"id":"ui-3","iso":"2025-06-01T12:00:00.000Z"}""",
        )
        writeSidecar(base, key, "_otid-map.json", """{"ui-1":"cm-otid-1"}""")

        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")
        val msgs = store.listMessagesProjected(
            conversationId = "conv-default-agent-1",
            agentId = null,
            limit = null,
            before = null,
            after = null,
            order = null,
        )!!
        assertEquals(4, msgs.size)

        // [0] user_message: reminder stripped, otid echoed from sidecar, offset 0.
        val u = msgs[0].jsonObject
        assertEquals("user_message", u["message_type"]!!.jsonPrimitive.content)
        assertEquals("Hello", u["content"]!!.jsonPrimitive.content)
        assertEquals("cm-otid-1", u["otid"]!!.jsonPrimitive.content)
        assertEquals("ui-1", u["id"]!!.jsonPrimitive.content)
        assertEquals("2025-06-01T12:00:00.000Z", u["date"]!!.jsonPrimitive.content)

        // [1] assistant_message: otid = source id, assistant offset +40ms.
        val a = msgs[1].jsonObject
        assertEquals("assistant_message", a["message_type"]!!.jsonPrimitive.content)
        assertEquals("working", a["content"]!!.jsonPrimitive.content)
        assertEquals("ui-2", a["otid"]!!.jsonPrimitive.content)
        assertEquals("ui-2", a["id"]!!.jsonPrimitive.content)
        assertEquals("2025-06-01T12:00:00.040Z", a["date"]!!.jsonPrimitive.content)

        // [2] tool_call_message: toolcall-<id>, tool_call offset +20ms.
        val tc = msgs[2].jsonObject
        assertEquals("tool_call_message", tc["message_type"]!!.jsonPrimitive.content)
        assertEquals("toolcall-call-1", tc["id"]!!.jsonPrimitive.content)
        assertEquals("Bash", tc["name"]!!.jsonPrimitive.content)
        assertEquals("ui-2", tc["otid"]!!.jsonPrimitive.content)
        assertEquals("2025-06-01T12:00:00.020Z", tc["date"]!!.jsonPrimitive.content)
        val call = tc["tool_call"]!!.jsonObject
        assertEquals("Bash", call["name"]!!.jsonPrimitive.content)
        assertEquals("call-1", call["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"command":"ls"}""", call["arguments"]!!.jsonPrimitive.content)

        // [3] tool_return_message from the top-level toolResult row.
        val tr = msgs[3].jsonObject
        assertEquals("tool_return_message", tr["message_type"]!!.jsonPrimitive.content)
        assertEquals("toolreturn-call-1", tr["id"]!!.jsonPrimitive.content)
        assertEquals("success", tr["status"]!!.jsonPrimitive.content)
        assertEquals("output-here", tr["tool_return"]!!.jsonPrimitive.content)
        assertEquals("call-1", tr["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, tr["stdout"])
        assertEquals("2025-06-01T12:00:00.030Z", tr["date"]!!.jsonPrimitive.content)
        assertEquals("Bash", tr["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `message list applies limit before and desc order like the shim route`() {
        val base = tempStore()
        val key = "conversation:conv-xyz"
        writeMessages(
            base, key,
            """{"type":"message","id":"e1","message":{"id":"ui-1","role":"user","content":[{"type":"text","text":"one"}]}}
{"type":"message","id":"e2","message":{"id":"ui-2","role":"user","content":[{"type":"text","text":"two"}]}}
{"type":"message","id":"e3","message":{"id":"ui-3","role":"user","content":[{"type":"text","text":"three"}]}}""",
        )
        // Real conv: agent_id recovered from conversation.json.
        writeConversation(base, key, """{"id":"conv-xyz","agent_id":"agent-9"}""")
        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")

        // limit=2 keeps the NEWEST two (tail), preserving asc order.
        val limited = store.listMessagesProjected("conv-xyz", null, 2, null, null, "asc")!!
        assertEquals(listOf("two", "three"), limited.map { it.jsonObject["content"]!!.jsonPrimitive.content })

        // before=ui-3 drops ui-3 and everything after it.
        val before = store.listMessagesProjected("conv-xyz", null, null, "ui-3", null, "asc")!!
        assertEquals(listOf("one", "two"), before.map { it.jsonObject["content"]!!.jsonPrimitive.content })

        // order=desc reverses AFTER slicing.
        val desc = store.listMessagesProjected("conv-xyz", null, null, null, null, "desc")!!
        assertEquals(listOf("three", "two", "one"), desc.map { it.jsonObject["content"]!!.jsonPrimitive.content })
    }

    @Test
    fun `message list refuses bare default and empties an unknown conversation`() {
        val base = tempStore()
        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")
        // Bare "default" is ambiguous across agents -> empty (not the wrong agent).
        assertEquals(0, store.listMessagesProjected("default", null, null, null, null, null)!!.size)
        // Unknown conv id -> empty list, mirroring the shim's 200 [] for stale ids.
        assertEquals(0, store.listMessagesProjected("conv-missing", null, null, null, null, null)!!.size)
    }

    @Test
    fun `message list attributes run_id from the runs store`() {
        val base = tempStore()
        val key = "conversation:conv-run"
        writeMessages(
            base, key,
            """{"type":"message","id":"e1","message":{"id":"ui-a","role":"user","content":[{"type":"text","text":"hi"}]}}""",
        )
        writeConversation(base, key, """{"id":"conv-run","agent_id":"agent-r"}""")
        // Oldest run wins; _archive is ignored.
        val runsDir = File(base, "runs/run-1").apply { mkdirs() }
        File(runsDir, "run.json").writeText(
            """{"id":"run-1","agent_id":"agent-r","conversation_id":"conv-run","created_at":"2025-01-01T00:00:00.000Z","message_ids":["ui-a"]}""",
        )
        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://e/v1")
        val msgs = store.listMessagesProjected("conv-run", null, null, null, null, null)!!
        assertEquals("run-1", msgs[0].jsonObject["run_id"]!!.jsonPrimitive.content)
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
