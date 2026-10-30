package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.transport.api.AppServerClientChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.iroh.WireContractFixture
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecentBootstrapTest {
    private val a = AgentId("agent-0")
    private val b = AgentId("agent-1")
    private val c = AgentId("agent-2")
    private val roster = List(137) { Agent(AgentId("agent-$it"), name = "Agent $it") }

    @Test
    fun bootstrapReadsOneVisiblePageAndNoRosterAgentLists() = runTest {
        val host = VisibleHost()
        val owner = owner(host)
        runCurrent()
        assertEquals(0, host.agentReads.size, "bootstrap must not fan out across the roster")
        assertEquals(1, host.recentsReads, "exactly one recents page")
        assertEquals(JsonPrimitive(true), host.calls.single().second["visible_only"])
        assertEquals(JsonPrimitive(30), host.calls.single().second["limit"])
        assertEquals(setOf("visible_only", "limit"), host.calls.single().second.keys)
        assertTrue(owner.state.value.loaded)
        assertFalse(owner.state.value.authoritative, "a recents page must never prune globally absent tabs")
        assertEquals(listOf("recent", "older"), owner.state.value.conversations.map { it.id.value })
    }

    @Test
    fun recentsGatewaySendsTheVisibleBooleanAndTypedCursor() = runTest {
        val host = VisibleHost()
        val gateway = IrohAdminRpcChatGateway(AppServerClientChannel(host, backgroundScope))
        val page = gateway.listRecentConversations(1, ConversationId("recent"))
        assertEquals(listOf("older"), page.map { it.id.value })
        assertEquals(JsonPrimitive(true), host.calls.single().second["visible_only"])
        assertEquals(JsonPrimitive("recent"), host.calls.single().second["after"])
        assertEquals(JsonPrimitive(1), host.calls.single().second["limit"])
        assertEquals(emptyList(), host.agentReads)
    }

    @Test
    fun bootstrapPrefetchesExactlyOneFirstPagePerPin() = runTest {
        val host = VisibleHost().apply { extras += List(65) { HostConversation("extra-$it", a.value, archived = true).json() } }
        val owner = owner(host, setOf(a, c))
        runCurrent()
        assertEquals(1, host.recentsReads)
        assertEquals(listOf(a.value, c.value), host.agentReads.sorted())
        assertTrue(host.calls.filter { it.second["agent_id"] != null }.all { it.second["after"] == null })
        assertTrue(owner.state.value.loaded)
        assertTrue(owner.lists.hasMore(a), "pinned prefetch must not read through the agent")
    }

    @Test
    fun focusingAndPagingReadOnlyTheFocusedAgent() = runTest {
        val host = VisibleHost().apply { extras += List(65) { HostConversation("extra-$it", a.value, archived = true).json() } }
        val owner = owner(host)
        runCurrent()
        host.calls.clear()
        owner.lists.focusAgent(a)
        assertEquals(listOf(a.value), host.agentReads)
        assertTrue(owner.lists.hasMore(a))
        assertFalse(a in owner.state.value.authoritativeAgents)
        host.failingAgents += a.value
        owner.lists.loadMore(a)
        assertTrue(owner.state.value.error != null, "a failed page must stop the drawer read-through loop")
        assertTrue(owner.lists.hasMore(a), "a failed page retains its cursor for retry")
        host.failingAgents.clear()
        owner.lists.loadMore(a)
        assertEquals(listOf(a.value, a.value, a.value), host.agentReads)
        assertFalse(owner.lists.hasMore(a))
        assertTrue(a in owner.state.value.authoritativeAgents)
        assertEquals(67, owner.state.value.conversations.size)
        assertTrue(host.calls.last().second["after"] != null)
        owner.lists.focusAgent(a)
        assertEquals(3, host.agentReads.size, "already loaded focus is cached")
        host.extras += List(65) { HostConversation("new-$it", a.value, archived = true).json() }
        host.calls.clear()
        owner.refresh()
        assertEquals(listOf(a.value), host.agentReads, "reconnect only refreshes the cached agent's head")
        assertEquals(1, host.recentsReads)
        assertEquals(67, owner.state.value.conversations.size, "cached tails stay visible during head refresh")
        assertTrue(owner.lists.hasMore(a), "a refreshed full page must not reuse an exhausted old cursor")
        owner.lists.prepareSearch(a)
        assertEquals(132, owner.state.value.conversations.size, "demand paging fills the post-reconnect gap")
        assertTrue(host.agentReads.all { it == a.value })
    }

    @Test
    fun pushesReadOneUnloadedRowButOnlyRefreshLoadedOwners() = runTest {
        val host = VisibleHost()
        val changes = MutableSharedFlow<Pair<ConversationId, AgentId?>>(extraBufferCapacity = 4)
        val base = IrohAdminRpcChatGateway(AppServerClientChannel(host, backgroundScope))
        val gateway = object : ChatGateway by base { override val conversationChanges = changes }
        val owner = TimelineConversations(backgroundScope, gateway, roster)
        runCurrent()
        host.extras += HostConversation("pushed", c.value, lastMessageAt = "2026-10-30T15:00:00Z").json()
        host.calls.clear()
        changes.emit(ConversationId("pushed") to c)
        runCurrent()
        assertEquals(emptyList(), host.agentReads)
        assertEquals(listOf("conversation.get"), host.calls.map { it.first })
        assertEquals("pushed", owner.state.value.conversations.first().id.value)
        host.calls.clear()
        changes.emit(ConversationId("pushed") to null)
        runCurrent()
        assertEquals(listOf("conversation.get"), host.calls.map { it.first }, "missing ownership never widens the read")
        owner.lists.focusAgent(a)
        host.calls.clear()
        changes.emit(ConversationId("recent") to a)
        runCurrent()
        assertEquals(listOf(a.value), host.agentReads)
        assertEquals(listOf("conversation.get", "conversation.list"), host.calls.map { it.first })
    }

    @Test
    fun loadedPushUpdatesRowsOutsideTheRefreshedFirstPage() = runTest {
        val host = VisibleHost().apply { extras += List(65) { HostConversation("extra-$it", a.value, archived = true).json() } }
        val changes = MutableSharedFlow<Pair<ConversationId, AgentId?>>(extraBufferCapacity = 4)
        val base = IrohAdminRpcChatGateway(AppServerClientChannel(host, backgroundScope))
        val gateway = object : ChatGateway by base { override val conversationChanges = changes }
        val owner = TimelineConversations(backgroundScope, gateway, roster)
        runCurrent()
        owner.lists.focusAgent(a)
        owner.lists.loadMore(a)
        host.extras[64] = HostConversation("extra-64", a.value, summary = "Tail changed", archived = true).json()
        host.calls.clear()
        changes.emit(ConversationId("extra-64") to a)
        runCurrent()
        assertEquals("Tail changed", owner.state.value.conversations.single { it.id.value == "extra-64" }.summary)
        assertEquals(listOf(a.value), host.agentReads)
    }

    @Test
    fun reconnectReadsRecentsPinsAndLoadedAgentsOnly() = runTest {
        val host = VisibleHost()
        val owner = owner(host, setOf(a))
        runCurrent()
        owner.lists.focusAgent(b)
        host.calls.clear()
        owner.refresh()
        assertEquals(1, host.recentsReads)
        assertEquals(listOf(a.value, b.value), host.agentReads.sorted())
    }

    @Test
    fun partialBootstrapNeverSettlesEmptyOrCreatesAnUnqueriedDuplicate() = runTest {
        val host = VisibleHost("window").apply {
            recentsGate = CompletableDeferred()
            extras += List(65) {
                HostConversation("archived-$it", c.value, archived = true, lastMessageAt = "2026-10-30T08:00:00Z").json()
            }
        }
        val owner = owner(host)
        runCurrent()
        assertFalse(owner.state.value.loaded)
        owner.openInitial(null, c, createIfNone = true)
        runCurrent()
        assertTrue(host.calls.none { it.first == "conversation.create" })
        host.recentsGate?.complete(Unit)
        runCurrent()
        assertTrue(owner.state.value.loaded)
        owner.openInitial(null, c, createIfNone = true)
        runCurrent()
        assertEquals(ConversationId("existing-outside-recents"), owner.selectedConversation)
        assertTrue(host.agentReads.isNotEmpty() && host.agentReads.all { it == c.value })
        assertTrue(host.calls.none { it.first == "conversation.create" })
    }

    @Test
    fun aCreatedSelectionSurvivesAnOlderInFlightRecentsSnapshot() = runTest {
        val telemetry = com.letta.mobile.util.Telemetry
        telemetry.installRecorder(com.letta.mobile.util.TelemetryRecorder()).use {
            val host = VisibleHost("window").apply { recentsGate = CompletableDeferred() }
            val owner = owner(host)
            runCurrent()
            owner.startNewChat(c)
            runCurrent()
            assertFalse(owner.state.value.loaded)
            assertEquals(ConversationId("new-created"), owner.selectedConversation)
            host.recentsGate?.complete(Unit)
            runCurrent()
            assertTrue(owner.state.value.loaded)
            assertEquals(31, owner.state.value.conversations.size)
            assertEquals("new-created", owner.state.value.conversations.first().id.value)
            owner.openInitial(null, c, createIfNone = true)
            runCurrent()
            assertEquals(1, host.calls.count { it.first == "conversation.create" })
            val first = telemetry.snapshot().single { it.name == "list.first_rows_published" }
            assertEquals(31, (first.attrs.getValue("rowCount") as com.letta.mobile.util.TelemetryValue.Numeric).value)
        }
    }

    @Test
    fun pinnedPrefetchMustLandBeforeLoadedBecomesTrue() = runTest {
        val host = VisibleHost().apply { agentGate = c.value to CompletableDeferred() }
        val owner = owner(host, setOf(c))
        runCurrent()
        assertFalse(owner.state.value.loaded)
        assertEquals(2, owner.state.value.conversations.size, "first rows publish before the pin finishes")
        host.agentGate?.second?.complete(Unit)
        runCurrent()
        assertTrue(owner.state.value.loaded)
    }

    @Test
    fun bothLegacyRawRowsAndCapabilityUnavailableRestoreEagerReads() = runTest {
        for (mode in listOf("legacy", "no-index")) {
            val host = VisibleHost(mode)
            val owner = owner(host)
            runCurrent()
            assertEquals(roster.map { it.id.value }.sorted(), host.agentReads.sorted(), mode)
            assertEquals(1, host.recentsReads)
            assertTrue(owner.state.value.loaded)
            assertTrue(owner.state.value.authoritative)
            host.calls.clear()
            owner.refresh()
            assertEquals(0, host.recentsReads, "fallback must be remembered for this session")
            assertEquals(roster.size, host.agentReads.size)
        }
    }

    @Test
    fun rosterMembershipChangeDoesNotFanOut() = runTest {
        val host = VisibleHost()
        val owner = owner(host)
        runCurrent()
        host.calls.clear()
        owner.roster = roster + Agent(AgentId("agent-new"), name = "New")
        runCurrent()
        assertEquals(emptyList(), host.calls)
        owner.roster = listOf(roster.first())
        runCurrent()
        assertEquals(listOf("recent", "older"), owner.state.value.conversations.map { it.id.value })
        owner.roster = listOf(roster.first().copy(hidden = true))
        runCurrent()
        assertEquals(listOf("older"), owner.state.value.conversations.map { it.id.value })
        assertEquals(emptyList(), host.calls, "directory arrival and known-hidden changes are local, not fleet reads")
    }

    @Test
    fun explicitSearchFindsPreviouslyUnloadedAgentsAndCachesTheirPages() = runTest {
        val host = VisibleHost().apply { extras += HostConversation("search-title", c.value, summary = "Needle", archived = true).json() }
        val owner = owner(host)
        runCurrent()
        host.calls.clear()
        owner.lists.prepareSearch(c)
        assertEquals(listOf(c.value), host.agentReads)
        assertTrue(owner.state.value.conversations.any { it.summary == "Needle" })
        owner.lists.prepareSearch(c)
        assertEquals(listOf(c.value), host.agentReads)
        host.calls.clear()
        host.failingAgents += a.value
        host.extras += HostConversation("across-fleet", b.value, summary = "Across fleet", archived = true).json()
        owner.lists.prepareSearch()
        assertEquals(roster.size - 1, host.agentReads.size)
        assertTrue(owner.state.value.conversations.any { it.summary == "Across fleet" }, "one failed agent cannot narrow search")
    }

    @Test
    fun bootstrapTelemetryPublishesOrderedMonotonicMeasurementsAndFallbackOnlyOnce() = runTest {
        val telemetry = com.letta.mobile.util.Telemetry
        telemetry.installRecorder(com.letta.mobile.util.TelemetryRecorder()).use {
            val host = VisibleHost()
            val live = owner(host)
            runCurrent()
            val events = telemetry.snapshot().filter { it.tag == "Conversations" && it.name.startsWith("list.") }.asReversed()
            assertEquals(
                listOf("list.bootstrap_start", "list.first_rows_published", "list.bootstrap_complete"),
                events.map { it.name },
            )
            val elapsed = events.map { (it.attrs.getValue("elapsedMs") as com.letta.mobile.util.TelemetryValue.Numeric).value.toLong() }
            assertTrue(elapsed.all { it >= 0 })
            assertEquals(elapsed.sorted(), elapsed)
            assertEquals(2, (events[1].attrs.getValue("rowCount") as com.letta.mobile.util.TelemetryValue.Numeric).value)
            live.refresh()
            assertEquals(3, telemetry.snapshot().count { it.tag == "Conversations" && it.name.startsWith("list.") })
            val oldHost = VisibleHost("legacy")
            val oldOwner = owner(oldHost)
            runCurrent()
            oldOwner.refresh()
            assertEquals(1, telemetry.snapshot().count { it.name == "list.legacy_fallback" })
        }
    }

    private fun TestScope.owner(host: VisibleHost, pins: Set<AgentId> = emptySet()) = TimelineConversations(
        backgroundScope, IrohAdminRpcChatGateway(AppServerClientChannel(host, backgroundScope)), roster, pinnedAgents = pins,
    )
}

/** Fixture responses are shared with real-handler conformance; other reads have strict identity and page checks. */
private class VisibleHost(private val mode: String = "visible") : AppServerClient by IrohHost() {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "scripts/ci/check-wire-contracts.py").isFile }
    private val fixture = WireContractFixture(
        File(root, "android-compose/sharedLogic/src/commonTest/resources/contracts/conversation.list/visible-recents-v1.json").readText(),
    )
    val extras = mutableListOf<JsonObject>()
    val failingAgents = mutableSetOf<String>()
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val agentReads: List<String> get() = calls.mapNotNull { it.second["agent_id"]?.jsonPrimitive?.content }
    val recentsReads: Int get() = calls.count { it.first == "conversation.list" && it.second["agent_id"] == null }
    var recentsGate: CompletableDeferred<Unit>? = null
    var agentGate: Pair<String, CompletableDeferred<Unit>>? = null
    private val rows: List<JsonObject>
        get() {
            val name = when (mode) {
                "window" -> "full-window"
                "legacy" -> "string-is-legacy"
                else -> "after-visible"
            }
            val case = fixture.cases.single { it.getValue("name").jsonPrimitive.content == name }
            return case.getValue("host_state").jsonObject.getValue("conversations").jsonArray.map { it.jsonObject } + extras
        }

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
        val params = requireNotNull(command.params)
        calls += command.method to params
        return when (command.method) {
            "conversation.list" -> list(command, params)
            "conversation.create" -> {
                check(mode == "window" && params.text("agent_id") == "agent-2") { "Undeclared conversation create" }
                val row = JsonObject(mapOf(
                    "id" to JsonPrimitive("new-created"), "agent_id" to params.getValue("agent_id"),
                    "archived" to JsonPrimitive(false), "archived_at" to JsonNull,
                    "created_at" to JsonPrimitive("2026-10-30T09:00:00Z"),
                    "updated_at" to JsonPrimitive("2026-10-30T09:00:00Z"),
                    "last_message_at" to JsonNull, "summary" to (params["summary"] ?: JsonNull),
                    "in_context_message_ids" to JsonArray(emptyList()),
                ))
                extras += row
                AppServerInboundFrame.AdminRpcResponse(command.requestId, true, result = row)
            }
            "conversation.get" -> {
                val id = params.text("conversation_id").also { require(it.isNotEmpty()) }
                val row = rows.firstOrNull { it.text("id") == id }
                AppServerInboundFrame.AdminRpcResponse(
                    command.requestId, row != null, result = row,
                    error = if (row == null) "app_server_error: conversation.get native command unsuccessful" else null,
                )
            }
            else -> error("Unexpected native/wire operation: ${command.method}")
        }
    }

    private suspend fun list(
        command: AppServerCommand.AdminRpc,
        params: JsonObject,
    ): AppServerInboundFrame.AdminRpcResponse {
        val agent = params.text("agent_id").takeIf { it.isNotEmpty() }
        if (agent == null) return listRecents(command, params)
        return listAgent(command, params, agent)
    }

    private fun optsIntoVisibleRecents(params: JsonObject): Boolean {
        val flag = params["visible_only"] as? JsonPrimitive
        val literalTrue = flag?.let { !it.isString && it.booleanOrNull == true } == true
        return literalTrue && listOf("agent_id", "summary_search", "archive_status", "order", "order_by")
            .none { params[it] != null }
    }

    private fun recentCase(params: JsonObject): String {
        val optedIn = optsIntoVisibleRecents(params)
        return when {
            mode == "no-index" && optedIn -> "no-index"
            mode == "legacy" || !optedIn -> "string-is-legacy"
            mode == "window" -> "full-window"
            else -> cursorCase(params)
        }
    }

    private fun cursorCase(params: JsonObject): String = when {
        params.text("after") == "recent" && params.text("limit") == "1" -> "after-visible"
        params.text("after") == "unknown" -> "unknown-cursor"
        else -> "visible"
    }

    private suspend fun listRecents(
        command: AppServerCommand.AdminRpc,
        params: JsonObject,
    ): AppServerInboundFrame.AdminRpcResponse {
        val response = fixture.response(recentCase(params))
        // Hold the already-read list response so a concurrent creation can overtake it.
        recentsGate?.await()
        return AppServerInboundFrame.AdminRpcResponse(
            command.requestId, response.getValue("success").jsonPrimitive.booleanOrNull == true,
            result = response["result"], error = response["error"]?.jsonPrimitive?.content,
        )
    }

    private suspend fun listAgent(
        command: AppServerCommand.AdminRpc,
        params: JsonObject,
        agent: String,
    ): AppServerInboundFrame.AdminRpcResponse {
        if (agent == agentGate?.first) agentGate?.second?.await()
        if (agent in failingAgents) return AppServerInboundFrame.AdminRpcResponse(
            command.requestId, false, error = "app_server_error: conversation.list native command unsuccessful",
        )
        val own = rows.filter { it.text("agent_id") == agent && it["hidden"] != JsonPrimitive(true) }.sortedWith(
            compareByDescending<JsonObject> {
                it.text("last_message_at").ifEmpty { it.text("updated_at").ifEmpty { it.text("created_at") } }
            }.thenBy { it.text("id") },
        )
        return AppServerInboundFrame.AdminRpcResponse(
            command.requestId, true, result = agentPage(own, params),
        )
    }

    private fun agentPage(own: List<JsonObject>, params: JsonObject): JsonArray {
        val after = params.text("after").takeIf { it.isNotEmpty() }
        val cursor = if (after == null) -1 else own.indexOfFirst { it.text("id") == after }
        val tail = own.drop(cursor + 1)
        val limit = params.text("limit").toLongOrNull() ?: 20L
        val count = if (limit < 0) (tail.size.toLong() + limit).coerceAtLeast(0) else minOf(limit, tail.size.toLong())
        return JsonArray(tail.take(count.toInt()))
    }
}
