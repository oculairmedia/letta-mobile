package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile data-efficiency Phase 4 (H4): the warmup allocation policy is
 * the single source of truth for how the foreground service reserves
 * background-stream slots at startup.
 */
class BackgroundStreamBudgetTest {

    @Test
    fun emptyInputsProduceEmptyAllocation() {
        assertEquals(
            emptyList<String>(),
            BackgroundStreamBudget.allocate(currentConversationId = null, recentConversationIds = emptyList()),
        )
    }

    @Test
    fun nullCurrentPlusRecentsFillsFromRecents() {
        val recents = listOf("a", "b", "c", "d", "e")
        assertEquals(
            listOf("a", "b", "c"),
            BackgroundStreamBudget.allocate(currentConversationId = null, recentConversationIds = recents),
        )
    }

    @Test
    fun currentConversationAlwaysComesFirstWhenKnown() {
        val recents = listOf("a", "b", "c", "d")
        val allocated = BackgroundStreamBudget.allocate(currentConversationId = "x", recentConversationIds = recents)
        assertEquals("x", allocated.first())
        assertEquals(listOf("x", "a", "b"), allocated)
    }

    @Test
    fun currentAndRecentOverlapIsDeduped() {
        // "a" appears as both current and in recents; must not be counted twice.
        val recents = listOf("a", "b", "c")
        val allocated = BackgroundStreamBudget.allocate(currentConversationId = "a", recentConversationIds = recents)
        assertEquals(listOf("a", "b", "c"), allocated)
        assertEquals(allocated.size, allocated.toSet().size)
    }

    @Test
    fun resultIsCappedAtMaxWarmStreams() {
        val recents = (1..10).map { "id-$it" }
        val allocated = BackgroundStreamBudget.allocate(currentConversationId = "current", recentConversationIds = recents)
        assertTrue(
            allocated.size <= BackgroundStreamBudget.MAX_WARM_STREAMS,
            "expected at most MAX_WARM_STREAMS, got ${allocated.size}",
        )
        // First slot is always the current id when present, then recents fill the rest.
        assertEquals("current", allocated.first())
        assertEquals(BackgroundStreamBudget.MAX_WARM_STREAMS, allocated.size)
    }

    @Test
    fun fewerRecentsThanBudgetJustReturnsWhatWasGiven() {
        val allocated = BackgroundStreamBudget.allocate(currentConversationId = null, recentConversationIds = listOf("a"))
        assertEquals(listOf("a"), allocated)
    }

    @Test
    fun constantMatchesChatPushServiceBudget() {
        // Sanity-check: if MAX_WARM_STREAMS drifts from
        // ChatPushService.MAX_BACKGROUND_PERSISTENT_STREAMS, warmup would
        // silently expand / shrink. The audit doc and PR description pin this
        // at 3; bump both call sites together if that ever changes.
        assertEquals(3, BackgroundStreamBudget.MAX_WARM_STREAMS)
    }
}