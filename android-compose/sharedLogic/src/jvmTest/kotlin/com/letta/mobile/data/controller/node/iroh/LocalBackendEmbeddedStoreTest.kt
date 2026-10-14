package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.storage.ImageBlobStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.21: the embedded store and the admin-query readers share one layout
 * ([LocalBackendLayout]). The app-level golden (`LettaCodeLocalBackendStoreGoldenTest`) pins
 * byte-identical output against the former app-side parser; these cover the shared seams.
 */
class LocalBackendEmbeddedStoreTest {
    private val root: File = createTempDirectory("by4vm-21-store").toFile()
    private val store = LocalBackendEmbeddedStore(root)
    private val fixedNow = { "2026-05-05T05:05:05Z" }
    private val agentA = AgentId("agent-a")
    private val agentT = AgentId("agent-t")

    private fun writeB64Agent(agentId: AgentId, body: String) {
        LocalBackendLayout.agentRecordFile(root, agentId).apply { parentFile!!.mkdirs() }.writeText(body)
    }

    private fun transcript(agentId: AgentId): File =
        File(LocalBackendLayout.defaultConversationDir(root, agentId).apply { mkdirs() }, LocalBackendLayout.TRANSCRIPT_FILE)

    private val noBlobs = object : ImageBlobStore {
        override fun putBytes(mediaType: String, bytes: ByteArray): String = error("read-only")
        override fun getBytes(ref: String): ByteArray? = null
        override fun has(ref: String): Boolean = false
    }

    @Test
    fun layoutCodecRoundTripsAndNamesDefaultConversations() {
        assertEquals("YWdlbnQtYQ", LocalBackendLayout.b64UrlEncode("agent-a"))
        assertEquals("agent-a", LocalBackendLayout.b64UrlDecode("YWdlbnQtYQ"))
        assertEquals("default:agent-a", LocalBackendLayout.conversationKey(ConversationId("default"), AgentId("agent-a")))
        assertEquals("conversation:conv-1", LocalBackendLayout.conversationKey(ConversationId("conv-1"), AgentId("agent-a")))
        assertEquals(
            File(root, "conversations/${LocalBackendLayout.b64UrlEncode("default:agent-a")}"),
            LocalBackendLayout.defaultConversationDir(root, AgentId("agent-a")),
        )
    }

    @Test
    fun findAgentRecordFilePrefersTheBareNameThenBase64Url() {
        assertNull(LocalBackendLayout.findAgentRecordFile(root, agentA))
        writeB64Agent(agentA, """{"id":"agent-a"}""")
        assertEquals(LocalBackendLayout.agentRecordFile(root, agentA), LocalBackendLayout.findAgentRecordFile(root, agentA))
        val bare = File(root, "agents/agent-a.json").apply { writeText("""{"id":"agent-a"}""") }
        assertEquals(bare, LocalBackendLayout.findAgentRecordFile(root, agentA))
    }

    @Test
    fun messageBodyUnwrapsOnlyRealEnvelopes() {
        val json = Json
        val flat = json.parseToJsonElement("""{"id":"m","role":"user"}""").jsonObject
        val envelope = json.parseToJsonElement("""{"type":"message","id":"e","message":{"id":"m","role":"user"}}""").jsonObject
        val header = json.parseToJsonElement("""{"type":"session","version":3}""").jsonObject
        assertEquals(flat, LocalBackendLayout.messageBody(flat))
        assertEquals(flat, LocalBackendLayout.messageBody(envelope))
        assertEquals(header, LocalBackendLayout.messageBody(header))
    }

    /** The admin `agent.context` reader used to look up only `agents/<id>.json` and missed letta.js's base64url records. */
    @Test
    fun adminAgentContextReadsABase64UrlNamedAgentRecord() {
        writeB64Agent(AgentId("agent-b64"), """{"id":"agent-b64","model_settings":{"context_window_limit":64000}}""")
        transcript(AgentId("agent-b64")).writeText("""{"id":"u1","role":"user","content":[{"type":"text","text":"hi"}]}""" + "\n")

        val context = assertNotNull(LocalBackendAdminStore(root).agentContextProjected("agent-b64", null))
        assertEquals(64000, context["context_window_size_max"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1, context["num_messages"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun contextWindowOverviewFallsBackTo128kAndIsNullForAnUnknownAgent() {
        writeB64Agent(agentA, """{"id":"agent-a"}""")
        assertEquals(LocalBackendEmbeddedStore.DEFAULT_LOCAL_CONTEXT_WINDOW, store.contextWindowOverview(AgentId("agent-a"))?.contextWindowSizeMax)
        assertNull(store.contextWindowOverview(AgentId("agent-missing")))
    }

    @Test
    fun seedAgentWritesTheRecordAndDefaultConversationOnce() {
        store.seedAgent(AgentId("agent-s"), "lmstudio/m1", fixedNow)
        store.seedAgent(AgentId("agent-s"), "lmstudio/m2") { error("existing conversation must not be restamped") }

        assertEquals("lmstudio/m2", store.storedModelHandle(AgentId("agent-s")))
        val conversation = store.listConversations().single()
        assertEquals(LocalBackendEmbeddedStore.localConversationIdFor(AgentId("agent-s")), conversation.id)
        assertEquals("2026-05-05T05:05:05Z", conversation.createdAt)
        assertEquals("Letta Mobile", store.listAgents().single().name)
    }

    @Test
    fun createConversationRequiresThePersistedAgentAndUpdatesTheSummary() {
        assertFailsWith<IllegalStateException> { store.createConversation(AgentId("agent-x"), null, fixedNow) }
        store.persistAgent(Agent(id = AgentId("agent-x"), name = "X"))
        store.createConversation(AgentId("agent-x"), null, fixedNow)

        val renamed = store.createConversation(AgentId("agent-x"), "renamed") { "2026-06-06T00:00:00Z" }
        assertEquals("renamed", renamed.summary)
        assertEquals("2026-06-06T00:00:00Z", renamed.updatedAt)
        assertEquals("2026-05-05T05:05:05Z", renamed.createdAt)
    }

    @Test
    fun readToolResultsUnwrapsEnvelopesWhileTheTranscriptProjectsFlatRows() {
        transcript(agentT).writeText(
            listOf(
                """{"type":"session","version":3}""",
                """{"type":"message","id":"e1","message":{"id":"r1","role":"toolResult","toolCallId":"c1","content":[{"type":"text","text":"ok"}],"isError":true}}""",
                """{"id":"u1","role":"user","content":[{"type":"text","text":"flat"}]}""",
            ).joinToString("\n") + "\n",
        )

        assertEquals(StoredToolResult("c1", "ok", isError = true), store.readToolResults(agentT)["c1"])
        val messages = store.readTranscript(agentT) { noBlobs }
        assertEquals(listOf("u1"), messages.map { it.id })
        assertTrue(messages.single() is UserMessage)
    }
}
