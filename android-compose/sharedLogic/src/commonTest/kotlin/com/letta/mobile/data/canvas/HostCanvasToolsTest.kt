package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The host's own canvas.* tools (letta-mobile-aknkw): an agent the host runs draws on the boards the
 * apps share, through the relay's log, with or without an app connected.
 */
class HostCanvasToolsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class Host {
        val store = InMemoryCanvasRelayStore()
        val relay = CanvasRelayHost(store, hostId = { "host-1" })
        val registry = ExternalToolRegistry.hostTools(
            HostCanvasTools.all(HostCanvasBackend(relay, store, InMemoryHostCanvasDirectory())),
        )

        suspend fun call(tool: String, input: JsonObject, agent: String?, conversation: String?) =
            registry.invoke(tool, input, agentId = agent, conversationId = conversation)
    }

    private fun input(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun addText(elementId: String, text: String) = CanvasOp.AddElementOp(
        opId = "model-chosen", actorId = "someone-else", lamport = 1L, elementId = elementId,
        elementJson = """{"id":"$elementId","type":"Text","text":"$text"}""",
    )

    private fun ops(vararg op: CanvasOp) = buildJsonObject {
        put("canvas_id", CanvasId.forConversation("conv-1").value)
        put("ops", JsonArray(op.map { json.encodeToJsonElement<CanvasOp>(it) }))
    }

    private fun Any.content(): String = assertIs<ExternalToolResult.Success>(this, "tool call failed: $this").content

    private fun Any.error(): String = assertIs<ExternalToolResult.Error>(this, "expected a refusal, got $this").error

    @Test
    fun theHostAdvertisesEveryCanvasTool() {
        val names = Host().registry.advertisedToolsCommandGroups()!!.flatMap { group -> group.tools.map { it.name } }
        assertEquals(CanvasToolContract.all.map { it.name }.toSet(), names.toSet())
    }

    @Test
    fun anAgentOnTheHostDrawsOnTheBoardBothAppsShow() = runTest {
        val host = Host()
        val phone = TestApp("phone", backgroundScope).open("conv-1", agentId = "agent-1")
        val desktop = TestApp("desktop", backgroundScope).open("conv-1", agentId = "agent-1")
        phone.connect(host.relay)
        desktop.connect(host.relay)
        runCurrent()

        val listed = host.call(CanvasToolContract.LIST, input(), "agent-1", "conv-1")
        val canvasId = CanvasId.forConversation("conv-1").value
        assertEquals(listOf(canvasId), json.decodeFromString<CanvasListResult>(listed.content()).ids)

        val applied = host.call(CanvasToolContract.APPLY_OPS, ops(addText("el-agent", "from the agent")), "agent-1", "conv-1")
        val revision = json.decodeFromString<CanvasApplyOpsResult>(applied.content()).revision
        runCurrent()

        assertTrue("from the agent" in phone.scene(), "the phone shows it: ${phone.scene()}")
        assertTrue("from the agent" in desktop.scene(), "the desktop shows it: ${desktop.scene()}")
        val logged = host.store.readAfter(CanvasRelayProtocol.conversationTopic("conv-1"), 0L).last()
        assertEquals("agent:agent-1", logged.origin, "the relay knows who it came from")
        assertEquals("agent-1", logged.op.actorId, "the op is the caller's, whatever actor the input named")
        assertEquals(revision, logged.cursor)

        val scene = host.call(CanvasToolContract.GET_SCENE, input("canvas_id" to canvasId), "agent-1", "conv-1")
        assertTrue("from the agent" in json.decodeFromString<CanvasGetSceneResult>(scene.content()).sceneJson)
    }

    @Test
    fun theToolsWorkWithNoAppConnectedAndAnAppOpeningLaterSeesTheChange() = runTest {
        val host = Host()
        host.call(CanvasToolContract.APPLY_OPS, ops(addText("el-offline", "drawn while you were away")), "agent-1", "conv-1").content()

        val phone = TestApp("phone", backgroundScope).open("conv-1", agentId = "agent-1")
        phone.connect(host.relay)
        runCurrent()
        assertTrue("drawn while you were away" in phone.scene(), phone.scene())
    }

    @Test
    fun anAgentCanReachOnlyItsOwnConversationsCanvas() = runTest {
        val host = Host()
        val canvasId = CanvasId.forConversation("conv-1").value
        host.call(CanvasToolContract.APPLY_OPS, ops(addText("el-1", "private")), "agent-1", "conv-1").content()

        val stranger = "agent-2" to "conv-2"
        val strangers = json.decodeFromString<CanvasListResult>(
            host.call(CanvasToolContract.LIST, input(), stranger.first, stranger.second).content(),
        ).ids
        assertEquals(listOf(CanvasId.forConversation("conv-2").value), strangers, "its own conversation's canvas only")
        assertEquals(
            emptyList(),
            json.decodeFromString<CanvasListResult>(
                host.call(CanvasToolContract.LIST, input("conversation_id" to "conv-1"), stranger.first, stranger.second).content(),
            ).ids,
        )
        assertTrue("cannot read" in host.call(CanvasToolContract.GET_SCENE, input("canvas_id" to canvasId), stranger.first, stranger.second).error())
        assertTrue("cannot read" in host.call(CanvasToolContract.APPLY_OPS, ops(addText("el-2", "vandal")), stranger.first, stranger.second).error())
        assertTrue(
            "cannot open" in host.call(CanvasToolContract.CREATE, input("conversation_id" to "conv-9"), stranger.first, stranger.second).error(),
            "nor claim a conversation that is not its own",
        )
    }

    @Test
    fun aCallWithNoRuntimeIdentityIsRefused() = runTest {
        val refused = Host().call(CanvasToolContract.LIST, input(), agent = null, conversation = null)
        assertTrue("authenticated agent identity" in refused.error())
    }

    @Test
    fun aCanvasCreatedOutsideAConversationIsTheCreatorsAlone() = runTest {
        val host = Host()
        val created = json.decodeFromString<CanvasCreateResult>(
            host.call(CanvasToolContract.CREATE, input("title" to "Plan"), "agent-1", "conv-1").content(),
        ).canvasId
        val mine = json.decodeFromString<CanvasListResult>(host.call(CanvasToolContract.LIST, input(), "agent-1", "conv-1").content()).ids
        assertTrue(created in mine)
        val replaced = host.call(
            CanvasToolContract.REPLACE_SCENE,
            input("canvas_id" to created, "scene_json" to """{"elements":[]}"""),
            "agent-1", "conv-1",
        )
        assertEquals(true, json.decodeFromString<CanvasReplaceSceneResult>(replaced.content()).ok)
        assertTrue("cannot read" in host.call(CanvasToolContract.GET_SCENE, input("canvas_id" to created), "agent-2", "conv-2").error())
    }

    @Test
    fun missingParametersAreNamed() = runTest {
        val host = Host()
        assertTrue("canvas_id" in host.call(CanvasToolContract.GET_SCENE, input(), "agent-1", "conv-1").error())
        assertTrue(
            "scene_json" in host.call(CanvasToolContract.REPLACE_SCENE, input("canvas_id" to "x"), "agent-1", "conv-1").error(),
        )
        val noOps = buildJsonObject { put("canvas_id", JsonPrimitive("x")) }
        assertTrue("ops" in host.call(CanvasToolContract.APPLY_OPS, noOps, "agent-1", "conv-1").error())
    }
}
