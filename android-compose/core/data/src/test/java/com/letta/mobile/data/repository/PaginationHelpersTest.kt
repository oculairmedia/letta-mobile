package com.letta.mobile.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Phase 2.2 (data-efficiency-audit Q3): unit tests for the
 * [exhaustPages] (offset-based) and [exhaustCursorPages] (cursor-based)
 * pagination helpers.
 *
 * The "exactly two pages" case from the audit doc drives the assertion:
 * the fake fetch returns a full page and a short trailing page, and we
 * assert both pages are merged correctly. These tests have no
 * repository/agent dependency, so they survive the pre-existing
 * `AgentEntity.kt:40` / `AgentRepository.kt:423` compile breakages and
 * still validate the helper semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class PaginationHelpersTest {

    @Test
    fun `exhaustPages returns all items across two pages`() = runTest {
        // 75 items, pageSize = 50 -> first page full (50), second short (25).
        val items = (1..75).map { "item-$it" }
        val observations = mutableListOf<Int>()

        val result = exhaustPages(
            pageSize = 50,
            maxPages = 100,
            fetch = { limit, offset ->
                observations += offset
                items.drop(offset).take(limit)
            },
            dedupKey = { it },
        )

        assertEquals(75, result.size)
        assertEquals(listOf(0, 50), observations)
        assertEquals("item-1", result.first())
        assertEquals("item-75", result.last())
    }

    @Test
    fun `exhaustPages stops after a short page without making a third request`() = runTest {
        val items = (1..30).map { "item-$it" }
        val observations = mutableListOf<Int>()

        val result = exhaustPages(
            pageSize = 50,
            fetch = { limit, offset ->
                observations += offset
                items.drop(offset).take(limit)
            },
            dedupKey = { it },
        )

        // 30 items, pageSize = 50 -> one full page of size 30 < pageSize, stop.
        assertEquals(30, result.size)
        assertEquals(listOf(0), observations)
    }

    @Test
    fun `exhaustPages dedups repeated items when server ignores offset`() = runTest {
        // Server bug: same page returns same 5 items on every request.
        val observations = mutableListOf<Int>()

        val result = exhaustPages(
            pageSize = 5,
            maxPages = 10,
            fetch = { limit, offset ->
                observations += offset
                (1..5).map { "item-$it" }
            },
            dedupKey = { it },
        )

        assertEquals(5, result.size)
        // First call sees new items -> add to merged. Second call sees no new
        // items -> break on the dedup guard before reaching the third request.
        assertEquals(listOf(0, 5), observations)
    }

    @Test
    fun `exhaustCursorPages returns all items across two cursor pages`() = runTest {
        val items = (1..75).map { "item-$it" }
        val observations = mutableListOf<String?>()

        val result = exhaustCursorPages(
            pageSize = 50,
            maxPages = 100,
            fetch = { limit, after ->
                observations += after
                val start = after?.let { id ->
                    items.indexOfFirst { it == id }.let { i -> if (i < 0) items.size else i + 1 }
                } ?: 0
                items.drop(start).take(limit)
            },
            extractCursor = { it },
            dedupKey = { it },
        )

        assertEquals(75, result.size)
        assertEquals(listOf<String?>(null, "item-50"), observations)
        assertEquals("item-1", result.first())
        assertEquals("item-75", result.last())
    }

    @Test
    fun `exhaustCursorPages stops after a short page without making a third request`() = runTest {
        val items = (1..30).map { "item-$it" }
        val observations = mutableListOf<String?>()

        val result = exhaustCursorPages(
            pageSize = 50,
            fetch = { limit, after ->
                observations += after
                val start = after?.let { id ->
                    items.indexOfFirst { it == id }.let { i -> if (i < 0) items.size else i + 1 }
                } ?: 0
                items.drop(start).take(limit)
            },
            extractCursor = { it },
            dedupKey = { it },
        )

        assertEquals(30, result.size)
        assertEquals(listOf<String?>(null), observations)
    }

    @Test
    fun `exhaustCursorPages dedups repeated items when server ignores after`() = runTest {
        val observations = mutableListOf<String?>()

        val result = exhaustCursorPages(
            pageSize = 5,
            maxPages = 10,
            fetch = { limit, after ->
                observations += after
                (1..5).map { "item-$it" }
            },
            extractCursor = { it },
            dedupKey = { it },
        )

        assertEquals(5, result.size)
        // First call adds new items. Second call returns the same 5 items; dedup
        // guard sees no fresh content and breaks.
        assertEquals(listOf<String?>(null, "item-5"), observations)
    }

    @Test
    fun `exhaustPages rejects non-positive pageSize`() = runTest {
        try {
            exhaustPages(
                pageSize = 0,
                fetch = { _, _ -> emptyList<String>() },
                dedupKey = { it },
            )
            org.junit.Assert.fail("expected IllegalArgumentException for pageSize=0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `exhaustCursorPages rejects non-positive maxPages`() = runTest {
        try {
            exhaustCursorPages(
                maxPages = 0,
                fetch = { _, _ -> emptyList<String>() },
                extractCursor = { it },
                dedupKey = { it },
            )
            org.junit.Assert.fail("expected IllegalArgumentException for maxPages=0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}