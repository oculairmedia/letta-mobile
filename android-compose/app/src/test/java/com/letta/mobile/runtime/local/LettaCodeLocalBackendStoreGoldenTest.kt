package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.Conversation
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * letta-mobile-by4vm.21: golden for the embedded local-backend store. The committed
 * `local-backend-golden/snapshot.json` was recorded from the app-side parser on main BEFORE it
 * was replaced by the sharedLogic `LocalBackend*` readers, so this test proves the adapter
 * produces byte-identical records, context inputs, conversations and transcript projections
 * for the same fixture backend dir.
 *
 * The actual snapshot is also written to `build/local-backend-golden-actual.json` so a
 * deliberate format change can be re-recorded by copying it over the resource.
 */
class LettaCodeLocalBackendStoreGoldenTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { prettyPrint = true }

    private val storageRoot: File get() = File(temp.root, "embedded-lettacode/local-backend")

    private fun store(): LettaCodeLocalBackendStore {
        val context = mockk<Context>()
        every { context.filesDir } returns temp.root
        return LettaCodeLocalBackendStore(context)
    }

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun conversationDir(agentId: String): File =
        File(storageRoot, "conversations/${b64("default:$agentId")}").apply { mkdirs() }

    private fun agentFile(agentId: String): File =
        File(storageRoot, "agents/${b64(agentId)}.json").apply { parentFile!!.mkdirs() }

    private fun writeFixture() {
        agentFile(AGENT_A).writeText(
            """{"id":"$AGENT_A","name":"Disk A","model":"lmstudio/m-disk","model_settings":{"context_window_limit":32000},"tags":["x"]}""",
        )
        agentFile(AGENT_B).writeText("""{"id":"$AGENT_B","model":"lmstudio/m-b"}""")
        agentFile("agent-blank-model").writeText("""{"id":"agent-blank-model","name":"Blank","model":""}""")
        File(storageRoot, "agents/garbage.json").writeText("not json")

        val dirA = conversationDir(AGENT_A)
        File(dirA, "conversation.json").writeText(
            """{"id":"default","agent_id":"$AGENT_A","archived":false,"archived_at":null,""" +
                """"created_at":"2026-02-01T00:00:00.000Z","updated_at":"2026-02-02T00:00:00.000Z",""" +
                """"last_message_at":"2026-02-03T00:00:00.000Z","summary":"conv A","in_context_message_ids":[]}""",
        )
        File(dirA, "system-prompt.json").writeText("""{"content":"You are a fixture. ${"word ".repeat(40)}"}""")
        val blob = byteArrayOf(1, 2, 3, 4, 5)
        val hash = MessageDigest.getInstance("SHA-256").digest(blob).joinToString("") { "%02x".format(it) }
        File(dirA, "blobs").mkdirs()
        File(dirA, "blobs/$hash.png").writeBytes(blob)
        val missingHash = "f".repeat(64)
        File(dirA, "messages.jsonl").writeText(
            listOf(
                """{"type":"session","version":3,"id":"s1"}""",
                """{"id":"u1","role":"user","content":[{"type":"text","text":"hello there"}],"metadata":{"created_at":"2026-02-03T00:00:01.000Z"}}""",
                """{"id":"u2","role":"user","content":[{"type":"text","text":"<system-reminder>ctx</system-reminder>"}]}""",
                """{"id":"a1","role":"assistant","content":[{"type":"thinking","thinking":"hm"},{"type":"text","text":"calling"},{"type":"toolCall","id":"call_1","name":"Bash","arguments":{"command":"ls"}},{"type":"toolCall","id":"call_2","name":"Read"}]}""",
                """{"id":"t1","role":"toolResult","toolCallId":"call_1","content":[{"type":"text","text":"a\nb"},{"type":"text","text":"c"}],"isError":false}""",
                """{"id":"t2","role":"toolResult","toolCallId":"call_x","content":[{"type":"text","text":"boom"}],"isError":true}""",
                """{"type":"message","id":"e1","message":{"id":"env-t","role":"toolResult","toolCallId":"call_env","content":[{"type":"text","text":"from envelope"}],"isError":false}}""",
                """{"type":"message","id":"e2","message":{"id":"env-u","role":"user","content":[{"type":"text","text":"enveloped user"}]}}""",
                """{"id":"u3","role":"user","content":[{"type":"text","text":"see image"},{"type":"text","text":"[image]","image_ref":"sha256:$hash","mediaType":"image/png"}]}""",
                """{"id":"u4","role":"user","content":[{"type":"text","text":"lost image"},{"type":"text","text":"[image]","image_ref":"sha256:$missingHash"}]}""",
                """{"id":"u5","role":"user","content":[{"type":"image","mimeType":"image/png","data":"AAAA"}]}""",
                "",
                "{broken",
                """{"id":"a2","role":"assistant","content":[{"type":"text","text":"done"}],"usage":{"input":1234,"output":5}}""",
                """{"id":"x1","role":"custom","content":[]}""",
            ).joinToString("\n") + "\n",
        )

        // agent-b: record only, archived default conversation, no transcript.
        File(conversationDir(AGENT_B), "conversation.json").writeText(
            """{"id":"default","agent_id":"$AGENT_B","archived":true,"created_at":"2026-01-05T00:00:00.000Z"}""",
        )
        // A non-default conversation is not listed by the embedded store.
        File(storageRoot, "conversations/${b64("conversation:conv-z")}").apply { mkdirs() }
            .resolve("conversation.json")
            .writeText("""{"id":"conv-z","agent_id":"$AGENT_A","created_at":"2026-03-01T00:00:00.000Z"}""")
    }

    private suspend fun snapshot(): JsonObject {
        writeFixture()
        val store = store()
        store.persistAgent(Agent(id = AgentId(AGENT_A), name = "Sidecar A", model = "lmstudio/m-a", system = "sidecar system"))
        store.persistAgent(Agent(id = AgentId(AGENT_C), name = "Sidecar C"))
        store.seedAgent(AGENT_A, "lmstudio/m-seeded")
        val created = store.createConversation(AgentId(AGENT_A), summary = null)

        val agents = store.listAgents().sortedBy { it.id.value }
        val conversations = store.listConversations()
        val overview = store.contextWindowOverview(AgentId(AGENT_A))
        val overviewB = store.contextWindowOverview(AgentId(AGENT_B))
        return buildJsonObject {
            put("agents", json.encodeToJsonElement(ListSerializer(Agent.serializer()), agents))
            put("conversations", json.encodeToJsonElement(ListSerializer(Conversation.serializer()), conversations))
            put("createdConversation", json.encodeToJsonElement(Conversation.serializer(), created))
            put("contextOverviewA", overview?.let { json.encodeToJsonElement(ContextWindowOverview.serializer(), it) } ?: JsonNull)
            put("contextOverviewB", overviewB?.let { json.encodeToJsonElement(ContextWindowOverview.serializer(), it) } ?: JsonNull)
            put("contextOverviewMissing", if (store.contextWindowOverview(AgentId("agent-none")) == null) "null" else "present")
            put("storedModelA", store.storedModelHandle(AgentId(AGENT_A)))
            put("storedModelB", store.storedModelHandle(AgentId(AGENT_B)))
            put("storedModelBlank", store.storedModelHandle(AgentId("agent-blank-model")))
            put("storedModelMissing", store.storedModelHandle(AgentId("agent-none")))
            put(
                "transcriptA",
                buildJsonArray { store.readTranscript(AGENT_A).forEach { add(JsonPrimitive(it.toString())) } },
            )
            put("transcriptMissing", store.readTranscript("agent-none").size)
            put(
                "toolResultsA",
                buildJsonObject {
                    store.readToolResults(AgentId(AGENT_A)).toSortedMap().forEach { (id, result) -> put(id, result.toString()) }
                },
            )
            put("toolResultsMissing", store.readToolResults(AgentId("agent-none")).size)
            put(
                "files",
                buildJsonObject {
                    storageRoot.walkTopDown()
                        .filter { it.isFile && it.extension == "json" }
                        .sortedBy { it.relativeTo(storageRoot).path }
                        .forEach { put(it.relativeTo(storageRoot).path, it.readText()) }
                },
            )
        }
    }

    @Test
    fun `adapter output matches the golden recorded from the app-side parser`() = runTest {
        val actual = json.encodeToString(JsonObject.serializer(), snapshot())
        File(System.getProperty("user.dir"), "build").apply { mkdirs() }
            .resolve("local-backend-golden-actual.json").writeText(actual)
        val golden = javaClass.getResource("/local-backend-golden/snapshot.json")?.readText()
            ?: error("golden resource missing; recorded actual output to build/local-backend-golden-actual.json")
        assertEquals(golden.trim(), actual.trim())
    }

    private companion object {
        const val AGENT_A = "agent-a"
        const val AGENT_B = "agent-b"
        const val AGENT_C = "agent-c"
    }
}
