package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fail-on-revert coverage for the 2026-08-01 post-cutover regression: `block.list`
 * served the union of EVERY agent's memory files in one response (live: 153 agents
 * / 1447 blocks / ~1.83 MB), which exceeds the 1 MiB
 * [com.letta.mobile.data.transport.iroh.IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES]
 * admin_rpc frame cap, so the memory surfaces received nothing at all.
 *
 * The two properties pinned here are exactly the two that were broken:
 *  1. an unwindowed sweep is NOT served in a single frame-sized response;
 *  2. DIFFERENT offsets return DIFFERENT pages (the observed failure mode was
 *     page 1 for every offset, which is also what made the client's repeated-page
 *     guard surface a truncated count).
 */
class BlockListPagingRegressionTest {
    private val roots = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    /** A store big enough that the unpaged union blows the 1 MiB frame cap. */
    private fun oversizedStore(agents: Int = 60, labelsPerAgent: Int = 6): Pair<LocalBackendAdminStore, Int> {
        val root = Files.createTempDirectory("block-paging").toFile().also { roots += it }
        // ~5 KB per block is the BLOCK_VALUE_LIMIT ceiling admin-shim advertises.
        val value = "x".repeat(LocalBackendBlockReader.BLOCK_VALUE_LIMIT)
        repeat(agents) { a ->
            val agentId = "agent-$a"
            LocalBackendFixtureStore.writeAgent(root, agentId, name = "Agent $a")
            repeat(labelsPerAgent) { l ->
                LocalBackendFixtureStore.writeBlock(root, agentId, "label-$l", value)
            }
        }
        val store = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1")
        return store to agents * labelsPerAgent
    }

    @Test
    fun theFullUnionExceedsTheFrameCapWhileEveryPageStaysUnderIt() {
        val (store, total) = oversizedStore()

        var unionBytes = 0
        var offset = 0
        while (true) {
            val page = assertNotNull(store.listBlocksProjected(limit = 50, offset = offset))
            if (page.isEmpty()) break
            val bytes = page.toString().encodeToByteArray().size
            // THE property: no single response may exceed what the frame layer
            // can deliver. This is what the unwindowed union violated.
            assertTrue(bytes < FRAME_CAP_BYTES, "page at offset=$offset was $bytes bytes")
            unionBytes += bytes
            offset += page.size
        }
        assertEquals(total, offset, "the sweep must still reach every block")
        assertTrue(
            unionBytes > FRAME_CAP_BYTES,
            "fixture must reproduce the regression: the union does NOT fit in one frame ($unionBytes bytes)",
        )
    }

    @Test
    fun differentOffsetsReturnDifferentPages() {
        val (store, _) = oversizedStore()
        val first = idsOf(assertNotNull(store.listBlocksProjected(limit = 50, offset = 0)))
        val second = idsOf(assertNotNull(store.listBlocksProjected(limit = 50, offset = 50)))
        val third = idsOf(assertNotNull(store.listBlocksProjected(limit = 50, offset = 100)))

        assertEquals(50, first.size)
        assertEquals(50, second.size)
        assertEquals(50, third.size)
        // THE regression: the backend ignored limit/offset and re-served page 1.
        assertTrue(first.intersect(second).isEmpty(), "offset=50 must not re-serve the offset=0 page")
        assertTrue(second.intersect(third).isEmpty(), "offset=100 must not re-serve the offset=50 page")
        assertTrue(first.intersect(third).isEmpty(), "pages must be disjoint windows over one stable order")
    }

    @Test
    fun pagingIsStableAndCoversTheWholeSetExactlyOnce() {
        val (store, total) = oversizedStore()
        val swept = mutableListOf<String>()
        var offset = 0
        while (true) {
            val page = idsOf(assertNotNull(store.listBlocksProjected(limit = 50, offset = offset)))
            if (page.isEmpty()) break
            swept += page
            offset += page.size
        }
        assertEquals(total, swept.size, "a cursor sweep must reach every block")
        assertEquals(total, swept.toSet().size, "and must not serve any block twice")
    }

    @Test
    fun aPageOfHugeBlocksIsTrimmedToStayUnderTheFrameCap() {
        // Block values are whole files and are NOT truncated on read, so a page
        // bounded only by COUNT can still exceed the frame cap. Live measurement:
        // one 50-block page was already 394 KB.
        val root = Files.createTempDirectory("block-paging-huge").toFile().also { roots += it }
        val huge = "y".repeat(64 * 1024)
        LocalBackendFixtureStore.writeAgent(root, "agent-huge", name = "Huge")
        repeat(40) { LocalBackendFixtureStore.writeBlock(root, "agent-huge", "label-$it", huge) }
        val store = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1")

        val page = assertNotNull(store.listBlocksProjected(limit = 40, offset = 0))

        assertTrue(page.size < 40, "an oversized page must be trimmed, not served whole")
        assertTrue(
            page.toString().encodeToByteArray().size < FRAME_CAP_BYTES,
            "a trimmed page must fit in one admin_rpc frame",
        )
        // And the remainder must still be reachable by advancing the cursor.
        val next = assertNotNull(store.listBlocksProjected(limit = 40, offset = page.size))
        assertTrue(next.isNotEmpty(), "the trimmed remainder must still be pageable")
        assertTrue(idsOf(page).intersect(idsOf(next)).isEmpty())
    }

    @Test
    fun offsetPastTheEndIsAnEmptyPageNotAnError() {
        val (store, total) = oversizedStore()
        assertEquals(0, assertNotNull(store.listBlocksProjected(limit = 50, offset = total + 500)).size)
    }

    @Test
    fun handlerDefaultsToABoundedPageAndEnvelopesTheTotalWhenMoreExists() = runTest {
        val (store, total) = oversizedStore()
        val router = AdminRpcRouter()
        ToolAdminHandlers.register(router, store, nativeClient = null)

        // No limit at all: the pre-fix default was "everything", which is what
        // could not be delivered. The default must now be a bounded page.
        val defaulted = dispatchResult(router, buildJsonObject { })
        val envelope = assertNotNull(defaulted as? JsonObject)
        assertEquals(
            ToolAdminHandlers.DEFAULT_BLOCK_LIST_LIMIT,
            envelope.getValue("blocks").let { (it as JsonArray).size },
        )
        assertEquals(total, envelope.getValue("total").jsonPrimitive.content.toInt())
        assertTrue(envelope.getValue("has_more").jsonPrimitive.content.toBoolean())
        assertTrue(
            defaulted.toString().encodeToByteArray().size < FRAME_CAP_BYTES,
            "a default-limit page must fit in one admin_rpc frame",
        )
    }

    @Test
    fun handlerCapsAnOverlargeRequestedLimit() = runTest {
        // Small values, so the COUNT ceiling is what binds rather than the byte
        // budget — a client must not be able to request its way past either.
        val root = Files.createTempDirectory("block-paging-many").toFile().also { roots += it }
        repeat(20) { a ->
            LocalBackendFixtureStore.writeAgent(root, "agent-$a", name = "Agent $a")
            repeat(50) { l -> LocalBackendFixtureStore.writeBlock(root, "agent-$a", "label-$l", "tiny") }
        }
        val store = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1")
        val router = AdminRpcRouter()
        ToolAdminHandlers.register(router, store, nativeClient = null)

        val result = dispatchResult(router, buildJsonObject { put("limit", "100000") })
        val blocks = (result as JsonObject).getValue("blocks") as JsonArray
        assertEquals(ToolAdminHandlers.MAX_BLOCK_LIST_LIMIT, blocks.size)
    }

    @Test
    fun handlerServesABareArrayWhenTheWholeSetFitsSoOlderClientsAreUnaffected() = runTest {
        val root = Files.createTempDirectory("block-paging-small").toFile().also { roots += it }
        LocalBackendFixtureStore.create(root)
        val store = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1")
        val router = AdminRpcRouter()
        ToolAdminHandlers.register(router, store, nativeClient = null)

        val result = dispatchResult(router, buildJsonObject { })
        assertTrue(
            result is JsonArray,
            "the legacy bare-array shape is the back-compat signal for 'this is the full set'",
        )
    }

    private fun idsOf(page: JsonArray): List<String> =
        page.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    private suspend fun dispatchResult(router: AdminRpcRouter, params: JsonObject) =
        assertNotNull(
            kotlinx.serialization.json.Json
                .parseToJsonElement(
                    router.dispatch(
                        AdminRpcInvocation(
                            requestId = "t",
                            method = "block.list",
                            params = params,
                            context = AdminRpcRequestContext.Authenticated,
                        ),
                    ),
                )
                .jsonObject["result"],
        )

    private companion object {
        /** [com.letta.mobile.data.transport.iroh.IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES]. */
        const val FRAME_CAP_BYTES = 1_048_576
    }
}
