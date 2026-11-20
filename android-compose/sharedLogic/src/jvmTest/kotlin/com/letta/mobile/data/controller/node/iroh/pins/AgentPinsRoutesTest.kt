package com.letta.mobile.data.controller.node.iroh.pins

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AgentScopeGuard
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.pins.shared.AgentPinsSnapshot
import com.letta.mobile.data.pins.wire.AgentPinsWire
import java.nio.file.Files
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** The pinned-agents v1 host contract: reads, compare-and-set, pushes and the capability gate. */
class AgentPinsRoutesTest {
    private val pushed = mutableListOf<String>()
    private val publisher = AgentPinsPublisher(CoroutineScope(Dispatchers.Unconfined)) { Instant.EPOCH }.apply {
        attach { frame -> pushed += frame }
    }
    private val router = AdminRpcRouter().also { AgentPinsRouteSet(AgentPinsStore(InMemoryAgentPinsPersistence()), publisher).register(it) }

    @Test
    fun anEmptyHostAnswersRevisionZero() = runBlocking {
        val reply = call(AgentPinsWire.GET, JsonObject(emptyMap()))
        assertEquals(0L, reply.revision())
        assertEquals(emptyList(), reply.ids())
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun anAcceptedSetBumpsTheRevisionAndPushesTheWholeList() = runBlocking {
        val reply = call(AgentPinsWire.SET, setParams(listOf("agent-a", "agent-b", "agent-a"), 0))
        assertEquals(1L, reply.revision())
        assertEquals(listOf("agent-a", "agent-b"), reply.ids())
        val frame = Json.parseToJsonElement(pushed.single()).jsonObject
        assertEquals(AgentPinsWire.FRAME_TYPE, frame["type"]?.jsonPrimitive?.content)
        assertEquals(1L, frame["revision"]?.jsonPrimitive?.long)
        assertEquals(listOf("agent-a", "agent-b"), frame[AgentPinsWire.AGENT_IDS]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("agent-a", "agent-b"), call(AgentPinsWire.GET, JsonObject(emptyMap())).ids())
    }

    @Test
    fun aStaleRevisionIsAConflictAndChangesNothing() = runBlocking {
        call(AgentPinsWire.SET, setParams(listOf("agent-a"), 0))
        val conflict = failure(AgentPinsWire.SET, setParams(listOf("agent-b"), 0))
        assertEquals(AgentPinsWire.REVISION_CONFLICT, conflict)
        assertEquals(listOf("agent-a"), call(AgentPinsWire.GET, JsonObject(emptyMap())).ids())
        assertEquals(1, pushed.size)
    }

    @Test
    fun anUnchangedSetIsNotPushed() = runBlocking {
        call(AgentPinsWire.SET, setParams(listOf("agent-a"), 0))
        val same = call(AgentPinsWire.SET, setParams(listOf("agent-a"), 1))
        assertEquals(1L, same.revision())
        assertEquals(1, pushed.size)
    }

    @Test
    fun malformedRequestsAreInvalid() = runBlocking {
        assertEquals(AgentPinsWire.INVALID_REQUEST, failure(AgentPinsWire.SET, idsParams(listOf("agent-a"))))
        assertEquals(AgentPinsWire.INVALID_REQUEST, failure(AgentPinsWire.SET, setParams(listOf(" "), 0)))
        assertEquals(AgentPinsWire.INVALID_REQUEST, failure(AgentPinsWire.SET, setParams(listOf("agent-a"), -1)))
        val tooMany = (0..AgentPinsWire.MAX_PINS).map { "agent-$it" }
        assertEquals(AgentPinsWire.INVALID_REQUEST, failure(AgentPinsWire.SET, setParams(tooMany, 0)))
        assertEquals(AgentPinsWire.INVALID_REQUEST, failure(AgentPinsWire.SET, JsonObject(emptyMap())))
    }

    @Test
    fun agentScopedPeersAreRefused() = runBlocking {
        val scoped = AdminRpcRequestContext(authenticated = true, authorizedAgentIds = setOf("agent-a"))
        val response = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", AgentPinsWire.GET, null, scoped))).jsonObject
        assertFalse(response["success"]!!.jsonPrimitive.boolean)
        assertEquals(AgentScopeGuard.DENIED, response["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun theFileKeepsTheListAcrossARestart() = runBlocking {
        val path = Files.createTempDirectory("agent-pins").resolve("pinned-agents.json")
        AgentPinsStore(FileAgentPinsPersistence(path)).set(listOf(AgentId("agent-a")), 0)
        val reopened = AgentPinsStore(FileAgentPinsPersistence(path))
        assertEquals(1L, reopened.snapshot().revision)
        assertEquals(AgentPinsWire.REVISION_CONFLICT, runCatching { reopened.set(emptyList(), 0) }.exceptionOrNull()?.message)
    }

    @Test
    fun theRoutesRegisterEveryMethodAdvertiseTheContractAndClassifyCapabilities() {
        assertEquals(AgentPinsWire.METHODS, router.registeredMethods)
        assertEquals(setOf(AgentPinsWire.CAPABILITY), router.featureCapabilities)
        val expected = mapOf(
            AgentPinsWire.GET to IrohPeerCapabilities.CHAT_READ,
            AgentPinsWire.SET to IrohPeerCapabilities.CONVERSATION_MANAGE,
        )
        assertEquals(expected, AgentPinsWire.METHODS.associateWith(IrohPeerCapabilities::forAdminMethod))
        val chatRole = IrohPeerCapabilities.CHAT_ROLE
        assertTrue(AgentPinsWire.METHODS.all { IrohPeerCapabilities.isAllowed(chatRole, IrohPeerCapabilities.forAdminMethod(it)) })
    }

    @Test
    fun everyFixtureCaseMatchesTheRoutesAndTheirPush() = runBlocking {
        val fixtures = listOf(
            com.letta.mobile.data.pins.shared.pinsGetContractText,
            com.letta.mobile.data.pins.shared.pinsSetContractText,
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
                    ),
                )
                assertEquals(case.getValue("expected_response"), Json.parseToJsonElement(replay.dispatch(invocation)), name)
                val expectedFrame = case["pushed_frame"]?.jsonObject
                assertEquals(listOfNotNull(expectedFrame), frames.map { frame -> pushFields(frame) }, "$name push")
            }
        }
    }

    private fun routerFor(case: JsonObject, frames: MutableList<String>): AgentPinsRouteSet {
        val stored = case.getValue("host_state").jsonObject.getValue("pins").jsonObject["stored"]
        val seed = stored?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.let { Json.decodeFromJsonElement(AgentPinsSnapshot.serializer(), it) } ?: AgentPinsSnapshot()
        val push = AgentPinsPublisher(CoroutineScope(Dispatchers.Unconfined)) { Instant.EPOCH }.apply { attach { frames += it } }
        return AgentPinsRouteSet(AgentPinsStore(InMemoryAgentPinsPersistence(seed)), push)
    }

    /** The deterministic fields of a pushed frame, after checking the per-push id and ts are present. */
    private fun pushFields(frame: String): JsonObject {
        val parsed = Json.parseToJsonElement(frame).jsonObject
        assertTrue(parsed.getValue("id").jsonPrimitive.content.startsWith("pins-updated-"))
        assertTrue(parsed.getValue("ts").jsonPrimitive.content.isNotBlank())
        return JsonObject(parsed - "id" - "ts")
    }

    @Test
    fun twoRoutesetsOverTheSameFileNeverLoseAWrite() = runBlocking {
        // Every on-device dial builds its own routes over the same state directory.
        val dir = Files.createTempDirectory("agent-pins-dials")
        val dials = (1..2).map { AdminRpcRouter().also { r -> AgentPinsRoutes.forHost(dir, publisher).register(r) } }
        repeat(25) { round ->
            val replies = dials.map { dial ->
                async(Dispatchers.Default) {
                    val params = setParams(listOf("agent-$round"), round.toLong())
                    Json.parseToJsonElement(dial.dispatch(AdminRpcInvocation("r", AgentPinsWire.SET, params))).jsonObject
                }
            }.awaitAll()
            assertEquals(1, replies.count { it["success"]!!.jsonPrimitive.boolean }, "round $round: one accept")
            assertEquals(
                AgentPinsWire.REVISION_CONFLICT,
                replies.single { !it["success"]!!.jsonPrimitive.boolean }["error"]?.jsonPrimitive?.content,
            )
        }
        val ids = Json.parseToJsonElement(dials[0].dispatch(AdminRpcInvocation("r", AgentPinsWire.GET, null)))
            .jsonObject.getValue("result").jsonObject.ids()
        assertEquals(1, ids.size, "each round replaced the list once")
    }

    @Test
    fun anUnreadableFileIsRefusedAndKept() = runBlocking {
        val path = Files.createTempDirectory("agent-pins").resolve("pinned-agents.json")
        Files.writeString(path, "{not json")
        val corrupt = AdminRpcRouter().also { AgentPinsRouteSet(AgentPinsStore(FileAgentPinsPersistence(path)), publisher).register(it) }
        val response = Json.parseToJsonElement(corrupt.dispatch(AdminRpcInvocation("r", AgentPinsWire.GET, null))).jsonObject
        assertEquals(AgentPinsWire.STATE_UNREADABLE, response["error"]?.jsonPrimitive?.content)
        assertEquals("{not json", Files.readString(path))
    }

    private suspend fun call(method: String, params: JsonObject): JsonObject {
        val response = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", method, params))).jsonObject
        assertTrue(response["success"]!!.jsonPrimitive.boolean, "$method failed: ${response["error"]}")
        return response["result"]!!.jsonObject
    }

    private suspend fun failure(method: String, params: JsonObject): String? {
        val response = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", method, params))).jsonObject
        assertFalse(response["success"]!!.jsonPrimitive.boolean)
        return response["error"]?.jsonPrimitive?.content
    }

    private fun idsParams(ids: List<String>) = buildJsonObject { put(AgentPinsWire.AGENT_IDS, JsonArray(ids.map(::JsonPrimitive))) }

    private fun setParams(ids: List<String>, expected: Long) = buildJsonObject {
        put(AgentPinsWire.AGENT_IDS, JsonArray(ids.map(::JsonPrimitive)))
        put(AgentPinsWire.EXPECTED_REVISION, expected)
    }

    private fun JsonObject.revision(): Long = getValue("revision").jsonPrimitive.long

    private fun JsonObject.ids(): List<String> = getValue(AgentPinsWire.AGENT_IDS).jsonArray.map { it.jsonPrimitive.content }
}
