package com.letta.mobile.data.workspace

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.ScriptedAppServerTransport
import com.letta.mobile.data.transport.appserver.requestId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AppServerWorkspaceFileSourceTest {
    private fun TestScope.source(
        maxViewChars: Int = AppServerWorkspaceFileSource.DEFAULT_MAX_VIEW_CHARS,
        respond: (JsonObject) -> List<String>,
    ): Pair<AppServerWorkspaceFileSource, ScriptedAppServerTransport> {
        val transport = ScriptedAppServerTransport(respond)
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope)
        return AppServerWorkspaceFileSource(client = { client }, requestId = { "$it-1" }, maxViewChars = maxViewChars) to transport
    }

    private fun readResponse(command: JsonObject, body: String) =
        """{"type":"read_file_response","request_id":"${command.requestId}","path":"/w/a.kt",$body}"""

    @Test
    fun searchSendsTheQueryWithinTheWorkingDirectory() = runTest {
        val (source, transport) = source { command ->
            listOf("""{"type":"search_files_response","request_id":"${command.requestId}","success":true,"files":[{"path":"src\\main.kt","type":"file"},{"path":"README.md","type":"file"}]}""")
        }
        val paths = source.search("ma", cwd = "/work/repo", limit = 25)

        assertEquals(listOf("src/main.kt", "README.md"), paths)
        val sent = transport.sent.single()
        assertEquals("search_files", (sent["type"] as JsonPrimitive).content)
        assertEquals("ma", (sent["query"] as JsonPrimitive).content)
        assertEquals("25", (sent["max_results"] as JsonPrimitive).content)
        assertEquals("/work/repo", (sent["cwd"] as JsonPrimitive).content)
    }

    @Test
    fun aTextFileReadsAsText() = runTest {
        val (source, _) = source { listOf(readResponse(it, """"content":"fun main() {}","encoding":"utf8","success":true""")) }
        assertEquals(WorkspaceFileContent.Text("/w/a.kt", "fun main() {}"), source.read("/w/a.kt"))
    }

    @Test
    fun aFileTheServerCannotDecodeIsBinary() = runTest {
        val (source, _) = source {
            listOf(readResponse(it, """"content":null,"success":false,"error":"File is not valid UTF-8 text: /w/a.kt. The file contains bytes that cannot be decoded as UTF-8.""""))
        }
        assertIs<WorkspaceFileContent.Binary>(source.read("/w/a.kt"))
    }

    @Test
    fun aFileWithNulBytesIsBinary() = runTest {
        val (source, _) = source { listOf(readResponse(it, """"content":"PK\u0000\u0003","success":true""")) }
        assertIs<WorkspaceFileContent.Binary>(source.read("/w/a.kt"))
    }

    @Test
    fun anOversizedFileIsNotShown() = runTest {
        val (source, _) = source(maxViewChars = 4) { listOf(readResponse(it, """"content":"0123456789","success":true""")) }
        assertEquals(WorkspaceFileContent.TooLarge("/w/a.kt", 10), source.read("/w/a.kt"))
    }

    @Test
    fun aMissingFileFailsWithAFriendlyReasonNotTheRawHostError() = runTest {
        val (source, _) = source {
            listOf(readResponse(it, """"content":null,"success":false,"error":"ENOENT: no such file or directory, open '/w/a.kt'""""))
        }
        val error = assertFailsWith<WorkspaceFileException> { source.read("/w/a.kt") }
        assertEquals(WorkspaceFileErrors.NOT_FOUND, error.message)
    }

    @Test
    fun anotherServerFailureKeepsItsReason() = runTest {
        val (source, _) = source { listOf(readResponse(it, """"content":null,"success":false,"error":"EACCES: permission denied"""")) }
        val error = assertFailsWith<WorkspaceFileException> { source.read("/w/a.kt") }
        assertEquals("EACCES: permission denied", error.message)
    }

    @Test
    fun fileResponsesDecodeAsUnknownFrames() {
        listOf("search_files_response", "read_file_response").forEach { type ->
            assertIs<AppServerInboundFrame.Unknown>(AppServerProtocol.decodeFrame("""{"type":"$type","request_id":"r"}""").frame, type)
        }
    }
}
