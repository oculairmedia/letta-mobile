package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.model.CanvasId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The shared fixtures of `docs/reference/contracts/canvas-mount-v1.md`: every case's params get exactly the parse
 * the host applies, and every result re-encodes byte for byte through the host's own outcome encoder.
 */
class CanvasMountContractFixturesTest {
    @Test
    fun everyRouteHasFixturesAndNothingElseDoes() {
        assertEquals(CanvasMountWire.METHODS, CanvasMountRoute.entries.map { fixture(it).getValue("method").jsonPrimitive.content }.toSet())
        CanvasMountRoute.entries.forEach {
            assertEquals(CanvasMountWire.CAPABILITY, fixture(it).getValue("capability").jsonPrimitive.content)
        }
    }

    @Test
    fun paramsParseExactlyAsDocumentedAndResultsReencodeUnchanged() {
        CanvasMountRoute.entries.forEach { route ->
            fixture(route).getValue("cases").jsonArray.map { it.jsonObject }.forEach { case -> check(route, case) }
        }
    }

    private fun check(route: CanvasMountRoute, case: JsonObject) {
        val label = "${route.method}/${case.getValue("name").jsonPrimitive.content}"
        val parsed = route.parse(case.getValue("params").jsonObject)
        val error = case["error"]?.jsonPrimitive?.content
        val result = case["result"]?.jsonObject
        assertTrue((error == null) != (result == null), "$label has exactly one outcome")
        when {
            error?.startsWith(CanvasMountWire.INVALID_INPUT_PREFIX) == true -> assertEquals(error, refusal(parsed), label)
            else -> assertIs<CanvasMountInput>(parsed, label)
        }
        if (error != null) assertTrue(error.startsWith(CanvasMountWire.INVALID_INPUT_PREFIX) || error == CanvasMountWire.DENIED, label)
        result?.let { assertEquals(it, decode(it).body(durabilityOf(it)), label) }
    }

    private fun refusal(parsed: CanvasMountParsed): String {
        val invalid = assertIs<CanvasMountOutcome.InvalidInput>(parsed)
        return "${CanvasMountWire.INVALID_INPUT_PREFIX}:${invalid.field.wire}:${invalid.problem.wire}"
    }

    private fun decode(result: JsonObject): CanvasMountOutcome {
        val canvas = result[CanvasMountWire.CANVAS_ID]?.jsonPrimitive?.content?.let(::CanvasId)
        return when (result.getValue(CanvasMountWire.STATUS).jsonPrimitive.content) {
            CanvasMountWire.FOUND -> CanvasMountOutcome.Found(checkNotNull(canvas), source(result))
            CanvasMountWire.BOUND ->
                CanvasMountOutcome.Bound(checkNotNull(canvas), result.getValue(CanvasMountWire.WON).jsonPrimitive.boolean)
            CanvasMountWire.REPLACED -> CanvasMountOutcome.Replaced(checkNotNull(canvas))
            CanvasMountWire.DEFAULT_SET -> CanvasMountOutcome.DefaultSet(checkNotNull(canvas))
            else -> plain(result)
        }
    }

    private fun plain(result: JsonObject): CanvasMountOutcome = when (result.getValue(CanvasMountWire.STATUS).jsonPrimitive.content) {
        CanvasMountWire.NONE -> CanvasMountOutcome.None
        CanvasMountWire.OUTCOME_UNKNOWN -> CanvasMountOutcome.OutcomeUnknown
        else -> CanvasMountOutcome.Unavailable(
            CanvasMountUnavailable.entries.single { it.wire == result.getValue(CanvasMountWire.REASON).jsonPrimitive.content },
        )
    }

    private fun source(result: JsonObject): CanvasMountSource =
        CanvasMountSource.entries.single { it.wire == result.getValue(CanvasMountWire.SOURCE).jsonPrimitive.content }

    private fun durabilityOf(result: JsonObject): CanvasMountDurability {
        val wire = result[CanvasMountWire.DURABILITY]?.jsonPrimitive?.content ?: return CanvasMountDurability.DURABLE
        return CanvasMountDurability.entries.single { it.wire == wire }
    }

    private fun fixture(route: CanvasMountRoute): JsonObject {
        val text = checkNotNull(javaClass.getResource("/canvas-mount/v1/${route.method}.json")) { route.method }.readText()
        return Json.parseToJsonElement(text).jsonObject
    }
}
