package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.secrets.AgentSecretsRedaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** letta-mobile-bzvro.37: the workspace relay's wire contract, allowlist and caps. */
class WorkspaceRelayTest {
    private val commands: List<AppServerCommand> = listOf(
        AppServerMemfsCommand.ListMemory("c", "agent-1"),
        AppServerMemfsCommand.ReadMemoryFile("c", "agent-1", "system/persona.md"),
        AppServerMemfsCommand.MemoryHistory("c", "agent-1", filePath = "system/persona.md", limit = 20),
        AppServerMemfsCommand.MemoryCommitDiff("c", "agent-1", "0123abcd"),
        AppServerMemfsCommand.MemoryFileAtRef("c", "agent-1", "system/persona.md", "HEAD~1"),
        AppServerMemfsCommand.EnableMemfs("c", "agent-1"),
        AppServerCommand.WriteMemoryFile("c", "agent-1", "notes/todo.md", "content"),
        AppServerSecretCommand.SecretList("c", "agent-1"),
        AppServerSecretCommand.SecretApply("c", "agent-1", set = mapOf("API_KEY" to "v"), unset = listOf("OLD")),
        AppServerFileCommand.SearchFiles("c", "main", maxResults = 25, cwd = "/repo"),
        AppServerFileCommand.ReadFile("c", "/repo/README.md"),
    )

    @Test
    fun everyRelayedCommandHasExactlyOneMethodAndRoundTripsWithTheHostsRequestId() {
        assertEquals(WorkspaceRelayMethod.entries.size, commands.map(WorkspaceRelay::methodFor).toSet().size)
        commands.forEach { command ->
            val method = WorkspaceRelay.methodFor(command)
            val params = WorkspaceRelay.encodeParams(command)
            assertNull(params["type"], "${method.method} params carry no type")
            assertNull(params["request_id"], "${method.method} params carry no request id")
            val decoded = WorkspaceRelay.decodeCommand(method, params, "host-1")
            assertEquals(AppServerProtocol.encodeCommand(command).replace("\"c\"", "\"host-1\""), AppServerProtocol.encodeCommand(decoded))
        }
    }

    @Test
    fun theMethodFixesTheCommandTypeSoParamsCannotSmuggleAnother() {
        val params = buildJsonObject {
            put("type", "secret_list")
            put("request_id", "client-chosen")
            put("agent_id", "agent-1")
            put("path", "system/persona.md")
        }
        val decoded = WorkspaceRelay.decodeCommand(WorkspaceRelayMethod.ReadMemoryFile, params, "host-1")
        assertEquals(AppServerMemfsCommand.ReadMemoryFile("host-1", "agent-1", "system/persona.md"), decoded)
    }

    @Test
    fun commandsOutsideTheAllowlistAreNotRelayed() {
        assertNull(WorkspaceRelayMethod.forMethod("agent.delete"))
        assertNull(WorkspaceRelayMethod.forMethod("memfs.delete"))
        assertFailsWith<WorkspaceRelayException> {
            WorkspaceRelay.methodFor(AppServerCommand.DeleteMemoryFile("c", "agent-1", "system/persona.md"))
        }
    }

    @Test
    fun memoryPathsMustStayInsideTheAgentsMemoryRoot() {
        listOf("../escape.md", "system/../../etc/passwd", "/etc/passwd", "\\\\server\\share", "C:/x.md", "", "a\u0000b").forEach { path ->
            assertFailsWith<WorkspaceRelayException>(path) {
                decode(WorkspaceRelayMethod.ReadMemoryFile, buildJsonObject { put("agent_id", "agent-1"); put("path", path) })
            }
        }
    }

    @Test
    fun agentIdsRefsLimitsAndSearchesAreCapped() {
        assertRejected(WorkspaceRelayMethod.ListMemory, buildJsonObject { put("agent_id", "agent/../2") })
        assertRejected(WorkspaceRelayMethod.ListMemory, buildJsonObject { put("agent_id", "a".repeat(WorkspaceRelay.MAX_ID_CHARS + 1)) })
        assertRejected(WorkspaceRelayMethod.MemoryHistory, buildJsonObject { put("agent_id", "a"); put("limit", WorkspaceRelay.MAX_HISTORY_LIMIT + 1) })
        assertRejected(WorkspaceRelayMethod.MemoryCommitDiff, buildJsonObject { put("agent_id", "a"); put("sha", "abc; rm -rf /") })
        assertRejected(WorkspaceRelayMethod.SearchFiles, buildJsonObject { put("query", "x"); put("max_results", 10_000) })
        assertRejected(WorkspaceRelayMethod.SearchFiles, buildJsonObject { put("query", "q".repeat(WorkspaceRelay.MAX_QUERY_CHARS + 1)) })
        assertRejected(WorkspaceRelayMethod.ReadFile, buildJsonObject { put("path", "/x"); put("encoding", "latin1") })
        assertRejected(WorkspaceRelayMethod.WriteMemoryFile, buildJsonObject {
            put("agent_id", "a"); put("path", "x.md"); put("content", "c".repeat(WorkspaceRelay.MAX_WRITE_CHARS + 1))
        })
    }

    @Test
    fun secretBatchesAreCappedAndTheirErrorsNeverQuoteAValue() {
        val tooLong = WorkspaceRelay.decodeCommandFailure(
            WorkspaceRelayMethod.SecretApply,
            buildJsonObject {
                put("agent_id", "a")
                put("set", buildJsonObject { put("API_KEY", "S3CRET-" + "v".repeat(WorkspaceRelay.MAX_SECRET_VALUE_CHARS)) })
            },
        )
        assertFalse(tooLong.contains("S3CRET"), tooLong)
        val badKey = WorkspaceRelay.decodeCommandFailure(
            WorkspaceRelayMethod.SecretApply,
            buildJsonObject { put("agent_id", "a"); put("set", buildJsonObject { put("not a key", "S3CRET") }) },
        )
        assertFalse(badKey.contains("S3CRET"), badKey)
        // A value of the wrong shape fails decoding; kotlinx would quote the input, the relay must not.
        val malformed = WorkspaceRelay.decodeCommandFailure(
            WorkspaceRelayMethod.SecretApply,
            Json.parseToJsonElement("""{"agent_id":"a","set":{"API_KEY":{"nested":"S3CRET"}}}""").jsonObject,
        )
        assertFalse(malformed.contains("S3CRET"), malformed)
        assertEquals("invalid params for secret.apply", malformed)
        val tooMany = buildJsonObject {
            put("agent_id", "a")
            put("set", buildJsonObject { repeat(WorkspaceRelay.MAX_SECRET_KEYS + 1) { put("K$it", "v") } })
        }
        assertRejected(WorkspaceRelayMethod.SecretApply, tooMany)
    }

    @Test
    fun resultsAreCappedAndCarryTheClientsRequestId() {
        val frame = buildJsonObject { put("type", "read_file_response"); put("request_id", "host-1"); put("success", true) }
        val decoded = WorkspaceRelay.decodeResult(WorkspaceRelay.encodeResult(listOf(frame, frame)), "client-7")
        assertEquals(listOf("client-7", "client-7"), decoded.map { it["request_id"]?.jsonPrimitive?.content })
        val huge = buildJsonObject { put("content", "x".repeat(WorkspaceRelay.MAX_RESULT_BYTES)) }
        assertFailsWith<WorkspaceRelayException> { WorkspaceRelay.encodeResult(listOf(huge)) }
        assertFailsWith<WorkspaceRelayException> { WorkspaceRelay.decodeResult(JsonPrimitive("nope"), "c") }
    }

    @Test
    fun readsAreRetrySafeAndWritesAreNot() {
        val reads = WorkspaceRelayMethod.entries.filter { it.isRead }.map { it.method }.toSet()
        assertTrue("memfs.list" in reads && "secret.list" in reads && "workspace.read_file" in reads)
        assertFalse("memfs.write" in reads || "memfs.enable" in reads || "secret.apply" in reads)
    }

    @Test
    fun redactionBlanksSecretValuesInRelayedEnvelopes() {
        val request = Json.parseToJsonElement(
            """{"type":"admin_rpc","request_id":"r","method":"secret.apply","params":{"agent_id":"a","set":{"API_KEY":"S3CRET"}}}""",
        ).jsonObject
        val response = Json.parseToJsonElement(
            """{"type":"admin_rpc_response","request_id":"r","success":true,"result":{"frames":[""" +
                """{"type":"secret_list_response","request_id":"r","secrets":[{"key":"API_KEY","value":"S3CRET"}],"success":true}]}}""",
        ).jsonObject
        val other = Json.parseToJsonElement("""{"type":"admin_rpc","method":"memfs.read","params":{"path":"S3CRET.md"}}""").jsonObject
        assertFalse(AgentSecretsRedaction.redact(request).toString().contains("S3CRET"))
        assertFalse(AgentSecretsRedaction.redact(response).toString().contains("S3CRET"))
        assertTrue(AgentSecretsRedaction.redact(response).toString().contains("API_KEY"), "keys stay visible")
        assertEquals(other, AgentSecretsRedaction.redact(other))
    }

    private fun decode(method: WorkspaceRelayMethod, params: JsonObject): AppServerCommand =
        WorkspaceRelay.decodeCommand(method, params, "host-1")

    private fun assertRejected(method: WorkspaceRelayMethod, params: JsonObject) {
        assertFailsWith<WorkspaceRelayException>(params.toString().take(80)) { decode(method, params) }
    }

    private fun WorkspaceRelay.decodeCommandFailure(method: WorkspaceRelayMethod, params: JsonObject): String =
        assertFailsWith<WorkspaceRelayException> { decodeCommand(method, params, "host-1") }.message.orEmpty()
}
