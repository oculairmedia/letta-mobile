package com.letta.mobile.data.context

import com.letta.mobile.data.model.ContextWindowOverview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextWindowUsagePolicyTest {
    private val overview = ContextWindowOverview(
        contextWindowSizeMax = 100_000,
        contextWindowSizeCurrent = 20_000,
        numTokensMessages = 20_000,
    )

    private fun key(
        agent: String? = "agent-1",
        conversation: String? = "conversation-1",
        settled: Boolean = true,
    ) = ContextWindowUsageKey(agent, conversation, settled)

    @Test
    fun readsOnlyWhenAnAgentIsFocusedAndTheTurnHasSettled() {
        assertTrue(ContextWindowUsagePolicy.readable(key()))
        assertTrue(!ContextWindowUsagePolicy.readable(key(settled = false)))
        assertTrue(!ContextWindowUsagePolicy.readable(key(agent = null)))
        assertTrue(!ContextWindowUsagePolicy.readable(key(agent = "  ")))
    }

    @Test
    fun dropsAnotherConversationsReadingRatherThanShowingItUnderTheSpinner() {
        // Switching to a conversation whose turn is still running must not
        // leave the previous conversation's usage on the chip.
        val loaded = ContextWindowUsagePolicy.read(overview)

        val reading = ContextWindowUsagePolicy.reading(
            current = loaded,
            key = key(conversation = "conversation-2"),
            previous = key(conversation = "conversation-1"),
        )

        assertNull(reading.usage)
        assertTrue(reading.loading)
    }

    @Test
    fun keepsThisConversationsReadingWhileItRefreshes() {
        val loaded = ContextWindowUsagePolicy.read(overview)

        val reading = ContextWindowUsagePolicy.reading(
            current = loaded,
            key = key(settled = true),
            previous = key(settled = false),
        )

        assertEquals(loaded.usage, reading.usage)
        assertTrue(reading.loading)
        assertNull(reading.error)
    }

    @Test
    fun keepsTheLastGoodReadingWhenAReadFails() {
        val loaded = ContextWindowUsagePolicy.read(overview)

        val failed = ContextWindowUsagePolicy.failed(loaded, "Backend unreachable.")

        assertEquals(loaded.usage, failed.usage)
        assertEquals("Backend unreachable.", failed.error)
        assertTrue(!failed.loading)
    }

    @Test
    fun substitutesAMessageWhenAFailureCarriesNone() {
        val failed = ContextWindowUsagePolicy.failed(ContextWindowUsageState(), null)

        assertEquals("Context window unavailable.", failed.error)
    }

    @Test
    fun matchesIdentityIgnoringWhetherTheTurnHasSettled() {
        assertTrue(key(settled = true).sameIdentityAs(key(settled = false)))
        assertTrue(!key(conversation = "conversation-2").sameIdentityAs(key()))
        assertTrue(!key().sameIdentityAs(null))
    }
}
