package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.model.CanvasId
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val CANVAS = CanvasId("canvas-input")

/** One malformed request and the exact refusal it must get. */
private class Malformed(val route: CanvasMountRoute, val params: JsonObject?, val field: String, val code: String) {
    override fun toString(): String = "${route.method} $params"
}

private fun bind(vararg changes: Pair<String, JsonElement?>): JsonObject {
    val fields = bindingParams(AGENT_A, CONV_A, CANVAS).toMutableMap()
    changes.forEach { (key, value) -> if (value == null) fields.remove(key) else fields[key] = value }
    return JsonObject(fields)
}

private fun text(value: String) = JsonPrimitive(value)

/** UTF-8 bytes, not characters, bound an id: 257 two-byte characters are 514 bytes. */
private val TOO_LONG = "\u00e9".repeat(AuthorizedCanvasMounts.MAX_ID_BYTES / 2 + 1)

private val CASES = listOf(
    Malformed(CanvasMountRoute.BIND, null, "params", "missing"),
    Malformed(CanvasMountRoute.BIND, JsonObject(emptyMap()), "agent_id", "missing"),
    Malformed(CanvasMountRoute.BIND, bind(CanvasMountWire.AGENT_ID to JsonPrimitive(5)), "agent_id", "not_string"),
    Malformed(CanvasMountRoute.BIND, bind(CanvasMountWire.AGENT_ID to JsonNull), "agent_id", "not_string"),
    Malformed(CanvasMountRoute.BIND, bind(CanvasMountWire.CONVERSATION_ID to text("   ")), "conversation_id", "blank"),
    Malformed(CanvasMountRoute.BIND, bind(CanvasMountWire.CONVERSATION_ID to text(TOO_LONG)), "conversation_id", "too_long"),
    Malformed(CanvasMountRoute.BIND, bind(CanvasMountWire.CANVAS_ID to null), "canvas_id", "missing"),
    Malformed(CanvasMountRoute.BIND, bind(CanvasMountWire.CANVAS_ID to text("canvas-\u202eevil")), "canvas_id", "malformed"),
    Malformed(CanvasMountRoute.BIND, bind(CanvasMountWire.CANVAS_ID to text("canvas-\u200bzero")), "canvas_id", "malformed"),
    Malformed(CanvasMountRoute.BIND, bind(CanvasMountWire.CANVAS_ID to text("canvas-\nline")), "canvas_id", "malformed"),
    Malformed(CanvasMountRoute.BIND, bind("note" to text("anything")), "params", "unknown_field"),
    Malformed(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A, CANVAS), "params", "unknown_field"),
    Malformed(CanvasMountRoute.SET_DEFAULT, bindingParams(AGENT_A, CONV_A, CANVAS), "params", "unknown_field"),
    Malformed(CanvasMountRoute.REPLACE, bind(CanvasMountWire.AGENT_ID to JsonObject(emptyMap())), "agent_id", "not_string"),
)

class CanvasMountInputTest {
    @Test
    fun malformedParamsGetFixedCodesAndNeverReachStorage() = runBlocking {
        val harness = MountHarness()
        harness.grant(PEER, CANVAS, CanvasMountGrant.MOUNT)
        CASES.forEach { case ->
            val reply = harness.call(case.route, case.params, PEER)
            assertEquals("${CanvasMountWire.INVALID_INPUT_PREFIX}:${case.field}:${case.code}", reply.error, "$case")
        }
        assertTrue(Files.notExists(harness.root.resolve("canvas-mounts")), "an invalid request touched storage")
    }

    @Test
    fun anIdOfExactlyTheByteLimitIsAccepted() = runBlocking {
        val harness = MountHarness()
        val canvas = CanvasId("c".repeat(AuthorizedCanvasMounts.MAX_ID_BYTES))
        harness.grant(PEER, canvas, CanvasMountGrant.MOUNT)
        val reply = harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, canvas), PEER)
        assertEquals(CanvasMountWire.BOUND to canvas.value, reply.status to reply.canvas)
    }
}
