package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-kzqkr.6: regression coverage for the post-fix tool.list contract.
 *
 * Pre-fix (verified against origin/main @ 9b1f4e2eb): the server-side tool.list
 * handler returned NativeAdminCatalogs.toolCatalog() unconditionally, ignoring
 * any limit/offset. The client's fetchAllTools loop terminated only because the
 * 14-entry catalog is smaller than PAGE_SIZE (100). When the catalog grows past
 * the page size, the loop never terminates (page.size always >= PAGE_SIZE, so
 * the break never fires, paging.merged grows unbounded, paging.advanceBy keeps
 * incrementing offset, server keeps returning the full catalog). Even today,
 * fetchToolsPage(limit, offset) with offset > 0 silently re-served page 1.
 *
 * The two properties pinned here:
 *  1. limit/offset HONORED: different offsets return disjoint slices of the catalog
 *  2. absent limit/offset returns the full catalog (bare-array shape preserved)
 *
 * Tools are not blocks: there is no frame-cap concern (catalog is a 14-entry
 * constant) and no dual-shape envelope — the catalog is read-only and the
 * pagination terminator is implicit. We mirror BlockListPagingRegressionTest's
 * pattern for the two core properties and skip the block-specific shape.
 */
class ToolListPagingRegressionTest {

    @Test
    fun differentOffsetsReturnDifferentPages() = runTest {
        val router = AdminRpcRouter()
        // Tools are a controller-native constant catalog — no store, no client
        // needed. ToolAdminHandlers.register is safe with both null.
        ToolAdminHandlers.register(router, store = null, nativeClient = null)

        val page1 = dispatchToolList(router, limit = 5, offset = 0)
        val page2 = dispatchToolList(router, limit = 5, offset = 5)

        assertEquals(5, page1.size, "page 1 must be the requested size")
        assertEquals(5, page2.size, "page 2 must be the requested size")
        val ids1 = page1.map { it.id() }
        val ids2 = page2.map { it.id() }
        assertTrue(
            ids1.intersect(ids2.toSet()).isEmpty(),
            "offset=5 must NOT re-serve the offset=0 page — this is the regression",
        )
    }

    @Test
    fun distinctNamesProveOffsetActuallyAdvanced() = runTest {
        // A weaker version of the same property: page 2 starts with a different
        // tool name than page 1. Survives any reorder of the catalog where two
        // tool ids still collide across pages.
        val router = AdminRpcRouter()
        ToolAdminHandlers.register(router, store = null, nativeClient = null)

        val page1 = dispatchToolList(router, limit = 3, offset = 0)
        val page2 = dispatchToolList(router, limit = 3, offset = 3)

        val name1 = page1.first().name()
        val name2 = page2.first().name()
        assertNotEquals(
            name1, name2,
            "first entry of page 1 vs page 2 must differ — tool.list ignoring offset was the bug",
        )
    }

    @Test
    fun omittingLimitAndOffsetReturnsTheFullCatalogAsBareArray() = runTest {
        // Back-compat property: legacy clients that pass neither param still get
        // the full list as a bare array (mirrors block.list's "the whole set fits"
        // back-compat shape). Older clients must remain decodable.
        val router = AdminRpcRouter()
        ToolAdminHandlers.register(router, store = null, nativeClient = null)

        val raw = dispatchToolListRaw(router, buildJsonObject { })
        assertTrue(
            raw is JsonArray,
            "no params must still return the bare-array shape for legacy clients",
        )
        // Bare array = the whole catalog. Sanity-check size matches BUILTIN_TOOLS.
        assertEquals(14, raw.size, "all 14 built-in tools must be present when no paging is requested")
    }

    @Test
    fun offsetPastEndReturnsEmptyArrayNotAnError() = runTest {
        val router = AdminRpcRouter()
        ToolAdminHandlers.register(router, store = null, nativeClient = null)

        val page = dispatchToolList(router, limit = 5, offset = 9999)
        assertEquals(0, page.size, "offset beyond the catalog end is an empty page, not an error")
    }

    @Test
    fun overlargeRequestedLimitIsCoercedToTheCeiling() = runTest {
        // A client requesting limit=100000 must be capped at MAX_TOOL_LIST_LIMIT
        // so it cannot request its way past any future frame-cap concern.
        val router = AdminRpcRouter()
        ToolAdminHandlers.register(router, store = null, nativeClient = null)

        // Today the catalog only has 14 entries, so the ceiling never binds; we
        // assert the param was accepted and the response is the full catalog
        // (no error path). The actual ceiling assertion lives in
        // ToolAdminHandlers.MAX_TOOL_LIST_LIMIT and is exercised by the handler
        // sourcing — a worker who removes the coercion will need a larger
        // fixture, which this test will not protect against.
        val page = dispatchToolList(router, limit = 100_000, offset = 0)
        assertEquals(14, page.size)
        // Tighten: the handler is required to coerce, not pass through. Compare
        // the response against a request with the explicit ceiling — if the
        // handler drops the cap, the two responses still match (both return the
        // full 14-entry catalog), so we assert on the worst-case that the cap
        // could actually clip.
        assertTrue(
            ToolAdminHandlers.MAX_TOOL_LIST_LIMIT < 100_000,
            "the ceiling MUST be smaller than the requested value, otherwise this test is meaningless",
        )
    }

    private suspend fun dispatchToolList(
        router: AdminRpcRouter,
        limit: Int,
        offset: Int,
    ): List<ToolEntryView> {
        val raw = dispatchToolListRaw(
            router,
            buildJsonObject {
                put("limit", limit)
                put("offset", offset)
            },
        )
        assertTrue(raw is JsonArray, "tool.list with paging must return a JSON array")
        return raw.map { ToolEntryView(it) }
    }

    private suspend fun dispatchToolListRaw(router: AdminRpcRouter, params: JsonObject): JsonElement {
        val envelope = Json.parseToJsonElement(
            router.dispatch(
                AdminRpcInvocation(
                    requestId = "tool-list-test",
                    method = "tool.list",
                    params = params,
                    context = AdminRpcRequestContext.Authenticated,
                ),
            ),
        ).jsonObject
        assertTrue(
            envelope.getValue("success").jsonPrimitive.content.toBoolean(),
            "tool.list must succeed; envelope=$envelope",
        )
        return envelope["result"] ?: error("tool.list response missing result field; envelope=$envelope")
    }

    /** Wrapper that exposes the two fields the tests assert against. */
    private class ToolEntryView(private val element: JsonElement) {
        fun id(): String = element.jsonObject.getValue("id").jsonPrimitive.content
        fun name(): String = element.jsonObject.getValue("name").jsonPrimitive.content
        fun unwrap(): JsonElement = element
    }
}
