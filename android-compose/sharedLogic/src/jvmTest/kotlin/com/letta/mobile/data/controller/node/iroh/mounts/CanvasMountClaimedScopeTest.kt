package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.CanvasListResult
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.canvas.HostCanvasTools
import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.scope.CallerScopeTrust
import com.letta.mobile.data.meridian.endpoint.MeridianCallerBinder
import com.letta.mobile.data.meridian.endpoint.MeridianCallerBindingMode
import com.letta.mobile.data.meridian.endpoint.MeridianLiveCalls
import com.letta.mobile.data.model.CanvasId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A canvas only the relay holds (made by an app): its ACL admits no agent, so only a mount grant could reach it. */
private val APP_BOARD = CanvasId("canvas-app-only-board")

private val NAMES_NOTHING = JsonObject(emptyMap())

/**
 * The mount grant follows only a scope the host itself established. Under env-scoped Meridian binding an agent's
 * shell environment names its agent and conversation, so anyone can claim the pair a canvas is mounted for: such a
 * caller must get exactly what it gets on a host without mounts (its ACL), never the mounted canvas.
 */
class CanvasMountClaimedScopeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val harness = MountHarness()
    private val records = harness.records
    private val backend = HostCanvasBackend(
        CanvasRelayHost(records.relay, hostId = { "host" }),
        records.relay,
        records.canvases,
        mounts = CanvasMountToolLookup(
            { authority -> AuthorizedCanvasMounts(harness.store(), authority) },
            testDeadline(10.seconds, 1.seconds),
        ),
    )
    private val tools = ExternalToolRegistry.hostTools(HostCanvasTools.all(backend))
    private val envScoped = MeridianCallerBinder(
        MeridianCallerBindingMode.ENV_SCOPED,
        MeridianLiveCalls(nowMs = { 0L }, newToken = { "t" }),
    )

    @Test
    fun anEnvScopedCallerGetsNoGrantOnARelayOnlyCanvasMountedForItsClaimedPair() = runBlocking {
        records.canvas(APP_BOARD, owner = null)
        assertEquals(CanvasMountWire.REPLACED, mount(APP_BOARD).status)
        val claimed = envScoped.bind(null, AGENT_A.value, CONV_A.value).getOrThrow()
        assertEquals(CallerScopeTrust.CLAIMED, claimed.scopeTrust)

        assertTrue("Canvas not found" in refusal(CanvasToolContract.GET_SCENE, named(APP_BOARD), claimed))
        assertTrue("Canvas not found" in refusal(CanvasToolContract.APPLY_OPS, ops("claimed-write", APP_BOARD), claimed))
        // Its board choice ignores the mount: a call naming no canvas acts on the conversation's own board.
        success(CanvasToolContract.APPLY_OPS, ops("own-board"), claimed)
        val listed = list(claimed)
        assertTrue(listed.canvases.none { it.canvasId == APP_BOARD.value }, "the mounted canvas is not listed")
        assertEquals(CanvasId.forConversation(CONV_A).value, listed.canvases.first { it.current }.canvasId)
        assertEquals(0L, records.relay.head(com.letta.mobile.data.canvas.CanvasRelayProtocol.canvasTopic(APP_BOARD)))
    }

    @Test
    fun anEnvScopedCallerGetsNoGrantOnAnotherAgentsCanvasMountedForItsClaimedPair() = runBlocking {
        val board = backend.create(HostCanvasCaller(AGENT_B.value, CONV_B.value), "Agent B's board")
        mount(board.id)
        val claimed = envScoped.bind(null, AGENT_A.value, CONV_A.value).getOrThrow()

        assertTrue("cannot read" in refusal(CanvasToolContract.GET_SCENE, named(board.id), claimed))
        assertTrue("cannot read" in refusal(CanvasToolContract.APPLY_OPS, ops("claimed-write", board.id), claimed))
        assertEquals(CanvasId.forConversation(CONV_A), backend.currentCanvas(HostCanvasCaller(AGENT_A.value, CONV_A.value)))
    }

    @Test
    fun aRuntimeStampedCallerForTheSamePairStillGetsTheGrant() = runBlocking {
        records.canvas(APP_BOARD, owner = null)
        mount(APP_BOARD)
        val stamped = ExternalToolCaller(AGENT_A.value, CONV_A.value, scopeTrust = CallerScopeTrust.RUNTIME_STAMPED)

        success(CanvasToolContract.APPLY_OPS, ops("stamped-write"), stamped)
        assertTrue("stamped-write" in success(CanvasToolContract.GET_SCENE, named(APP_BOARD), stamped))
        assertEquals(APP_BOARD.value, list(stamped).canvases.first { it.current }.canvasId)
    }

    private suspend fun mount(canvas: CanvasId): Reply =
        harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, canvas), PEER)

    private suspend fun list(caller: ExternalToolCaller): CanvasListResult =
        json.decodeFromString(success(CanvasToolContract.LIST, NAMES_NOTHING, caller))

    private suspend fun success(tool: String, input: JsonObject, caller: ExternalToolCaller): String =
        assertIs<ExternalToolResult.Success>(tools.invoke(tool, input, caller), "tool call failed").content

    private suspend fun refusal(tool: String, input: JsonObject, caller: ExternalToolCaller): String =
        assertIs<ExternalToolResult.Error>(tools.invoke(tool, input, caller), "expected a refusal").error

    private fun named(canvas: CanvasId) = buildJsonObject { put("canvas_id", canvas.value) }

    private fun ops(text: String, canvas: CanvasId? = null) = buildJsonObject {
        canvas?.let { put("canvas_id", it.value) }
        put("ops", JsonArray(listOf(json.encodeToJsonElement<CanvasOp>(addText(text)))))
    }

    private fun addText(text: String) = CanvasOp.AddElementOp(
        opId = "op-$text", actorId = "anyone", lamport = 1L, elementId = "el-$text",
        elementJson = buildJsonObject {
            put("id", "el-$text")
            put("type", "Text")
            put("text", text)
            put("textTopLeft", "10.0,10.0")
        }.toString(),
    )
}
