package com.letta.mobile.data.memory.memfs

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.ScriptedAppServerTransport
import com.letta.mobile.data.transport.appserver.requestId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppServerMemfsSourceTest {
    private fun TestScope.source(
        timeoutMs: Long = 30_000L,
        respond: (JsonObject) -> List<String>,
    ): Pair<AppServerMemfsSource, ScriptedAppServerTransport> {
        val transport = ScriptedAppServerTransport(respond)
        val client = DefaultAppServerClient(transport, requestTimeoutMs = timeoutMs, parentScope = backgroundScope)
        val source = AppServerMemfsSource(client = { client }, events = client.events, requestId = { "$it-1" })
        return source to transport
    }

    private fun JsonObject.type(): String = (this["type"] as JsonPrimitive).content

    private fun entry(path: String, system: Boolean = true) =
        """{"relative_path":"$path","is_system":$system,"description":"d","content":"body of $path","size":12,"kind":"markdown"}"""

    @Test
    fun listingCollectsEveryPageOfTheMultiFrameResponse() = runTest {
        val (source, transport) = source { command ->
            val id = command.requestId
            listOf(
                """{"type":"list_memory_response","request_id":"$id","entries":[${entry("system/persona.md")},${entry("system/human.md")}],"done":false,"total":3,"success":true,"memfs_enabled":true}""",
                """{"type":"list_memory_response","request_id":"$id","entries":[${entry("notes/today.md", system = false)}],"done":true,"total":3,"success":true,"memfs_enabled":true}""",
            )
        }
        val listing = source.list("agent-1")

        assertTrue(listing.enabled)
        assertEquals(listOf("system/persona.md", "system/human.md", "notes/today.md"), listing.files.map { it.path })
        assertFalse(listing.files.last().isSystem)
        assertEquals("body of notes/today.md", listing.files.last().body)
        val sent = transport.sent.single()
        assertEquals("list_memory", sent.type())
        assertEquals("agent-1", (sent["agent_id"] as JsonPrimitive).content)
    }

    @Test
    fun aDisabledAgentListsNothingAndSaysSo() = runTest {
        val (source, _) = source { command ->
            listOf("""{"type":"list_memory_response","request_id":"${command.requestId}","entries":[],"done":true,"total":0,"success":true,"memfs_enabled":false}""")
        }
        val listing = source.list("agent-1")
        assertFalse(listing.enabled)
        assertTrue(listing.files.isEmpty())
    }

    @Test
    fun aStalledListingSurfacesATimeout() = runTest {
        val (source, _) = source(timeoutMs = 1_000L) { command ->
            listOf("""{"type":"list_memory_response","request_id":"${command.requestId}","entries":[],"done":false,"total":9,"success":true}""")
        }
        val result = async { runCatching { source.list("agent-1") } }
        advanceTimeBy(1_500L)
        runCurrent()
        val error = result.await().exceptionOrNull()
        assertIs<MemfsException>(error)
        assertEquals("The App Server did not answer in time.", error.message)
    }

    @Test
    fun readHistoryDiffAndPastVersionsDecode() = runTest {
        val (source, transport) = source { command ->
            val id = command.requestId
            when (command.type()) {
                "read_memory_file" -> listOf("""{"type":"read_memory_file_response","request_id":"$id","agent_id":"a","path":"system/persona.md","content":"---\ndescription: x\n---\nhi","encoding":"utf8","success":true}""")
                "memory_history" -> listOf("""{"type":"memory_history_response","request_id":"$id","file_path":"system/persona.md","commits":[{"sha":"abcdef123","message":"Update persona","timestamp":"2026-10-01T10:00:00Z","author_name":"Letta"}],"success":true}""")
                "memory_commit_diff" -> listOf("""{"type":"memory_commit_diff_response","request_id":"$id","sha":"abcdef123","diff":"diff --git a/x b/x","success":true}""")
                "memory_file_at_ref" -> listOf("""{"type":"memory_file_at_ref_response","request_id":"$id","file_path":"x","ref":"abc","content":"old","success":true}""")
                else -> emptyList()
            }
        }

        assertEquals("---\ndescription: x\n---\nhi", source.read(MemfsFileRef("a", "system/persona.md")))
        val commit = source.history(MemfsHistoryScope("a", "system/persona.md")).single()
        assertEquals("abcdef1", commit.shortSha)
        assertEquals("Letta", commit.author)
        assertEquals("diff --git a/x b/x", source.commitDiff(MemfsCommitRef("a", "abcdef123")))
        assertEquals("old", source.fileAtRef(MemfsFileRef("a", "x"), "abc"))
        assertEquals(
            listOf("read_memory_file", "memory_history", "memory_commit_diff", "memory_file_at_ref"),
            transport.sent.map { it.type() },
        )
        assertEquals("system/persona.md", (transport.sent[1]["file_path"] as JsonPrimitive).content)
    }

    @Test
    fun aRefusedRequestCarriesTheServerError() = runTest {
        val (source, _) = source { command ->
            listOf("""{"type":"read_memory_file_response","request_id":"${command.requestId}","content":null,"success":false,"error":"memfs is not enabled for this agent"}""")
        }
        val error = assertFailsWith<MemfsException> { source.read(MemfsFileRef("a", "system/x.md")) }
        assertEquals("memfs is not enabled for this agent", error.message)
    }

    @Test
    fun savesGoThroughWriteMemoryFile() = runTest {
        val (source, transport) = source { command ->
            listOf("""{"type":"write_memory_file_response","request_id":"${command.requestId}","agent_id":"a","path":"p","success":true,"committed":true,"commit_sha":"c0ffee"}""")
        }
        assertEquals("c0ffee", source.write(MemfsFileRef("a", "system/persona.md"), "new"))
        val sent = transport.sent.single()
        assertEquals("write_memory_file", sent.type())
        assertEquals("new", (sent["content"] as JsonPrimitive).content)
    }

    @Test
    fun memoryUpdatedPushesBecomeUpdates() = runTest {
        val (source, transport) = source { emptyList() }
        val update = async { source.updates.first() }
        runCurrent()
        transport.push("""{"type":"memory_updated","affected_paths":["system\\persona.md"],"timestamp":1}""")
        val received = update.await()
        assertTrue(received.touches("system/persona.md"))
        assertFalse(received.touches("system/human.md"))
    }

    @Test
    fun enablingSendsEnableMemfs() = runTest {
        val (source, transport) = source { command ->
            listOf("""{"type":"enable_memfs_response","request_id":"${command.requestId}","success":true,"memory_directory":"/m"}""")
        }
        source.enable("agent-1")
        assertEquals("enable_memfs", transport.sent.single().type())
    }

    /**
     * The responses stay untyped at the protocol layer: if one were ever added to the typed
     * inbound set, [AppServerMemfsSource] would stop matching it and every call would time out.
     */
    @Test
    fun memfsResponsesDecodeAsUnknownFrames() {
        listOf(
            "list_memory_response",
            "read_memory_file_response",
            "memory_history_response",
            "memory_commit_diff_response",
            "memory_file_at_ref_response",
            "enable_memfs_response",
            "memory_updated",
        ).forEach { type ->
            val frame = AppServerProtocol.decodeFrame("""{"type":"$type","request_id":"r","success":true}""").frame
            assertIs<AppServerInboundFrame.Unknown>(frame, type)
            assertNull(frame.runtime, type)
        }
    }
}
