package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.model.AppServerListModelsAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the REAL `list_models_response` wire shape captured live from the
 * bundled `@letta-ai/letta-code` 0.29.12 runtime in `--backend local` mode
 * (letta-mobile debugging session: PR #1225 follow-up).
 *
 * Captured by spawning `node letta.js server --backend local --listen
 * ws://127.0.0.1:0` against a scratch `LETTA_LOCAL_BACKEND_DIR` with a
 * configured `lc-openai-compatible` provider (the exact provider name/type
 * [com.letta.mobile.data.runtime.LOCAL_RUNTIME_PROVIDER_NAME] writes),
 * sending a raw `{"type":"list_models","request_id":"..."}` command over a
 * plain WebSocket, and recording the response verbatim. This is what
 * grounds the claim that the runtime genuinely answers `list_models` (unlike
 * `admin_rpc`, which the bundle never implements at all) and that
 * [AppServerProtocol.decodeFrame] / [AppServerListModelsAdapter] round-trip
 * it without error — the request/response mismatch hypothesized as the
 * cause of an empty desktop model dropdown could not be reproduced against
 * the real runtime, including with the configured provider pointed at both
 * an unreachable address and a raw TCP socket that accepts and never
 * responds (simulating a hung/misconfigured litellm-style endpoint). All
 * four probed configurations answered in well under 100ms.
 */
class AppServerListModelsWireShapeTest {
    @Test
    fun realRuntimeListModelsResponseDecodesAndAdaptsCleanly() {
        val raw = AppServerListModelsWireShapeTest::class.java
            .getResourceAsStream("/appserver/probe-list-models-response.json")!!
            .bufferedReader()
            .readText()

        val received = AppServerProtocol.decodeFrame(raw)

        assertEquals(AppServerChannel.Control, received.channel)
        val response = received.frame as? AppServerInboundFrame.ListModelsResponse
            ?: error(
                "expected ListModelsResponse, got ${received.frame::class.simpleName}" +
                    ((received.frame as? AppServerInboundFrame.DecodeFailure)?.diagnostic?.let { " ($it)" } ?: ""),
            )
        assertEquals(true, response.success)
        val entries = response.entries
        assertTrue(entries != null && entries.isNotEmpty(), "expected a non-empty entries array")

        // Never throws even though the real payload carries fields this app's
        // AppServerListModelEntry doesn't model (isDefault/isFeatured/free) plus
        // top-level available_handles/byok_provider_aliases keys — ignoreUnknownKeys
        // plus JsonArray-typed `entries` keep this forward-compatible.
        val models = AppServerListModelsAdapter.toLlmModels(checkNotNull(entries))
        assertTrue(models.isNotEmpty(), "adapter should produce at least one model from a real catalog")
        assertTrue(models.any { it.handle?.startsWith("openai/") == true })
    }

    @Test
    fun listModelsCommandEncodesToTheExactShapeTheRuntimeAccepted() {
        val encoded = AppServerProtocol.encodeCommand(
            AppServerCommand.ListModels(requestId = "probe-encoded-1"),
        )
        // `force` is null and explicitNulls=false, so it must NOT appear on the
        // wire — verified by sending exactly this string to the live runtime.
        assertEquals("""{"type":"list_models","request_id":"probe-encoded-1"}""", encoded)
    }
}
