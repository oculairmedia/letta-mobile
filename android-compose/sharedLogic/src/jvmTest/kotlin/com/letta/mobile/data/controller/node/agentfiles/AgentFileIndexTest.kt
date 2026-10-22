package com.letta.mobile.data.controller.node.agentfiles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The agent index reads attributes for every file but parses only a record that changed, and never covers more than its cap. */
class AgentFileIndexTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun isHidden(record: JsonObject): Boolean =
        (record["hidden"] as? JsonPrimitive)?.booleanOrNull
            ?: ((record["tags"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "role:subagent" } == true)

    private fun store(): File = Files.createTempDirectory("agent-index").toFile().also { File(it, "agents").mkdirs() }

    private fun File.agent(id: String, body: String = """{"id":"$id"}""", mtimeMs: Long): File =
        File(this, "agents/$id.json").also {
            it.writeText(body)
            Files.setLastModifiedTime(it.toPath(), FileTime.fromMillis(mtimeMs))
        }

    private fun index(root: File, max: Int = AgentFileIndex.DEFAULT_MAX_ENTRIES) =
        AgentFileIndex(File(root, "agents"), json, isHidden = ::isHidden, maxEntries = max)

    @Test
    fun entriesComeNewestFirstAndSkipUnreadableRecords() {
        val root = store()
        root.agent("old", mtimeMs = 1_000)
        root.agent("new", mtimeMs = 3_000)
        root.agent("mid", mtimeMs = 2_000)
        root.agent("broken", body = "not json", mtimeMs = 4_000)
        root.agent("noid", body = """{"name":"x"}""", mtimeMs = 5_000)

        assertEquals(listOf("new", "mid", "old"), index(root).entries().map { it.id })
    }

    @Test
    fun aRecordThatCouldNotBeReadIsRetriedOnTheNextScan() {
        val root = store()
        val file = root.agent("locked", mtimeMs = 1_000)
        // Root ignores file permissions, so this only runs where a chmod takes effect.
        org.junit.Assume.assumeTrue(file.setReadable(false) && !file.canRead())
        val index = index(root)
        assertEquals(emptyList(), index.entries().map { it.id })

        file.setReadable(true)

        assertEquals(listOf("locked"), index.entries().map { it.id })
    }

    @Test
    fun theSignatureMovesWithEveryAddRemoveAndRewriteOnly() {
        val root = store()
        val index = index(root)
        val a = root.agent("a", mtimeMs = 1_000)
        val first = index.signature()
        assertEquals(first, index.signature())

        root.agent("b", mtimeMs = 2_000)
        val added = index.signature()
        a.writeText("""{"id":"a","name":"renamed"}""")
        Files.setLastModifiedTime(a.toPath(), FileTime.fromMillis(1_000))
        val rewritten = index.signature()
        File(root, "agents/b.json").delete()
        val removed = index.signature()

        assertEquals(4, setOf(first, added, rewritten, removed).size)
    }

    @Test
    fun hiddenAgentsAreFlaggedByTheirTagOrTheirHiddenField() {
        val root = store()
        root.agent("plain", mtimeMs = 1_000)
        root.agent("tagged", body = """{"id":"tagged","tags":["role:subagent"]}""", mtimeMs = 2_000)
        root.agent("flagged", body = """{"id":"flagged","hidden":true}""", mtimeMs = 3_000)
        root.agent("shown", body = """{"id":"shown","hidden":false,"tags":["role:subagent"]}""", mtimeMs = 4_000)

        val hidden = index(root).entries().filter { it.hidden }.map { it.id }.toSet()

        assertEquals(setOf("tagged", "flagged"), hidden)
    }

    @Test
    fun anUnchangedRecordIsNotParsedAgain() {
        val root = store()
        val file = root.agent("a1", body = """{"id":"a1"}""", mtimeMs = 1_000)
        val index = index(root)
        assertEquals(listOf("a1"), index.entries().map { it.id })

        // Same size, same modified time, different content: only a re-parse would notice.
        file.writeText("""{"id":"b2"}""")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_000))

        assertEquals(listOf("a1"), index.entries().map { it.id })
    }

    @Test
    fun aChangedRecordIsParsedAgain() {
        val root = store()
        val file = root.agent("a1", body = """{"id":"a1"}""", mtimeMs = 1_000)
        val index = index(root)
        assertEquals(listOf("a1"), index.entries().map { it.id })

        file.writeText("""{"id":"a1","tags":["role:subagent"]}""")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(2_000))

        assertTrue(index.entries().single().hidden)
    }

    @Test
    fun aDeletedRecordLeavesTheIndex() {
        val root = store()
        val gone = root.agent("gone", mtimeMs = 1_000)
        root.agent("kept", mtimeMs = 2_000)
        val index = index(root)
        assertEquals(2, index.entries().size)

        gone.delete()

        assertEquals(listOf("kept"), index.entries().map { it.id })
    }

    @Test
    fun theIndexNeverCoversMoreThanItsCapAndKeepsTheNewest() {
        val root = store()
        (1..10).forEach { root.agent("a$it", mtimeMs = it * 1_000L) }

        val entries = index(root, max = 3).entries()

        assertEquals(listOf("a10", "a9", "a8"), entries.map { it.id })
    }

    @Test
    fun filesPastTheCapAreNamedAsUncovered() {
        val root = store()
        (1..5).forEach { root.agent("a$it", mtimeMs = it * 1_000L) }

        val scan = index(root, max = 3).scan()

        assertEquals(listOf("a5", "a4", "a3"), scan.entries.map { it.id })
        assertEquals(setOf("a1", "a2"), scan.uncoveredIds)
    }

    @Test
    fun aRewriteWithinTheSameMillisecondAndSizeIsStillSeen() {
        val root = store()
        val file = root.agent("x", body = """{"id":"x","hidden":false}""", mtimeMs = 1_000)
        val index = index(root)
        assertEquals(listOf(false), index.entries().map { it.hidden })

        file.writeText("""{"id":"x","hidden":true }""")
        Files.setLastModifiedTime(file.toPath(), FileTime.from(java.time.Instant.ofEpochMilli(1_000).plusNanos(500_000)))

        assertEquals(listOf(true), index.entries().map { it.hidden })
    }

    @Test
    fun crossingTheCapIsReportedOncePerCrossingNotPerScan() {
        val root = store()
        (1..5).forEach { root.agent("a$it", mtimeMs = it * 1_000L) }
        val reports = mutableListOf<Pair<Int, Int>>()
        val index = AgentFileIndex(File(root, "agents"), json, isHidden = ::isHidden, maxEntries = 3) { found, max ->
            reports += found to max
        }

        repeat(3) { index.entries() }

        assertEquals(listOf(5 to 3), reports)
    }

    @Test
    fun aMissingDirectoryIsAnEmptyIndex() {
        val root = Files.createTempDirectory("no-agents").toFile()

        assertTrue(index(root).entries().isEmpty())
    }
}
