package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.context.estimate.LocalMessageTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * letta-mobile-by4vm.49: the store-side `agent.context` reader must read `messages.jsonl` through
 * [BoundedTranscriptReader], so an oversized string value (a multi-MB base64 image part) is
 * replaced by the length marker instead of being materialised as one giant String.
 */
class LocalBackendContextReaderBoundedTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val query = AgentContextQuery(LocalBackendFixtureStore.AGENT_ID, null)

    private fun fixtureRoot(): File =
        LocalBackendFixtureStore.create(createTempDirectory("by4vm-49-store").toFile())

    private fun transcriptFile(root: File): File =
        File(LocalBackendFixtureStore.conversationDir(root, LocalBackendFixtureStore.AGENT_ID), "messages.jsonl")

    private fun reader(root: File, maxInlineValueChars: Int): LocalBackendContextReader {
        val support = LocalBackendStoreSupport(root, "http://e/v1")
        return LocalBackendContextReader(
            support,
            LocalBackendMessageReader(support),
            maxInlineValueChars = maxInlineValueChars,
        )
    }

    private fun userRow(part: String): String = """{"id":"u1","role":"user","content":[$part]}"""

    @Test
    fun oversizedTextValueIsCountedAsTheMarkerNotTheOriginalText() {
        val root = fixtureRoot()
        val original = "x".repeat(10_000)
        transcriptFile(root).writeText(userRow("""{"type":"text","text":"$original"}""") + "\n")

        val bounded = assertNotNull(reader(root, maxInlineValueChars = 64).transcriptTokens(query))
        val unbounded = assertNotNull(reader(root, maxInlineValueChars = Int.MAX_VALUE).transcriptTokens(query))

        val marker = "${BoundedTranscriptReader.OVERSIZED_VALUE_MARKER_PREFIX}${original.length} "
        val markerRow = json.parseToJsonElement(userRow("""{"type":"text","text":"$marker"}""")).jsonObject
        assertEquals(LocalMessageTokens.of(markerRow).toLong(), bounded)
        assertEquals(2_500L, unbounded)
    }

    @Test
    fun oversizedImagePartStillCountsAsOneImage() {
        val root = fixtureRoot()
        transcriptFile(root).writeText(userRow("""{"type":"image","mimeType":"image/png","data":"${"A".repeat(10_000)}"}""") + "\n")
        val smallRoot = fixtureRoot()
        transcriptFile(smallRoot).writeText(userRow("""{"type":"image","mimeType":"image/png","data":"AAAA"}""") + "\n")

        assertEquals(
            reader(smallRoot, maxInlineValueChars = 64).transcriptTokens(query),
            reader(root, maxInlineValueChars = 64).transcriptTokens(query),
        )
    }

    @Test
    fun nineMebibyteImagePartIsReadAtTheDefaultCap() {
        val root = fixtureRoot()
        val imageChars = 9 * 1024 * 1024
        // Written in chunks so the test itself never holds the whole line.
        transcriptFile(root).bufferedWriter().use { writer ->
            writer.write("""{"id":"u1","role":"user","content":[{"type":"text","text":"look"},""")
            writer.write("""{"type":"image","mimeType":"image/png","data":"""")
            val chunk = "A".repeat(1024 * 1024)
            repeat(imageChars / chunk.length) { writer.write(chunk) }
            writer.write("\"}]}\n")
            writer.write("""{"id":"a1","role":"assistant","content":[{"type":"text","text":"a cat"}]}""" + "\n")
        }
        val store = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1")

        val smallImage = json.parseToJsonElement(
            userRow("""{"type":"text","text":"look"},{"type":"image","mimeType":"image/png","data":"AAAA"}"""),
        ).jsonObject
        val reply = json.parseToJsonElement(
            """{"id":"a1","role":"assistant","content":[{"type":"text","text":"a cat"}]}""",
        ).jsonObject
        val expected = (LocalMessageTokens.of(smallImage) + LocalMessageTokens.of(reply)).toLong()
        assertEquals(expected, store.transcriptTokens(query))
        val context = assertNotNull(store.agentContextProjected(query))
        assertEquals(2, context.getValue("num_messages").jsonPrimitive.content.toInt())
    }
}
