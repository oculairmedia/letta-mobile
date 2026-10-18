package com.letta.mobile.data.search

import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.serialization.LettaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** letta-mobile-k5nbq.5/.16: each mode builds a request Letta's `/v1/messages/search` accepts; roles filter on the client. */
class MessageSearchRequestTest {
    @Test
    fun eachModeWritesALettaSearchMode() {
        assertEquals("hybrid", request(MessageSearchMode.Hybrid).searchMode)
        assertEquals("vector", request(MessageSearchMode.Semantic).searchMode)
        assertEquals("fts", request(MessageSearchMode.FullText).searchMode)
    }

    @Test
    fun theRequestBodyCarriesNoRoleFilter() {
        val body = encoded(MessageSearchQuery("q", agentId = "a-1").toRequest(MessageSearchCapabilities.REMOTE, limit = 20))
        assertFalse("roles" in body)
        assertEquals(JsonPrimitive("a-1"), body["agent_id"])
        assertEquals(JsonPrimitive(20), body["limit"])
    }

    @Test
    fun aLocalIndexFallsBackToFullText() {
        assertEquals("fts", request(MessageSearchMode.Semantic, MessageSearchCapabilities.LOCAL).searchMode)
        assertFalse(MessageSearchCapabilities.LOCAL.showsModes())
        assertTrue(MessageSearchCapabilities.REMOTE.showsModes())
    }

    @Test
    fun aNewSearchIsFullText() {
        assertEquals("fts", MessageSearchQuery("q").toRequest(MessageSearchCapabilities.REMOTE).searchMode)
    }

    @Test
    fun backendHitsAreKeptByTheSelectedRoles() = runTest {
        val hits = listOf(
            hit("u", "user_message"),
            hit("a", "assistant_message"),
            hit("r", "reasoning_message"),
            hit("s", "system_message"),
        )
        val defaults = MessageSearchQuery("q").searchBackend { hits }
        assertEquals(listOf("u", "a"), defaults.map { it.messageId })
        val reasoningOnly = MessageSearchQuery(
            text = "q",
            selection = MessageSearchSelection(roles = setOf(MessageSearchRole.Reasoning)),
        ).searchBackend { hits }
        assertEquals(listOf("r"), reasoningOnly.map { it.messageId })
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

    private fun encoded(request: MessageSearchRequest): JsonObject =
        LettaJson.adminRpc.encodeToJsonElement(MessageSearchRequest.serializer(), request).jsonObject

    private fun hit(id: String, type: String) = ParsedSearchMessage(id, "a-1", type, "q", null, "c-1")
}
