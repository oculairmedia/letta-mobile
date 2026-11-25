package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `agent.context` is called on every chat switch, and the host logs showed it as the slowest
 * local read: the reader re-read and re-parsed the agent record, `system-prompt.json`,
 * `conversation.json` and `messages.jsonl` on every call. The fingerprint-keyed LRU answers
 * an unchanged store from the previous parse, and this test class pins that contract:
 * a repeat call parses nothing, a changed file is re-read, and past the cap the least
 * recently used entry is dropped.
 */
class LocalBackendContextReaderCacheTest {

    private fun fixtureRoot(): File =
        LocalBackendFixtureStore.create(createTempDirectory("ctx-cache-store").toFile())

    private fun reader(root: File): LocalBackendContextReader {
        val support = LocalBackendStoreSupport(root, "http://e/v1")
        return LocalBackendContextReader(support, LocalBackendMessageReader(support))
    }

    private fun transcriptFile(root: File): File =
        File(LocalBackendFixtureStore.conversationDir(root, LocalBackendFixtureStore.AGENT_ID), "messages.jsonl")

    private fun numMessages(context: JsonObject): Int =
        context.getValue("num_messages").jsonPrimitive.content.toInt()

    @Test
    fun unchangedFilesServeTheCachedParseWithoutReparsingTheBodies() {
        val root = fixtureRoot()
        val sut = reader(root)
        val query = AgentContextQuery(LocalBackendFixtureStore.AGENT_ID, null)

        val first = assertNotNull(sut.agentContextProjected(query))
        assertEquals(1, sut.agentContextFullReads)
        val second = assertNotNull(sut.agentContextProjected(query))
        assertEquals(1, sut.agentContextFullReads)
        assertEquals(first, second)
    }

    @Test
    fun appendedTranscriptLineIsServedFreshNotFromTheCache() {
        val root = fixtureRoot()
        val sut = reader(root)
        val query = AgentContextQuery(LocalBackendFixtureStore.AGENT_ID, null)

        val before = assertNotNull(sut.agentContextProjected(query))
        assertEquals(1, sut.agentContextFullReads)
        assertEquals(1, numMessages(before))

        transcriptFile(root).appendText(
            """{"id":"m-2","role":"user","parts":[{"type":"text","text":"more"}]}""" + "\n",
        )

        val after = assertNotNull(sut.agentContextProjected(query))
        assertEquals(2, sut.agentContextFullReads)
        assertEquals(2, numMessages(after))
    }

    @Test
    fun theLeastRecentlyUsedEntryIsEvictedPastTheCap() {
        val root = fixtureRoot()
        val sut = reader(root)
        val agentId = LocalBackendFixtureStore.AGENT_ID
        fun query(total: Int) = AgentContextQuery(agentId, null, total)

        // One distinct key per reported total: two more than the cap fits.
        val totals = 1..(MAX_CACHED_AGENT_CONTEXTS + 2)
        val distinctReads = totals.count()
        totals.forEach { total -> assertNotNull(sut.agentContextProjected(query(total))) }
        assertEquals(distinctReads, sut.agentContextFullReads)

        // The most recently used entry is still cached and must not re-parse.
        sut.agentContextProjected(query(totals.last()))
        assertEquals(distinctReads, sut.agentContextFullReads)

        // The first entry was the least recently used: it was evicted and must re-parse.
        sut.agentContextProjected(query(totals.first()))
        assertEquals(distinctReads + 1, sut.agentContextFullReads)
    }
}
