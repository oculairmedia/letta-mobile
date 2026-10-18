package com.letta.mobile.data.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-k5nbq.5: each mode builds the backend request, and an unsupported mode falls back. */
class MessageSearchRequestTest {
    @Test
    fun eachModeWritesItsWireValue() {
        assertEquals("hybrid", request(MessageSearchMode.Hybrid).searchMode)
        assertEquals("semantic", request(MessageSearchMode.Semantic).searchMode)
        assertEquals("fts", request(MessageSearchMode.FullText).searchMode)
    }

    @Test
    fun rolesAndAgentGoOnTheRequest() {
        val built = MessageSearchQuery(
            text = "deploy",
            agentId = "a-1",
            selection = MessageSearchSelection(
                mode = MessageSearchMode.Semantic,
                roles = setOf(MessageSearchRole.User, MessageSearchRole.Reasoning),
            ),
        ).toRequest(MessageSearchCapabilities.REMOTE, limit = 20)
        assertEquals("deploy", built.query)
        assertEquals("a-1", built.agentId)
        assertEquals(listOf("user", "reasoning"), built.roles)
        assertEquals(20, built.limit)
    }

    @Test
    fun aLocalIndexFallsBackToFullText() {
        val built = request(MessageSearchMode.Semantic, MessageSearchCapabilities.LOCAL)
        assertEquals("fts", built.searchMode)
        assertFalse(MessageSearchCapabilities.LOCAL.showsModes())
        assertTrue(MessageSearchCapabilities.REMOTE.showsModes())
    }

    @Test
    fun emptyRolesBecomeTheDefaultPersonAndAgent() {
        val built = MessageSearchQuery(
            text = "hi",
            selection = MessageSearchSelection(roles = emptySet()),
        ).toRequest(MessageSearchCapabilities.REMOTE)
        assertEquals(listOf("user", "assistant"), built.roles)
    }

    @Test
    fun togglingTheLastRoleRestoresTheDefault() {
        val none = MessageSearchSelection().toggled(MessageSearchRole.User).toggled(MessageSearchRole.Assistant)
        assertEquals(MessageSearchRole.DEFAULT, none.roles)
    }

    private fun request(
        mode: MessageSearchMode,
        capabilities: MessageSearchCapabilities = MessageSearchCapabilities.REMOTE,
    ) = MessageSearchQuery("q", selection = MessageSearchSelection(mode = mode)).toRequest(capabilities)
}
