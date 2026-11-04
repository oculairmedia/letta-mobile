package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptTailTest {
    private fun file(text: String): File = Files.createTempFile("tail", ".jsonl").toFile().also { it.writeText(text) }

    @Test
    fun committedUserLookupChecksRoleOwnerAndIdentityWithinABoundedTail() {
        val root = Files.createTempDirectory("arrival-tail").toFile()
        try {
            val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("conversation:inbox".toByteArray())
            val dir = File(root, "conversations/$key").apply { mkdirs() }
            File(dir, "conversation.json").writeText("""{"id":"inbox","agent_id":"recipient"}""")
            val transcript = File(dir, "messages.jsonl")
            fun row(id: String, role: String, identity: String = ""): String =
                """{"id":"$id","role":"$role",$identity"content":[{"type":"text","text":"wake"}]}""" + "\n"
            val store = LocalBackendAdminStore(root)
            transcript.writeText(row("source", "user", "\"otid\":\"direct\","))
            File(dir, "_otid-map.json").writeText("""{"source":"legacy"}""")
            assertEquals("source", store.committedUserMessageId(ConversationId("inbox"), AgentId("recipient"), MessageId("direct")))
            assertEquals(null, store.committedUserMessageId(ConversationId("inbox"), AgentId("other-agent"), MessageId("direct")))
            assertEquals(
                null, store.committedUserMessageId(ConversationId("inbox"), AgentId("recipient"), MessageId("legacy")),
                "direct identity wins over a stale sidecar",
            )
            transcript.writeText(row("source", "user"))
            assertEquals("source", store.committedUserMessageId(ConversationId("inbox"), AgentId("recipient"), MessageId("legacy")))
            transcript.writeText(row("source", "assistant", "\"otid\":\"direct\","))
            assertEquals(null, store.committedUserMessageId(ConversationId("inbox"), AgentId("recipient"), MessageId("direct")))
            transcript.writeText(row("source", "user", "\"otid\":\"direct\","))
            transcript.appendText((1..64).joinToString("") { row("new-$it", "user") })
            assertEquals(null, store.committedUserMessageId(ConversationId("inbox"), AgentId("recipient"), MessageId("direct")),
                "out-of-window absence must not invent evidence")
        } finally { root.deleteRecursively() }
    }

    @Test
    fun theLastLinesComeBackOldestFirst() {
        val lines = (1..10).map { "line-$it" }

        assertEquals(listOf("line-8", "line-9", "line-10"), file(lines.joinToString("\n", postfix = "\n")).tailLines(3, 1_000))
        assertEquals(listOf("line-9", "line-10"), file(lines.joinToString("\n")).tailLines(2, 1_000))
    }

    @Test
    fun blankLinesAreSkippedAndAShortFileComesBackWhole() {
        assertEquals(listOf("a", "b"), file("a\n\n  \nb\n").tailLines(5, 1_000))
    }

    @Test
    fun aLineCutByTheByteWindowIsLeftOut() {
        val text = "first-line-is-long\nsecond\nthird\n"

        assertEquals(listOf("second", "third"), file(text).tailLines(5, "second\nthird\n".length.toLong() + 3))
    }

    @Test
    fun aWindowStartingExactlyAtALineKeepsThatLine() {
        val text = "first\nsecond\nthird\n"

        assertEquals(listOf("second", "third"), file(text).tailLines(5, "second\nthird\n".length.toLong()))
    }

    @Test
    fun linesSpanningReadChunksAndMultiByteTextSurvive() {
        val long = "é".repeat(50_000)
        val lines = listOf("old", long, "x".repeat(70_000), "newest")

        assertEquals(lines.drop(1), file(lines.joinToString("\n", postfix = "\n")).tailLines(3, 10_000_000))
    }

    @Test
    fun aMissingFileHasNoLines() {
        assertEquals(emptyList(), File("/nonexistent/tail.jsonl").tailLines(3, 1_000))
    }
}
