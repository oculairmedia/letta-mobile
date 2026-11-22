package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.ConnectionRegistry
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.ViewerHandle
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every `feed-delta/v1.json` case through the real journal, the real connection registry and the real [FeedPusher]: each
 * viewer is registered under the id it views, and the frames the host wrote to it must equal the fixture's, exactly. A viewer
 * that is out of scope, that is bound to a conversation it must not push, or that never read the feed receives nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedDeltaFixtureTest {
    private val fixture = Json.parseToJsonElement(
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "scripts/ci/check-wire-contracts.py").isFile }
            .resolve("android-compose/sharedLogic/src/commonTest/resources/contracts/feed-delta/v1.json").readText(),
    ).jsonObject

    private class Viewer(
        override val connectionId: String,
        private val pushes: Boolean,
        private val agents: Set<String>?,
        private val bound: Set<String>?,
    ) : ViewerHandle {
        val frames = mutableListOf<JsonObject>()
        override suspend fun writeFrame(frame: String): Boolean = frames.add(Json.parseToJsonElement(frame).jsonObject)
        override fun receivesFeedPushes(): Boolean = pushes
        override fun adminContext() = AdminRpcRequestContext(
            authenticated = true, authorizedAgentIds = agents, authorizedConversationIds = bound,
            grantedCapabilities = IrohPeerCapabilities.CHAT_ROLE,
        )
    }

    private fun JsonObject.strings(name: String): Set<String>? = this[name]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()

    @Test
    fun everyCaseDeliversExactlyTheFixturesFramesToExactlyItsViewers() = runTest {
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 6)
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val journal = FeedJournalStore(java.nio.file.Files.createTempDirectory("feed-delta"), FeedRetention()) { 1000 }
            val registry = ConnectionRegistry()
            FeedPusher(backgroundScope, journal, { registry.viewersFor(it.value) }, registry::isViewed, 16)
            val viewers = case.getValue("viewers").jsonObject.mapValues { (id, spec) ->
                val fields = spec.jsonObject
                Viewer(id, fields.getValue("pushes").jsonPrimitive.boolean, fields.strings("authorized_agent_ids"), fields.strings("bound_conversation_ids"))
                    .also { registry.register(fields.getValue("conversation").jsonPrimitive.content, it) }
            }
            case.getValue("writes").jsonArray.map { it.jsonObject }.forEach { write ->
                val target = Conv(AgentId(write.getValue("agent_id").jsonPrimitive.content), ConversationId(write.getValue("conversation_id").jsonPrimitive.content))
                val writer = journal.writerFor(target, TurnName(write.getValue("turn").jsonPrimitive.content))
                write.getValue("deltas").jsonArray.forEach { writer.append(it.jsonObject.toString()) }
                if (write.getValue("close").jsonPrimitive.boolean) writer.close(true)
            }
            advanceTimeBy(40)
            runCurrent()
            val expected = case.getValue("expected_frames").jsonObject
            assertEquals(expected.keys, viewers.keys, name)
            viewers.forEach { (id, viewer) ->
                assertEquals(expected.getValue(id).jsonArray, JsonArray(viewer.frames), "$name: frames of $id")
            }
        }
    }
}
