package com.letta.mobile.data.controller.node.iroh.shellstate

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AgentScopeGuard
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateSchema
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateChange
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.shellstate.wire.toJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** The shell-state v1 host contract: reads, compare-and-set, races, corruption, pushes and the capability gate. */
class ShellStateRoutesTest {
    private val pushed = mutableListOf<String>()
    private val publisher = ShellStatePublisher(CoroutineScope(Dispatchers.Unconfined)) { Instant.EPOCH }.apply {
        attach { frame -> pushed += frame }
    }
    private val router = routerOver(InMemoryShellStatePersistence())

    private fun routerOver(persistence: ShellStatePersistence) = AdminRpcRouter().also {
        ShellStateRouteSet(ShellStateStore(persistence) { ShellStateFixtures.CLOCK_MS }, publisher).register(it)
    }

    @Test
    fun anEmptyHostAnswersRevisionZero() = runBlocking {
        val reply = call(ShellStateWire.GET, JsonObject(emptyMap()))
        assertEquals(0L, reply.getValue("document_revision").jsonPrimitive.long)
        assertEquals(ShellStateSchema.VERSION, reply.getValue("schema_version").jsonPrimitive.long.toInt())
        assertTrue(reply.getValue("entries").jsonObject.isEmpty())
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun anAcceptedProposalBumpsTheDocumentRevisionRecordsTheActorAndPushesTheWholeDocument() = runBlocking {
        val reply = call(ShellStateWire.PROPOSE, propose(change("appearance.theme", JsonPrimitive("dark"), 0)), endpoint = "endpoint-a")
        assertEquals(1L, reply.getValue("document_revision").jsonPrimitive.long)
        val entry = reply.getValue("entries").jsonObject.getValue("appearance.theme").jsonObject
        assertEquals(1L, entry.getValue("revision").jsonPrimitive.long)
        assertEquals(ShellStateFixtures.CLOCK_MS, entry.getValue("updated_at_ms").jsonPrimitive.long)
        assertEquals(
            buildJsonObject { put("type", "user"); put("client_id", "endpoint-a") },
            entry.getValue("updated_by"),
        )
        val frame = Json.parseToJsonElement(pushed.single()).jsonObject
        assertEquals(ShellStateWire.FRAME_TYPE, frame.getValue("type").jsonPrimitive.content)
        assertEquals(reply.getValue("entries"), frame.getValue("entries"))
        assertEquals(1L, frame.getValue("document_revision").jsonPrimitive.long)
    }

    @Test
    fun theActorIsTheServingConnectionNeverAValueTheCallerSent() = runBlocking {
        val forged = propose(change("a", JsonPrimitive(1), 0)).toMutableMap().apply {
            put("actor", buildJsonObject { put("type", "agent"); put("agent_id", "agent-evil") })
        }
        val reply = call(ShellStateWire.PROPOSE, JsonObject(forged), endpoint = "endpoint-a")
        val actor = reply.getValue("entries").jsonObject.getValue("a").jsonObject.getValue("updated_by").jsonObject
        assertEquals("user", actor.getValue("type").jsonPrimitive.content)
        assertEquals("endpoint-a", actor.getValue("client_id").jsonPrimitive.content)
    }

    @Test
    fun anInProcessCallerIsRecordedAsTheHostLocalClient() = runBlocking {
        val reply = call(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), 0)), endpoint = null)
        val actor = reply.getValue("entries").jsonObject.getValue("a").jsonObject.getValue("updated_by").jsonObject
        assertEquals(ShellStateWire.LOCAL_CLIENT_ID, actor.getValue("client_id").jsonPrimitive.content)
    }

    @Test
    fun writersOfDifferentEntriesNeverConflictAndASameEntryStaleWriterDoes() = runBlocking {
        call(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), 0), change("b", JsonPrimitive(1), 0)))
        call(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(2), 1)))
        // b is still at revision 1 for a writer who last saw it there, whatever happened to a.
        val b = call(ShellStateWire.PROPOSE, propose(change("b", JsonPrimitive(9), 1)))
        assertEquals(3L, b.getValue("document_revision").jsonPrimitive.long)
        assertEquals(ShellStateWire.REVISION_CONFLICT, failure(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(3), 1))))
    }

    @Test
    fun aProposalIsAllOrNothing() = runBlocking {
        call(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), 0)))
        val conflict = failure(
            ShellStateWire.PROPOSE,
            propose(change("b", JsonPrimitive(1), 0), change("a", JsonPrimitive(2), 0)),
        )
        assertEquals(ShellStateWire.REVISION_CONFLICT, conflict)
        val now = call(ShellStateWire.GET, JsonObject(emptyMap()))
        assertEquals(setOf("a"), now.getValue("entries").jsonObject.keys)
        assertEquals(1, pushed.size)
    }

    @Test
    fun aRemovedEntryNeverReusesARevision() = runBlocking {
        call(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), 0)))
        call(ShellStateWire.PROPOSE, propose(change("a", null, 1)))
        val again = call(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), 0)))
        assertEquals(3L, again.getValue("entries").jsonObject.getValue("a").jsonObject.getValue("revision").jsonPrimitive.long)
    }

    @Test
    fun anUnchangedProposalKeepsTheRevisionAndIsNotPushed() = runBlocking {
        call(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), 0)))
        val same = call(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), 1)))
        assertEquals(1L, same.getValue("document_revision").jsonPrimitive.long)
        assertEquals(1, pushed.size)
    }

    @Test
    fun twoProposersRacingOnTheSameEntryGetOneAcceptanceAndOneConflict() = runBlocking {
        val store = ShellStateStore(InMemoryShellStatePersistence())
        val actor = ShellStateActor.User("endpoint-a")
        val results = (1..2).map { n ->
            async(Dispatchers.Default) {
                runCatching { store.propose(listOf(ShellStateChange("a", JsonPrimitive(n), 0)), actor) }
            }
        }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        val failure = results.single { it.isFailure }.exceptionOrNull() as ShellStateRefusal
        assertEquals(ShellStateWire.REVISION_CONFLICT, failure.code)
        assertEquals(1L, store.snapshot().documentRevision)
    }

    @Test
    fun manyConcurrentWritersIssueEveryRevisionExactlyOnce() = runBlocking {
        val path = Files.createTempDirectory("shell-state-race").resolve("shell-state.json")
        val store = ShellStateStore(FileShellStatePersistence(path))
        val actor = ShellStateActor.User("endpoint-a")
        (1..20).map { n ->
            async(Dispatchers.Default) { store.propose(listOf(ShellStateChange("key-$n", JsonPrimitive(n), 0)), actor) }
        }.awaitAll()
        val now = store.snapshot()
        assertEquals(20L, now.documentRevision)
        assertEquals((1L..20L).toList(), now.entries.values.map { it.revision }.sorted())
    }

    @Test
    fun malformedProposalsAreInvalid() = runBlocking {
        val invalid = ShellStateWire.INVALID_REQUEST
        assertEquals(invalid, failure(ShellStateWire.PROPOSE, JsonObject(emptyMap())))
        assertEquals(invalid, failure(ShellStateWire.PROPOSE, JsonObject(mapOf(ShellStateWire.CHANGES to JsonArray(emptyList())))))
        assertEquals(invalid, failure(ShellStateWire.PROPOSE, propose(change("Upper", JsonPrimitive(1), 0))))
        assertEquals(invalid, failure(ShellStateWire.PROPOSE, propose(change("", JsonPrimitive(1), 0))))
        assertEquals(invalid, failure(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), -1))))
        assertEquals(invalid, failure(ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), 0), change("a", JsonPrimitive(2), 0))))
        val longKey = "k".repeat(ShellStateWire.MAX_KEY_CHARS + 1)
        assertEquals(invalid, failure(ShellStateWire.PROPOSE, propose(change(longKey, JsonPrimitive(1), 0))))
        val tooMany = (0..ShellStateWire.MAX_CHANGES).map { change("key-$it", JsonPrimitive(it), 0) }
        assertEquals(invalid, failure(ShellStateWire.PROPOSE, propose(*tooMany.toTypedArray())))
        val notAnObject = JsonObject(mapOf(ShellStateWire.CHANGES to JsonArray(listOf(JsonPrimitive("a")))))
        assertEquals(invalid, failure(ShellStateWire.PROPOSE, notAnObject))
    }

    @Test
    fun anOversizedValueOrDocumentIsTooLargeAndWritesNothing() = runBlocking {
        val big = JsonPrimitive("x".repeat(ShellStateWire.MAX_VALUE_BYTES))
        assertEquals(ShellStateWire.TOO_LARGE, failure(ShellStateWire.PROPOSE, propose(change("a", big, 0))))
        val fits = JsonPrimitive("x".repeat(ShellStateWire.MAX_VALUE_BYTES - 2))
        var accepted = 0
        while (accepted < ShellStateWire.MAX_ENTRIES) {
            val params = propose(change("key-$accepted", fits, 0))
            val response = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", ShellStateWire.PROPOSE, params))).jsonObject
            if (!response.getValue("success").jsonPrimitive.boolean) {
                assertEquals(ShellStateWire.TOO_LARGE, response["error"]?.jsonPrimitive?.content)
                break
            }
            accepted += 1
        }
        assertTrue(accepted > 1 && accepted < ShellStateWire.MAX_ENTRIES, "the document cap stops it after $accepted entries")
        val now = call(ShellStateWire.GET, JsonObject(emptyMap()))
        assertEquals(accepted, now.getValue("entries").jsonObject.size)
        assertEquals(accepted.toLong(), now.getValue("document_revision").jsonPrimitive.long)
    }

    @Test
    fun agentScopedPeersAreRefusedBothMethods() = runBlocking {
        val scoped = AdminRpcRequestContext(authenticated = true, authorizedAgentIds = setOf("agent-a"))
        val proposal = propose(change("a", JsonPrimitive(1), 0))
        listOf(ShellStateWire.GET to null, ShellStateWire.PROPOSE to proposal).forEach { (method, params) ->
            val response = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", method, params, scoped))).jsonObject
            assertFalse(response.getValue("success").jsonPrimitive.boolean)
            assertEquals(AgentScopeGuard.DENIED, response["error"]?.jsonPrimitive?.content)
        }
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun theFileKeepsTheDocumentAcrossARestartAndIsOwnerOnly() = runBlocking {
        val path = Files.createTempDirectory("shell-state").resolve("shell-state.json")
        val actor = ShellStateActor.User("endpoint-a")
        ShellStateStore(FileShellStatePersistence(path)).propose(listOf(ShellStateChange("a", JsonPrimitive(1), 0)), actor)
        val reopened = ShellStateStore(FileShellStatePersistence(path))
        assertEquals(1L, reopened.snapshot().documentRevision)
        val stale = runCatching { reopened.propose(listOf(ShellStateChange("a", JsonPrimitive(2), 0)), actor) }.exceptionOrNull()
        assertEquals(ShellStateWire.REVISION_CONFLICT, stale?.message)
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(path))
        }
        val leftovers = Files.list(path.parent).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertEquals(listOf(path.fileName.toString()), leftovers, "no temp file is left behind")
    }

    @Test
    fun theRoutesRegisterEveryMethodAdvertiseTheContractAndClassifyCapabilities() {
        assertEquals(ShellStateWire.METHODS, router.registeredMethods)
        assertEquals(setOf(ShellStateWire.CAPABILITY), router.featureCapabilities)
        val expected = mapOf(
            ShellStateWire.GET to IrohPeerCapabilities.CHAT_READ,
            ShellStateWire.PROPOSE to IrohPeerCapabilities.CONVERSATION_MANAGE,
        )
        assertEquals(expected, ShellStateWire.METHODS.associateWith(IrohPeerCapabilities::forAdminMethod))
        val chatRole = IrohPeerCapabilities.CHAT_ROLE
        assertTrue(ShellStateWire.METHODS.all { IrohPeerCapabilities.isAllowed(chatRole, IrohPeerCapabilities.forAdminMethod(it)) })
    }

    @Test
    fun aHostThatNeverRegisteredTheRoutesAnswersUnknownMethodAndAdvertisesNothing() = runBlocking {
        val old = AdminRpcRouter()
        val response = Json.parseToJsonElement(old.dispatch(AdminRpcInvocation("r", ShellStateWire.GET, null))).jsonObject
        assertFalse(response.getValue("success").jsonPrimitive.boolean)
        assertEquals("Unknown method: ${ShellStateWire.GET}", response["error"]?.jsonPrimitive?.content)
        assertFalse(ShellStateWire.CAPABILITY in old.featureCapabilities)
    }

    @Test
    fun everyFixtureCaseMatchesTheRoutesAndTheirPush() = runBlocking {
        val fixtures = listOf(
            com.letta.mobile.data.shellstate.shared.shellStateGetContractText,
            com.letta.mobile.data.shellstate.shared.shellStateProposeContractText,
        )
        for (text in fixtures) {
            val root = Json.parseToJsonElement(text).jsonObject
            val method = root.getValue("method").jsonPrimitive.content
            for (element in root.getValue("cases").jsonArray) {
                val case = element.jsonObject
                val name = "$method/${case.getValue("name").jsonPrimitive.content}"
                val frames = mutableListOf<String>()
                val replay = AdminRpcRouter().also { routerFor(case, frames).register(it) }
                val context = case.getValue("context").jsonObject
                val invocation = AdminRpcInvocation(
                    "contract", method, case.getValue("params").jsonObject,
                    AdminRpcRequestContext(
                        authenticated = context.getValue("authenticated").jsonPrimitive.boolean,
                        authorizedAgentIds = context["authorized_agent_ids"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
                        authenticatedEndpointId = context["authenticated_endpoint_id"]?.jsonPrimitive?.content,
                    ),
                )
                assertEquals(case.getValue("expected_response"), Json.parseToJsonElement(replay.dispatch(invocation)), name)
                val expectedFrame = case["pushed_frame"]?.jsonObject
                assertEquals(listOfNotNull(expectedFrame), frames.map { frame -> pushFields(frame) }, "$name push")
            }
        }
    }

    private fun routerFor(case: JsonObject, frames: MutableList<String>): ShellStateRouteSet {
        val stored = case.getValue("host_state").jsonObject.getValue("shell_state").jsonObject["stored"]
        val seed = stored?.takeUnless { it is JsonNull }
            ?.let { Json.decodeFromJsonElement(ShellStateSnapshot.serializer(), it) } ?: ShellStateStore.EMPTY
        val push = ShellStatePublisher(CoroutineScope(Dispatchers.Unconfined)) { Instant.EPOCH }.apply { attach { frames += it } }
        return ShellStateRouteSet(ShellStateStore(InMemoryShellStatePersistence(seed)) { ShellStateFixtures.CLOCK_MS }, push)
    }

    /** The deterministic fields of a pushed frame, after checking the per-push id and ts are present. */
    private fun pushFields(frame: String): JsonObject {
        val parsed = Json.parseToJsonElement(frame).jsonObject
        assertTrue(parsed.getValue("id").jsonPrimitive.content.startsWith("shell-state-updated-"))
        assertTrue(parsed.getValue("ts").jsonPrimitive.content.isNotBlank())
        return JsonObject(parsed - "id" - "ts")
    }

    @Test
    fun anUnreadableFileIsRefusedAndKeptAndNeverReset() = runBlocking {
        val path = Files.createTempDirectory("shell-state").resolve("shell-state.json")
        Files.write(path, "{not json".toByteArray())
        val corrupt = routerOver(FileShellStatePersistence(path))
        assertEquals(ShellStateWire.STATE_UNREADABLE, failureOn(corrupt, ShellStateWire.GET, null))
        assertEquals(
            ShellStateWire.STATE_UNREADABLE,
            failureOn(corrupt, ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), 0))),
        )
        assertEquals("{not json", String(Files.readAllBytes(path)))
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun aFileFromANewerSchemaIsRefusedAndKept() = runBlocking {
        val path = Files.createTempDirectory("shell-state").resolve("shell-state.json")
        val future = """{"schema_version":99,"document_revision":7,"entries":{}}"""
        Files.write(path, future.toByteArray())
        val newer = routerOver(FileShellStatePersistence(path))
        assertEquals(ShellStateWire.STATE_UNREADABLE, failureOn(newer, ShellStateWire.GET, null))
        assertEquals(future, String(Files.readAllBytes(path)))
    }

    @Test
    fun theHostRecoversOnceTheOwnerRepairsTheFile() = runBlocking {
        val path = Files.createTempDirectory("shell-state").resolve("shell-state.json")
        Files.write(path, "{not json".toByteArray())
        val recovering = routerOver(FileShellStatePersistence(path))
        assertEquals(ShellStateWire.STATE_UNREADABLE, failureOn(recovering, ShellStateWire.GET, null))
        Files.delete(path)
        val reply = Json.parseToJsonElement(recovering.dispatch(AdminRpcInvocation("r", ShellStateWire.GET, null))).jsonObject
        assertTrue(reply.getValue("success").jsonPrimitive.boolean)
    }

    @Test
    fun onDeviceHostKeepsItsDocumentInHostState() = runBlocking {
        val storage: Path = Files.createTempDirectory("runtime-storage")
        val local = AdminRpcRouter().also { ShellStateRoutes.forOnDeviceHost(storage, CoroutineScope(Dispatchers.Unconfined)).register(it) }
        val response = local.dispatch(AdminRpcInvocation("r", ShellStateWire.PROPOSE, propose(change("a", JsonPrimitive(1), 0))))
        assertTrue(Json.parseToJsonElement(response).jsonObject.getValue("success").jsonPrimitive.boolean)
        assertTrue(Files.exists(storage.resolve("host-state").resolve("shell-state.json")))
    }

    private fun change(key: String, value: JsonElement?, expected: Long) = ShellStateChange(key, value, expected)

    private fun propose(vararg changes: ShellStateChange) =
        buildJsonObject { put(ShellStateWire.CHANGES, JsonArray(changes.map(ShellStateChange::toJson))) }

    private suspend fun call(method: String, params: JsonObject, endpoint: String? = "endpoint-a"): JsonObject {
        val context = AdminRpcRequestContext(authenticated = true, authenticatedEndpointId = endpoint)
        val response = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", method, params, context))).jsonObject
        assertTrue(response["success"]!!.jsonPrimitive.boolean, "$method failed: ${response["error"]}")
        return response["result"]!!.jsonObject
    }

    private suspend fun failure(method: String, params: JsonObject?): String? = failureOn(router, method, params)

    private suspend fun failureOn(on: AdminRpcRouter, method: String, params: JsonObject?): String? {
        val response = Json.parseToJsonElement(on.dispatch(AdminRpcInvocation("r", method, params))).jsonObject
        assertFalse(response["success"]!!.jsonPrimitive.boolean)
        return response["error"]?.jsonPrimitive?.content
    }
}
