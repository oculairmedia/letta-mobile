package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerExternalToolResult
import com.letta.mobile.data.transport.appserver.AppServerExternalToolResultContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-lgns8.22.4.1.6 — the external-tool result cache must outlive an
 * ambiguous (one-way) send so a reconnect replay reuses the result, yet must not
 * retain orphans the server will never replay for.
 */
class ExternalToolResultCacheTest {

    @Test
    fun anEntryOlderThanTheReplayHorizonIsEvictedAsAnOrphan() {
        var now = 1_000L
        val cache = ExternalToolResultCache(ttlMs = 500L, nowMs = { now })
        cache.put(key("ext-1", "tc-1"), result("ok"))
        assertNotNull(cache.get(key("ext-1", "tc-1")))

        // Still inside the replay horizon.
        now = 1_400L
        assertNotNull(cache.get(key("ext-1", "tc-1")))

        // The server never replayed: the entry is an orphan and must be dropped.
        now = 1_600L
        assertNull(cache.get(key("ext-1", "tc-1")))
        assertEquals(0, cache.size())
    }

    @Test
    fun pruneExpiredDropsOrphansWithoutAnAccess() {
        var now = 0L
        val cache = ExternalToolResultCache(ttlMs = 100L, nowMs = { now })
        cache.put(key("ext-1", "tc-1"), result("ok"))
        now = 500L
        cache.pruneExpired()
        assertEquals(0, cache.size())
    }

    @Test
    fun theCacheIsBoundedAndEvictsOldestFirst() {
        var now = 0L
        val cache = ExternalToolResultCache(maxEntries = 4, ttlMs = 1_000_000L, nowMs = { now += 1; now })
        repeat(10) { index -> cache.put(key("ext-$index", "tc-$index"), result("r$index")) }

        assertEquals(4, cache.size())
        assertFalse(cache.contains(key("ext-0", "tc-0")))
        assertTrue(cache.contains(key("ext-9", "tc-9")))
    }

    @Test
    fun identityIncludesToolCallIdSoAReusedRequestIdDoesNotCollide() {
        val cache = ExternalToolResultCache(nowMs = { 0L })
        cache.put(key("ext-shared", "tc-a"), result("a"))
        cache.put(key("ext-shared", "tc-b"), result("b"))

        assertEquals("a", cache.get(key("ext-shared", "tc-a"))?.content?.single()?.text)
        assertEquals("b", cache.get(key("ext-shared", "tc-b"))?.content?.single()?.text)
    }

    private fun key(requestId: String, toolCallId: String?) =
        ExternalToolResultCache.Key(requestId, toolCallId)

    private fun result(text: String) = AppServerExternalToolResult(
        content = listOf(AppServerExternalToolResultContent(type = "text", text = text)),
        isError = false,
    )
}
