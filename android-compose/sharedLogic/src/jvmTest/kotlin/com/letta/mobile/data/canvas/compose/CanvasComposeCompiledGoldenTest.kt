package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasOp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The compiled goldens (letta-mobile-bglj6.10): `compiled-ops.json` is exactly the batch the request
 * fixture compiles to on an empty board, the batch `CanvasBatchValidator.check` receives; and
 * `receipt-dry-run.json` is exactly what the dry-run fixture answers. A change to placement, the
 * reserve, the codec or the colours fails here by design: update the fixture with the change.
 */
class CanvasComposeCompiledGoldenTest {
    private fun text(name: String): String = checkNotNull(javaClass.getResource("$DIR/$name")) { "missing fixture $name" }.readText()

    private fun fixture(name: String): JsonElement = ComposeJson.parse(text(name))

    @Test
    fun theRequestFixtureCompilesToTheGoldenBatch() {
        val ready = readyOf(CanvasComposeCompiler.compile(fixture("request.json"), "") { error("the request names its artifact") })
        val encoded = OPS_JSON.encodeToJsonElement(ListSerializer(CanvasOp.serializer()), ready.ops)
        assertEquals(ComposeJson.canonical(fixture("compiled-ops.json")), ComposeJson.canonical(encoded))
    }

    @Test
    fun theGoldenBatchDecodesToTheSameOps() {
        val ready = readyOf(CanvasComposeCompiler.compile(fixture("request.json"), "") { error("unused") })
        assertEquals(ready.ops, OPS_JSON.decodeFromString(ListSerializer(CanvasOp.serializer()), text("compiled-ops.json")))
    }

    @Test
    fun theGoldenWritesTheGeometryOwnerInLowerCase() {
        val golden = text("compiled-ops.json")
        assertTrue("\"owner\": \"auto\"" in golden)
        assertTrue("\"AUTO\"" !in golden)
    }

    @Test
    fun theDryRunFixtureAnswersTheGoldenReceipt() = runTest {
        val outcome = CanvasComposeService.compose(
            fixture("request-dry-run.json"), ComposeTarget("canvas-conversation-conv-123", "", 7, "call-1"),
            publish = { error("a dry run must not publish") },
        )
        val done = assertIs<ComposeOutcome.Done>(outcome)
        assertEquals(ComposeJson.canonical(fixture("receipt-dry-run.json")), ComposeJson.canonical(ComposeJson.parse(done.json)))
    }

    private companion object {
        const val DIR = "/canvas/compose/v1"

        /** How the op log writes ops: no nulls, and an absent optional field stays absent. */
        val OPS_JSON = Json {
            encodeDefaults = false
            explicitNulls = false
        }
    }
}
