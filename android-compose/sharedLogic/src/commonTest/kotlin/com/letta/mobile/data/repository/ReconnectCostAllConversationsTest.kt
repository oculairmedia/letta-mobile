package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.chat.conversations.listing.repositoryConversationListingFactory
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-fj56l: a reconnect must cost O(1) admin calls, never O(roster). The prod app's reconnects
 * measured a median of 272 `conversation.list` per connection (75,443 of 152k host admin calls) on a roster of
 * 136 visible agents. The host here answers as `ConversationAdminHandlers` / `NativeAdminSupport` do: a
 * visible-recents read refused with `capability_unavailable` without a visibility index, a transient
 * `capability_unavailable` (App Server timeout / open breaker) on every `conversation.list` while the App Server
 * is down, an agent list only with an `agent_id`, and a `conversation.get` that fails for an id it cannot resolve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectCostAllConversationsTest {
    private companion object {
        /** Visible agents on the prod host when the reconnect bursts were measured. */
        const val ROSTER_SIZE = 136
        const val RECONNECTS = 5
        const val CONCURRENT_REFRESHES = 5

        /** The host's refusal of visible recents (`ConversationAdminHandlers.visibleRecents`, no visibility index). */
        const val NO_INDEX = "capability_unavailable: visible_only requires local agent visibility"

        /** The App Server's transient refusal of any read (`NativeAdminSupport.onRequireTimeout`). */
        const val TRANSIENT = "capability_unavailable: conversation.list App Server v2 timed out"

        fun stamp(n: Int): String = "2026-11-14T${(n / 60).toString().padStart(2, '0')}:${(n % 60).toString().padStart(2, '0')}:00Z"
    }

    private val roster = List(ROSTER_SIZE) { Agent(AgentId("agent-$it"), name = "Agent $it") }
    private val pins = setOf(AgentId("agent-7"), AgentId("agent-42"))

    private fun hostRows() = roster.indices.map { HostRow(ConversationId("conv-$it"), AgentId("agent-$it"), at = ROSTER_SIZE - it) }

    private fun TestScope.repository(host: Host, scope: CoroutineScope = backgroundScope) = CachedAllConversationsRepository(
        listingFactory = repositoryConversationListingFactory(),
        roster = { roster }, remote = RemoteRoute.Absent(SessionRoute.CONVERSATIONS), repositoryScope = scope,
        irohConversationListSource = host.source(), transport = host, pinnedAgents = { pins },
    )

    private fun TestScope.reconnect(host: Host) {
        host.state.value = ChannelTransportState.Disconnected(1006, "offline")
        runCurrent()
        host.state.value = ChannelTransportState.Connected("server", "session", "device")
        runCurrent()
    }

    @Test
    fun reconnectsOnAHealthyHostReadOnlyRecentsNeverAnAgent() = runTest {
        val host = Host(hostRows())
        val repository = repository(host)
        runCurrent()
        repository.refresh()
        host.reset()

        repeat(RECONNECTS) { reconnect(host) }

        assertEquals(
            Counts(recents = RECONNECTS, agentLists = 0, gets = 0), host.counts(),
            "admin calls for $RECONNECTS reconnects of a $ROSTER_SIZE-agent roster; per-agent reads: ${host.agentLists}",
        )
    }

    @Test
    fun reconnectsOnAHostWithoutRecentsNeverSweepTheRoster() = runTest {
        val host = Host(hostRows(), visibilityIndex = false)
        val repository = repository(host)
        runCurrent()
        repository.refresh() // an explicit list open keeps a legacy host's per-agent reads
        assertEquals(ROSTER_SIZE, host.agentLists.size, "the explicit open reads each visible agent once")
        host.reset()

        repeat(RECONNECTS) { reconnect(host) }

        assertEquals(
            Counts(recents = RECONNECTS, agentLists = 0, gets = 0), host.counts(),
            "admin calls for $RECONNECTS reconnects of a legacy host; per-agent reads: ${host.agentLists.size}",
        )
    }

    @Test
    fun aTransientRefusalWhileLoadedDoesNotTurnLaterReconnectsIntoRosterSweeps() = runTest {
        val host = Host(hostRows())
        val repository = repository(host)
        runCurrent()
        repository.refresh()
        host.reset()

        host.appServerDown = true
        reconnect(host)
        assertEquals(Counts(recents = 1, agentLists = 0, gets = 0), host.counts(), "a down App Server costs one read")
        host.appServerDown = false
        host.reset()
        repeat(RECONNECTS) { reconnect(host) }

        assertEquals(
            Counts(recents = RECONNECTS, agentLists = 0, gets = 0), host.counts(),
            "admin calls after the App Server recovered; per-agent reads: ${host.agentLists.size}",
        )
    }

    @Test
    fun aTransientRefusalOnTheFirstReadIsAskedAgainNotRememberedAsNoRecents() = runTest {
        val host = Host(hostRows())
        val repository = repository(host)
        runCurrent()
        host.appServerDown = true
        runCatching { repository.refresh() }
        assertEquals(Counts(recents = 1, agentLists = 0, gets = 0), host.counts(), "a down App Server costs one read")
        host.appServerDown = false
        host.reset()

        repository.refresh()

        assertEquals(
            Counts(recents = 1, agentLists = pins.size, gets = 0), host.counts(),
            "the recovered refresh is the recents page plus the pinned agents; per-agent reads: ${host.agentLists.size}",
        )
    }

    @Test
    fun aReconnectBringsInWhatChangedWhileOfflineFromTheRecentsPage() = runTest {
        val host = Host(hostRows())
        val repository = repository(host)
        runCurrent()
        repository.refresh()
        host.reset()

        // One unlisted agent's conversation and one pinned agent's conversation changed while the app was offline.
        host.rows = host.rows.map {
            if (it.id.value in setOf("conv-100", "conv-7")) it.copy(summary = "fresh ${it.id.value}", at = ROSTER_SIZE * 2) else it
        }
        reconnect(host)

        val summaries = repository.conversations.value.associate { it.id.value to it.summary }
        assertEquals("fresh conv-100" to "fresh conv-7", summaries.getValue("conv-100") to summaries.getValue("conv-7"))
        assertEquals(Counts(recents = 1, agentLists = 0, gets = 0), host.counts())
    }

    @Test
    fun refreshesRequestedWhileOneIsInFlightShareOneMoreRead() = runTest {
        val host = Host(hostRows())
        val repository = repository(host)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        host.recentsGate = gate
        backgroundScope.launch { repository.refresh() }
        runCurrent()
        assertEquals(1, host.recentsReads, "the first refresh is in flight")

        repeat(CONCURRENT_REFRESHES) { backgroundScope.launch { repository.refresh() } }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(2, host.recentsReads, "$CONCURRENT_REFRESHES refreshes arriving during one: that one plus a single shared read")
    }

    private data class Counts(val recents: Int, val agentLists: Int, val gets: Int)

    private data class HostRow(val id: ConversationId, val agent: AgentId, val at: Int, val summary: String = id.value) {
        fun json(): JsonObject = buildJsonObject {
            put("id", id.value)
            put("agent_id", agent.value)
            put("summary", summary)
            put("archived", false)
            put("last_message_at", stamp(at))
            put("updated_at", stamp(at))
        }
    }

    /** The host's `conversation.list` / `conversation.get`, counting every call and refusing what the real one refuses. */
    private class Host(var rows: List<HostRow>, val visibilityIndex: Boolean = true) : NoOpChannelTransport() {
        override val events = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 32)
        override val state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Connected("server", "session", "device"))
        var appServerDown = false
        var recentsGate: CompletableDeferred<Unit>? = null
        var recentsReads = 0
        val agentLists = mutableListOf<String>()
        private var gets = 0

        fun reset() {
            recentsReads = 0
            gets = 0
            agentLists.clear()
        }

        fun counts() = Counts(recentsReads, agentLists.size, gets)

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

        private suspend fun list(params: JsonObject): AppServerInboundFrame.AdminRpcResponse {
            val agent = params.text("agent_id")
            val recents = (params["visible_only"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true &&
                listOf("agent_id", "summary_search", "archive_status", "order", "order_by").all { params.text(it) == null }
            if (recents) recentsReads++ else agentLists += requireNotNull(agent) { "the list never asks for every agent at once" }
            if (recents) recentsGate?.await()
            if (appServerDown) return refused(TRANSIENT)
            if (recents && !visibilityIndex) return refused(NO_INDEX)
            val own = rows.filter { recents || it.agent.value == agent }.sortedByDescending { it.at }
            val limit = params.text("limit")?.toIntOrNull() ?: if (recents) RECENTS_DEFAULT else own.size
            return AppServerInboundFrame.AdminRpcResponse(
                "request", success = true, result = JsonArray(own.take(limit).map(HostRow::json)),
            )
        }

        private fun get(id: String): AppServerInboundFrame.AdminRpcResponse {
            gets++
            val row = rows.firstOrNull { it.id.value == id } ?: return refused("conversation not found")
            return AppServerInboundFrame.AdminRpcResponse("request", success = true, result = row.json())
        }

        private fun refused(error: String) = AppServerInboundFrame.AdminRpcResponse("request", success = false, error = error)

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private companion object {
            const val RECENTS_DEFAULT = 30
        }
    }
}
