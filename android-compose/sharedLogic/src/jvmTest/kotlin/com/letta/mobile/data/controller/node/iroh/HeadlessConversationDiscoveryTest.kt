package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import com.letta.mobile.util.attr
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Models the local server's scan-once store and strict retrieve using the actual directory layout. */
class HeadlessConversationDiscoveryTest {
    @Test
    fun externalConversationAppearsInHeldAgentPageAndVisibleRecents() = runTest {
        val root = createTempDirectory("headless-conversation").toFile()
        try {
            LocalBackendFixtureStore.writeAgent(root, "agent-1", "Agent")
            File(root, "conversations").mkdirs()
            val server = ScanOnceClient(root)
            val router = AdminRpcRouter()
            ConversationAdminHandlers.register(router, NativeReadTiers(
                nativeClient = server,
                localBackendStore = LocalBackendAdminStore(root),
            ))
            assertEquals(emptyList(), ids(dispatch(router, mapOf("agent_id" to "agent-1"))))
            writeConversation(root, "local-conv-anonymous", "agent-1")
            // Allow the bounded discovery interval to elapse after the initial request.
            Thread.sleep(6_000)
            assertEquals(listOf("local-conv-anonymous"), ids(dispatch(router, mapOf("agent_id" to "agent-1"))))
            assertEquals(listOf("local-conv-anonymous"), ids(dispatch(router, mapOf("visible_only" to true))))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun scopedAndUnauthenticatedRequestsDoNotExposeAnotherAgentsPrimedRecord() = runTest {
        val root = createTempDirectory("headless-scope").toFile()
        try {
            LocalBackendFixtureStore.writeAgent(root, "agent-1", "Agent")
            LocalBackendFixtureStore.writeAgent(root, "agent-2", "Other")
            File(root, "conversations").mkdirs()
            val server = ScanOnceClient(root)
            server.conversationList(AppServerCommand.ConversationList("initial"))
            writeConversation(root, "local-conv-other", "agent-2")
            val router = AdminRpcRouter()
            ConversationAdminHandlers.register(router, NativeReadTiers(
                nativeClient = server,
                localBackendStore = LocalBackendAdminStore(root),
            ))
            assertEquals(emptyList(), ids(dispatch(router, emptyMap(), AdminRpcRequestContext.Unauthenticated)))
            assertEquals(0, server.retrieveCalls)
            val scoped = AdminRpcRequestContext(authenticated = true, authorizedAgentIds = setOf("agent-1"))
            assertEquals(emptyList(), ids(dispatch(router, mapOf("visible_only" to true), scoped)))
            assertEquals(1, server.retrieveCalls)
            assertEquals(emptyList(), ids(dispatch(router, emptyMap(), scoped)))
            assertEquals(listOf("local-conv-other"), ids(dispatch(router, emptyMap())))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retrieveFailureDoesNotFailTheListOrTripItsCircuit() = runTest {
        assertListSurvivesRetrieveFailure(timeout = false)
    }

    @Test
    fun retrieveTimeoutDoesNotTripListCircuit() = runTest {
        assertListSurvivesRetrieveFailure(timeout = true)
    }

    private suspend fun assertListSurvivesRetrieveFailure(timeout: Boolean) {
        val root = createTempDirectory("headless-failure").toFile()
        val previousEnabled = Telemetry.enabled.get()
        val recorder = TelemetryRecorder()
        val recording = Telemetry.installRecorder(recorder)
        try {
            Telemetry.enabled.set(true)
            File(root, "conversations").mkdirs()
            val server = ScanOnceClient(root)
            server.conversationList(AppServerCommand.ConversationList("initial"))
            writeConversation(root, "local-conv-failure", "agent-1")
            server.failRetrieve = !timeout
            server.timeoutRetrieve = timeout
            val router = AdminRpcRouter()
            ConversationAdminHandlers.register(router, NativeReadTiers(
                nativeClient = server,
                localBackendStore = LocalBackendAdminStore(root),
            ))
            assertEquals(emptyList(), ids(dispatch(router, emptyMap())))
            assertTrue(server.retrieveCalls > 0)
            assertEquals(emptyList(), ids(dispatch(router, emptyMap())))
            if (timeout) assertEquals(1, server.retrieveCalls)
            val selected = recorder.snapshot().filter {
                it.tag == "IrohAdminRoute" && it.name == "selected" &&
                    it.attrs["method"] == ("method" attr "conversation.discovery").second
            }
            assertTrue(selected.any { it.attrs["outcome"] == ("outcome" attr "error").second })
            assertFalse(selected.any { it.attrs["outcome"] == ("outcome" attr "success").second })
        } finally {
            recording.close()
            Telemetry.enabled.set(previousEnabled)
            root.deleteRecursively()
        }
    }

    private suspend fun dispatch(
        router: AdminRpcRouter,
        params: Map<String, Any>,
        context: AdminRpcRequestContext = AdminRpcRequestContext.Authenticated,
    ): String = router.dispatch(
        AdminRpcInvocation("test", "conversation.list", buildJsonObject {
            params.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Boolean -> put(key, value)
                }
            }
        }, context),
    )

    private fun ids(response: String): List<String> = Json.parseToJsonElement(response).jsonObject
        .getValue("result").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    private fun writeConversation(root: File, id: String, agent: String) {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("conversation:$id".toByteArray())
        val dir = File(root, "conversations/$encoded").apply { mkdirs() }
        File(dir, "conversation.json").writeText(
            """{"id":"$id","agent_id":"$agent","created_at":"2026-01-01T00:00:00.000Z",""" +
                """"updated_at":"2026-01-01T00:00:00.000Z","last_message_at":null,"summary":null,""" +
                """"archived":false,"archived_at":null,"in_context_message_ids":[]}""",
        )
        File(dir, "messages.jsonl").writeText("")
    }

    private class ScanOnceClient(private val root: File) : AppServerClient by FakeNativeClient() {
        private val records = linkedMapOf<String, JsonObject>()
        private var scanned = false
        var retrieveCalls = 0
        var failRetrieve = false
        var timeoutRetrieve = false

        override suspend fun conversationList(command: AppServerCommand.ConversationList): AppServerInboundFrame.ConversationListResponse {
            scanOnce()
            refreshRecords()
            return AppServerInboundFrame.ConversationListResponse(command.requestId, true, JsonArray(page(command.query)))
        }

        private fun scanOnce() {
            if (scanned) return
            scanned = true
            File(root, "conversations").listFiles().orEmpty().forEach { dir ->
                readRecord(dir)?.let { records[it.getValue("id").jsonPrimitive.content] = it }
            }
        }

        private fun refreshRecords() {
            records.keys.toList().forEach { id ->
                readRecord(conversationDirectory(id))?.takeIf { it["id"]?.jsonPrimitive?.contentOrNull == id }
                    ?.let { records[id] = it }
            }
        }

        private fun page(query: JsonObject?): List<JsonObject> {
            val after = queryString(query, "after")?.takeIf { it.isNotEmpty() }
            val limit = (query?.get("limit") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull() ?: 20
            val filtered = records.values.filter { matches(it, query) }.sortedByDescending(::recency)
            val cursor = filtered.indexOfFirst { it.getValue("id").jsonPrimitive.content == after }
            val remaining = if (cursor < 0) filtered else filtered.drop(cursor + 1)
            val count = if (limit >= 0) limit else (remaining.size + limit).coerceAtLeast(0)
            return remaining.take(count).map(::publicRow)
        }

        private fun matches(row: JsonObject, query: JsonObject?): Boolean {
            val id = row.getValue("id").jsonPrimitive.content
            val agent = queryString(query, "agent_id")?.takeIf { it.isNotEmpty() }
            val includeHidden = (query?.get("include_hidden") as? JsonPrimitive)?.booleanOrNull == true
            val hidden = (row["hidden"] as? JsonPrimitive)?.booleanOrNull == true
            if (id == "default") return false
            if (!includeHidden && hidden) return false
            if (agent != null && row["agent_id"]?.jsonPrimitive?.contentOrNull != agent) return false
            return matchesSearch(row, queryString(query, "summary_search")?.trim()?.lowercase().orEmpty())
        }

        private fun matchesSearch(row: JsonObject, search: String): Boolean {
            val id = row.getValue("id").jsonPrimitive.content
            val summary = row["summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return search.isEmpty() || id.lowercase().contains(search) || summary.lowercase().contains(search)
        }

        private fun recency(row: JsonObject): String = listOf("last_message_at", "updated_at", "created_at")
            .firstNotNullOfOrNull { row[it]?.jsonPrimitive?.contentOrNull }.orEmpty()

        private fun publicRow(row: JsonObject): JsonObject =
            if ((row["agent_free"] as? JsonPrimitive)?.booleanOrNull == true) {
                JsonObject(row + ("agent_id" to JsonNull))
            } else {
                row
            }

        private fun queryString(query: JsonObject?, key: String): String? = (query?.get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull

        override suspend fun conversationRetrieve(
            command: AppServerCommand.ConversationRetrieve,
        ): AppServerInboundFrame.ConversationRetrieveResponse {
            retrieveCalls++
            if (timeoutRetrieve) delay(3_000)
            if (failRetrieve) error("simulated transport failure")
            val record = readRecord(conversationDirectory(command.conversationId))
                ?.takeIf { it["id"]?.jsonPrimitive?.contentOrNull == command.conversationId }
                ?: records[command.conversationId]
            if (record != null) records[command.conversationId] = record
            return AppServerInboundFrame.ConversationRetrieveResponse(command.requestId, record != null, record?.let(::publicRow))
        }

        private fun conversationDirectory(id: String): File {
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("conversation:$id".toByteArray())
            return File(root, "conversations/$encoded")
        }

        private fun readRecord(dir: File): JsonObject? {
            val row = readValidTestConversation(dir) ?: return null
            val agentFree = (row["agent_free"] as? JsonPrimitive)?.booleanOrNull == true
            if (row["agent_id"] != JsonNull || !agentFree) return row
            val id = row.getValue("id").jsonPrimitive.content
            return JsonObject(row + ("agent_id" to JsonPrimitive("agent-free:$id")))
        }
    }
}

/** LocalStore accepts only records with an id and a string owner, or an agent-free null owner. */
internal fun readValidTestConversation(dir: File): JsonObject? = runCatching {
    val row = Json.parseToJsonElement(File(dir, "conversation.json").readText()).jsonObject
    val id = (row["id"] as? JsonPrimitive)?.contentOrNull
    val owner = row["agent_id"]
    val agentFree = (row["agent_free"] as? JsonPrimitive)?.booleanOrNull == true
    val stringOwner = owner is JsonPrimitive && owner.isString
    val agentFreeWithoutOwner = owner == JsonNull && agentFree
    require(!id.isNullOrEmpty())
    require(stringOwner || agentFreeWithoutOwner)
    row
}.getOrNull()
