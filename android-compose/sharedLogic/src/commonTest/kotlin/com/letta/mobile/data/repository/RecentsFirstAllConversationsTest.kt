package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.iroh.WireContractFixture
import com.letta.mobile.data.transport.iroh.visibleRecentsContractText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-ybdv3: Android's fleet list read every visible agent's conversations one by one
 * (359 `conversation.list` calls on the live host) whenever the drawer opened, on the heartbeat and
 * on reconnect. It now reads recents-first like web and desktop: one visible recents page plus the
 * pinned agents' pages, and only a host without `visible_only` keeps the per-agent read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentsFirstAllConversationsTest {
    private companion object {
        /** Visible agents on the live host when the fan-out was measured. */
        const val ROSTER_SIZE = 359
        const val DRAWER_MAX_AGE_MS = 60_000L
        const val SETTLE_MS = 2_000L
    }

    private val roster = List(ROSTER_SIZE) { Agent(AgentId("agent-$it"), name = "Agent $it") }
    private val pins = setOf(AgentId("agent-7"), AgentId("agent-42"))

    /** Each agent's own conversation, the newest agent first, behind a burst of agent-free worker rows. */
    private fun hostRows(): List<HostRow> =
        List(40) { HostRow("worker-$it", agent = null, at = stamp(ROSTER_SIZE + it)) } +
            HostRow("archived", agent = "agent-1", at = stamp(ROSTER_SIZE + 50), archived = true) +
            roster.indices.map { HostRow("conv-$it", agent = "agent-$it", at = stamp(ROSTER_SIZE - it)) }

    private fun repository(host: Host, scope: kotlinx.coroutines.CoroutineScope) = CachedAllConversationsRepository(
        roster = { roster }, remote = RemoteRoute.Absent(SessionRoute.CONVERSATIONS), repositoryScope = scope,
        irohConversationListSource = host.source(), transport = host, pinnedAgents = { pins },
    )

    @Test
    fun aRefreshReadsOneRecentsPagePlusPinnedAgentsNotEveryAgent() = runTest {
        val host = Host(hostRows())
        val repository = repository(host, backgroundScope)
        runCurrent()

        repository.refresh()

        // main: 359 per-agent lists and no recents call.
        val expected = setOf(Read.Recents, Read.Agent("agent-7"), Read.Agent("agent-42"))
        assertEquals(expected, host.lists.toSet(), "conversation.list calls for a $ROSTER_SIZE-agent roster: ${host.lists.size}")
        assertEquals(3, host.lists.size, "conversation.list calls for a $ROSTER_SIZE-agent roster")
        val ids = repository.conversations.value.map { it.id.value }.toSet()
        assertTrue(ids.containsAll(List(30) { "conv-$it" }), "the 30 newest visible conversations are listed: $ids")
        assertTrue(ids.containsAll(setOf("conv-7", "conv-42")))
        assertTrue(ids.none { it.startsWith("worker-") || it == "archived" }, "agent-free and archived rows stay out: $ids")
    }

    @Test
    fun theDrawersStaleRefreshReadsRecentsOnceAndNotAgainWhileFresh() = runTest {
        val host = Host(hostRows())
        val repository = repository(host, backgroundScope)
        runCurrent()

        assertTrue(repository.refreshIfStale(DRAWER_MAX_AGE_MS))
        assertEquals(3, host.lists.size, "conversation.list calls when the drawer first opens")
        assertEquals(1, host.lists.count { it == Read.Recents })

        host.lists.clear()
        assertEquals(false, repository.refreshIfStale(DRAWER_MAX_AGE_MS))
        assertEquals(0, host.lists.size, "a fresh list is not read again when the drawer reopens")
    }

    @Test
    fun aReconnectReReadsRecentsAndPinsOnly() = runTest {
        val host = Host(hostRows())
        val repository = repository(host, backgroundScope)
        runCurrent()
        repository.refresh()
        host.lists.clear()

        host.state.value = ChannelTransportState.Disconnected(1006, "offline")
        runCurrent()
        host.state.value = ChannelTransportState.Connected("server", "session", "device")
        runCurrent()

        assertEquals(3, host.lists.size, "conversation.list calls after a reconnect: ${host.lists}")
    }

    @Test
    fun aHostWithoutVisibleRecentsFallsBackToReadingEachVisibleAgent() = runTest {
        val host = Host(hostRows(), visibilityIndex = false)
        val repository = repository(host, backgroundScope)
        runCurrent()

        repository.refresh()

        assertEquals(1, host.lists.count { it == Read.Recents }, "the recents opt-in is tried once")
        assertEquals(ROSTER_SIZE, host.lists.count { it is Read.Agent }, "every visible agent is read on a legacy host")
        assertEquals(ROSTER_SIZE + 1, repository.conversations.value.size, "every agent's rows, the archived one included")

        host.lists.clear()
        repository.refresh()
        assertEquals(0, host.lists.count { it == Read.Recents }, "a host found without recents is not asked again")
        assertEquals(ROSTER_SIZE, host.lists.size)
    }

    @Test
    fun aBackendSwitchAsksTheNextHostForRecentsAgain() = runTest {
        val host = Host(hostRows(), visibilityIndex = false)
        val repository = repository(host, backgroundScope)
        runCurrent()
        repository.refresh()

        repository.clearForBackendSwitch()
        host.visibilityIndex = true
        host.lists.clear()
        repository.refresh()

        assertEquals(3, host.lists.size, "conversation.list calls on a host with recents after a switch: ${host.lists}")
    }

    @Test
    fun aPushForAnUnlistedAgentReReadsOnlyThatConversation() = runTest {
        val host = Host(hostRows())
        val repository = repository(host, backgroundScope)
        runCurrent()
        repository.refresh()
        host.lists.clear()
        host.gets.clear()

        host.rows = host.rows.map { if (it.id == "conv-300") it.copy(summary = "fresh", at = stamp(ROSTER_SIZE * 2)) else it }
        host.events.emit(push("conv-300", "agent-300"))
        advanceTimeBy(SETTLE_MS)
        runCurrent()

        assertEquals(0, host.lists.size, "a push lists no agent")
        assertEquals(listOf("conv-300"), host.gets)
        val row = repository.conversations.value.first()
        assertEquals("conv-300" to "fresh", row.id.value to row.summary)
    }

    @Test
    fun anOptimisticRowSurvivesAPushForAnotherConversation() = runTest {
        val host = Host(hostRows())
        val repository = repository(host, backgroundScope)
        runCurrent()
        repository.refresh()
        val optimistic = com.letta.mobile.data.model.Conversation(
            ConversationId("local-new"), AgentId("agent-5"), summary = "draft", lastMessageAt = stamp(ROSTER_SIZE * 3),
        )
        repository.handleOptimisticUpdate(optimistic)

        host.events.emit(push("conv-0", "agent-0"))
        advanceTimeBy(SETTLE_MS)
        runCurrent()

        assertEquals(ConversationId("local-new"), repository.conversations.value.first().id)
    }

    private fun push(id: String, agent: String) = ServerFrame.ConversationUpdated(
        id = "push-$id", ts = "2026-11-14T00:00:00Z", conversationId = id, agentId = agent,
    )

    /** ISO-8601 instants ordered by [n]: a larger [n] is newer. */
    private fun stamp(n: Int): String {
        val minutes = n % 60
        val hours = n / 60
        return "2026-11-14T${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:00Z"
    }

    private data class HostRow(
        val id: String,
        val agent: String?,
        val at: String,
        val archived: Boolean = false,
        val summary: String = id,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("id", id)
            put("agent_id", agent)
            put("summary", summary)
            put("archived", archived)
            put("last_message_at", at)
            put("updated_at", at)
        }
    }

    private sealed interface Read {
        data object Recents : Read
        data class Agent(val agent: String) : Read
    }

    /**
     * The host's `conversation.list` and `conversation.get` as ConversationAdminHandlers serves them:
     * `visible_only` without agent/search/archive/order params is the recents page (agent-free,
     * hidden and archived rows removed, newest first, 30 by default), failing with the contract's
     * `no-index` answer when the host has no visibility index; an agent's list is its own rows
     * (archived too with `archive_status: all`) after the cursor; an id the host cannot retrieve fails.
     */
    private class Host(
        var rows: List<HostRow>,
        var visibilityIndex: Boolean = true,
    ) : NoOpChannelTransport() {
        override val events = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 32)
        override val state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Connected("server", "session", "device"))
        val lists = mutableListOf<Read>()
        val gets = mutableListOf<String>()

        fun source() = IrohAdminRpcConversationListSource(
            this,
            ActiveConfigSettingsRepository(LettaConfig(id = "test", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://fake")),
        )

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            val params = body?.let { LettaJson.wire.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
            return when (method) {
                "conversation.list" -> list(params)
                "conversation.get" -> get(path.substringAfterLast('/'))
                else -> error("unexpected $method")
            }
        }

        private fun list(params: JsonObject): AppServerInboundFrame.AdminRpcResponse {
            val agent = params.text("agent_id")
            val recents = (params["visible_only"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true &&
                listOf("agent_id", "summary_search", "archive_status", "order", "order_by").all { params.text(it) == null }
            if (recents) {
                lists += Read.Recents
                if (!visibilityIndex) return noIndex()
                val visible = newestFirst(rows.filter { it.agent != null && !it.archived })
                return ok(page(visible, params, default = 30))
            }
            requireNotNull(agent) { "the fleet list never asks for every agent at once" }
            lists += Read.Agent(agent)
            val own = newestFirst(rows.filter { it.agent == agent })
                .filter { params.text("archive_status") == "all" || !it.archived }
            return ok(page(own, params, default = own.size))
        }

        private fun get(id: String): AppServerInboundFrame.AdminRpcResponse {
            gets += id
            val row = rows.firstOrNull { it.id == id }
                ?: return AppServerInboundFrame.AdminRpcResponse("request", success = false, error = "conversation not found")
            return AppServerInboundFrame.AdminRpcResponse("request", success = true, result = row.json())
        }

        private fun newestFirst(rows: List<HostRow>) = rows.sortedByDescending { it.at }

        private fun page(rows: List<HostRow>, params: JsonObject, default: Int): List<HostRow> {
            val after = params.text("after")
            val from = after?.let { id -> rows.indexOfFirst { it.id == id } + 1 } ?: 0
            return rows.drop(from).take(params.text("limit")?.toIntOrNull() ?: default)
        }

        private fun ok(rows: List<HostRow>) =
            AppServerInboundFrame.AdminRpcResponse("request", success = true, result = JsonArray(rows.map(HostRow::json)))

        private fun noIndex(): AppServerInboundFrame.AdminRpcResponse {
            val envelope = WireContractFixture(visibleRecentsContractText).response("no-index")
            return AppServerInboundFrame.AdminRpcResponse(
                "request", success = envelope.getValue("success").jsonPrimitive.booleanOrNull == true,
                result = envelope["result"], error = envelope["error"]?.jsonPrimitive?.contentOrNull,
            )
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
