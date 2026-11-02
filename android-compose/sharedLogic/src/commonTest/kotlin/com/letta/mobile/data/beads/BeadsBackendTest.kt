package com.letta.mobile.data.beads

import com.letta.mobile.data.beads.wire.mutations.CompareAndSetMetadataResponse
import com.letta.mobile.data.beads.wire.mutations.CreateIssueRequest
import com.letta.mobile.data.beads.wire.mutations.IssuePatchBody
import com.letta.mobile.data.beads.wire.payloads.ApplyCreateItem
import com.letta.mobile.data.beads.wire.payloads.ApplyPatchBody
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeadsBackendTest {
    // Fixtures follow the pinned schema and Go handler shapes; they are not live captures.
    private val issue = """{"id":"bd-1","title":"First","priority":1,"created_at":"2026-01-01T00:00:00Z",""" +
        """"updated_at":"2026-01-01T00:00:00Z","dependency_count":0,"dependent_count":0,"comment_count":0}"""
    private val event = """{"seq":1,"ts":"2026-01-01T00:00:00Z","op":"delete","issue_id":"bd-1","issue":null}"""

    @Test
    fun responseFamiliesDecode() = runTest {
        val backend = connect { path ->
            when (path) {
                "/v0/beads/issues" -> """{"items":[$issue],"has_more":false}"""
                "/v0/beads/dependencies" -> """{"items":[{"issue_id":"bd-1","depends_on_id":"bd-2","type":"blocks",""" +
                    """"created_at":"2026-01-01T00:00:00Z"}],"missing":["bd-missing"]}"""
                "/v0/beads/ready" -> """{"items":[$issue],"has_more":false}"""
                "/v0/beads/events" -> """{"records":[$event],"head":1}"""
                else -> error("Unexpected path $path")
            }
        }
        val listed = backend.listIssues().items.single()
        assertEquals("First", listed.title)
        assertEquals(0L, listed.dependencyCount)
        val edges = backend.listDependencies(BeadsIssueId("bd-1"))
        assertEquals(listOf("bd-missing"), edges.missing)
        assertEquals("bd-2", edges.items.single().dependsOnId)
        assertEquals("bd-1", backend.listReadyWork().items.single().id)
        val eventRecord = backend.listEvents(0).records.single()
        assertEquals(1L, eventRecord.seq)
        assertEquals(JsonNull, eventRecord.issue)
    }

    @Test
    fun detailRetainsIssueShapedDependenciesAndRequiredEventNull() = runTest {
        val dependency = issue.dropLast(1) + ",\"dependency_type\":\"blocks\"}"
        val detail = issue.dropLast(1) + ",\"revision\":\"r1\",\"dependencies\":[$dependency]}"
        val backend = connect { path ->
            when (path) {
                "/v0/beads/issues/bd-1" -> detail
                "/v0/beads/events" -> """{"records":[${event.replace(",\"issue\":null", "")}],"head":1}"""
                else -> error("Unexpected path $path")
            }
        }
        val decoded = backend.getIssue(BeadsIssueId("bd-1"))
        assertEquals("r1", decoded.revision)
        assertEquals("bd-1", decoded.dependencies!!.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertFailsWith<BeadsProtocolException> { backend.listEvents(0) }
    }

    @Test
    fun sharedCreateFieldsPreserveSingleAndBatchShapes() {
        val single = wireJson.encodeToJsonElement(CreateIssueRequest(actor = "alice", title = "One")).jsonObject
        val batch = wireJson.encodeToJsonElement(ApplyCreateItem(key = "first", title = "Two")).jsonObject
        assertEquals("alice", single.getValue("actor").jsonPrimitive.content)
        assertFalse("key" in single)
        assertEquals("first", batch.getValue("key").jsonPrimitive.content)
        assertFalse("actor" in batch)
    }

    @Test
    fun missingCapabilityMakesNoOperationRequest() = runTest {
        var calls = 0
        val allButWatch = BeadsOperation.entries.mapNotNull { it.capability }.filter { it != "events.watch" }
        val context = context(allButWatch + "project.enforce")
        val client = MockEngine { calls++; respond(context) }
        val backend = KtorBeadsBackend.connect(client, "https://example.test", "secret")
        assertEquals(1, calls)
        assertEquals("1.3.2", backend.context.bdVersion)
        assertEquals("dolt", backend.context.backend)
        assertEquals(39, backend.context.capabilities.size)
        val failure = runCatching { backend.watchEvents(0).toList() }.exceptionOrNull()
        assertEquals(1, calls, "An unsupported operation must make no HTTP request")
        assertTrue(failure is BeadsUnsupportedOperation)
    }

    @Test
    fun missingCapabilityBlocksOrdinaryRequests() = runTest {
        var calls = 0
        val capabilities = BeadsOperation.entries.mapNotNull { it.capability }.filter { it != "config.get" }
        val engine = MockEngine { calls++; respond(context(capabilities)) }
        val backend = KtorBeadsBackend.connect(engine, "https://example.test", "secret")
        val failure = runCatching { backend.getSetting("key") }.exceptionOrNull()
        assertEquals(1, calls, "An unsupported operation must make no HTTP request")
        assertTrue(failure is BeadsUnsupportedOperation)
        backend.close()
    }

    @Test
    fun streamsTwoEventsAndCancels() = runTest {
        val second = event.replace("\"seq\":1", "\"seq\":2")
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("retry: 3000\n\nid: 1\ndata: $event\n\nid: 2\ndata: $second\n\n")
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/v0/beads/context") {
                respond(context(BeadsOperation.entries.mapNotNull { it.capability }))
            } else {
                assertEquals("0", request.headers["Last-Event-ID"])
                assertEquals("0", request.url.parameters["since"])
                respond(channel)
            }
        }
        val backend = KtorBeadsBackend.connect(engine, "https://example.test", "secret")
        assertEquals(listOf(1L, 2L), backend.watchEvents(0, lastEventId = 0).take(2).toList().map { it.seq })
        assertTrue(channel.isClosedForWrite)
        backend.close()
    }

    @Test
    fun truncatedStreamFailsInsteadOfLosingTheGap() = runTest {
        val backend = connect { "event: truncated\ndata: {\"code\":\"events_truncated\"}\n\n" }
        assertFailsWith<BeadsProtocolException> { backend.watchEvents(0).toList() }
    }

    @Test
    fun sendsBearerAndSerializesMutationAndFilters() = runTest {
        val seen = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            seen.add(request)
            when (request.url.encodedPath) {
                "/v0/beads/context" -> respond(context(BeadsOperation.entries.mapNotNull { it.capability }))
                "/v0/beads/issues" -> {
                    if (request.method.value == "POST") respond(issue)
                    else respond("""{"items":[$issue],"has_more":false}""")
                }
                else -> error("Unexpected request")
            }
        }
        val backend = KtorBeadsBackend.connect(engine, "https://example.test", "secret")
        backend.listIssues(mapOf("label" to listOf("one", "two")))
        backend.createIssue(CreateIssueRequest(actor = "alice", title = "First"))
        assertTrue(seen.all { it.headers[HttpHeaders.Authorization] == "Bearer secret" })
        assertEquals(listOf("one", "two"), seen[1].url.parameters.getAll("label"))
        val body = (seen[2].body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val json = wireJson.parseToJsonElement(body).jsonObject
        assertEquals("alice", json.getValue("actor").jsonPrimitive.content)
        assertEquals("First", json.getValue("title").jsonPrimitive.content)
        backend.close()
    }

    @Test
    fun patchesDistinguishOmittedFromExplicitNull() {
        val patch = IssuePatchBody(estimatedMinutes = JsonNull, dueAt = JsonPrimitive("2026-02-01T00:00:00Z"))
        val encoded = wireJson.encodeToJsonElement(patch).jsonObject
        assertEquals(JsonNull, encoded.getValue("estimated_minutes"))
        assertEquals("2026-02-01T00:00:00Z", encoded.getValue("due_at").jsonPrimitive.content)
        assertFalse("external_ref" in encoded)
        val batch = wireJson.encodeToJsonElement(ApplyPatchBody(externalRef = JsonNull)).jsonObject
        assertEquals(JsonNull, batch.getValue("external_ref"))
        assertFalse("defer_until" in batch)
        val present = wireJson.decodeFromJsonElement<CompareAndSetMetadataResponse>(
            wireJson.parseToJsonElement("""{"swapped":false,"current":null}"""),
        )
        val absent = wireJson.decodeFromJsonElement<CompareAndSetMetadataResponse>(
            wireJson.parseToJsonElement("""{"swapped":false}"""),
        )
        assertEquals(JsonNull, present.current)
        assertEquals(null, absent.current)
    }

    @Test
    fun unknownWireValuesHaveFallbacks() {
        assertEquals(BeadsEventKind.UNKNOWN, BeadsEventKind.fromWire("future_op"))
        assertEquals(BeadsIssueStatus.UNKNOWN, BeadsIssueStatus.fromWire("custom"))
    }

    @Test
    fun unauthorizedAndTransportErrorsNeverExposeToken() = runTest {
        val token = "secret-sentinel"
        val unauthorized = MockEngine { respond("echo $token", HttpStatusCode.Unauthorized) }
        val authError = assertFailsWith<BeadsHttpException> {
            KtorBeadsBackend.connect(unauthorized, "https://example.test", token)
        }
        assertEquals(401, authError.status)
        assertFalse(authError.toString().contains(token))
        val failing = MockEngine { error("failed $token") }
        val failure = assertFailsWith<BeadsTransportException> {
            KtorBeadsBackend.connect(failing, "https://example.test", token)
        }
        assertFalse(failure.toString().contains(token))
        assertEquals(null, failure.cause)
    }

    private suspend fun connect(answer: (String) -> String): BeadsBackend {
        val client = MockEngine { request ->
            val path = request.url.encodedPath
            respond(if (path == "/v0/beads/context") context(BeadsOperation.entries.mapNotNull { it.capability }) else answer(path))
        }
        return KtorBeadsBackend.connect(client, "https://example.test", "secret")
    }

    private fun context(capabilities: List<String>): String =
        """{"api_version":"v0","bd_version":"1.3.2","schema_version":1,"backend":"dolt",""" +
            """"dolt_mode":"server","database":"beads","project_id":"p",""" +
            """"capabilities":[${capabilities.joinToString(",") { "\"$it\"" }}]}"""
}
