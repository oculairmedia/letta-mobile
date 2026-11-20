package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AgentScopeGuard
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.model.AgentId
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `feed.subscribe` through the real router: every refusal the real handlers apply to `message.list` (conversation scope)
 * and to agent-addressed frames (agent scope), plus the cases the journal's own keying adds (a conversation id borrowed
 * under another agent, an alias, an owner nobody recorded).
 */
class FeedRoutesTest {
    private val dir = createTempDirectory("feed-routes")
    private val journal = FeedJournalStore(dir)
    private val router = AdminRpcRouter().also { FeedRoutes.forHost(journal).register(it) }

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val unscoped = AdminRpcRequestContext(authenticated = true)

    private fun agentScoped(agents: Set<AgentId>) =
        AdminRpcRequestContext(authenticated = true, authorizedAgentIds = agents.map { it.value }.toSet())

    private fun call(params: JsonObject, context: AdminRpcRequestContext = unscoped): JsonObject = runBlocking {
        Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", ConversationFeedWire.SUBSCRIBE, params, context))).jsonObject
    }

    private fun params(conversation: String, agent: String? = null) = buildJsonObject {
        put(ConversationFeedWire.CONVERSATION_ID, conversation)
        agent?.let { put(ConversationFeedWire.AGENT_ID, it) }
    }

    private fun JsonObject.ok() = this["success"]!!.jsonPrimitive.boolean

    private fun JsonObject.error() = this["error"]?.jsonPrimitive?.content

    private fun JsonObject.entries() = this["result"]!!.jsonObject["entries"]!!.jsonArray

    private fun seedConversation(agent: AgentId, conversation: String, vararg deltas: Delta) {
        journal.write(Conv.of(conversation, agent), TurnScript(TurnName("cm-$conversation"), deltas.toList()))
    }

    @Test
    fun theRouteAdvertisesTheCapabilityRegistersOneMethodAndClassifiesItAsAChatRead() {
        assertEquals(setOf(ConversationFeedWire.SUBSCRIBE), router.registeredMethods)
        assertEquals(setOf(ConversationFeedWire.CAPABILITY), router.featureCapabilities)
        assertEquals(IrohPeerCapabilities.CHAT_READ, IrohPeerCapabilities.forAdminMethod(ConversationFeedWire.SUBSCRIBE))
        assertTrue(IrohPeerCapabilities.isAllowed(IrohPeerCapabilities.CHAT_ROLE, IrohPeerCapabilities.CHAT_READ))
        assertFalse(IrohPeerCapabilities.isAllowed(emptySet(), IrohPeerCapabilities.forAdminMethod(ConversationFeedWire.SUBSCRIBE)))
    }

    @Test
    fun anUnscopedPeerReadsTheJournalOfAnyConversation() {
        seedConversation(Ids.agent, "conv-1", Delta.toolCall(CallId("t1")))

        val reply = call(params("conv-1"))

        assertTrue(reply.ok())
        assertEquals(3, reply.entries().size)
        assertEquals("snapshot", reply["result"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun aScopedViewerCannotReadAnotherAgentsJournalByNamingItsConversationUnderItsOwnAgent() {
        seedConversation(Ids.otherAgent, "conv-secret", Delta.toolCall(CallId("secret")))

        val borrowed = call(params("conv-secret", agent = Ids.agent.value), agentScoped(setOf(Ids.agent)))
        val unnamed = call(params("conv-secret"), agentScoped(setOf(Ids.agent)))
        val direct = call(params("conv-secret", agent = Ids.otherAgent.value), agentScoped(setOf(Ids.agent)))

        listOf(borrowed, unnamed, direct).forEach { reply ->
            assertFalse(reply.ok())
            assertEquals(AgentScopeGuard.DENIED, reply.error())
        }
    }

    @Test
    fun aScopedViewerReadsItsOwnAgentsJournalEvenWhenItNamesNoAgent() {
        seedConversation(Ids.agent, "conv-mine", Delta.toolCall(CallId("mine")))

        val unnamed = call(params("conv-mine"), agentScoped(setOf(Ids.agent)))
        val named = call(params("conv-mine", agent = Ids.agent.value), agentScoped(setOf(Ids.agent)))

        assertEquals(3, unnamed.entries().size)
        assertEquals(unnamed["result"], named["result"])
    }

    @Test
    fun aScopedViewerAskingForAnOwnerlessConversationIsRefusedNotAnsweredEmpty() {
        val reply = call(params("conv-nobody-recorded"), agentScoped(setOf(Ids.agent)))

        assertFalse(reply.ok(), "an empty page would tell the peer the conversation is someone else's")
        assertEquals(AgentScopeGuard.DENIED, reply.error())
    }

    @Test
    fun aDefaultConversationAliasResolvesItsOwnerAndTheAliasOfAnotherAgentIsRefused() {
        seedConversation(Ids.agent, "default", Delta.toolCall(CallId("mine")))
        seedConversation(Ids.otherAgent, "default", Delta.toolCall(CallId("theirs")))

        val mine = call(params("conv-default-${Ids.agent}"), agentScoped(setOf(Ids.agent)))
        val theirs = call(params("conv-default-${Ids.otherAgent}"), agentScoped(setOf(Ids.agent)))
        val bare = call(params("default", agent = Ids.agent.value), agentScoped(setOf(Ids.agent)))
        val bareOther = call(params("default", agent = Ids.otherAgent.value), agentScoped(setOf(Ids.agent)))
        val bareUnnamed = call(params("default"), agentScoped(setOf(Ids.agent)))

        assertTrue(mine.ok())
        assertEquals(3, mine.entries().size)
        assertEquals(mine["result"]!!.jsonObject["entries"], bare["result"]!!.jsonObject["entries"])
        assertEquals(AgentScopeGuard.DENIED, theirs.error())
        assertEquals(AgentScopeGuard.DENIED, bareOther.error())
        assertEquals(
            ConversationFeedWire.INVALID_REQUEST,
            bareUnnamed.error(),
            "the bare default names no agent, so the request is malformed (the v1 contract requires agent_id with it)",
        )
    }

    @Test
    fun aPeerBoundToOneConversationCannotReadAnother() {
        seedConversation(Ids.agent, "conv-viewed", Delta.toolCall(CallId("a")))
        seedConversation(Ids.agent, "conv-other", Delta.toolCall(CallId("b")))
        val bound = AdminRpcRequestContext(authenticated = true, authorizedConversationIds = setOf("conv-viewed"))

        assertTrue(call(params("conv-viewed"), bound).ok())
        val denied = call(params("conv-other"), bound)

        assertFalse(denied.ok())
        assertEquals("forbidden: conversation out of authorized scope", denied.error())
    }

    @Test
    fun aPeerBoundToTheBareDefaultCannotReadAnotherInScopeAgentsDefaultJournal() {
        seedConversation(Ids.agent, "default", Delta.toolCall(CallId("mine")))
        seedConversation(Ids.otherAgent, "default", Delta.toolCall(CallId("theirs")))
        val bound = AdminRpcRequestContext(
            authenticated = true,
            authorizedConversationIds = setOf("default"),
            authorizedAgentIds = setOf(Ids.agent.value, Ids.otherAgent.value),
        )

        val other = call(params("default", agent = Ids.otherAgent.value), bound)
        val own = call(params("default", agent = Ids.agent.value), bound)

        assertFalse(other.ok(), "the bare default is ambiguous: naming an in-scope agent must not select its journal")
        assertFalse(own.ok(), "so a bounded peer gets no bare-default reads at all; it uses its conv-default alias")
        assertEquals("forbidden: conversation out of authorized scope", other.error())
    }

    @Test
    fun aPeerBoundToItsDefaultAliasReadsThatAgentsDefaultJournalOnly() {
        seedConversation(Ids.agent, "default", Delta.toolCall(CallId("mine")))
        seedConversation(Ids.otherAgent, "default", Delta.toolCall(CallId("theirs")))
        val alias = "conv-default-${Ids.agent}"
        val bound = AdminRpcRequestContext(
            authenticated = true,
            authorizedConversationIds = setOf(alias),
            authorizedAgentIds = setOf(Ids.agent.value, Ids.otherAgent.value),
        )

        assertTrue(call(params(alias), bound).ok())
        assertFalse(call(params("conv-default-${Ids.otherAgent}"), bound).ok())
    }

    @Test
    fun aBareDefaultWithoutAnAgentIsInvalidWhetherOrNotThePeerIsScoped() {
        seedConversation(Ids.agent, "default", Delta.toolCall(CallId("mine")))

        assertEquals(ConversationFeedWire.INVALID_REQUEST, call(params("default")).error())
        assertEquals(ConversationFeedWire.INVALID_REQUEST, call(params("default"), agentScoped(setOf(Ids.agent))).error())
        assertTrue(call(params("default", agent = Ids.agent.value)).ok(), "naming the agent is the valid form")
    }

    @Test
    fun aHostWhoseEpochCouldNotBePersistedAnswersUnavailableNeverAFeed() {
        val dir2 = createTempDirectory("feed-unpersisted")
        try {
            dir2.resolve("epoch").createDirectories().resolve("blocker").writeText("x")
            val unpersisted = AdminRpcRouter().also { FeedRoutes.forHost(FeedJournalStore(dir2)).register(it) }

            val reply = runBlocking {
                Json.parseToJsonElement(
                    unpersisted.dispatch(AdminRpcInvocation("r", ConversationFeedWire.SUBSCRIBE, params("conv-1"), unscoped)),
                ).jsonObject
            }

            assertFalse(reply.ok())
            assertTrue(reply.error()!!.startsWith("unavailable"), reply.error())
        } finally {
            dir2.toFile().deleteRecursively()
        }
    }

    @Test
    fun anUnauthenticatedPeerIsRefused() {
        seedConversation(Ids.agent, "conv-1", Delta.toolCall(CallId("t1")))

        val reply = call(params("conv-1"), AdminRpcRequestContext(authenticated = false))

        assertFalse(reply.ok())
    }

    @Test
    fun aRequestNamingTwoDifferentAgentsIsRefusedByTheRouterBeforeTheHandler() {
        seedConversation(Ids.agent, "conv-1", Delta.toolCall(CallId("t1")))
        val smuggled = buildJsonObject {
            put(ConversationFeedWire.CONVERSATION_ID, "conv-1")
            put(ConversationFeedWire.AGENT_ID, Ids.agent.value)
            put("runtime", buildJsonObject { put("agent_id", Ids.otherAgent.value) })
        }

        assertEquals(AgentScopeGuard.DENIED, call(smuggled, agentScoped(setOf(Ids.agent))).error())
    }

    @Test
    fun malformedRequestsAreInvalid() {
        fun invalid(params: JsonObject) = assertEquals(ConversationFeedWire.INVALID_REQUEST, call(params).error(), params.toString())
        invalid(JsonObject(emptyMap()))
        invalid(buildJsonObject { put(ConversationFeedWire.CONVERSATION_ID, " ") })
        invalid(buildJsonObject { put(ConversationFeedWire.CONVERSATION_ID, 7) })
        invalid(buildJsonObject { put(ConversationFeedWire.CONVERSATION_ID, "c"); put(ConversationFeedWire.LIMIT, 0) })
        invalid(buildJsonObject {
            put(ConversationFeedWire.CONVERSATION_ID, "c")
            put(ConversationFeedWire.LIMIT, ConversationFeedWire.MAX_LIMIT + 1)
        })
        invalid(buildJsonObject { put(ConversationFeedWire.CONVERSATION_ID, "c"); put(ConversationFeedWire.LIMIT, "ten") })
        invalid(buildJsonObject { put(ConversationFeedWire.CONVERSATION_ID, "c"); put(ConversationFeedWire.FROM, "x") })
        invalid(buildJsonObject {
            put(ConversationFeedWire.CONVERSATION_ID, "c")
            put(ConversationFeedWire.FROM, buildJsonObject { put("epoch", 1) })
        })
        invalid(buildJsonObject {
            put(ConversationFeedWire.CONVERSATION_ID, "c")
            put(ConversationFeedWire.FROM, buildJsonObject { put("epoch", -1); put("seq", 0) })
        })
        invalid(buildJsonObject {
            put(ConversationFeedWire.CONVERSATION_ID, "c")
            put(ConversationFeedWire.FROM, buildJsonObject { put("epoch", "1"); put("seq", 0) })
        })
    }

    @Test
    fun resumingThroughTheRouteReturnsOnlyWhatFollowsTheCursor() {
        seedConversation(Ids.agent, "conv-1", Delta.toolCall(CallId("t1")))
        val first = call(params("conv-1")).get("result")!!.jsonObject
        val cursor = first["cursor"]!!.jsonObject

        val next = TurnScript(TurnName("cm-next"), listOf(Delta.toolCall(CallId("t2")), Delta.stopReason()), Ending.Terminal)
        journal.write(Conv.of("conv-1"), next)
        val resumed = call(buildJsonObject {
            put(ConversationFeedWire.CONVERSATION_ID, "conv-1")
            put(ConversationFeedWire.FROM, cursor)
        })["result"]!!.jsonObject

        assertEquals("replay", resumed["mode"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("turn_open", "stream_delta", "stream_delta", "turn_closed"),
            resumed["entries"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content },
        )
        val after = cursor["seq"]!!.jsonPrimitive.content.toLong()
        assertTrue(resumed["entries"]!!.jsonArray.all { it.jsonObject["seq"]!!.jsonPrimitive.content.toLong() > after })
        assertEquals(JsonPrimitive(false), resumed["has_more"])
    }
}
