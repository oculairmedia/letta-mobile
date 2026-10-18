package com.letta.mobile.ui.shell.header

import com.letta.mobile.data.search.MessageSearchMode
import com.letta.mobile.data.search.MessageSearchQuery
import com.letta.mobile.data.search.MessageSearchRole
import com.letta.mobile.data.search.MessageSearchSelection
import com.letta.mobile.data.search.MessageSearchSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-k5nbq.5: the unified search's message query carries the mode and role chips. */
class UnifiedSearchResultsTest {
    private val search = MessageSearchSource { emptyList() }
    private val sources = UnifiedSearchSources(agentId = "a-1", messageSearch = search)

    @Test
    fun messageQueryCarriesModeAndRolesAndTheAgentScope() {
        val asked = messageQueryFor(
            UnifiedSearchQuery(
                text = "hi",
                filterToAgent = true,
                message = MessageSearchSelection(MessageSearchMode.Semantic, setOf(MessageSearchRole.Tool)),
            ),
            sources,
        )
        val expected = MessageSearchQuery(
            "hi",
            "a-1",
            MessageSearchSelection(MessageSearchMode.Semantic, setOf(MessageSearchRole.Tool)),
        )
        assertEquals(expected, asked)
    }

    @Test
    fun blankOrOutOfScopeQueriesDoNotRun() {
        assertNull(messageQueryFor(UnifiedSearchQuery(""), sources))
        assertNull(messageQueryFor(UnifiedSearchQuery("hi", scopeId = UnifiedSearchKind.Agents.id), sources))
    }
}
